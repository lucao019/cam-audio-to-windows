const net = require("net");
const fs = require("fs");
const path = require("path");

const PORT = 5051;

const outputPath = path.join(__dirname, "capture.h264");

let totalBytes = 0;

const server = net.createServer((socket) => {

    console.log("");
    console.log("ANDROID CONECTADO");
    console.log(
        `${socket.remoteAddress}:${socket.remotePort}`
    );

    const output = fs.createWriteStream(outputPath);

    totalBytes = 0;

    socket.on("data", (data) => {

        output.write(data);

        totalBytes += data.length;

        console.log(
            `H264 RECEBIDO: ${totalBytes} bytes`
        );
    });

    socket.on("end", () => {

        console.log("ANDROID DESCONECTADO");
        output.end();
    });

    socket.on("close", () => {

        output.end();
    });

    socket.on("error", (error) => {

        console.error(
            "ERRO SOCKET:",
            error.message
        );

        output.end();
    });
});

server.listen(PORT, "0.0.0.0", () => {

    console.log("LUCAO LINK - PC RECEIVER");
    console.log(`TCP aguardando na porta ${PORT}`);
    console.log(`Arquivo: ${outputPath}`);
});