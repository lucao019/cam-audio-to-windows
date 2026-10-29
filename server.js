const express = require("express");
const dgram = require("dgram");
const os = require("os");
const path = require("path");

// ============================================================
// LUCAO LINK - servidor de controle
//
// Porta 5050:
//   - API de controle (Android <-> Windows)
//   - Painel em http://localhost:5050/control
//
// Porta 5051 (live-receiver.js):
//   - video H.264, uma sessao por aparelho
//
// Porta 5053 (udp):
//   - descoberta: o celular pergunta e este servidor responde
// ============================================================

const app = express();
const PORT = 5050;
const VERSAO = "29";

// Descoberta na LAN: o celular faz broadcast "LUCAO?1" e o PC
// responde "LUCAO!1 <nome> <ip>". Protocolo em texto simples para
// nao precisar de dependencia nova nos dois lados.
const PORTA_DESCOBERTA = 5053;
const PING_DESCOBERTA = "LUCAO?1";
const NOME_PC = os.hostname() || "pc";

app.use(express.json({ limit: "64kb" }));

// O app Electron (janela propria) fala com a API por HTTP.
// CORS liberado apenas para o que roda nesta maquina/LAN.
app.use((req, res, next) => {
    res.set("Access-Control-Allow-Origin", "*");
    res.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    res.set("Access-Control-Allow-Headers", "Content-Type");
    res.set("Access-Control-Allow-Private-Network", "true");

    if (req.method === "OPTIONS") {
        return res.sendStatus(204);
    }

    next();
});

// ------------------------------------------------------------
// REDE
// ------------------------------------------------------------

function ipLocal() {
    const interfaces = os.networkInterfaces();

    for (const nome of Object.keys(interfaces)) {
        for (const net of interfaces[nome]) {
            if (net.family === "IPv4" && !net.internal) {
                return net.address;
            }
        }
    }

    return "127.0.0.1";
}

const HOST = ipLocal();

// ------------------------------------------------------------
// APARELHOS
// ------------------------------------------------------------

// id -> estado
const aparelhos = new Map();

const ONLINE_MS = 6000;
const RETENCAO_MS = 5 * 60 * 1000;

function obterAparelho(id) {
    if (!aparelhos.has(id)) {
        aparelhos.set(id, {
            id,
            nome: "android",
            transporte: "usb",
            ip: "",
            video: false,
            fps: 0,
            kbps: 0,
            zoom: 1,
            zoomMin: 1,
            zoomMax: 1,
            webcam: true,
            ultimoContato: 0,
            comandos: [],
            criadoEm: Date.now()
        });
    }

    return aparelhos.get(id);
}

function enfileirar(id, comando) {
    const aparelho = obterAparelho(id);

    aparelho.comandos.push(comando);

    // Fila curta: slider gera varios eventos.
    if (aparelho.comandos.length > 8) {
        aparelho.comandos.shift();
    }
}

function listarAparelhos() {
    const agora = Date.now();
    const lista = [];

    for (const [id, aparelho] of aparelhos) {
        if (agora - aparelho.ultimoContato > RETENCAO_MS) {
            aparelhos.delete(id);
            continue;
        }

        lista.push({
            id,
            nome: aparelho.nome,
            transporte: aparelho.transporte,
            ip: aparelho.ip,
            video: aparelho.video,
            fps: aparelho.fps,
            kbps: aparelho.kbps,
            zoom: aparelho.zoom,
            zoomMin: aparelho.zoomMin,
            zoomMax: aparelho.zoomMax,
            webcam: aparelho.webcam,
            ultimoContato: aparelho.ultimoContato,
            online: agora - aparelho.ultimoContato < ONLINE_MS
        });
    }

    return lista.sort((a, b) => a.nome.localeCompare(b.nome));
}

// ------------------------------------------------------------
// API - ANDROID -> WINDOWS
// ------------------------------------------------------------

