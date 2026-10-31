(() => {
    "use strict";

    // ============================================================
    // LUCAO LINK - UI
    // ============================================================

    const STREAM = `http://${location.hostname}:5052`;

    const el = {
        lista: document.getElementById("lista"),
        vazio: document.getElementById("vazio"),
        vazioSub: document.getElementById("vazioSub"),
        contagem: document.getElementById("contagem"),
        tagVideo: document.getElementById("tagVideo"),
        tagDevices: document.getElementById("tagDevices"),
        pcHost: document.getElementById("pcHost"),
        dica: document.getElementById("dica"),
        video: document.getElementById("video"),
        videoWrap: document.getElementById("videoWrap"),
        overlay: document.getElementById("overlay"),
        overlayTitulo: document.getElementById("overlayTitulo"),
        overlaySub: document.getElementById("overlaySub"),
        hud: document.getElementById("hud"),
        hudDireita: document.getElementById("hudDireita"),
        hudNome: document.getElementById("hudNome"),
        hudFps: document.getElementById("hudFps"),
        hudKbps: document.getElementById("hudKbps"),
        hudBuf: document.getElementById("hudBuf"),
        hudRes: document.getElementById("hudRes"),
        hudCodec: document.getElementById("hudCodec"),
        hudMic: document.getElementById("hudMic"),
        hudMicSep: document.getElementById("hudMicSep"),
        zoomValor: document.getElementById("zoomValor"),
        zoomPresets: document.getElementById("zoomPresets"),
        btnLente: document.getElementById("btnLente"),
        btnFlash: document.getElementById("btnFlash"),
        btnRotacao: document.getElementById("btnRotacao"),
        btnEspelho: document.getElementById("btnEspelho"),
        btnAutoRotacao: document.getElementById("btnAutoRotacao"),
        btnMic: document.getElementById("btnMic"),
        btnWebcam: document.getElementById("btnWebcam"),
        btnReconectar: document.getElementById("btnReconectar"),
        btnTela: document.getElementById("btnTela"),
        rodapeHost: document.getElementById("rodapeHost"),
        rodapeVideo: document.getElementById("rodapeVideo"),
        rodapeStream: document.getElementById("rodapeStream"),
        rodapeFfmpeg: document.getElementById("rodapeFfmpeg"),
        rodapeVersao: document.getElementById("rodapeVersao"),
        avisoFfmpeg: document.getElementById("avisoFfmpeg"),
        controles: document.getElementById("controles"),
        btnOcultarControles: document.getElementById("btnOcultarControles"),
        btnMostrarControles: document.getElementById("btnMostrarControles")
    };

    const itens = new Map();

    let aparelhos = [];
    let selecionadoId = null;
    // Presets de zoom desenhados no momento (para nao redesenhar a
    // cada status e nao perder o foco/clique do usuario).
    let zoomPresetsChave = "";
    let zoomAlvoPendente = null;
    let verificando = false;
    let semCodecDesde = 0;
    let timerReconexao = null;

    // Cache do ultimo valor escrito em cada elemento: o poll roda a
    // 700ms e reescrever textContent/className sem mudanca forcava
    // repaint em cima do video (painel "travado"). So escrevemos
    // quando o valor realmente muda.
    const cacheTexto = new Map();  // chave -> String
    const cacheClasse = new Map(); // chave -> String

    function setTexto(el, valor) {
        const s = valor == null ? "" : String(valor);

        if (cacheTexto.get(el) === s) {
            return;
        }

        cacheTexto.set(el, s);
        el.textContent = s;
    }

    function setClasse(el, valor) {
        const s = valor == null ? "" : String(valor);

        if (cacheClasse.get(el) === s) {
            return;
        }

        cacheClasse.set(el, s);
        el.className = s;
    }

    // Alterna a classe de um elemento so se o cache mudou.
    function setToggle(el, classe, ativo) {
        const chave = el.id || el;
        const mapa = cacheToggle.get(chave) || new Map();

        cacheToggle.set(chave, mapa);

        if (mapa.get(classe) === Boolean(ativo)) {
            return;
        }

        mapa.set(classe, Boolean(ativo));
        el.classList.toggle(classe, Boolean(ativo));
    }

    const cacheToggle = new Map();

    // Estado do alto-falante do PC (o mic do celular continua
    // capturando; isto só silencia a reproducao local).
    let micMudo = false;

    // Estado do pipeline MSE. O video e o audio (opcional) usam o
    // mesmo MediaSource mas SourceBuffers separados, alimentados por
    // dois HTTP streams independentes (/stream e /audio).
    const player = {
        id: null,
        geracao: null,
        codec: null,
        ms: null,
        sb: null,
        url: null,
        fila: [],
        reader: null,
        abort: null,
        token: 0,
        tocando: false,
        posicionado: false,
        ultimoDado: 0,
        ultimoPedidoReconexao: 0,
        // Audio
        audio: false,
        audioCodec: null,
        sba: null,
        filaAudio: [],
        readerAudio: null,
        abortAudio: null,
        audioTocando: false
    };

    // ------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------

    async function fetchJson(url) {
        const resposta = await fetch(url, { cache: "no-store" });

        if (!resposta.ok) {
            throw new Error(`HTTP ${resposta.status}`);
        }

        return resposta.json();
    }

    function juntar(partes) {
        if (partes.length === 1) {
            return partes[0];
        }

        const total = partes.reduce((soma, item) => soma + item.length, 0);
        const saida = new Uint8Array(total);

        let offset = 0;

        for (const parte of partes) {
            saida.set(parte, offset);
            offset += parte.length;
        }

        return saida;
    }

    // ------------------------------------------------------------
    // OVERLAY / STATUS
    // ------------------------------------------------------------

    function mostrarOverlay(titulo, sub, erro = false) {
        el.overlayTitulo.textContent = titulo;
        el.overlaySub.textContent = sub || "";
        el.overlay.classList.toggle("erro", Boolean(erro));
        el.overlay.hidden = false;
        el.videoWrap.classList.add("sem-sinal");
    }

    function esconderOverlay() {
        el.overlay.hidden = true;
        el.videoWrap.classList.remove("sem-sinal");
    }

    function definirTagVideo(estado, texto) {
        setTexto(el.tagVideo, texto);
        setClasse(el.tagVideo, "tag " + estado);
    }

    // ------------------------------------------------------------
    // VIDEO (MSE + fMP4)
    // ------------------------------------------------------------

    function pararVideo(motivo) {
        player.token++;

        if (timerReconexao) {
            clearTimeout(timerReconexao);
            timerReconexao = null;
        }

        if (player.abort) {
            try {
                player.abort.abort();
            } catch (erro) {
                // Ignora.
            }
        }

        if (player.reader) {
            try {
                player.reader.cancel().catch(() => {});
            } catch (erro) {
                // Ignora.
            }
        }

        if (player.abortAudio) {
            try {
                player.abortAudio.abort();
            } catch (erro) {
                // Ignora.
            }
        }

        if (player.readerAudio) {
            try {
                player.readerAudio.cancel().catch(() => {});
            } catch (erro) {
                // Ignora.
            }
        }

        if (player.ms && player.ms.readyState === "open") {
            try {
                player.ms.endOfStream();
            } catch (erro) {
                // Ignora.
            }
        }

        try {
            el.video.pause();
            el.video.removeAttribute("src");
            el.video.load();
        } catch (erro) {
            // Ignora.
        }

        if (player.url) {
            URL.revokeObjectURL(player.url);
        }

        player.id = null;
        player.geracao = null;
        player.codec = null;
        player.ms = null;
        player.sb = null;
        player.url = null;
        player.fila = [];
        player.reader = null;
        player.abort = null;
        player.tocando = false;
        player.posicionado = false;
        player.ultimoDado = 0;
        player.ultimoPedidoReconexao = 0;
        player.audio = false;
        player.audioCodec = null;
        player.sba = null;
        player.filaAudio = [];
        player.readerAudio = null;
        player.abortAudio = null;
        player.audioTocando = false;

        el.hud.hidden = true;
        el.hudDireita.hidden = true;
        el.hudBuf.textContent = "buf --";
        el.hudMic.hidden = true;
        el.hudMicSep.hidden = true;

        definirTagVideo("is-off", "video off");

        if (motivo) {
            mostrarOverlay("SEM SINAL", motivo);
        }
    }

    function limparBuffer() {
        const sb = player.sb;

        if (!sb || sb.updating || !sb.buffered.length) {
            return;
        }

        const inicio = sb.buffered.start(0);
        const atual = el.video.currentTime;
        // Guarda ~1s de historia atras do playhead: espaco de sobra
        // para a limpeza (que roda a cada append) nao competir com o
        // playhead, sem manter atraso perceptivel.
        const alvo = atual - 1.0;

        if (alvo > inicio + 0.25) {
            try {
                sb.remove(inicio, alvo);
            } catch (erro) {
                // Ignora.
            }
        }
    }

    function bombear() {
        const sb = player.sb;

        if (
            !sb ||
            sb.updating ||
            !player.fila.length ||
            !player.ms ||
            player.ms.readyState !== "open"
        ) {
            return;
        }

        let total = 0;
        const partes = [];

        while (player.fila.length && total < 128 * 1024) {
            const parte = player.fila.shift();

            partes.push(parte);
            total += parte.length;
        }

        const dados = juntar(partes);

        try {
            sb.appendBuffer(dados);
        } catch (erro) {
            if (erro.name === "QuotaExceededError") {
                // Buffer do MSE cheio: recomeca do init segment.
                falharVideo("buffer do video cheio, reconectando");
                return;
            }

            falharVideo(`erro no video: ${erro.message}`);

            return;
        }

        // Depois do append, garante que o player esta dentro do
        // trecho que existe no buffer (ao vivo).
        setTimeout(alinharAoVivo, 0);
    }

    // ------------------------------------------------------------
    // Audio (segundo SourceBuffer, endpoint /audio)
    // ------------------------------------------------------------

    function limparBufferAudio() {
        const sba = player.sba;

        if (!sba || sba.updating || !sba.buffered.length) {
            return;
        }

        const inicio = sba.buffered.start(0);
        const atual = el.video.currentTime;
        const alvo = atual - 0.4;

        if (alvo > inicio + 0.1) {
            try {
                sba.remove(inicio, alvo);
            } catch (erro) {
                // Ignora.
            }
        }
    }

    function bombearAudio() {
        const sba = player.sba;

        if (
            !sba ||
            sba.updating ||
            !player.filaAudio.length ||
            !player.ms ||
            player.ms.readyState !== "open"
        ) {
            return;
        }

        let total = 0;
        const partes = [];

        while (player.filaAudio.length && total < 128 * 1024) {
            const parte = player.filaAudio.shift();

            partes.push(parte);
            total += parte.length;
        }

        const dados = juntar(partes);

        try {
            sba.appendBuffer(dados);
        } catch (erro) {
            if (erro.name === "QuotaExceededError") {
                // Fila de audio cheia: descarta o que esta pendente
                // em vez de derrubar o video. O proximo init do
                // servidor reancora a trilha.
                player.filaAudio = [];
                return;
            }

            console.warn(`audio: ${erro.message}`);
        }
    }

    // Alimenta o SourceBuffer de audio a partir de /audio/:id. E
    // independente do stream de video: se o audio cair, o video
    // segue; se o video cair, o abort do audio vem em pararVideo().
    async function conectarAudio(id, token) {
        const controle = new AbortController();

        player.abortAudio = controle;

        try {
            const resposta = await fetch(
                `${STREAM}/audio/${encodeURIComponent(id)}` +
                    `?g=${player.geracao}`,
                {
                    signal: controle.signal,
                    cache: "no-store"
                }
            );

            if (token !== player.token) {
                return;
            }

            if (!resposta.ok || !resposta.body) {
                // Sem audio agora (celular pode ligar o mic depois).
                return;
            }

            const reader = resposta.body.getReader();

            player.readerAudio = reader;

            while (true) {
                const { done, value } = await reader.read();

                if (token !== player.token) {
                    return;
                }

                if (done) {
                    break;
                }

                if (value && value.length) {
                    player.filaAudio.push(value);
                    bombearAudio();
                }
            }
        } catch (erro) {
            if (token !== player.token || erro.name === "AbortError") {
                return;
            }

            console.warn("audio:", erro.message);
        }
    }

    function iniciarAudio(id, codec) {
        const mime = `audio/mp4; codecs="${codec}"`;

        if (!window.MediaSource || !MediaSource.isTypeSupported(mime)) {
            console.warn(`audio nao suportado: ${mime}`);
            return;
        }

        let sba;

        try {
            sba = player.ms.addSourceBuffer(mime);
        } catch (erro) {
            console.warn(`audio buffer: ${erro.message}`);
            return;
        }

        sba.mode = "segments";

        player.audio = true;
        player.audioCodec = codec;
        player.sba = sba;

        sba.addEventListener("updateend", () => {
            bombearAudio();
            limparBufferAudio();
        });

        sba.addEventListener("error", () => {
            console.warn("erro no SourceBuffer de audio");
        });

        conectarAudio(id, player.token);
    }

    // Mantem o player no fim do buffer. Um stream que comeca no
    // meio da sessao (reconexao) pode ter timeline a partir de
    // 9s; sem isso o currentTime fica abaixo do buffer e trava.
    // Distancia alvo entre o playhead e o fim do buffer (ao vivo).
    // ~0.35s absorve o jitter normal da rede/LAN sem estourar o
    // atraso percebido. Antes o alvo era ~1 frame (33ms): qualquer
    // oscilacao maior que isso disparava um seek, e seek em MSE
    // causa re-decode - era isso que dava a sensacao de video
    // engasgado e travava a janela.
    const ATRASO_ALVO = 0.35;
    // Margem para nao ficar corrigindo por diferencas irrelevantes.
    const ATRASO_FOLGA = 0.12;

    function alinharAoVivo() {
        const sb = player.sb;

        if (!sb || sb.updating || !player.id || !sb.buffered.length) {
            return;
        }

        const inicio = sb.buffered.start(0);
        const fim = sb.buffered.end(sb.buffered.length - 1);
        const atual = el.video.currentTime;

        const fora = atual < inicio - 0.02 || atual > fim + 0.02;
        const atras = fim - atual > ATRASO_ALVO + ATRASO_FOLGA;

        if (player.posicionado && !fora && !atras) {
            return;
        }

        const alvo = Math.max(inicio + 0.02, fim - ATRASO_ALVO);

        player.posicionado = true;

        if (Math.abs(alvo - atual) > ATRASO_FOLGA) {
            try {
                el.video.currentTime = alvo;
            } catch (erro) {
                // Ignora.
            }
        }

        el.video.play().catch(() => {});
    }

    function falharVideo(mensagem) {
        console.warn(mensagem);

        const id = player.id;

        pararVideo(null);

        mostrarOverlay("ERRO NO VÍDEO", mensagem, true);

        agendarReconexao(id);
    }

    function agendarReconexao(id) {
        if (timerReconexao) {
            clearTimeout(timerReconexao);
        }

        timerReconexao = setTimeout(() => {
            timerReconexao = null;

            if (selecionadoId === id) {
                verificarVideo();
            }
        }, 1200);
    }

    async function conectarStream(id, token) {
        const controle = new AbortController();

        player.abort = controle;

        try {
            const resposta = await fetch(
                `${STREAM}/stream/${encodeURIComponent(id)}` +
                    `?g=${player.geracao}`,
                {
                    signal: controle.signal,
                    cache: "no-store"
                }
            );

            if (token !== player.token) {
                return;
            }

            if (!resposta.ok || !resposta.body) {
                throw new Error(`stream HTTP ${resposta.status}`);
            }

            const reader = resposta.body.getReader();

            player.reader = reader;

            while (true) {
                const { done, value } = await reader.read();

                if (token !== player.token) {
                    return;
                }

                if (done) {
                    break;
                }

                if (value && value.length) {
                    player.ultimoDado = Date.now();
                    player.fila.push(value);

                    // Teto de fila: se o appendBuffer nao acompanha
                    // (decode lento/rede rapida) a fila cresce sem
                    // limite e a memoria/CPU sobem ate congelar a
                    // janela. Descarta os pedacos mais antigos -
                    // melhor pular alguns frames do que travar.
                    while (player.fila.length > 120) {
                        player.fila.shift();
                    }

                    bombear();
                }
            }
        } catch (erro) {
            if (token !== player.token || erro.name === "AbortError") {
                return;
            }

            console.warn("stream:", erro.message);
        }

        if (token !== player.token) {
            return;
        }

        // O servidor encerrou o stream (celular reconectou ou
        // app fechou). Recomeca com um init segment novo.
        const idAtual = player.id;

        pararVideo(null);

        mostrarOverlay(
            "RECONECTANDO",
            "aguardando o celular retomar o vídeo..."
        );

        definirTagVideo("is-espera", "reconectando");

        agendarReconexao(idAtual);
    }

    function iniciarVideo(id, info) {
        pararVideo(null);

        const token = player.token;

        player.id = id;
        player.geracao = info.geracao;
        player.codec = info.codec;

        // Aplica o mute escolhido pelo usuario a nova sessao.
        el.video.muted = micMudo;

        el.hudNome.textContent = info.nome || id;
        el.hudRes.textContent = info.largura
            ? `${info.largura}x${info.altura}`
            : "--";
        el.hudCodec.textContent = info.codec || "--";

        const ms = new MediaSource();

        player.ms = ms;
        player.url = URL.createObjectURL(ms);

        const mime = info.codec
            ? `video/mp4; codecs="${info.codec}"`
            : "";

        el.video.src = player.url;

        ms.addEventListener(
            "sourceopen",
            () => {
                if (token !== player.token || player.ms !== ms) {
                    return;
                }

                if (!mime || !MediaSource.isTypeSupported(mime)) {
                    falharVideo(
                        `codec ${info.codec || "desconhecido"} ` +
                            "nao suportado por este Chromium"
                    );

                    return;
                }

                let sb;

                try {
                    sb = ms.addSourceBuffer(mime);
                } catch (erro) {
                    falharVideo(`nao foi possivel criar o buffer: ${erro.message}`);
                    return;
                }

                try {
                    ms.duration = Infinity;
                } catch (erro) {
                    // Alguns builds ignoram; o buffered manda.
                }

                sb.mode = "segments";

                player.sb = sb;

                sb.addEventListener("updateend", () => {
                    bombear();

                    alinharAoVivo();

                    const atual = el.video.currentTime;

                    if (
                        sb.buffered.length &&
                        sb.buffered.end(sb.buffered.length - 1) - atual > 1
                    ) {
                        limparBuffer();
                    }
                });

                sb.addEventListener("error", () => {
                    falharVideo("erro no SourceBuffer");
                });

                // Audio no mesmo MediaSource, em SourceBuffer
                // separado. Só entra se a sessao anunciar audio;
                // se o celular não tiver mic ligado ainda, o vídeo
                // segue sozinho.
                if (info.audio && info.audioCodec) {
                    iniciarAudio(id, info.audioCodec);
                }

                definirTagVideo("is-espera", "conectando");

                mostrarOverlay(
                    "CONECTANDO",
                    `${info.nome || id} · ${info.codec}`
                );

                conectarStream(id, token);
            },
            { once: true }
        );
    }

    async function verificarVideo() {
        if (verificando) {
            return;
        }

        if (!selecionadoId) {
            return;
        }

        const aparelho = aparelhos.find(item => item.id === selecionadoId);

        if (!aparelho) {
            return;
        }

        if (!aparelho.video) {
            if (player.id) {
                pararVideo(null);
            }

            semCodecDesde = 0;

            mostrarOverlay(
                "AGUARDANDO VÍDEO",
                `${aparelho.nome} está online, mas ainda não está ` +
                    "transmitindo. Abra a câmera no celular."
            );

            definirTagVideo("is-espera", "aguardando video");

            return;
        }

        if (player.id === selecionadoId && player.ms) {
            return;
        }

        verificando = true;

        try {
            let info = null;

            try {
                info = await fetchJson(
                    `${STREAM}/info/${encodeURIComponent(selecionadoId)}`
                );
            } catch (erro) {
                info = null;
            }

            if (selecionadoId !== aparelho.id) {
                return;
            }

            if (!info || !info.ativo || !info.codec) {
                if (!semCodecDesde) {
                    semCodecDesde = Date.now();
                }

                // Sessao aberta mas sem SPS/PPS: o celular
                // conectou e nao esta enviando imagem (camera
                // parada). Depois de 12s deixa isso explicito.
                if (Date.now() - semCodecDesde > 12000) {
                    mostrarOverlay(
                        "SEM FRAMES",
                        `${aparelho.nome} está conectado, mas não envia ` +
                            "imagem. Abra a câmera no celular ou toque em " +
                            "reconectar."
                    );

                    definirTagVideo("is-espera", "sem frames");
                } else {
                    mostrarOverlay(
                        "CONECTANDO",
                        "preparando o stream de vídeo..."
                    );

                    definirTagVideo("is-espera", "conectando");
                }

                if (info && info.erro) {
                    mostrarOverlay("ERRO NO VÍDEO", info.erro, true);
                }

                return;
            }

            if (player.id === aparelho.id || player.ms) {
                pararVideo(null);
            }

            semCodecDesde = 0;

            iniciarVideo(aparelho.id, info);
        } finally {
            verificando = false;
        }
    }

    // ------------------------------------------------------------
    // ZOOM / WEBCAM
    // ------------------------------------------------------------

    // Converte o ratio da camera (ex.: 2.0x) para o linear 0..1 que o
    // celular usa no setLinearZoom. linear = (1 - 1/r) / (1 - 1/max).
    function linearDeRatio(ratio, max) {
        if (!Number.isFinite(max) || max <= 1) return 0;

        return Math.min(
            1,
            Math.max(0, (1 - 1 / ratio) / (1 - 1 / max))
        );
    }

    // Monta a lista de presets de zoom no estilo "camera de celular"
    // (1x, 1.5x, 2x, ...). Sempre inclui 1x e o maximo; no meio usa
    // passos "bonitos" que caibam no intervalo disponivel.
    function presetsDeZoom(min, max) {
        const lo = Math.max(1, Number(min) || 1);
        const hi = Number(max) || lo;

        if (!(hi > lo)) {
            return [lo];
        }

        const lista = [lo];
        const candidatos = [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10, 12, 15, 20];

        for (const valor of candidatos) {
            if (valor > lo + 1e-6 && valor < hi - 1e-6) {
                lista.push(valor);
            }
        }

        lista.push(hi);

        return lista;
    }

    function irParaZoom(aparelho, ratio) {
        const max = Number(aparelho.zoomMax) || 1;

        zoomAlvoPendente = ratio;
        el.zoomValor.textContent = Number(ratio).toFixed(2) + "x";

        // O celular espera um linear normalizado 0..1 (setLinearZoom),
        // nao o ratio. Convertemos aqui: linear 0 -> 1x, linear 1 -> max.
        enviarZoom(aparelho.id, linearDeRatio(ratio, max));
    }

    // Desenha (ou so re-marca) a regua de presets. So recria o DOM
    // quando a lista muda de verdade, pra nao perder hover/clique.
    function desenharZoomPresets(aparelho, habilitado, atual) {
        const max = aparelho ? (Number(aparelho.zoomMax) || 1) : 1;
        const min = aparelho ? (Number(aparelho.zoomMin) || 1) : 1;
        const lista = aparelho ? presetsDeZoom(min, max) : [];
        const chave = lista.join("|");

        if (chave !== zoomPresetsChave) {
            el.zoomPresets.innerHTML = "";
            zoomPresetsChave = chave;

            for (const valor of lista) {
                const botao = document.createElement("button");

                botao.className = "zoom-preset";
                botao.type = "button";
                botao.dataset.ratio = String(valor);
                botao.textContent = formatarZoom(valor);
                el.zoomPresets.appendChild(botao);
            }
        }

        const alvo = Number(atual) || 1;

        for (const botao of el.zoomPresets.children) {
            const ratio = Number(botao.dataset.ratio);
            const ativo = Math.abs(ratio - alvo) / alvo < 0.06;

            botao.disabled = !habilitado;
            botao.classList.toggle("is-ativo", ativo);
        }
    }

    function formatarZoom(valor) {
        const n = Number(valor);

        return (Number.isInteger(n) ? n : n.toFixed(1)) + "x";
    }

    function aparelhoSelecionado() {
        return aparelhos.find(item => item.id === selecionadoId) || null;
    }

    async function enviarZoom(id, valor) {
        try {
            await fetch(`/api/devices/${encodeURIComponent(id)}/zoom`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ valor })
            });
        } catch (erro) {
            console.warn("zoom:", erro.message);
        }
    }

    async function enviarLente(id, valor) {
        try {
            await fetch(`/api/devices/${encodeURIComponent(id)}/lente`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ valor })
            });
        } catch (erro) {
            console.warn("lente:", erro.message);
        }
    }

    async function enviarFlash(id, ligado) {
        try {
            await fetch(`/api/devices/${encodeURIComponent(id)}/flash`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ ligado })
            });
        } catch (erro) {
            console.warn("flash:", erro.message);
        }
    }

    async function enviarWebcam(id, ligada) {
        try {
            await fetch(`/api/devices/${encodeURIComponent(id)}/webcam`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ ligada })
            });
        } catch (erro) {
            console.warn("webcam:", erro.message);
        }
    }

    async function enviarRotacao(id, valor) {
        try {
            await fetch(`/api/devices/${encodeURIComponent(id)}/rotacao`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ valor })
            });
        } catch (erro) {
            console.warn("rotacao:", erro.message);
        }
    }

    async function enviarEspelho(id, ligado) {
        try {
            await fetch(`/api/devices/${encodeURIComponent(id)}/espelho`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ ligado })
            });
        } catch (erro) {
            console.warn("espelho:", erro.message);
        }
    }

    // Liga/desliga a auto-rotacao por sensor no celular.
    async function enviarAutoRotacao(id, ligada) {
        try {
            await fetch(`/api/devices/${encodeURIComponent(id)}/auto-rotacao`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ ligada })
            });
        } catch (erro) {
            console.warn("auto-rotacao:", erro.message);
        }
    }

    // Liga/desliga a captura do microfone no celular (nao e so
    // mute local: o celular para de gravar de verdade).
    async function enviarMic(id, ligado) {
        try {
            await fetch(`/api/devices/${encodeURIComponent(id)}/mic`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ ligado })
            });
        } catch (erro) {
            console.warn("mic:", erro.message);
        }
    }

    const NOME_LENTE = { back: "traseira", front: "frontal" };

    function atualizarLente(aparelho, lentes) {
        const atual = lentes.includes(aparelho.lente)
            ? aparelho.lente
            : (lentes[0] || "");

        el.btnLente.textContent = "lente: " + (atual
            ? (NOME_LENTE[atual] || atual)
            : "--");
    }

    function atualizarControles() {
        const aparelho = aparelhoSelecionado();

        const ativo = Boolean(
            aparelho && aparelho.online && aparelho.video
        );

        const zoomOk = Boolean(aparelho && aparelho.online);

        el.btnReconectar.disabled = !aparelho || !aparelho.online;
        el.btnTela.disabled = !aparelho;
        el.btnWebcam.disabled = !aparelho || !aparelho.online;

        const lentes = (aparelho && Array.isArray(aparelho.lentesDisponiveis))
            ? aparelho.lentesDisponiveis
            : [];

        el.btnLente.disabled = !aparelho || !aparelho.online || lentes.length < 2;

        el.btnFlash.disabled =
            !aparelho ||
            !aparelho.online ||
            !aparelho.flashDisponivel;

        el.btnRotacao.disabled = !aparelho || !aparelho.online;
        el.btnAutoRotacao.disabled = !aparelho || !aparelho.online;
        el.btnEspelho.disabled = !aparelho || !aparelho.online;

        if (!aparelho) {
            setTexto(el.zoomValor, "1.0x");
            desenharZoomPresets(null);
            setTexto(el.btnLente, "lente: --");
            setTexto(el.btnFlash, "flash: --");
            setToggle(el.btnFlash, "is-success", false);
            setToggle(el.btnFlash, "is-danger", false);
            setTexto(el.btnWebcam, "webcam: --");
            setToggle(el.btnWebcam, "is-success", false);
            setToggle(el.btnWebcam, "is-danger", false);
            el.btnRotacao.title = "girar 90°";
            setToggle(el.btnRotacao, "is-success", false);
            setToggle(el.btnRotacao, "is-warn", false);
            el.btnAutoRotacao.title = "rotacao automatica";
            setToggle(el.btnAutoRotacao, "is-success", false);
            setToggle(el.btnAutoRotacao, "is-danger", false);
            el.btnEspelho.title = "espelhar";
            setToggle(el.btnEspelho, "is-success", false);
            setToggle(el.btnEspelho, "is-danger", false);
            return;
        }

        const rotacao = Number(aparelho.rotacao) || 0;

        el.btnRotacao.title = "girar 90° (atual: " + rotacao + "°)";
        setToggle(el.btnRotacao, "is-warn", rotacao !== 0);
        setToggle(el.btnRotacao, "is-success", false);

        const autoRot = aparelho.autoRotacao !== false;
        el.btnAutoRotacao.title = autoRot
            ? "rotacao automatica: ligada"
            : "rotacao automatica: desligada";
        setToggle(el.btnAutoRotacao, "is-success", autoRot);
        setToggle(el.btnAutoRotacao, "is-danger", !autoRot);

        el.btnEspelho.title = aparelho.espelho
            ? "espelho: ligado"
            : "espelho: desligado";
        setToggle(el.btnEspelho, "is-success", Boolean(aparelho.espelho));
        setToggle(el.btnEspelho, "is-danger", !aparelho.espelho);

        // Se o usuario acabou de clicar num preset, mostramos o alvo
        // dele ate o celular confirmar (o status pode demorar ~150ms
        // e voltar com o valor antigo, o que daria um "pulo" visual).
        if (zoomAlvoPendente !== null) {
            if (Math.abs((aparelho.zoom || 1) - zoomAlvoPendente) < 0.08) {
                zoomAlvoPendente = null;
            }
        }

        const atual = zoomAlvoPendente !== null
            ? zoomAlvoPendente
            : (aparelho.zoom || 1);

        setTexto(el.zoomValor, Number(atual).toFixed(2) + "x");

        desenharZoomPresets(aparelho, zoomOk, atual);

        atualizarLente(aparelho, lentes);

        setTexto(
            el.btnWebcam,
            aparelho.webcam ? "webcam: on" : "webcam: off"
        );

        setToggle(el.btnWebcam, "is-success", Boolean(aparelho.webcam));
        setToggle(el.btnWebcam, "is-danger", !aparelho.webcam);

        setTexto(el.btnFlash, aparelho.flash ? "flash: on" : "flash: off");

        setToggle(el.btnFlash, "is-success", Boolean(aparelho.flash));
        setToggle(el.btnFlash, "is-danger", !aparelho.flash);
    }

    // ------------------------------------------------------------
    // LISTA DE APARELHOS
    // ------------------------------------------------------------

    function criarItem() {
        const card = document.createElement("div");

        card.className = "item";

        card.innerHTML = `
            <div class="item-topo">
                <span class="item-nome"></span>
                <span class="item-transporte"></span>
            </div>
            <div class="item-linha">
                <span class="status"><span class="bolinha"></span><span class="texto"></span></span>
                <span class="video"><span class="bolinha"></span><span class="texto"></span></span>
            </div>
            <div class="item-linha">
                <span>fps <b class="fps">--</b></span>
                <span>bitrate <b class="kbps">--</b></span>
            </div>
        `;

        const refs = {
            el: card,
            nome: card.querySelector(".item-nome"),
            transporte: card.querySelector(".item-transporte"),
            statusBola: card.querySelector(".status .bolinha"),
            statusTexto: card.querySelector(".status .texto"),
            videoBola: card.querySelector(".video .bolinha"),
            videoTexto: card.querySelector(".video .texto"),
            fps: card.querySelector(".fps"),
            kbps: card.querySelector(".kbps")
        };

        card.addEventListener("click", () => selecionar(refs.id));

        return refs;
    }

    function atualizarItem(refs, aparelho) {
        refs.id = aparelho.id;

        setTexto(refs.nome, aparelho.nome);
        setTexto(
            refs.transporte,
            aparelho.transporte + (aparelho.ip ? " · " + aparelho.ip : "")
        );

        setClasse(
            refs.statusBola,
            "bolinha " + (aparelho.online ? "on" : "off")
        );

        setTexto(refs.statusTexto, aparelho.online ? "online" : "offline");

        setClasse(
            refs.videoBola,
            "bolinha " + (aparelho.video ? "video" : "")
        );

        setTexto(refs.videoTexto, aparelho.video ? "ao vivo" : "sem video");

        setTexto(refs.fps, aparelho.fps ? Number(aparelho.fps).toFixed(1) : "--");

        setTexto(
            refs.kbps,
            aparelho.kbps ? (aparelho.kbps / 1000).toFixed(1) + " mbps" : "--"
        );

        setToggle(refs.el, "is-selecionado", aparelho.id === selecionadoId);
    }

    function desenharLista() {
        const vistos = new Set();

        for (const aparelho of aparelhos) {
            vistos.add(aparelho.id);

            let refs = itens.get(aparelho.id);

            if (!refs) {
                refs = criarItem();
                refs.id = aparelho.id;
                itens.set(aparelho.id, refs);
                el.lista.appendChild(refs.el);
            }

            atualizarItem(refs, aparelho);
        }

        for (const [id, refs] of itens) {
            if (!vistos.has(id)) {
                refs.el.remove();
                itens.delete(id);
            }
        }

        el.vazio.hidden = aparelhos.length > 0;
    }

    function selecionar(id) {
        if (!id) {
            return;
        }

        const mesmo = selecionadoId === id;

        selecionadoId = id;

        for (const [itemId, refs] of itens) {
            refs.el.classList.toggle("is-selecionado", itemId === id);
        }

        if (mesmo) {
            return;
        }

        pararVideo(null);

        mostrarOverlay("CONECTANDO", "aguardando vídeo do celular...");
        definirTagVideo("is-espera", "conectando");

        atualizarControles();
        verificarVideo();
    }

    // ------------------------------------------------------------
    // POLL
    // ------------------------------------------------------------

    async function atualizar() {
        let dados = null;

        try {
            dados = await fetchJson("/api/devices");
        } catch (erro) {
            setTexto(el.tagDevices, "servidor offline");
            setClasse(el.tagDevices, "tag is-danger");
            return;
        }

        aparelhos = dados.aparelhos || [];

        const online = aparelhos.filter(item => item.online).length;

        setTexto(el.tagDevices, `${online} online / ${aparelhos.length}`);
        setClasse(el.tagDevices, "tag " + (online ? "is-success" : ""));

        setTexto(el.contagem, String(aparelhos.length));

        const endereco = `${dados.host}:${dados.porta}`;

        setTexto(el.pcHost, endereco);
        setTexto(el.rodapeHost, endereco);

        el.dica.innerHTML =
            "no celular: campo <b>pc host</b> = " +
            `<b>${dados.host}</b>` +
            " (wifi) · usb e automatico";

        setTexto(
            el.vazioSub,
            "conecte por usb (depuração ativa) ou no celular " +
                `informe o pc host ${dados.host}`
        );

        desenharLista();

        // Auto-seleciona o primeiro aparelho: "plugar e aparecer".
        const atual = aparelhos.find(item => item.id === selecionadoId);

        if (!atual) {
            const candidato =
                aparelhos.find(item => item.online && item.video) ||
                aparelhos.find(item => item.online) ||
                aparelhos[0] ||
                null;

            if (candidato) {
                selecionar(candidato.id);
            } else {
                selecionadoId = null;
                pararVideo(null);
                mostrarOverlay(
                    "SEM SINAL",
                    "conecte o android por usb ou wi-fi"
                );
            }
        }

        atualizarControles();

        if (atual) {
            setTexto(el.hudNome, atual.nome);
            setTexto(
                el.hudFps,
                atual.fps ? Number(atual.fps).toFixed(1) + " fps" : "-- fps"
            );
            setTexto(
                el.hudKbps,
                atual.kbps ? (atual.kbps / 1000).toFixed(1) + " mbps" : "--"
            );
        }

        if (atual) {
            // mic eh estado real do celular: espelha no PC.
            if (typeof atual.mic === "boolean") {
                micMudo = !atual.mic;
                el.video.muted = micMudo;
            }

            atualizarHudMic(atual);
        }

        if (atual && atual.video) {
            verificarVideo();
        }
    }

    // Mostra o estado do mic no HUD e habilita o botao.
    // micMudo espelha o estado real do celular (atual.mic):
    // off = celular parou de capturar de verdade.
    function atualizarHudMic(atual) {
        const temAudio = Boolean(atual && atual.audio && player.audio);

        el.btnMic.disabled = !temAudio;

        if (!temAudio) {
            el.hudMic.hidden = true;
            el.hudMicSep.hidden = true;
            return;
        }

        el.hudMic.hidden = false;
        el.hudMicSep.hidden = false;
        setTexto(el.hudMic, micMudo ? "mic off" : "mic on");
        setToggle(el.hudMic, "is-mudo", micMudo);

        setTexto(el.btnMic, micMudo ? "mic: off" : "mic: on");
        setToggle(el.btnMic, "is-danger", micMudo);
        setToggle(el.btnMic, "is-success", !micMudo);
    }

    // ------------------------------------------------------------
    // EVENTOS
    // ------------------------------------------------------------

    el.video.addEventListener("playing", () => {
        player.tocando = true;

        esconderOverlay();

        el.hud.hidden = false;
        el.hudDireita.hidden = false;

        definirTagVideo("is-live", "● ao vivo");
    });

    el.video.addEventListener("pause", () => {
        player.tocando = false;

        if (player.id) {
            definirTagVideo("is-espera", "pausado");
        }
    });

    // Decoder sem dados: em vez de esperar o buffer antigo ser
    // drenado (o que aumenta o atraso), busca a borda ao vivo assim
    // que o player volta a ter o que tocar.
    el.video.addEventListener("stalled", () => {
        setTimeout(alinharAoVivo, 0);
    });

    el.zoomPresets.addEventListener("click", ev => {
        const botao = ev.target.closest(".zoom-preset");

        if (!botao || botao.disabled) {
            return;
        }

        const aparelho = aparelhoSelecionado();

        if (!aparelho) {
            return;
        }

        irParaZoom(aparelho, Number(botao.dataset.ratio));
    });

    el.btnWebcam.addEventListener("click", () => {
        const aparelho = aparelhoSelecionado();

        if (!aparelho) {
            return;
        }

        const ligada = !aparelho.webcam;

        aparelho.webcam = ligada;

        atualizarControles();
        enviarWebcam(aparelho.id, ligada);
    });

    el.btnRotacao.addEventListener("click", () => {
        const aparelho = aparelhoSelecionado();

        if (!aparelho) {
            return;
        }

        // "clicou virou": gira 90° por clique.
        const rotacao = ((Number(aparelho.rotacao) || 0) + 90) % 360;

        aparelho.rotacao = rotacao;

        atualizarControles();
        enviarRotacao(aparelho.id, rotacao);
    });

    el.btnAutoRotacao.addEventListener("click", () => {
        const aparelho = aparelhoSelecionado();

        if (!aparelho) {
            return;
        }

        const ligada = aparelho.autoRotacao === false;

        aparelho.autoRotacao = ligada;

        atualizarControles();
        enviarAutoRotacao(aparelho.id, ligada);
    });

    el.btnEspelho.addEventListener("click", () => {
        const aparelho = aparelhoSelecionado();

        if (!aparelho) {
            return;
        }

        const espelhado = !aparelho.espelho;

        aparelho.espelho = espelhado;

        atualizarControles();
        enviarEspelho(aparelho.id, espelhado);
    });

    el.btnLente.addEventListener("click", () => {
        const aparelho = aparelhoSelecionado();

        if (!aparelho) {
            return;
        }

        const lentes = Array.isArray(aparelho.lentesDisponiveis)
            ? aparelho.lentesDisponiveis
            : [];

        if (lentes.length < 2) {
            return;
        }

        // "clicou virou": alterna para a proxima lente da lista.
        const atual = lentes.indexOf(aparelho.lente);
        const valor = lentes[(atual + 1) % lentes.length];

        aparelho.lente = valor;

        atualizarLente(aparelho, lentes);
        enviarLente(aparelho.id, valor);
    });

    el.btnFlash.addEventListener("click", () => {
        const aparelho = aparelhoSelecionado();

        if (!aparelho) {
            return;
        }

        const ligado = !aparelho.flash;

        aparelho.flash = ligado;

        atualizarControles();
        enviarFlash(aparelho.id, ligado);
    });

    // Alterna o microfone do CELULAR (captura real), nao so o
    // mute local. O celular para/retoma a gravacao e o estado
    // volta sincronizado pelo HUD.
    el.btnMic.addEventListener("click", () => {
        const aparelho = aparelhoSelecionado();

        if (!aparelho) {
            return;
        }

        const ligado = Boolean(aparelho.mic) ? false : true;

        aparelho.mic = ligado;
        micMudo = !ligado;

        // Mantem o PC tocando o audio quando o mic esta ligado.
        el.video.muted = micMudo;

        atualizarHudMic(aparelho);
        enviarMic(aparelho.id, ligado);
    });

    el.btnReconectar.addEventListener("click", () => {
        if (!selecionadoId) {
            return;
        }

        const id = selecionadoId;

        fetch(`/api/devices/${encodeURIComponent(id)}/reconectar`, {
            method: "POST"
        }).catch(() => {});

        pararVideo(null);
        mostrarOverlay(
            "RECONECTANDO",
            "pedindo ao celular para abrir um novo vídeo..."
        );
        definirTagVideo("is-espera", "reconectando");

        agendarReconexao(id);
    });

    el.btnTela.addEventListener("click", () => {
        if (document.fullscreenElement) {
            document.exitFullscreen();
            return;
        }

        el.videoWrap.requestFullscreen().catch(() => {});
    });

    document.addEventListener("fullscreenchange", () => {
        el.btnTela.textContent = document.fullscreenElement
            ? "sair da tela"
            : "tela cheia";
    });

    // ------------------------------------------------------------
    // CAIXA DE CONTROLES (flutuante, esconde sozinha ao usar o video)
    // ------------------------------------------------------------

    let timerEsconderControles = null;
    let controlesFixados = false;

    function mostrarControles(mostrar) {
        el.controles.classList.toggle("is-oculto", !mostrar);
        el.btnMostrarControles.hidden = mostrar;
    }

    function agendarEsconderControles() {
        if (timerEsconderControles) {
            clearTimeout(timerEsconderControles);
        }

        timerEsconderControles = setTimeout(() => {
            if (!controlesFixados) {
                mostrarControles(false);
            }
        }, 2600);
    }

    // Qualquer interacao com a caixa mantem ela aberta mais um tempo.
    el.controles.addEventListener("mouseenter", () => {
        if (timerEsconderControles) {
            clearTimeout(timerEsconderControles);
        }
    });

    el.controles.addEventListener("mouseleave", () => {
        if (!controlesFixados) {
            agendarEsconderControles();
        }
    });

    // Mouse perto do canto (ou sobre a caixa) reabre.
    el.videoWrap.addEventListener("mousemove", ev => {
        const caixa = el.controles.getBoundingClientRect();
        const perto =
            ev.clientX > caixa.left - 90 &&
            ev.clientY > caixa.top - 90;

        if (perto) {
            mostrarControles(true);

            if (timerEsconderControles) {
                clearTimeout(timerEsconderControles);
            }
        } else if (!el.controles.classList.contains("is-oculto")) {
            agendarEsconderControles();
        }
    });

    el.btnOcultarControles.addEventListener("click", () => {
        controlesFixados = false;
        mostrarControles(false);
    });

    el.btnMostrarControles.addEventListener("click", () => {
        controlesFixados = true;
        mostrarControles(true);
    });

    // ------------------------------------------------------------
    // JANELA (Electron)
    // ------------------------------------------------------------

    const janelaApi = window.lucao;

    if (!janelaApi) {
        document.body.classList.add("sem-electron");
    } else {
        document
            .getElementById("btnFechar")
            .addEventListener("click", () => janelaApi.fechar());

        document
            .getElementById("btnMinimizar")
            .addEventListener("click", () => janelaApi.minimizar());

        document
            .getElementById("btnMaximizar")
            .addEventListener("click", () => janelaApi.maximizar());
    }

    async function carregarInfo() {
        let info = null;

        try {
            if (janelaApi) {
                info = await janelaApi.info();
            }
        } catch (erro) {
            info = null;
        }

        if (info) {
            el.rodapeVersao.textContent = "checkpoint " + info.versao;
            el.rodapeVideo.textContent = "tcp " + info.portaVideo;
            el.rodapeStream.textContent = String(info.portaStream);

            el.avisoFfmpeg.hidden = Boolean(info.ffmpeg.ok);

            el.rodapeFfmpeg.textContent = info.ffmpeg.ok
                ? "ffmpeg ok"
                : "ffmpeg ausente";

            el.rodapeFfmpeg.className =
                "ffmpeg " + (info.ffmpeg.ok ? "ok" : "erro");
        } else {
            el.rodapeFfmpeg.textContent = "ffmpeg (navegador)";
            el.rodapeFfmpeg.className = "ffmpeg";
        }
    }

    // ------------------------------------------------------------
    // LOOP DE FUNDO
    // ------------------------------------------------------------

    // Socket aberto mas sem dados: pede reconexao (no maximo a
    // cada 10s) sem derrubar o player. Se o celular reconectar,
    // o stream termina e o fluxo normal de reconexao assume.
    function pedirReconexaoSeCongelado() {
        if (!player.id || !player.ultimoDado) {
            return;
        }

        if (Date.now() - player.ultimoDado < 6000) {
            return;
        }

        if (Date.now() - player.ultimoPedidoReconexao < 10000) {
            return;
        }

        player.ultimoPedidoReconexao = Date.now();

        definirTagVideo("is-espera", "sem frames");

        fetch(
            `/api/devices/${encodeURIComponent(player.id)}/reconectar`,
            { method: "POST" }
        ).catch(() => {});
    }

    setInterval(() => {
        pedirReconexaoSeCongelado();

        const sb = player.sb;

        if (!sb || !player.id || !sb.buffered.length) {
            return;
        }

        const fim = sb.buffered.end(sb.buffered.length - 1);
        const atual = el.video.currentTime;

        el.hudBuf.textContent =
            "buf " + Math.max(0, fim - atual).toFixed(1) + "s";

        alinharAoVivo();
    }, 400);

    carregarInfo();
    atualizar();
    setInterval(atualizar, 700);
})();
