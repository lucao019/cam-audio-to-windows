const net = require("net");
const http = require("http");
const { spawn } = require("child_process");

// ============================================================
// LUCAO LINK - LIVE RECEIVER (MULTI-DEVICE)
//
// Porta 5051: video H.264.
//
// Cada aparelho abre uma conexao e envia primeiro um
// handshake de texto:
//
//   LUCAO/1 <deviceId> <nome> <transporte>\n
//
// Logo depois vem o stream H.264 cru (SPS/PPS + frames).
// Clientes antigos (sem handshake) continuam funcionando:
// se os primeiros bytes forem um start code H.264, o
// receptor trata a conexao como legado.
//
// Uma sessao ffplay por aparelho -> varios celulares
// podem transmitir ao mesmo tempo.
// ============================================================

const PORT = 5051;
const CONTROL_HOST = process.env.LUCAO_HOST || "127.0.0.1";
const CONTROL_PORT = 5050;

const HANDSHAKE_TIMEOUT_MS = 2500;

// id -> { socket, ffplay, nome, transporte, legado }
const sessoes = new Map();

// ------------------------------------------------------------
// CONTROLE (avisa o server.js quem esta conectado)
// ------------------------------------------------------------

function avisarControle(id, conectado, nome, transporte) {
    const corpo = JSON.stringify({
        id,
        conectado,
        nome,
        transporte
    });

    const req = http.request(
        {
            host: CONTROL_HOST,
            port: CONTROL_PORT,
            path: "/api/video",
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "Content-Length": Buffer.byteLength(corpo)
            },
            timeout: 1500
        },
        (res) => res.resume()
    );

    req.on("error", () => {
        // Servidor de controle desligado: o video nao depende dele.
    });

    req.on("timeout", () => req.destroy());

    req.end(corpo);
}

// ------------------------------------------------------------
// FFPLAY
// ------------------------------------------------------------

function tituloJanela(sessao) {
    const nome = (sessao.nome || "android").slice(0, 40);
    const transporte = sessao.transporte || "usb";

    return `LUCAO LINK - ${nome} (${transporte})`;
}

function iniciarFfplay(id, nome, transporte) {
    const ffplay = spawn(
        process.env.FFPLAY_PATH || "ffplay",
        [
            "-loglevel", "warning",
            "-fflags", "nobuffer",
            "-flags", "low_delay",
            "-framedrop",
            // -sync ext: sincroniza pelo relogio externo e DESCARTA
            // frames atrasados em vez de acumular atraso. Sem isso o
            // ffplay segura ~1s de buffer de video.
            "-sync", "ext",
            "-analyzeduration", "0",
            "-probesize", "32",
            "-framerate", "30",
            "-f", "h264",
            "-window_title", tituloJanela({ nome, transporte }),
            "-i", "pipe:0"
        ],
        {
            stdio: ["pipe", "inherit", "inherit"],
            windowsHide: false
        }
    );

    ffplay.on("error", (error) => {
        console.error(
            `[${id}] ERRO AO ABRIR FFPLAY: ${error.message}`
        );
        console.error(
            "  Defina FFPLAY_PATH ou adicione o ffmpeg ao PATH."
        );
    });

    return ffplay;
}

// ------------------------------------------------------------
// SESSAO
// ------------------------------------------------------------

function encerrarSessao(id, motivo) {
    const sessao = sessoes.get(id);

    if (!sessao) {
        return;
    }

    sessoes.delete(id);

    if (sessao.socket && !sessao.socket.destroyed) {
        sessao.socket.destroy();
    }

    if (sessao.ffplay && !sessao.ffplay.killed) {
        // killed evita que o handler de "close" abra algo de novo.
        sessao.ffplay.killed = true;
        sessao.ffplay.kill();
    }

    avisarControle(id, false, sessao.nome, sessao.transporte);

    console.log(
        `[${id}] SESSAO ENCERRADA${motivo ? " - " + motivo : ""}`
    );
    console.log(
        `Sessoes ativas: ${sessoes.size}`
    );
}

function iniciarSessao(socket, id, nome, transporte, restante) {
    // Mesmo aparelho reconectando: derruba a sessao anterior.
    if (sessoes.has(id)) {
        encerrarSessao(id, "reconexao");
    }

    const ffplay = iniciarFfplay(id, nome, transporte);

    const sessao = {
        socket,
        ffplay,
        nome,
        transporte
    };

    sessoes.set(id, sessao);

    console.log("");
    console.log("ANDROID CONECTADO");
    console.log(`  id        : ${id}`);
    console.log(`  nome      : ${nome}`);
    console.log(`  transporte: ${transporte}`);
    console.log(`  origem    : ${socket.remoteAddress}:${socket.remotePort}`);
    console.log(`  sessoes   : ${sessoes.size}`);

    avisarControle(id, true, nome, transporte);

    // Bytes que chegaram junto com o handshake.
    if (restante && restante.length) {
        ffplay.stdin.write(restante);
    }

    socket.pipe(ffplay.stdin);

    ffplay.stdin.on("error", () => {
        // ffplay pode fechar antes do socket; ignora EPIPE.
    });

    socket.on("error", (error) => {
        console.error(`[${id}] ERRO SOCKET: ${error.message}`);
        encerrarSessao(id, "erro de socket");
    });

    socket.on("close", () => {
        encerrarSessao(id, "socket fechado");
    });

    socket.on("end", () => {
        encerrarSessao(id, "android desconectou");
    });

    ffplay.on("close", (code) => {
        console.log(`[${id}] FFPLAY ENCERRADO: ${code}`);

        // Fechar a JANELA do ffplay nao pode derrubar a sessao:
        // se derrubasse, o android veria o socket cair, reconectaria
        // e abriria OUTRO ffplay -> loop infinito de janelas.
        // A sessao so acaba quando o socket cai.
        const sessao = sessoes.get(id);

        if (sessao && sessao.ffplay === ffplay) {
            sessao.ffplay = null;
        }
    });
}

