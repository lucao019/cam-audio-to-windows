const { execFile, spawn } = require("child_process");
const net = require("net");
const os = require("os");
const fs = require("fs");
const path = require("path");

// ============================================================
// LUCAO LINK - WINDOWS COMPANION
//
// - sobe server.js (5050) e live-receiver.js (5051)
// - instala/atualiza o APK quando o build muda
// - configura adb reverse por aparelho (5050/5051)
// - abre o app passando o IP da LAN (modo Wi-Fi)
// - funciona com varios aparelhos simultaneos
// ============================================================

const RAIZ = path.resolve(__dirname, "..", "..");
const APK = path.join(
    RAIZ,
    "android",
    "app",
    "build",
    "outputs",
    "apk",
    "debug",
    "app-debug.apk"
);
const ESTADO = path.join(__dirname, ".state.json");
const PACOTE = "com.lucao.link";

const INTERVALO_MS = 2000;

let processoReceiver = null;

// serial -> { apkMtime, apkTamanho, instaladoEm }
let estado = {};

// serial -> { assinatura } ja configurado nesta execucao
const configurados = new Map();

// ------------------------------------------------------------
// UTIL
// ------------------------------------------------------------

function executarADB(argumentos, opcoes = {}) {
    return new Promise((resolve, reject) => {
        execFile(
            "adb",
            argumentos,
            {
                timeout: opcoes.timeout || 45000,
                maxBuffer: 8 * 1024 * 1024
            },
            (erro, stdout, stderr) => {
                if (erro) {
                    erro.stdout = stdout;
                    erro.stderr = stderr;
                    reject(erro);
                    return;
                }

                resolve(String(stdout).trim());
            }
        );
    });
}

