const { execFile, spawn } = require("child_process");
const net = require("net");
const path = require("path");

let processoReceiver = null;

function executarADB(argumentos) {
    return new Promise((resolve, reject) => {
        execFile("adb", argumentos, (erro, stdout) => {
            if (erro) {
                reject(erro);
                return;
            }

            resolve(stdout.trim());
        });
    });
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

        socket.once("error", () => {
            resolve(false);
        });

        socket.connect(porta, "127.0.0.1");
    });
}

async function garantirServidorHTTP() {
    if (await portaEmUso(5050)) {
        console.log("Servidor HTTP 5050 jÃ¡ estÃ¡ ativo.");
        return;
    }

    console.log("Iniciando servidor HTTP 5050...");

    const serverPath = path.resolve(
        __dirname,
        "..",
        "..",
        "server.js"
    );

    spawn(
        process.execPath,
        [serverPath],
        {
            cwd: path.dirname(serverPath),
            stdio: "ignore",
            windowsHide: true
        }
    );

    console.log("server.js iniciado.");
}

async function garantirReceiverVideo() {
    if (
        processoReceiver &&
        processoReceiver.exitCode === null &&
        !processoReceiver.killed
    ) {
        console.log("Receiver de vÃ­deo 5051 jÃ¡ estÃ¡ ativo.");
        return;
    }

    console.log("Iniciando receiver de vÃ­deo 5051...");

    const receiverPath = path.resolve(
        __dirname,
        "..",
        "live-receiver.js"
    );

    processoReceiver = spawn(
        process.execPath,
        [receiverPath],
        {
            cwd: path.dirname(receiverPath),
            stdio: "inherit",
            windowsHide: false
        }
    );

    processoReceiver.on("exit", () => {
        processoReceiver = null;
    });

    console.log("live-receiver.js iniciado.");
}

async function iniciar() {
    try {
        await garantirServidorHTTP();
        await garantirReceiverVideo();
        const saida = await executarADB(["devices"]);

        const dispositivos = saida
            .split(/\r?\n/)
            .slice(1)
            .map(linha => linha.trim())
            .filter(Boolean)
            .map(linha => {
                const [serial, estado] = linha.split(/\s+/);

                return { serial, estado };
            });

        const disponiveis = dispositivos.filter(
            dispositivo => dispositivo.estado === "device"
        );

        if (disponiveis.length === 0) {
            console.log("Nenhum Android disponÃ­vel.");
            return;
        }

        for (const dispositivo of disponiveis) {
            const serial = dispositivo.serial;

            console.log("Configurando tÃºneis USB...");

await executarADB([
    "-s",
    serial,
    "reverse",
    "--remove-all"
]);

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

console.log("ADB Reverse configurado:");
console.log("5050 -> 5050");
console.log("5051 -> 5051");

            const fabricante = await executarADB([
                "-s",
                serial,
                "shell",
                "getprop",
                "ro.product.manufacturer"
            ]);

            const modelo = await executarADB([
                "-s",
                serial,
                "shell",
                "getprop",
                "ro.product.model"
            ]);

            console.log("ANDROID DETECTADO");
            console.log(`Serial: ${serial}`);
            console.log(`Fabricante: ${fabricante}`);
            console.log(`Modelo: ${modelo}`);
            console.log("Abrindo LUCAO LINK no Android...");

await executarADB([
    "-s",
    serial,
    "shell",
    "monkey",
    "-p",
    "com.lucao.link",
    "1"
]);

console.log("LUCAO LINK iniciado.");

        }
    } catch (erro) {
        console.error("Erro no Windows Companion:");
        console.error(erro.message);
    }
}

async function monitorar() {
    console.log("LUCAO LINK Companion ativo.");
    console.log("Monitorando dispositivos USB...\n");

    let dispositivoAnterior = null;

    while (true) {
        try {
            const saida = await executarADB(["devices"]);

            const dispositivos = saida
                .split(/\r?\n/)
                .slice(1)
                .map(linha => linha.trim())
                .filter(Boolean)
                .map(linha => {
                    const [serial, estado] = linha.split(/\s+/);
                    return { serial, estado };
                });

            const dispositivo = dispositivos.find(
                item => item.estado === "device"
            );

            const serialAtual = dispositivo
                ? dispositivo.serial
                : null;

            // Detectou mudanÃ§a no USB
            if (serialAtual !== dispositivoAnterior) {

                if (serialAtual) {
                    console.log(
                        `Android conectado: ${serialAtual}`
                    );

                    console.log("Restaurando LUCAO LINK...");

                    await garantirServidorHTTP();
                    await garantirReceiverVideo();

                    await executarADB([
                        "-s",
                        serialAtual,
                        "reverse",
                        "--remove-all"
                    ]);

                    await executarADB([
                        "-s",
                        serialAtual,
                        "reverse",
                        "tcp:5050",
                        "tcp:5050"
                    ]);

                    await executarADB([
                        "-s",
                        serialAtual,
                        "reverse",
                        "tcp:5051",
                        "tcp:5051"
                    ]);

                    await executarADB([
                        "-s",
                        serialAtual,
                        "shell",
                        "monkey",
                        "-p",
                        "com.lucao.link",
                        "1"
                    ]);

                    console.log("LUCAO LINK restaurado.");

                } else {
                    console.log("Android desconectado.");
                }

                dispositivoAnterior = serialAtual;
            }



        } catch (erro) {
            console.error(
                "Erro durante monitoramento:",
                erro.message
            );
        }

        // Verifica novamente a cada 2 segundos
        await new Promise(resolve =>
            setTimeout(resolve, 2000)
        );
    }
}

monitorar();
