const net = require("net");
const http = require("http");
const { spawn } = require("child_process");

// ============================================================
// LUCAO LINK - VIDEO BRIDGE
//
// Porta 5051 (TCP): recebe o H.264 cru do Android, uma sessao
// por aparelho. Protocolo:
//
//   LUCAO/1 <deviceId> <nome> <transporte>\n
//
// Porta 5052 (HTTP): entrega o mesmo video remuxado em MP4
// fragmentado (fMP4) para o <video> do app (MSE).
//
//   GET /info/:id   -> estado/codec/geracao da sessao
//   GET /stream/:id -> init segment + fragmentos ao vivo
//
// Cada sessao tem um processo ffmpeg que apenas troca o
// container (Annex B -> fMP4, -c:v copy). O video nao e
// recodificado, entao o custo de CPU e minimo.
// ============================================================

const PORTA_VIDEO = 5051;
const PORTA_STREAM = 5052;
const PORTA_CONTROLE = 5050;

const HANDSHAKE_TIMEOUT_MS = 2500;
const RETENCAO_SESSAO_MS = 60 * 1000;
const MAX_HANDSHAKE_BYTES = 4096;
const MAX_CAIXA_BYTES = 64 * 1024 * 1024;

const CORS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Allow-Private-Network": "true"
};

// id -> sessao
const sessoes = new Map();

let servidorVideo = null;
let servidorStream = null;
let configuracao = null;
let intervaloLimpeza = null;
let intervaloAnuncio = null;

// ------------------------------------------------------------
// UTIL
// ------------------------------------------------------------

function log(mensagem) {
    if (configuracao && typeof configuracao.log === "function") {
        configuracao.log(mensagem);
        return;
    }

    console.log(mensagem);
}

function responderJson(res, status, corpo) {
    const texto = JSON.stringify(corpo);

    res.writeHead(status, {
        ...CORS,
        "Content-Type": "application/json; charset=utf-8",
        "Content-Length": Buffer.byteLength(texto),
        "Cache-Control": "no-store"
    });

    res.end(texto);
}

// ------------------------------------------------------------
// CONTROLE (avisa o server.js quem esta transmitindo)
// ------------------------------------------------------------