// Estado enviado pelo Android a cada ~1s.
app.post("/api/status", (req, res) => {
    const corpo = req.body || {};

    if (!corpo.id) {
        return res.status(400).json({ ok: false, erro: "id ausente" });
    }

    const aparelho = obterAparelho(String(corpo.id));

    if (typeof corpo.nome === "string" && corpo.nome) {
        aparelho.nome = corpo.nome.slice(0, 64);
    }

    if (typeof corpo.transporte === "string") {
        aparelho.transporte = corpo.transporte;
    }

    if (typeof corpo.ip === "string") {
        aparelho.ip = corpo.ip;
    }

    if (Number.isFinite(corpo.zoom)) {
        aparelho.zoom = corpo.zoom;
    }

    if (Number.isFinite(corpo.zoomMin)) {
        aparelho.zoomMin = corpo.zoomMin;
    }

    if (Number.isFinite(corpo.zoomMax)) {
        aparelho.zoomMax = corpo.zoomMax;
    }

    if (typeof corpo.webcam === "boolean") {
        aparelho.webcam = corpo.webcam;
    }

    if (Number.isFinite(corpo.fps)) {
        aparelho.fps = corpo.fps;
    }

    if (Number.isFinite(corpo.kbps)) {
        aparelho.kbps = corpo.kbps;
    }

    aparelho.ultimoContato = Date.now();

    res.json({ ok: true, host: HOST });
});

// Android consulta comandos pendentes.
app.get("/api/poll", (req, res) => {
    const id = String(req.query.id || "");

    if (!id) {
        return res.json({ comando: null });
    }

    const aparelho = obterAparelho(id);

    aparelho.ultimoContato = Date.now();

    const comando = aparelho.comandos.shift() || null;

    res.set("Cache-Control", "no-store");
    res.json({ comando });
});

// Receiver de video avisa quem esta conectado.
app.post("/api/video", (req, res) => {
    const corpo = req.body || {};

    if (!corpo.id) {
        return res.status(400).json({ ok: false });
    }

    const aparelho = obterAparelho(String(corpo.id));

    aparelho.video = Boolean(corpo.conectado);
    aparelho.ultimoContato = Date.now();

    if (typeof corpo.nome === "string" && corpo.nome) {
        aparelho.nome = corpo.nome.slice(0, 64);
    }

    if (typeof corpo.transporte === "string") {
        aparelho.transporte = corpo.transporte;
    }

    res.json({ ok: true });
});

// ------------------------------------------------------------
// API - WINDOWS -> ANDROID / PAINEL
// ------------------------------------------------------------

app.get("/api/devices", (req, res) => {
    res.set("Cache-Control", "no-store");

    res.json({
        host: HOST,
        porta: PORT,
        versao: VERSAO,
        aparelhos: listarAparelhos()
    });
});

app.post("/api/devices/:id/zoom", (req, res) => {
    const valor = Number((req.body || {}).valor);

    if (!Number.isFinite(valor) || valor < 0 || valor > 1) {
        return res.status(400).json({ ok: false, erro: "zoom deve estar entre 0 e 1" });
    }

    enfileirar(req.params.id, { tipo: "zoom", valor });

    res.json({ ok: true });
});

app.post("/api/devices/:id/webcam", (req, res) => {
    const ligada = Boolean((req.body || {}).ligada);

    enfileirar(req.params.id, { tipo: "webcam", ligada });

    res.json({ ok: true });
});

// Forca o celular a derrubar o socket de video e conectar de
// novo (util quando o app do PC reinicia com o socket antigo
// preso no celular).
app.post("/api/devices/:id/reconectar", (req, res) => {
    enfileirar(req.params.id, { tipo: "reconectar" });

    res.json({ ok: true });
});

// Compatibilidade com a rota antiga (teste via curl/powershell).
app.get("/control/zoom/:value", (req, res) => {
    const valor = Number(req.params.value);

    if (!Number.isFinite(valor) || valor < 0 || valor > 1) {
        return res.status(400).send("ZOOM DEVE ESTAR ENTRE 0 E 1");
    }

    const aparelhosAtivos = listarAparelhos().filter(a => a.online);

    if (aparelhosAtivos.length === 0) {
        return res.status(404).send("NENHUM APARELHO ONLINE");
    }

    enfileirar(aparelhosAtivos[0].id, { tipo: "zoom", valor });

    res.json({ ok: true, aparelho: aparelhosAtivos[0].id, zoom: valor });
});

app.get("/control/poll", (req, res) => {
    // Mantida apenas para clientes antigos (versao anterior do app).
    const comando = aparelhos.size
        ? [...aparelhos.values()][0].comandos.shift() || null
        : null;

    if (!comando || comando.tipo !== "zoom") {
        return res.json({ command: null });
    }

    res.json({ command: "zoom", value: comando.valor });
});

