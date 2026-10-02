const net = require("net");
const { spawn } = require("child_process");

const PORT = 5051;

let socketAtivo = null;
let ffplayAtivo = null;

function encerrarSessaoAnterior() {
    if (socketAtivo && !socketAtivo.destroyed) {
        socketAtivo.destroy();
    }

    if (ffplayAtivo && !ffplayAtivo.killed) {
        ffplayAtivo.kill();
    }

    socketAtivo = null;
    ffplayAtivo = null;
}

const server = net.createServer((socket) => {

    console.log("");
    console.log("ANDROID CONECTADO");
    console.log(`${socket.remoteAddress}:${socket.remotePort}`);

    // Nunca permitir várias sessões simultâneas.
    encerrarSessaoAnterior();

    socketAtivo = socket;

        const ffplay = spawn(
        "ffplay",
        [
            "-loglevel", "warning",
            "-fflags", "nobuffer",
            "-flags", "low_delay",
            "-framedrop",
            "-analyzeduration", "0",
            "-probesize", "32",
            "-framerate", "30",
            "-f", "h264",
            "-i", "pipe:0"
        ],
                {
            stdio: ["pipe", "inherit", "inherit"],
            windowsHide: false
        }
    );

    ffplayAtivo = ffplay;

    console.log("FFPLAY INICIADO");

    socket.pipe(ffplay.stdin);

    socket.on("end", () => {
        console.log("ANDROID DESCONECTADO");

        if (socketAtivo === socket) {
            socketAtivo = null;
        }

        if (!ffplay.killed) {
            ffplay.kill();
        }
    });

    socket.on("close", () => {
        if (socketAtivo === socket) {
            socketAtivo = null;
        }
    });

    socket.on("error", (error) => {
        console.error(
            "ERRO SOCKET:",
            error.message
        );
    });

    ffplay.on("close", (code) => {
        console.log(
            `FFPLAY ENCERRADO: ${code}`
        );

        if (ffplayAtivo === ffplay) {
            ffplayAtivo = null;
        }
    });

    ffplay.on("error", (error) => {
        console.error(
            "ERRO AO ABRIR FFPLAY:",
            error.message
        );
    });
});

server.listen(PORT, "0.0.0.0", () => {
    console.log("LUCAO LINK - LIVE RECEIVER");
    console.log(
        `H264 AO VIVO aguardando na porta ${PORT}`
    );
});