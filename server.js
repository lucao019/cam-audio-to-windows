const express = require("express");
const os = require("os");

const app = express();
const PORT = 5050;

// Guarda o último frame recebido do celular
let ultimoFrame = null;
let totalFrames = 0;

// Permite receber JPEG diretamente no corpo da requisição
app.use(
    "/frame",
    express.raw({
        type: "image/jpeg",
        limit: "10mb"
    })
);

// Teste de conexão
app.get("/", (req, res) => {

    console.log(`[LUCAO-CAM] Conexao recebida de ${req.ip}`);

    res.send(`
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>LUCAO CAM</title>
        </head>

        <body style="
            background:#111;
            color:#fff;
            font-family:Arial;
            text-align:center;
            padding-top:80px;
        ">

            <h1>LUCAO CAM</h1>

            <h2 style="color:#00ff88;">
                SERVIDOR ONLINE
            </h2>

            <p>
                Comunicacao celular -> PC funcionando.
            </p>

            <p>
                Frames recebidos: ${totalFrames}
            </p>

            <p>
                <a
                    href="/camera"
                    style="color:#00ff88;"
                >
                    ABRIR CAMERA
                </a>
            </p>

        </body>
        </html>
    `);
});

// Recebe um frame JPEG enviado pelo Android
app.post("/frame", (req, res) => {

    if (!req.body || req.body.length === 0) {
        return res.status(400).send("FRAME VAZIO");
    }

    ultimoFrame = Buffer.from(req.body);
    totalFrames++;

    if (totalFrames % 30 === 0) {
        console.log(
            `[CAMERA] ${totalFrames} frames recebidos`
        );
    }

    res.sendStatus(204);
});

// Entrega o último JPEG recebido
app.get("/latest.jpg", (req, res) => {

    if (!ultimoFrame) {
        return res.status(404).send("AGUARDANDO CAMERA");
    }

    res.set("Content-Type", "image/jpeg");
    res.set("Cache-Control", "no-store");

    res.send(ultimoFrame);
});

// Tela que mostra os frames
app.get("/camera", (req, res) => {

    res.send(`
        <!DOCTYPE html>
        <html>

        <head>
            <meta charset="UTF-8">
            <title>LUCAO CAM - CAMERA</title>
        </head>

        <body style="
            margin:0;
            background:#000;
            color:#fff;
            font-family:Arial;
            text-align:center;
        ">

            <h2>LUCAO CAM</h2>

            <div id="status">
                AGUARDANDO CAMERA...
            </div>

            <br>

            <img
                id="camera"
                style="
                    max-width:90vw;
                    max-height:80vh;
                "
            >

            <script>

                const camera =
                    document.getElementById("camera");

                const status =
                    document.getElementById("status");

                function atualizarCamera() {

                    const imagem = new Image();

                    imagem.onload = function () {

                        camera.src = imagem.src;

                        status.innerText =
                            "CAMERA CONECTADA";
                    };

                    imagem.src =
                        "/latest.jpg?t=" + Date.now();
                }

                setInterval(
                    atualizarCamera,
                    100
                );

            </script>

        </body>
        </html>
    `);
});

app.listen(PORT, "0.0.0.0", () => {

    console.log("");
    console.log("==============================");
    console.log("       LUCAO CAM SERVER");
    console.log("==============================");
    console.log("");
    console.log(`Servidor iniciado na porta ${PORT}`);
    console.log("");

    const interfaces = os.networkInterfaces();

    for (const name of Object.keys(interfaces)) {

        for (const net of interfaces[name]) {

            if (
                net.family === "IPv4" &&
                !net.internal
            ) {

                console.log(
                    `CELULAR -> http://${net.address}:${PORT}`
                );

                console.log(
                    `CAMERA  -> http://${net.address}:${PORT}/camera`
                );
            }
        }
    }
});