// ------------------------------------------------------------
// HANDSHAKE
// ------------------------------------------------------------

function pareceH264(buffer) {
    if (buffer.length < 3) {
        return false;
    }

    // 00 00 00 01 ou 00 00 01
    return (
        (buffer[0] === 0x00 && buffer[1] === 0x00 && buffer[2] === 0x01) ||
        (buffer.length >= 4 &&
            buffer[0] === 0x00 &&
            buffer[1] === 0x00 &&
            buffer[2] === 0x00 &&
            buffer[3] === 0x01)
    );
}

function idLegado(socket) {
    const ip = String(socket.remoteAddress || "android")
        .replace(/^::ffff:/, "")
        .replace(/\./g, "-");

    return `legacy-${ip}`;
}

const server = net.createServer((socket) => {
    socket.setNoDelay(true);

    let buffer = Buffer.alloc(0);
    let decidido = false;

    const timer = setTimeout(() => {
        if (!decidido) {
            console.error(
                `HANDSHAKE TIMEOUT: ${socket.remoteAddress}:${socket.remotePort}`
            );
            socket.destroy();
        }
    }, HANDSHAKE_TIMEOUT_MS);

    function decidir() {
        if (decidido) {
            return;
        }

        // Cliente antigo: comeca direto com o stream H.264.
        // (Verificado antes do handshake porque bytes de video
        // podem conter 0x0a por acaso.)
        if (pareceH264(buffer)) {
            decidido = true;
            clearTimeout(timer);

            socket.pause();

            const restante = buffer;

            socket.removeAllListeners("data");

            const id = idLegado(socket);

            iniciarSessao(socket, id, "android", "usb", restante);

            socket.resume();

            return true;
        }

        // Handshake completo?
        const quebra = buffer.indexOf(0x0a);

        if (quebra >= 0) {
            const linha = buffer
                .subarray(0, quebra)
                .toString("utf8")
                .trim();

            if (linha.startsWith("LUCAO/")) {
                decidido = true;
                clearTimeout(timer);

                socket.pause();

                const partes = linha.split(/\s+/);

                const id = (partes[1] || idLegado(socket)).slice(0, 64);
                const nome = (partes[2] || "android").slice(0, 64);
                const transporte = (partes[3] || "usb").slice(0, 16);

                const restante = buffer.subarray(quebra + 1);

                socket.removeAllListeners("data");

                iniciarSessao(socket, id, nome, transporte, restante);

                socket.resume();

                return true;
            }

            // Linha desconhecida: descarta e segue tentando.
            buffer = buffer.subarray(quebra + 1);
        }

        return false;
    }

    socket.on("data", (chunk) => {
        if (decidido) {
            return;
        }

        buffer = Buffer.concat([buffer, chunk]);

        // Tenta identificar o protocolo antes de aplicar o limite:
        // o primeiro pacote pode trazer handshake + varios KB de video.
        if (decidir()) {
            return;
        }

        // Nao deixa um cliente malicioso encher a memoria.
        if (buffer.length > 4096) {
            console.error(
                `HANDSHAKE INVALIDO: ${socket.remoteAddress}:${socket.remotePort}`
            );
            console.error(
                "  primeiros bytes: " +
                    buffer.subarray(0, 100).toString("hex") +
                    " | " +
                    buffer
                        .subarray(0, 100)
                        .toString("utf8")
                        .replace(/[^\x20-\x7e]/g, ".")
            );
            console.error(
                "  tamanho: " + buffer.length +
                    " | newline em: " + buffer.indexOf(0x0a)
            );
            clearTimeout(timer);
            socket.destroy();
            return;
        }

        decidir();
    });

    socket.on("error", (error) => {
        if (!decidido) {
            clearTimeout(timer);
            console.error(
                `ERRO NO HANDSHAKE: ${error.message}`
            );
        }
    });

    socket.on("close", () => {
        clearTimeout(timer);
    });
});

server.listen(PORT, "0.0.0.0", () => {
    console.log("");
    console.log("==============================");
    console.log("   LUCAO LINK - LIVE RECEIVER");
    console.log("==============================");
    console.log("");
    console.log(`H264 aguardando na porta ${PORT}`);
    console.log(`Controle em ${CONTROL_HOST}:${CONTROL_PORT}`);
    console.log("");
});

// Reanuncia sessoes ativas: se o server.js reiniciar, o painel
// volta a mostrar o video sem depender de uma nova conexao.
setInterval(() => {
    for (const [id, sessao] of sessoes) {
        avisarControle(
            id,
            true,
            sessao.nome,
            sessao.transporte
        );
    }
}, 3000).unref();
