const { app, BrowserWindow, Menu, dialog, ipcMain, shell } = require("electron");
const path = require("path");
const fs = require("fs");
const { execFile } = require("child_process");

const servidor = require("../../server.js");
const bridge = require("./video-bridge.js");
const companion = require("../../pc/companion/companion.js");

// ============================================================
// LUCAO LINK - DESKTOP
//
// Uma janela unica:
//   - 5050: API de controle + UI do app
//   - 5051: video H.264 vindo do Android
//   - 5052: mesmo video em fMP4 para o <video> (MSE)
//
// O companion roda em segundo plano para instalar/abrir o
// APK e criar os tuneis USB (adb reverse).
// ============================================================

const PORTA_CONTROLE = 5050;

let janela = null;
let ffmpegInfo = {
    caminho: process.env.FFMPEG_PATH || "ffmpeg",
    ok: false,
    versao: ""
};

let encerrando = false;

// ------------------------------------------------------------
// LOG
// ------------------------------------------------------------

function registrarLog(mensagem) {
    const linha = `[${new Date().toISOString()}] ${mensagem}`;

    console.log(mensagem);

    try {
        fs.appendFileSync(
            path.join(app.getPath("userData"), "lucao-link.log"),
            linha + "\n",
            "utf8"
        );
    } catch (erro) {
        // Log em arquivo e opcional.
    }
}

// ------------------------------------------------------------
// FFMPEG
// ------------------------------------------------------------

function caminhosFfmpeg() {
    const lista = [];

    if (process.env.FFMPEG_PATH) {
        lista.push(process.env.FFMPEG_PATH);
    }

    if (process.resourcesPath) {
        lista.push(path.join(process.resourcesPath, "ffmpeg.exe"));
        lista.push(
            path.join(process.resourcesPath, "ffmpeg", "ffmpeg.exe")
        );
    }

    lista.push("ffmpeg");

    return lista;
}

function detectarFfmpeg() {
    return new Promise((resolve) => {
        const candidatos = caminhosFfmpeg();

        function tentar(indice) {
            if (indice >= candidatos.length) {
                ffmpegInfo = {
                    caminho: candidatos[0],
                    ok: false,
                    versao: ""
                };

                registrarLog(
                    "FFMPEG NAO ENCONTRADO. Instale com " +
                        "'winget install Gyan.FFmpeg' ou defina FFMPEG_PATH."
                );

                return resolve(ffmpegInfo);
            }

            const caminho = candidatos[indice];

            execFile(
                caminho,
                ["-version"],
                { timeout: 5000, windowsHide: true },
                (erro, stdout) => {
                    if (erro) {
                        return tentar(indice + 1);
                    }

                    const primeira = String(stdout || "")
                        .split(/\r?\n/)[0]
                        .trim();

                    ffmpegInfo = {
                        caminho,
                        ok: true,
                        versao: primeira
                    };

                    registrarLog(`FFMPEG: ${primeira} (${caminho})`);

                    resolve(ffmpegInfo);
                }
            );
        }

        tentar(0);
    });
}

// ------------------------------------------------------------
// JANELA
// ------------------------------------------------------------

function criarJanela() {
    const icone = path.join(__dirname, "icone.ico");

    janela = new BrowserWindow({
        width: 1360,
        height: 860,
        minWidth: 1000,
        minHeight: 640,
        show: false,
        frame: false,
        backgroundColor: "#050505",
        title: "LUCAO LINK",
        icon: fs.existsSync(icone) ? icone : undefined,
        webPreferences: {
            preload: path.join(__dirname, "preload.js"),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: false,
            backgroundThrottling: false
        }
    });

    janela.once("ready-to-show", () => janela.show());

    // O painel e servido pelo nosso servidor local, que ja manda
    // no-cache. Mas o Chromium ainda guarda HTML/JS/CSS em memoria
    // entre aberturas. Isso garante que cada abertura pegue a versao
    // atual do disco, sem precisar de Ctrl+R manual.
    janela.webContents.session
        .clearCache()
        .catch(() => {})
        .finally(() => {
            janela.loadURL(`http://127.0.0.1:${PORTA_CONTROLE}/app/`);
        });

    janela.webContents.setWindowOpenHandler(({ url }) => {
        shell.openExternal(url);
        return { action: "deny" };
    });

    janela.webContents.on("will-navigate", (evento, url) => {
        if (!url.startsWith(`http://127.0.0.1:${PORTA_CONTROLE}/`)) {
            evento.preventDefault();
        }
    });

    janela.webContents.on("before-input-event", (evento, entrada) => {
        const devtools =
            entrada.key === "F12" ||
            (entrada.control && entrada.shift && entrada.key === "I");

        if (devtools && entrada.type === "keyDown") {
            janela.webContents.toggleDevTools();
        }
    });

    janela.on("maximize", () => {
        if (janela) {
            janela.webContents.send("janela:maximizada", true);
        }
    });

    janela.on("unmaximize", () => {
        if (janela) {
            janela.webContents.send("janela:maximizada", false);
        }
    });

    janela.on("closed", () => {
        janela = null;
    });
}