// ------------------------------------------------------------
// PAINEL
// ------------------------------------------------------------

app.get("/terminal.css", (req, res) => {
    res.type("text/css");
    res.sendFile(path.join(__dirname, "pc", "web", "terminal.css"));
});

// ------------------------------------------------------------
// APP DE DESKTOP (Electron carrega esta UI)
// ------------------------------------------------------------

app.use(
    "/app",
    express.static(path.join(__dirname, "pc", "app", "ui"), {
        etag: false,
        cacheControl: false
    })
);

app.get("/", (req, res) => res.redirect("/app/"));

app.get("/control", (req, res) => {
    res.set("Cache-Control", "no-store");
    res.type("html").send(painel());
});

function painel() {
    return `<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>LUCAO LINK - CONTROL</title>
<link rel="stylesheet" href="/terminal.css">
<style>
  body { padding: 16px; }
  .wrap { max-width: 980px; margin: 0 auto; }
  .dots { display: flex; gap: 6px; align-items: center; }
  .dot { width: 11px; height: 11px; border-radius: 50%; display: inline-block; }
  .dot.red { background: #ff5555; }
  .dot.yellow { background: #f1fa8c; }
  .dot.green { background: #50fa7b; }
  .row { display: flex; justify-content: space-between; gap: 12px; padding: 4px 0; }
  .row .k { color: var(--muted); text-transform: uppercase; letter-spacing: .08em; font-size: .75rem; }
  .row .v { color: var(--terminal-fg); font-weight: bold; }
  .v.ok { color: var(--success); }
  .v.off { color: var(--danger); }
  .v.warn { color: var(--warning); }
  .card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
  .card-content { padding: 14px 16px; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 24px; }
  input[type="range"] {
    width: 100%;
    accent-color: var(--primary);
    background: transparent;
    margin: 6px 0 0 0;
  }
  .zoom-line { display: flex; justify-content: space-between; align-items: baseline; margin-top: 10px; }
  .actions { display: flex; gap: 10px; margin-top: 12px; flex-wrap: wrap; }
  .muted { color: var(--muted); }
  .small { font-size: .78rem; }
  .empty { border: 1px dashed var(--border-color); padding: 24px; text-align: center; color: var(--muted); }
  .cursor { color: var(--primary); animation: blink 1.1s steps(1) infinite; }
  @keyframes blink { 50% { opacity: 0; } }
  .badge { font-size: .7rem; padding: 2px 8px; border: 1px solid currentColor; text-transform: uppercase; letter-spacing: .1em; }
</style>
</head>
<body>
<div class="wrap">
  <div class="terminal">
    <div class="terminal-header">
      <div class="dots">
        <span class="dot red"></span>
        <span class="dot yellow"></span>
        <span class="dot green"></span>
      </div>
      <span>lucao@windows:~$ control</span>
      <span id="resumo" class="muted small">0 devices</span>
    </div>
    <div class="terminal-body">
      <h1 class="title is-3 has-terminal-glow" style="margin-bottom:4px">LUCAO LINK</h1>
      <p class="subtitle is-6" style="margin-bottom:18px">
        android → windows · usb / wi-fi
      </p>

      <div id="lista"></div>

      <div class="empty" id="vazio">
        &gt; aguardando android...<span class="cursor">█</span>
      </div>

      <hr>
      <div class="row">
        <span class="k">servidor</span>
        <span class="v">${HOST}:${PORT}</span>
      </div>
      <div class="row">
        <span class="k">video</span>
        <span class="v">${HOST}:5051</span>
      </div>
      <div class="row">
        <span class="k">versao</span>
        <span class="v">checkpoint ${VERSAO}</span>
      </div>
      <p class="muted small" style="margin-top:14px">
        lucao@windows:~$ <span class="cursor">█</span>
      </p>
    </div>
  </div>
</div>

<script>
  const lista = document.getElementById("lista");
  const vazio = document.getElementById("vazio");
  const resumo = document.getElementById("resumo");

  const cards = new Map();

  function fmt(n, casas = 1) {
    return Number(n).toFixed(casas);
  }

  function criarCard(aparelho) {
    const card = document.createElement("div");
    card.className = "card";

    card.innerHTML = \`
      <div class="card-header">
        <h4 class="card-header-title">▸ <span class="nome"></span></h4>
        <span class="badge status"></span>
      </div>
      <div class="card-content">
        <div class="grid">
          <div class="row"><span class="k">transporte</span><span class="v transporte"></span></div>
          <div class="row"><span class="k">video</span><span class="v video"></span></div>
          <div class="row"><span class="k">fps</span><span class="v fps"></span></div>
          <div class="row"><span class="k">bitrate</span><span class="v kbps"></span></div>
        </div>

        <div class="zoom-line">
          <span class="k muted small">zoom</span>
          <span class="v zoom-valor"></span>
        </div>
        <input type="range" min="0" max="1" step="0.01" class="zoom">
        <div class="row small muted">
          <span class="zoom-min"></span>
          <span class="zoom-max"></span>
        </div>

        <div class="actions">
          <button class="button webcam"></button>
          <span class="muted small atualizado"></span>
        </div>
      </div>
    \`;

    const refs = {
      card,
      nome: card.querySelector(".nome"),
      status: card.querySelector(".status"),
      transporte: card.querySelector(".transporte"),
      video: card.querySelector(".video"),
      fps: card.querySelector(".fps"),
      kbps: card.querySelector(".kbps"),
      zoom: card.querySelector(".zoom"),
      zoomValor: card.querySelector(".zoom-valor"),
      zoomMin: card.querySelector(".zoom-min"),
      zoomMax: card.querySelector(".zoom-max"),
      webcam: card.querySelector(".webcam"),
      atualizado: card.querySelector(".atualizado")
    };

    let timerZoom = null;

    refs.zoom.addEventListener("input", () => {
      const valor = Number(refs.zoom.value);

      refs.zoomValor.textContent =
        fmt(ratioDeLinear(valor, Number(refs.zoom.dataset.max) || 1)) + "x";

      clearTimeout(timerZoom);
      timerZoom = setTimeout(() => {
        enviarZoom(aparelho.id, valor);
      }, 60);
    });

    refs.webcam.addEventListener("click", () => {
      const atual = refs.webcam.dataset.ligada === "true";
      enviarWebcam(aparelho.id, !atual);
    });

    return refs;
  }

  function ratioDeLinear(linear, max) {
    if (!Number.isFinite(max) || max <= 1) return 1;
    return 1 / (1 - linear * (1 - 1 / max));
  }

  function linearDeRatio(ratio, max) {
    if (!Number.isFinite(max) || max <= 1) return 0;
    return Math.min(1, Math.max(0, (1 - 1 / ratio) / (1 - 1 / max)));
  }

  async function enviarZoom(id, valor) {
    await fetch("/api/devices/" + encodeURIComponent(id) + "/zoom", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ valor })
    });
  }

  async function enviarWebcam(id, ligada) {
    await fetch("/api/devices/" + encodeURIComponent(id) + "/webcam", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ligada })
    });
  }

  function atualizarCard(refs, aparelho) {
    const online = aparelho.online;
    const video = aparelho.video;

    refs.nome.textContent = aparelho.nome;

    refs.status.textContent = online ? "online" : "offline";
    refs.status.style.color = online ? "var(--success)" : "var(--danger)";

    refs.transporte.textContent = aparelho.transporte +
      (aparelho.ip ? " · " + aparelho.ip : "");
    refs.transporte.className = "v";

    refs.video.textContent = video ? "online" : "offline";
    refs.video.className = "v " + (video ? "ok" : "off");

    refs.fps.textContent = aparelho.fps ? fmt(aparelho.fps) : "--";
    refs.kbps.textContent = aparelho.kbps
      ? fmt(aparelho.kbps / 1000) + " mbps"
      : "--";

    const max = aparelho.zoomMax || 1;

    refs.zoom.dataset.max = max;

    if (document.activeElement !== refs.zoom) {
      const linear = linearDeRatio(aparelho.zoom, max);
      refs.zoom.value = linear;
      refs.zoomValor.textContent = fmt(aparelho.zoom) + "x";
    }

    refs.zoomMin.textContent = fmt(aparelho.zoomMin || 1) + "x";
    refs.zoomMax.textContent = fmt(max) + "x";

    refs.webcam.dataset.ligada = String(aparelho.webcam);
    refs.webcam.textContent = aparelho.webcam ? "webcam: on" : "webcam: off";
    refs.webcam.classList.toggle("is-success", aparelho.webcam);
    refs.webcam.classList.toggle("is-danger", !aparelho.webcam);

    refs.atualizado.textContent = online ? "sync ok" : "sem contato";
  }

  function razãoParaLinear(razao, min, max) {
    // CameraX: linearZoom 0..1 -> razao min..max
    if (max <= min) return 0;
    return Math.min(1, Math.max(0, (razao - min) / (max - min)));
  }

  async function atualizar() {
    try {
      const resposta = await fetch("/api/devices", { cache: "no-store" });
      const dados = await resposta.json();

      const aparelhos = dados.aparelhos || [];

      resumo.textContent = aparelhos.filter(a => a.online).length +
        " online / " + aparelhos.length + " total";

      vazio.style.display = aparelhos.length ? "none" : "block";

      const vistos = new Set();

      for (const aparelho of aparelhos) {
        vistos.add(aparelho.id);

        let refs = cards.get(aparelho.id);

        if (!refs) {
          refs = criarCard(aparelho);
          cards.set(aparelho.id, refs);
          lista.appendChild(refs.card);
        }

        atualizarCard(refs, aparelho);
      }

      for (const [id, refs] of cards) {
        if (!vistos.has(id)) {
          refs.card.remove();
          cards.delete(id);
        }
      }

    } catch (erro) {
      resumo.textContent = "servidor offline";
    }
  }

  atualizar();
  setInterval(atualizar, 500);
</script>
</body>
</html>`;
}