function avisarControle(sessao, conectado) {
    const corpo = JSON.stringify({
        id: sessao.id,
        conectado,
        nome: sessao.nome,
        transporte: sessao.transporte
    });

    const requisicao = http.request(
        {
            host: "127.0.0.1",
            port: configuracao.portaControle,
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

    requisicao.on("error", () => {
        // Servidor de controle desligado: o video nao depende dele.
    });

    requisicao.on("timeout", () => requisicao.destroy());

    requisicao.end(corpo);
}

// ------------------------------------------------------------
// MP4 (fMP4): leitura de caixas e metadados
// ------------------------------------------------------------

function lerCodec(moov) {
    const indice = moov.indexOf("avcC");

    if (indice < 4 || indice + 8 > moov.length) {
        return null;
    }

    // [size][avcC][version][profile][compat][level]
    const perfil = moov[indice + 5];
    const compat = moov[indice + 6];
    const nivel = moov[indice + 7];

    if (!perfil) {
        return null;
    }

    const hex = [perfil, compat, nivel]
        .map(valor => valor.toString(16).padStart(2, "0"))
        .join("");

    return `avc1.${hex}`;
}

function lerDimensoes(moov) {
    // VisualSampleEntry: width/height ficam 24 bytes depois
    // do inicio do payload da caixa "avc1".
    const indice = moov.indexOf("avc1");

    if (indice < 0 || indice + 32 > moov.length) {
        return { largura: 0, altura: 0 };
    }

    const base = indice + 4 + 24;

    return {
        largura: moov.readUInt16BE(base),
        altura: moov.readUInt16BE(base + 2)
    };
}

function prepararInit(sessao) {
    if (sessao.init || !sessao.ftyp || !sessao.moov) {
        return;
    }

    const dimensoes = lerDimensoes(sessao.moov);

    sessao.init = Buffer.concat([sessao.ftyp, sessao.moov]);
    sessao.codec = lerCodec(sessao.moov);
    sessao.largura = dimensoes.largura;
    sessao.altura = dimensoes.altura;

    log(
        `[${sessao.id}] VIDEO PRONTO ` +
            `${sessao.largura}x${sessao.altura} ` +
            `${sessao.codec || "codec?"}`
    );
}

function tratarCaixa(sessao, tipo, caixa) {
    if (!sessao.init) {
        if (tipo === "ftyp") {
            sessao.ftyp = Buffer.from(caixa);
            return;
        }

        if (tipo === "moov") {
            sessao.moov = Buffer.from(caixa);
            prepararInit(sessao);
            return;
        }

        // "free", "styp" etc: irrelevante para o MSE.
        return;
    }

    if (tipo === "moof") {
        sessao.fragmento = { moof: Buffer.from(caixa), mdat: null };
        return;
    }

    if (tipo === "mdat") {
        if (!sessao.fragmento) {
            return;
        }

        sessao.fragmento.mdat = Buffer.from(caixa);
        sessao.bytes += sessao.fragmento.moof.length + caixa.length;

        publicarFragmento(sessao);

        return;
    }
}

function consumir(sessao, chunk) {
    sessao.pendente = sessao.pendente.length
        ? Buffer.concat([sessao.pendente, chunk])
        : chunk;

    while (sessao.pendente.length >= 8) {
        let tamanho = sessao.pendente.readUInt32BE(0);

        if (tamanho === 1) {
            if (sessao.pendente.length < 16) {
                return;
            }

            const grande = sessao.pendente.readBigUInt64BE(8);

            if (grande > BigInt(Number.MAX_SAFE_INTEGER)) {
                sessao.pendente = Buffer.alloc(0);
                return;
            }

            tamanho = Number(grande);
        }

        const tipo = sessao.pendente.toString("latin1", 4, 8);

        if (tamanho < 8 || tamanho > MAX_CAIXA_BYTES) {
            log(
                `[${sessao.id}] CAIXA MP4 INVALIDA ` +
                    `(${tipo}, ${tamanho} bytes) - reiniciando parser`
            );

            sessao.pendente = Buffer.alloc(0);
            sessao.fragmento = null;
            return;
        }

        if (sessao.pendente.length < tamanho) {
            return;
        }

        const caixa = sessao.pendente.subarray(0, tamanho);

        sessao.pendente = sessao.pendente.subarray(tamanho);

        tratarCaixa(sessao, tipo, caixa);
    }
}

// ------------------------------------------------------------
// CLIENTES HTTP (MSE)
// ------------------------------------------------------------

function escrever(cliente, buffer) {
    if (!cliente || cliente.fechado) {
        return;
    }

    try {
        cliente.res.write(buffer);
    } catch (erro) {
        cliente.fechado = true;
    }
}

function ativarCliente(sessao, cliente) {
    if (!sessao.init) {
        return;
    }

    escrever(cliente, sessao.init);
    cliente.ativo = true;
}

function publicarFragmento(sessao) {
    if (!sessao.fragmento || !sessao.fragmento.mdat) {
        return;
    }

    const fragmento = Buffer.concat([
        sessao.fragmento.moof,
        sessao.fragmento.mdat
    ]);

    sessao.fragmento = null;
    sessao.ultimoFragmento = fragmento;

    for (const cliente of sessao.clientes) {
        if (cliente.fechado) {
            sessao.clientes.delete(cliente);
            continue;
        }

        if (!cliente.ativo) {
            ativarCliente(sessao, cliente);
        }

        if (cliente.ativo) {
            escrever(cliente, fragmento);
        }
    }
}

function aceitarCliente(sessao, req, res) {
    res.writeHead(200, {
        ...CORS,
        "Content-Type": "video/mp4",
        "Cache-Control": "no-store, no-transform",
        Connection: "keep-alive",
        "X-Content-Type-Options": "nosniff"
    });

    if (res.socket) {
        res.socket.setNoDelay(true);
        res.socket.setTimeout(0);
    }

    const cliente = {
        res,
        ativo: false,
        fechado: false,
        desde: Date.now()
    };

    sessao.clientes.add(cliente);

    const remover = () => {
        cliente.fechado = true;
        sessao.clientes.delete(cliente);
    };

    req.on("close", remover);
    res.on("close", remover);
    res.on("error", remover);

    // Quem chega no meio da transmissao recebe o init e o
    // ultimo fragmento completo: comeca a tocar na hora.
    if (sessao.init) {
        ativarCliente(sessao, cliente);

        if (sessao.ultimoFragmento) {
            escrever(cliente, sessao.ultimoFragmento);
        }
    }

    log(
        `[${sessao.id}] STREAM CLIENTE +1 ` +
            `(${sessao.clientes.size} conectado(s))`
    );
}

// ------------------------------------------------------------
// FFMPEG: H.264 cru -> MP4 fragmentado
// ------------------------------------------------------------

function argumentosFfmpeg() {
    return [
        "-hide_banner",
        "-loglevel", "error",
        "-fflags", "nobuffer",
        "-flags", "low_delay",
        "-analyzeduration", "0",
        "-probesize", "131072",
        // O SPS do celular traz um VUI com timing estranho
        // (1.200.000 fps). -r 30 antes do -i forca 30 fps
        // reais no container, sem tocar no bitstream.
        "-framerate", "30",
        "-r", "30",
        "-f", "h264",
        "-i", "pipe:0",
        "-an",
        "-c:v", "copy",
        "-f", "mp4",
        "-movflags", "frag_keyframe+empty_moov+default_base_moof",
        "-flush_packets", "1",
        "pipe:1"
    ];
}

function iniciarFfmpeg(sessao) {
    if (!configuracao.ffmpegOk) {
        return null;
    }

    const ffmpeg = spawn(
        configuracao.ffmpeg,
        argumentosFfmpeg(),
        {
            stdio: ["pipe", "pipe", "pipe"],
            windowsHide: true
        }
    );

    let erroStderr = "";

    ffmpeg.on("error", (erro) => {
        sessao.erro = `ffmpeg: ${erro.message}`;
        log(`[${sessao.id}] ERRO FFMPEG: ${erro.message}`);
        encerrarSessao(sessao, "ffmpeg indisponivel");
    });

    ffmpeg.stderr.on("data", (dados) => {
        const texto = String(dados).trim();

        if (texto) {
            erroStderr = texto;

            log(`[${sessao.id}] ffmpeg: ${texto}`);
        }
    });

    ffmpeg.stdout.on("data", (chunk) => consumir(sessao, chunk));

    ffmpeg.on("close", (codigo) => {
        if (!sessao.encerrando && sessao.ativo) {
            sessao.erro =
                erroStderr || `ffmpeg encerrou (codigo ${codigo})`;

            log(`[${sessao.id}] FFMPEG ENCERROU: ${codigo}`);

            encerrarSessao(sessao, "ffmpeg encerrou");
        }
    });

    ffmpeg.stdin.on("error", () => {
        // O socket pode fechar antes; EPIPE e esperado.
    });

    return ffmpeg;
}

// ------------------------------------------------------------
// SESSOES
// ------------------------------------------------------------

function encerrarClientes(sessao) {
    for (const cliente of sessao.clientes) {
        cliente.fechado = true;

        try {
            cliente.res.end();
        } catch (erro) {
            // Ja fechado.
        }
    }

    sessao.clientes.clear();
}

function encerrarSessao(sessao, motivo) {
    if (!sessao || sessao.encerrando) {
        return;
    }

    sessao.encerrando = true;
    sessao.ativo = false;
    sessao.encerradaEm = Date.now();
    sessao.erro = sessao.erro || motivo || null;

    encerrarClientes(sessao);

    avisarControle(sessao, false);

    if (sessao.socket && !sessao.socket.destroyed) {
        sessao.socket.destroy();
    }

    if (sessao.ffmpeg && !sessao.ffmpeg.killed) {
        try {
            sessao.ffmpeg.kill();
        } catch (erro) {
            // Ja morreu.
        }
    }

    log(
        `[${sessao.id}] SESSAO ENCERRADA` +
            (motivo ? ` - ${motivo}` : "")
    );
}

function iniciarSessao(socket, id, nome, transporte, restante) {
    const anterior = sessoes.get(id);

    if (anterior && anterior.ativo) {
        encerrarSessao(anterior, "reconexao");
    }

    const sessao = {
        id,
        nome,
        transporte,
        socket,
        geracao: anterior ? anterior.geracao + 1 : 1,
        ativo: true,
        encerrando: false,
        iniciadoEm: Date.now(),
        encerradaEm: 0,
        erro: null,
        bytes: 0,
        init: null,
        codec: null,
        largura: 0,
        altura: 0,
        ftyp: null,
        moov: null,
        fragmento: null,
        ultimoFragmento: null,
        pendente: Buffer.alloc(0),
        clientes: new Set(),
        ffmpeg: null
    };

    sessoes.set(id, sessao);

    log("");
    log("ANDROID CONECTADO");
    log(`  id        : ${id}`);
    log(`  nome      : ${nome}`);
    log(`  transporte: ${transporte}`);
    log(`  origem    : ${socket.remoteAddress}:${socket.remotePort}`);
    log(`  sessoes   : ${sessoes.size}`);

    avisarControle(sessao, true);

    sessao.ffmpeg = iniciarFfmpeg(sessao);

    if (restante && restante.length) {
        if (sessao.ffmpeg) {
            sessao.ffmpeg.stdin.write(restante);
        }
    }

    if (sessao.ffmpeg) {
        socket.pipe(sessao.ffmpeg.stdin);
    } else {
        // Sem ffmpeg: mantem a conexao viva (evita o Android
        // reconectar em loop) e descarta os dados.
        socket.resume();
    }

    socket.on("error", (erro) => {
        log(`[${id}] ERRO SOCKET: ${erro.message}`);
        encerrarSessao(sessao, "erro de socket");
    });

    socket.on("close", () => {
        encerrarSessao(sessao, "android desconectou");
    });

    socket.on("end", () => {
        encerrarSessao(sessao, "android encerrou");
    });
}

// ------------------------------------------------------------
// HANDSHAKE (identico ao live-receiver)
// ------------------------------------------------------------

function pareceH264(buffer) {
    if (buffer.length < 3) {
        return false;
    }

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
        .replace(/[^0-9a-z]/gi, "-");

    return `legacy-${ip}`;
}

function tratarConexao(socket) {
    socket.setNoDelay(true);

    let buffer = Buffer.alloc(0);
    let decidido = false;

    const timer = setTimeout(() => {
        if (!decidido) {
            log(
                `HANDSHAKE TIMEOUT: ` +
                    `${socket.remoteAddress}:${socket.remotePort}`
            );

            socket.destroy();
        }
    }, HANDSHAKE_TIMEOUT_MS);

    function decidir() {
        if (decidido) {
            return true;
        }

        if (pareceH264(buffer)) {
            decidido = true;
            clearTimeout(timer);

            socket.pause();
            socket.removeAllListeners("data");

            const restante = buffer;

            iniciarSessao(
                socket,
                idLegado(socket),
                "android",
                "usb",
                restante
            );

            socket.resume();

            return true;
        }

        const quebra = buffer.indexOf(0x0a);

        if (quebra < 0) {
            return false;
        }

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

        buffer = buffer.subarray(quebra + 1);

        return false;
    }

    socket.on("data", (chunk) => {
        if (decidido) {
            return;
        }

        buffer = Buffer.concat([buffer, chunk]);

        if (decidir()) {
            return;
        }

        if (buffer.length > MAX_HANDSHAKE_BYTES) {
            log(
                "HANDSHAKE INVALIDO: " +
                    `${socket.remoteAddress}:${socket.remotePort} ` +
                    `(${buffer.length} bytes)`
            );

            clearTimeout(timer);
            socket.destroy();
        }
    });

    socket.on("error", (erro) => {
        if (!decidido) {
            clearTimeout(timer);
        }

        log(`ERRO NO HANDSHAKE: ${erro.message}`);
    });

    socket.on("close", () => clearTimeout(timer));
}

// ------------------------------------------------------------
// HTTP /info e /stream
// ------------------------------------------------------------

function infoSessao(sessao) {
    return {
        id: sessao.id,
        nome: sessao.nome,
        transporte: sessao.transporte,
        ativo: sessao.ativo,
        erro: sessao.erro,
        geracao: sessao.geracao,
        codec: sessao.codec,
        largura: sessao.largura,
        altura: sessao.altura,
        clientes: sessao.clientes.size,
        bytes: sessao.bytes,
        ffmpeg: Boolean(configuracao.ffmpegOk),
        iniciadoEm: sessao.iniciadoEm
    };
}

function tratarHttp(req, res) {
    if (req.method === "OPTIONS") {
        res.writeHead(204, CORS);
        return res.end();
    }

    if (req.method !== "GET") {
        return responderJson(res, 405, { ok: false, erro: "metodo" });
    }

    let url;

    try {
        url = new URL(req.url, "http://127.0.0.1");
    } catch (erro) {
        return responderJson(res, 400, { ok: false });
    }

    const partes = url.pathname.split("/").filter(Boolean);

    if (url.pathname === "/health") {
        return responderJson(res, 200, {
            ok: true,
            ffmpeg: configuracao.ffmpeg,
            ffmpegOk: configuracao.ffmpegOk,
            sessoes: sessoes.size,
            portaVideo: PORTA_VIDEO,
            portaStream: PORTA_STREAM
        });
    }

    if (partes[0] === "info") {
        if (!partes[1]) {
            return responderJson(res, 200, {
                sessoes: [...sessoes.values()].map(infoSessao)
            });
        }

        const id = decodeURIComponent(partes[1]);
        const sessao = sessoes.get(id);

        if (!sessao) {
            return responderJson(res, 404, {
                ok: false,
                ativo: false,
                id,
                erro: "sem sinal"
            });
        }

        return responderJson(res, 200, infoSessao(sessao));
    }

    if (partes[0] === "stream") {
        const id = decodeURIComponent(partes[1] || "");
        const sessao = sessoes.get(id);

        if (!sessao || !sessao.ativo) {
            return responderJson(res, 404, {
                ok: false,
                ativo: false,
                erro: "sem sinal"
            });
        }

        return aceitarCliente(sessao, req, res);
    }

    return responderJson(res, 404, { ok: false });
}

// ------------------------------------------------------------
// START / STOP
// ------------------------------------------------------------

function abrirVideo() {
    return new Promise((resolve, reject) => {
        servidorVideo = net.createServer(tratarConexao);

        servidorVideo.once("error", reject);

        servidorVideo.listen(PORTA_VIDEO, "0.0.0.0", () => {
            servidorVideo.removeListener("error", reject);

            log(`H264 aguardando na porta ${PORTA_VIDEO}`);

            resolve();
        });
    });
}

function abrirStream() {
    return new Promise((resolve, reject) => {
        servidorStream = http.createServer(tratarHttp);

        servidorStream.once("error", reject);

        servidorStream.listen(PORTA_STREAM, "127.0.0.1", () => {
            servidorStream.removeListener("error", reject);

            log(`Stream fMP4 em http://127.0.0.1:${PORTA_STREAM}`);

            resolve();
        });
    });
}

async function iniciar(opcoes = {}) {
    if (configuracao) {
        return api;
    }

    configuracao = {
        ffmpeg: opcoes.ffmpeg || process.env.FFMPEG_PATH || "ffmpeg",
        ffmpegOk: opcoes.ffmpegOk !== false,
        portaControle: opcoes.portaControle || PORTA_CONTROLE,
        log: opcoes.log
    };

    await abrirVideo();
    await abrirStream();

    // Se o servidor de controle reiniciar, ele volta a saber
    // quem esta transmitindo sem exigir reconexao do celular.
    intervaloAnuncio = setInterval(() => {
        for (const sessao of sessoes.values()) {
            if (sessao.ativo) {
                avisarControle(sessao, true);
            }
        }
    }, 3000);

    intervaloAnuncio.unref();

    intervaloLimpeza = setInterval(() => {
        const agora = Date.now();

        for (const [id, sessao] of sessoes) {
            if (
                !sessao.ativo &&
                agora - sessao.encerradaEm > RETENCAO_SESSAO_MS
            ) {
                sessoes.delete(id);
            }
        }
    }, 10000);

    intervaloLimpeza.unref();

    return api;
}

function fechar() {
    if (intervaloLimpeza) {
        clearInterval(intervaloLimpeza);
        intervaloLimpeza = null;
    }

    if (intervaloAnuncio) {
        clearInterval(intervaloAnuncio);
        intervaloAnuncio = null;
    }

    for (const sessao of sessoes.values()) {
        encerrarSessao(sessao, "app encerrado");
    }

    sessoes.clear();

    if (servidorVideo) {
        servidorVideo.close();
        servidorVideo = null;
    }

    if (servidorStream) {
        servidorStream.close();
        servidorStream = null;
    }

    configuracao = null;
}

const api = {
    iniciar,
    fechar,
    sessoes,
    portas: {
        video: PORTA_VIDEO,
        stream: PORTA_STREAM
    },
    info(id) {
        const sessao = sessoes.get(id);

        return sessao ? infoSessao(sessao) : null;
    }
};

module.exports = api;