function configurarIpc() {
    ipcMain.on("janela:minimizar", () => {
        if (janela) {
            janela.minimize();
        }
    });

    ipcMain.on("janela:maximizar", () => {
        if (!janela) {
            return;
        }

        if (janela.isMaximized()) {
            janela.unmaximize();
        } else {
            janela.maximize();
        }
    });

    ipcMain.on("janela:fechar", () => {
        app.quit();
    });

    ipcMain.handle("app:info", () => ({
        versao: servidor.VERSAO,
        host: servidor.HOST,
        porta: PORTA_CONTROLE,
        portaVideo: bridge.portas.video,
        portaStream: bridge.portas.stream,
        empacotado: app.isPackaged,
        ffmpeg: ffmpegInfo,
        log: path.join(app.getPath("userData"), "lucao-link.log")
    }));
}

// ------------------------------------------------------------
// VIGIA DE VIDEO
//
// Se um aparelho esta online mas o bridge nao tem sessao,
// pede para ele reconectar. Resolve o caso de socket antigo
// preso quando o app do PC e reaberto.
// ------------------------------------------------------------

const pedidosReconexao = new Map();

async function vigiarVideo() {
    let dados = null;

    try {
        const resposta = await fetch(
            `http://127.0.0.1:${PORTA_CONTROLE}/api/devices`,
            { cache: "no-store" }
        );

        if (!resposta.ok) {
            return;
        }

        dados = await resposta.json();
    } catch (erro) {
        return;
    }

    const agora = Date.now();

    for (const aparelho of dados.aparelhos || []) {
        if (!aparelho.online) {
            continue;
        }

        const sessao = bridge.info(aparelho.id);

        // Sessao saudavel (ou recem-aberta): nao mexer.
        if (
            sessao &&
            sessao.ativo &&
            (sessao.codec || agora - sessao.iniciadoEm < 15000)
        ) {
            continue;
        }

        const ultimo = pedidosReconexao.get(aparelho.id) || 0;

        if (agora - ultimo < 8000) {
            continue;
        }

        pedidosReconexao.set(aparelho.id, agora);

        fetch(
            `http://127.0.0.1:${PORTA_CONTROLE}/api/devices/` +
                `${encodeURIComponent(aparelho.id)}/reconectar`,
            { method: "POST" }
        ).catch(() => {});
    }
}

// ------------------------------------------------------------
// START
// ------------------------------------------------------------

async function iniciar() {
    // Uma janela so. Se a anterior ainda estiver fechando
    // (bridge/ffmpeg desligando), tenta de novo por alguns
    // segundos em vez de sair sem mostrar nada.
    let dono = app.requestSingleInstanceLock();

    for (let tentativa = 1; !dono && tentativa <= 6; tentativa++) {
        await new Promise((resolver) => setTimeout(resolver, 1000));
        dono = app.requestSingleInstanceLock();
    }

    if (!dono) {
        app.quit();
        return;
    }

    app.on("second-instance", () => {
        if (janela) {
            if (janela.isMinimized()) {
                janela.restore();
            }

            janela.focus();
        }
    });

    // Video comeca sozinho: sem exigir clique para dar play.
    app.commandLine.appendSwitch(
        "autoplay-policy",
        "no-user-gesture-required"
    );

    // Agrupa a janela/taskbar com o proprio app no Windows.
    app.setAppUserModelId("com.lucao.link.desktop");

    await app.whenReady();

    Menu.setApplicationMenu(null);

    configurarIpc();

    await detectarFfmpeg();

    try {
        await servidor.iniciar(PORTA_CONTROLE);
    } catch (erro) {
        const mensagem =
            erro.code === "EADDRINUSE"
                ? `A porta ${PORTA_CONTROLE} ja esta em uso.\n\n` +
                  "Feche outro LUCAO LINK / node server.js e abra de novo."
                : `Falha ao abrir a porta ${PORTA_CONTROLE}:\n${erro.message}`;

        registrarLog(mensagem);

        dialog.showErrorBox("LUCAO LINK", mensagem);

        app.quit();
        return;
    }

    try {
        await bridge.iniciar({
            ffmpeg: ffmpegInfo.caminho,
            ffmpegOk: ffmpegInfo.ok,
            log: registrarLog
        });
    } catch (erro) {
        const mensagem =
            erro.code === "EADDRINUSE"
                ? `A porta ${bridge.portas.video} ja esta em uso.\n\n` +
                  "Feche o live-receiver.js ou outro LUCAO LINK."
                : `Falha ao abrir o video na porta ` +
                  `${bridge.portas.video}:\n${erro.message}`;

        registrarLog(mensagem);

        dialog.showErrorBox("LUCAO LINK", mensagem);

        app.quit();
        return;
    }

    // USB (adb): instala o APK, cria adb reverse e abre o app.
    // Nao sobe servidores duplicados: o app ja e o servidor.
    companion
        .monitorar({
            servidores: false,
            estado: path.join(app.getPath("userData"), "companion-state.json")
        })
        .catch((erro) => registrarLog(`companion: ${erro.message}`));

    criarJanela();

    setInterval(vigiarVideo, 4000).unref();

    registrarLog(
        `APP PRONTO - controle ${servidor.HOST}:${PORTA_CONTROLE} ` +
            `- video tcp ${bridge.portas.video} ` +
            `- stream ${bridge.portas.stream}`
    );
}

app.on("before-quit", () => {
    if (encerrando) {
        return;
    }

    encerrando = true;

    try {
        bridge.fechar();
    } catch (erro) {
        // Sem bridge.
    }
});

app.on("window-all-closed", () => {
    app.quit();
});

iniciar().catch((erro) => {
    console.error(erro);
    dialog.showErrorBox("LUCAO LINK", erro.stack || erro.message);
    app.quit();
});