// ------------------------------------------------------------
// DESCOBERTA (UDP)
// ------------------------------------------------------------

let socketDescoberta = null;

/**
 * Responde aos broadcasts de descoberta do app Android.
 * Reutilizavel: chamar duas vezes devolve o mesmo socket.
 */
function iniciarDescoberta(porta = PORTA_DESCOBERTA) {
    if (socketDescoberta) {
        return socketDescoberta;
    }

    const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });

    socket.on("message", (mensagem, origem) => {
        const texto = mensagem.toString("utf8").trim();

        if (texto !== PING_DESCOBERTA) {
            return;
        }

        const resposta = Buffer.from(
            `LUCAO!1 ${NOME_PC} ${HOST}`,
            "utf8"
        );

        socket.send(resposta, origem.port, origem.address, (erro) => {
            if (erro) {
                console.error(
                    `Descoberta: falha ao responder ${origem.address}: ${erro.message}`
                );
            }
        });
    });

    socket.on("error", (erro) => {
        console.error(`Descoberta: ${erro.message}`);
        socket.close();
        socketDescoberta = null;
    });

    socket.bind(porta, () => {
        socket.setBroadcast(true);
    });

    socketDescoberta = socket;

    return socket;
}

function pararDescoberta() {
    if (socketDescoberta) {
        socketDescoberta.close();
        socketDescoberta = null;
    }
}

// ------------------------------------------------------------
// START
// ------------------------------------------------------------

function iniciar(porta = PORT) {
    return new Promise((resolve, reject) => {
        const servidor = app.listen(porta, "0.0.0.0", () => {
            iniciarDescoberta();

            console.log("");
            console.log("==============================");
            console.log("       LUCAO CAM SERVER");
            console.log("==============================");
            console.log("");
            console.log(`App      : http://localhost:${PORT}/app/`);
            console.log(`Controle : http://${HOST}:${PORT}/control`);
            console.log(`Video    : tcp ${HOST}:5051`);
            console.log(`Descoberta: udp ${PORTA_DESCOBERTA}`);
            console.log("");

            resolve(servidor);
        });

        servidor.once("error", reject);
    });
}

if (require.main === module) {
    iniciar().catch((erro) => {
        console.error(`Nao foi possivel abrir a porta ${PORT}: ${erro.message}`);
        process.exit(1);
    });
}

module.exports = {
    app,
    iniciar,
    iniciarDescoberta,
    pararDescoberta,
    HOST,
    PORT,
    VERSAO,
    PORTA_DESCOBERTA,
    PING_DESCOBERTA,
};
