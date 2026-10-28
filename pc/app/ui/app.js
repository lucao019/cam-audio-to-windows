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
        zoom: document.getElementById("zoom"),
        zoomValor: document.getElementById("zoomValor"),
        zoomMin: document.getElementById("zoomMin"),
        zoomMax: document.getElementById("zoomMax"),
        btnWebcam: document.getElementById("btnWebcam"),
        btnReconectar: document.getElementById("btnReconectar"),
        btnTela: document.getElementById("btnTela"),
        rodapeHost: document.getElementById("rodapeHost"),
        rodapeVideo: document.getElementById("rodapeVideo"),
        rodapeStream: document.getElementById("rodapeStream"),
        rodapeFfmpeg: document.getElementById("rodapeFfmpeg"),
        rodapeVersao: document.getElementById("rodapeVersao"),
        avisoFfmpeg: document.getElementById("avisoFfmpeg")
    };

    const itens = new Map();

    let aparelhos = [];
    let selecionadoId = null;
    let arrastandoZoom = false;
    let timerZoom = null;
    let verificando = false;
    let semCodecDesde = 0;
    let timerReconexao = null;

    // Estado do pipeline MSE.
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
        ultimoPedidoReconexao: 0
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
        el.tagVideo.textContent = texto;
        el.tagVideo.className = "tag " + estado;
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

        el.hud.hidden = true;
        el.hudDireita.hidden = true;
        el.hudBuf.textContent = "buf --";

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
        const alvo = atual - 4;

        if (alvo > inicio + 0.5) {
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

        while (player.fila.length && total < 512 * 1024) {
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

    // Mantem o player no fim do buffer. Um stream que comeca no
    // meio da sessao (reconexao) pode ter timeline a partir de
    // 9s; sem isso o currentTime fica abaixo do buffer e trava.
    function alinharAoVivo() {
        const sb = player.sb;

        if (!sb || sb.updating || !player.id || !sb.buffered.length) {
            return;
        }

        const inicio = sb.buffered.start(0);
        const fim = sb.buffered.end(sb.buffered.length - 1);
        const atual = el.video.currentTime;

        const fora = atual < inicio - 0.05 || atual > fim + 0.05;
        const atras = fim - atual > 2.5;

        if (player.posicionado && !fora && !atras) {
            return;
        }

        const alvo = Math.max(inicio + 0.05, fim - 0.3);

        player.posicionado = true;

        if (Math.abs(alvo - atual) > 0.15) {
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
                        sb.buffered.end(sb.buffered.length - 1) - atual > 12
                    ) {
                        limparBuffer();
                    }
                });

                sb.addEventListener("error", () => {
                    falharVideo("erro no SourceBuffer");
                });

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

    function ratioDeLinear(linear, max) {
        if (!Number.isFinite(max) || max <= 1) return 1;

        return 1 / (1 - linear * (1 - 1 / max));
    }

    function linearDeRatio(ratio, max) {
        if (!Number.isFinite(max) || max <= 1) return 0;

        return Math.min(
            1,
            Math.max(0, (1 - 1 / ratio) / (1 - 1 / max))
        );
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

    function atualizarControles() {
        const aparelho = aparelhoSelecionado();

        const ativo = Boolean(
            aparelho && aparelho.online && aparelho.video
        );

        el.zoom.disabled = !aparelho || !aparelho.online;
        el.btnReconectar.disabled = !aparelho || !aparelho.online;
        el.btnTela.disabled = !aparelho;
        el.btnWebcam.disabled = !aparelho || !aparelho.online;

        if (!aparelho) {
            el.zoomValor.textContent = "1.0x";
            el.zoomMin.textContent = "1.0x";
            el.zoomMax.textContent = "1.0x";
            el.btnWebcam.textContent = "webcam: --";
            el.btnWebcam.classList.remove("is-success", "is-danger");
            return;
        }

        const max = aparelho.zoomMax || 1;

        el.zoom.dataset.max = String(max);

        if (!arrastandoZoom) {
            const linear = linearDeRatio(aparelho.zoom || 1, max);

            el.zoom.value = String(linear);
            el.zoomValor.textContent =
                Number(aparelho.zoom || 1).toFixed(2) + "x";
        }

        el.zoomMin.textContent = Number(aparelho.zoomMin || 1).toFixed(1) + "x";
        el.zoomMax.textContent = Number(max).toFixed(1) + "x";

        el.btnWebcam.textContent = aparelho.webcam
            ? "webcam: on"
            : "webcam: off";

        el.btnWebcam.classList.toggle("is-success", Boolean(aparelho.webcam));
        el.btnWebcam.classList.toggle("is-danger", !aparelho.webcam);
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

        refs.nome.textContent = aparelho.nome;
        refs.transporte.textContent =
            aparelho.transporte + (aparelho.ip ? " · " + aparelho.ip : "");

        refs.statusBola.className =
            "bolinha " + (aparelho.online ? "on" : "off");

        refs.statusTexto.textContent = aparelho.online ? "online" : "offline";

        refs.videoBola.className =
            "bolinha " + (aparelho.video ? "video" : "");

        refs.videoTexto.textContent = aparelho.video
            ? "ao vivo"
            : "sem video";

        refs.fps.textContent = aparelho.fps
            ? Number(aparelho.fps).toFixed(1)
            : "--";

        refs.kbps.textContent = aparelho.kbps
            ? (aparelho.kbps / 1000).toFixed(1) + " mbps"
            : "--";

        refs.el.classList.toggle(
            "is-selecionado",
            aparelho.id === selecionadoId
        );
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
            el.tagDevices.textContent = "servidor offline";
            el.tagDevices.className = "tag is-danger";
            return;
        }

        aparelhos = dados.aparelhos || [];

        const online = aparelhos.filter(item => item.online).length;

        el.tagDevices.textContent = `${online} online / ${aparelhos.length}`;
        el.tagDevices.className =
            "tag " + (online ? "is-success" : "");

        el.contagem.textContent = String(aparelhos.length);

        const endereco = `${dados.host}:${dados.porta}`;

        el.pcHost.textContent = endereco;
        el.rodapeHost.textContent = endereco;

        el.dica.innerHTML =
            "no celular: campo <b>pc host</b> = " +
            `<b>${dados.host}</b>` +
            " (wifi) · usb e automatico";

        el.vazioSub.textContent =
            "conecte por usb (depuração ativa) ou no celular " +
            `informe o pc host ${dados.host}`;

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
            el.hudNome.textContent = atual.nome;
            el.hudFps.textContent = atual.fps
                ? Number(atual.fps).toFixed(1) + " fps"
                : "-- fps";
            el.hudKbps.textContent = atual.kbps
                ? (atual.kbps / 1000).toFixed(1) + " mbps"
                : "--";
        }

        if (atual && atual.video) {
            verificarVideo();
        }
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

    el.zoom.addEventListener("input", () => {
        const aparelho = aparelhoSelecionado();

        if (!aparelho) {
            return;
        }

        const linear = Number(el.zoom.value);
        const max = Number(el.zoom.dataset.max) || 1;

        el.zoomValor.textContent = ratioDeLinear(linear, max).toFixed(2) + "x";

        clearTimeout(timerZoom);

        timerZoom = setTimeout(() => {
            enviarZoom(aparelho.id, linear);
        }, 60);
    });

    el.zoom.addEventListener("pointerdown", () => {
        arrastandoZoom = true;
    });

    el.zoom.addEventListener("pointerup", () => {
        setTimeout(() => {
            arrastandoZoom = false;
        }, 350);

        const aparelho = aparelhoSelecionado();

        if (aparelho) {
            enviarZoom(aparelho.id, Number(el.zoom.value));
        }
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
    }, 1000);

    carregarInfo();
    atualizar();
    setInterval(atualizar, 700);
})();