function dormir(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function ipLocal() {
    const interfaces = os.networkInterfaces();

    for (const nome of Object.keys(interfaces)) {
        for (const net of interfaces[nome] || []) {
            if (net.family === "IPv4" && !net.internal) {
                return net.address;
            }
        }
    }

    return "";
}

function portaEmUso(porta) {
    return new Promise(resolve => {
        const socket = new net.Socket();

        socket.setTimeout(500);

        socket.once("connect", () => {
            socket.destroy();
            resolve(true);
        });

        socket.once("timeout", () => {
            socket.destroy();
            resolve(false);
        });

        socket.once("error", () => resolve(false));

        socket.connect(porta, "127.0.0.1");
    });
}

function carregarEstado() {
    try {
        estado = JSON.parse(fs.readFileSync(ESTADO, "utf8"));
    } catch (erro) {
        estado = {};
    }
}

function salvarEstado() {
    try {
        fs.writeFileSync(
            ESTADO,
            JSON.stringify(estado, null, 2),
            "utf8"
        );
    } catch (erro) {
        console.error(
            "Nao foi possivel salvar .state.json:",
            erro.message
        );
    }
}

function assinaturaApk() {
    try {
        const info = fs.statSync(APK);

        return {
            apkMtime: Math.round(info.mtimeMs),
            apkTamanho: info.size
        };
    } catch (erro) {
        return null;
    }
}

// ------------------------------------------------------------
// SERVIDORES
// ------------------------------------------------------------

async function garantirServidorHTTP() {
    if (await portaEmUso(5050)) {
        return;
    }

    console.log("Iniciando servidor HTTP 5050...");

    const serverPath = path.join(RAIZ, "server.js");

    spawn(process.execPath, [serverPath], {
        cwd: path.dirname(serverPath),
        stdio: "ignore",
        windowsHide: true
    });
}

async function garantirReceiverVideo() {
    if (await portaEmUso(5051)) {
        return;
    }

    if (
        processoReceiver &&
        processoReceiver.exitCode === null &&
        !processoReceiver.killed
    ) {
        return;
    }

    console.log("Iniciando receiver de video 5051...");

    const receiverPath = path.join(RAIZ, "pc", "live-receiver.js");

    processoReceiver = spawn(process.execPath, [receiverPath], {
        cwd: path.dirname(receiverPath),
        stdio: "inherit",
        windowsHide: false
    });

    processoReceiver.on("exit", () => {
        processoReceiver = null;
    });
}

// ------------------------------------------------------------
// APARELHOS
// ------------------------------------------------------------

async function listarDispositivos() {
    const saida = await executarADB(["devices"]);

    return saida
        .split(/\r?\n/)
        .slice(1)
        .map(linha => linha.trim())
        .filter(Boolean)
        .map(linha => {
            const [serial, estadoDevice] = linha.split(/\s+/);
            return { serial, estado: estadoDevice };
        })
        .filter(item => item.estado === "device");
}

async function appInstalado(serial) {
    try {
        const saida = await executarADB([
            "-s",
            serial,
            "shell",
            "pm",
            "list",
            "packages",
            PACOTE
        ]);

        return saida.includes(`package:${PACOTE}`);
    } catch (erro) {
        return false;
    }
}

async function acordarAparelho(serial) {
    try {
        await executarADB([
            "-s",
            serial,
            "shell",
            "input",
            "keyevent",
            "KEYCODE_WAKEUP"
        ]);

        await executarADB([
            "-s",
            serial,
            "shell",
            "input",
            "swipe",
            "540",
            "1800",
            "540",
            "600",
            "250"
        ]);
    } catch (erro) {
        // Sem tela / sem permissao: segue em frente.
    }
}

async function instalarApk(serial) {
    console.log(`[${serial}] Instalando APK...`);

    // Em instalacoes limpas o verificador do MIUI bloqueia.
    try {
        await executarADB([
            "-s",
            serial,
            "shell",
            "settings",
            "put",
            "global",
            "verifier_verify_adb_installs",
            "0"
        ]);
    } catch (erro) {
        // Nem todo aparelho permite; nao e fatal.
    }

    try {
        await executarADB(
            ["-s", serial, "install", "-r", APK],
            { timeout: 240000 }
        );

        return true;

    } catch (erro) {
        const mensagem =
            (erro.stderr || "") + (erro.message || "");

        console.error(
            `[${serial}] Falha na instalacao: ${mensagem.trim()}`
        );

        if (
            mensagem.includes("USER_RESTRICTED") ||
            mensagem.includes("VERIFICATION_FAILURE")
        ) {
            console.log(
                `[${serial}] Acordando o aparelho e tentando novamente...`
            );

            await acordarAparelho(serial);
            await dormir(1500);

            try {
                await executarADB(
                    ["-s", serial, "install", "-r", APK],
                    { timeout: 240000 }
                );

                return true;

            } catch (segunda) {
                console.error(
                    `[${serial}] Instalacao cancelada no aparelho. ` +
                        "Desbloqueie a tela e rode o companion de novo."
                );
            }
        }

        return false;
    }
}

async function garantirApp(serial) {
    const assinatura = assinaturaApk();

    if (!assinatura) {
        console.error(
            "APK nao encontrado. Compile antes:\n" +
                "  cd android && gradlew.bat :app:assembleDebug"
        );
        return;
    }

    const instalado = await appInstalado(serial);
    const anterior = estado[serial];

    const precisaInstalar =
        !instalado ||
        !anterior ||
        anterior.apkMtime !== assinatura.apkMtime ||
        anterior.apkTamanho !== assinatura.apkTamanho;

    if (!precisaInstalar) {
        console.log(`[${serial}] APK atualizado.`);
        return;
    }

    if (!instalado) {
        console.log(`[${serial}] LUCAO LINK ainda nao instalado.`);
    } else {
        console.log(`[${serial}] Build novo detectado.`);
    }

    const ok = await instalarApk(serial);

    if (ok) {
        estado[serial] = {
            ...assinatura,
            instaladoEm: new Date().toISOString()
        };

        salvarEstado();
        console.log(`[${serial}] APK instalado.`);
    }
}

async function configurarAparelho(serial) {
    const host = ipLocal();

    // 1. tuneis USB
    try {
        await executarADB([
            "-s",
            serial,
            "reverse",
            "--remove-all"
        ]);
    } catch (erro) {
        // Alguns aparelhos nao tem tuneis antigos.
    }

    await executarADB([
        "-s",
        serial,
        "reverse",
        "tcp:5050",
        "tcp:5050"
    ]);

    await executarADB([
        "-s",
        serial,
        "reverse",
        "tcp:5051",
        "tcp:5051"
    ]);

    // 2. APK
    await garantirApp(serial);

    // 3. abre o app ja com o IP da LAN
    const argumentos = [
        "-s",
        serial,
        "shell",
        "am",
        "start",
        "-n",
        `${PACOTE}/.MainActivity`
    ];

    if (host) {
        argumentos.push("--es", "pcHost", host);
    }

    await executarADB(argumentos);

    let modelo = serial;

    try {
        modelo = await executarADB([
            "-s",
            serial,
            "shell",
            "getprop",
            "ro.product.model"
        ]);
    } catch (erro) {
        // Mantem o serial.
    }

    console.log(
        `[${serial}] ${modelo} pronto ` +
            `(USB 5050/5051${host ? " + Wi-Fi " + host : ""})`
    );
}

// ------------------------------------------------------------
// MONITOR
// ------------------------------------------------------------

async function monitorar() {
    console.log("");
    console.log("==============================");
    console.log("     LUCAO LINK COMPANION");
    console.log("==============================");
    console.log("");

    carregarEstado();

    const assinatura = assinaturaApk();

    if (assinatura) {
        console.log(
            `APK: ${APK}\n` +
                `     ${assinatura.apkTamanho} bytes\n`
        );
    }

    console.log("Monitorando aparelhos USB / Wi-Fi ADB...\n");

    const avisados = new Set();
    let ultimaContagem = -1;

    while (true) {
        try {
            await garantirServidorHTTP();
            await garantirReceiverVideo();

            const dispositivos = await listarDispositivos();
            const seriais = new Set(
                dispositivos.map(item => item.serial)
            );

            for (const serial of [...configurados.keys()]) {
                if (!seriais.has(serial)) {
                    configurados.delete(serial);
                }
            }

            const assinaturaAtual = assinaturaApk();

            const chave = assinaturaAtual
                ? `${assinaturaAtual.apkMtime}:${assinaturaAtual.apkTamanho}`
                : "sem-apk";

            for (const { serial } of dispositivos) {
                if (configurados.get(serial) === chave) {
                    continue;
                }

                try {
                    await configurarAparelho(serial);
                    configurados.set(serial, chave);
                    avisados.delete(serial);

                } catch (erro) {
                    if (!avisados.has(serial)) {
                        console.error(
                            `[${serial}] Erro: ${erro.message}`
                        );
                        avisados.add(serial);
                    }
                }
            }

            if (dispositivos.length !== ultimaContagem) {
                console.log(
                    `Aparelhos ativos: ${dispositivos.length}`
                );

                if (dispositivos.length === 0) {
                    console.log("Nenhum Android disponivel.");
                }

                ultimaContagem = dispositivos.length;
            }

        } catch (erro) {
            console.error(
                "Erro durante monitoramento:",
                erro.message
            );
        }

        await dormir(INTERVALO_MS);
    }
}

monitorar();
