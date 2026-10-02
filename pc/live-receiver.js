const net = require("net");
const { spawn } = require("child_process");

const PORT = 5051;

const server = net.createServer((socket) => {

    console.log("");
    console.log("ANDROID CONECTADO");
    console.log(`${socket.remoteAddress}:${socket.remotePort}`);

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
    { stdio: ["pipe", "inherit", "inherit"] }
);

    console.log("FFPLAY INICIADO");

    socket.pipe(ffplay.stdin);
    socket.on("end", () => {
    console.log("ANDROID DESCONECTADO");
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
    console.log(`H264 AO VIVO aguardando na porta ${PORT}`);
});