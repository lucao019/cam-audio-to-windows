## Checkpoint 31 — Seleção de lente (traseira/frontal), flash e menos delay

### Adicionado

- Escolha da lente no app do PC: seletor `traseira`/`frontal` ao lado do zoom, habilitado só quando o aparelho expõe as duas.
- Android (`CameraForegroundService`): detecção das lentes via `CameraCharacteristics.LENS_FACING`, `trocarLente()` com `unbindAll()` + rebind do `CameraSelector`, persistência em prefs e comando remoto `lente`.
- Android: controle de flash pelo app do PC (`comando remoto flash`), com o toggle refletindo o estado real da lanterna.
- `server.js`: campos `lente`/`lentesDisponiveis` no status do aparelho e endpoint `POST /api/devices/:id/lente`.
- UI (`pc/app/ui`): `atualizarLente()`/`enviarLente()` e o elemento `#lente`.
- `server.js`: `VERSAO` sincronizada com o checkpoint (estava em "29").

### Alterado

- Delay do vídeo próximo de tempo real:
  - Android: `KEY_I_FRAME_INTERVAL` de 1s para 0 (all-intra). O PC só fecha fragmento fMP4 em keyframe, então o pior caso de espera era ~1s.
  - Android: fila de escrita TCP limitada (~4 frames) com descarte do frame mais antigo — sob Wi-Fi lento o vídeo pula frames em vez de acumular atraso.
  - Android: pedido de keyframe forçado (`PARAMETER_KEY_REQUEST_SYNC_FRAME`) ao trocar de lente, ao reconectar e sempre que a fila descarta, evitando imagem preta até o próximo I-frame.
- Estado de vídeo mais próximo do ao vivo: alinhamento na borda com tolerância de 0.15s, poda do buffer em 1s e retenção de apenas 0.5s de histórico atrás do playhead.
- Tratamento de `stalled` e verificação de borda a cada 400ms para recuperar travamentos sem acumular atraso.
- `video-bridge.js`: fMP4 fragmentado a cada ~100ms (`-frag_duration 100000`) em vez de um fragmento por keyframe, reduzindo o atraso percebido.

---

## Checkpoint 30 — Descoberta automática do PC na LAN (Wi‑Fi sem digitar IP)

### Adicionado

- Descoberta do PC por UDP na porta 5053: o celular faz broadcast de `LUCAO?1`, o PC responde `LUCAO!1 <hostname> <ip>`.
- `server.js`: socket UDP de descoberta (`iniciarDescoberta()`/`pararDescoberta()`), iniciado junto com o servidor HTTP e no app Electron.
- Android (`CameraForegroundService`): thread de descoberta que sobe no `onCreate`, procura o PC ao abrir e preenche o `pc host` sozinho.
- Host descoberto entra automaticamente na lista de candidatos, entre o USB e o `pc host` digitado manualmente.

### Alterado

- `candidatosHost()` passa a considerar o host descoberto na LAN antes do IP manual, então a reconexão Wi‑Fi usa o endereço achado sem intervenção.
- Descoberta para sozinha quando o vídeo já está conectado por USB (o cabo tem prioridade) e ao destruir o serviço.
- `README.md`: tabela de portas com a 5053/UDP, seção "USB vs Wi‑Fi" descrevendo a descoberta automática e linha de solução de problemas atualizada.

### Validado

```text
PC: node -e "require('./server.js').iniciarDescoberta()" responde LUCAO!1 01lucas 192.168.2.121
Android: gradlew.bat :app:assembleDebug --offline -> BUILD SUCCESSFUL
```

## Checkpoint 29 — App de Desktop (janela única, sem ffplay)

### Adicionado

- App Electron em `pc/app` com janela frameless no estilo terminal (três bolinhas, ciano/verde, monospace).
- `pc/app/video-bridge.js`: recebe H.264 cru na porta 5051 (handshake `LUCAO/1`), remuxa com ffmpeg (`-c:v copy`) e serve MP4 fragmentado em HTTP na 5052 (`/info/:id`, `/stream/:id`, `/health`).
- `pc/app/main.js` e `preload.js`: janela, IPC (minimizar/maximizar/fechar/info), detecção de ffmpeg, vigia de vídeo e companion embutido.
- UI (`pc/app/ui`): player MSE com alinhamento ao vivo, lista de aparelhos, zoom, webcam, reconectar, tela cheia e HUD (codec/resolução/fps/bitrate/buffer).
- Ícone do app (`pc/app/icone.ico`, 16–256 px) e empacotamento portable com `electron-builder`.
- Rota `POST /api/devices/:id/reconectar` e comando `reconectar` no Android.

### Alterado

- `server.js` na versão 29: CORS, serve a UI em `/app`, exporta `iniciar()`/`app` sem subir servidor ao ser importado.
- `companion.js`: `monitorar({ servidores, estado })`, APK resolvido por candidatos (build, `resourcesPath`, `__dirname`), estado em `userData` quando empacotado e `adb reverse` reposto a cada 10 s.
- App abre com autoplay liberado e seleciona o primeiro aparelho sozinho ("plugar e aparecer").

### Corrigido

- Timestamps errados no fMP4 causados pelo VUI do SPS do celular: `-r 30` antes do `-i` gera 30 fps reais no container.
- Codec lido do `avcC` (offset +5/+6/+7) e MSE recebendo o MIME completo (`video/mp4; codecs="avc1.64001f"`).
- Player travado em "CONECTANDO" quando o buffer começava no meio da sessão (ex.: 9 s): agora busca a borda ao vivo.
- Sessão aberta sem SPS/PPS: após 12 s a UI troca "CONECTANDO" por "SEM FRAMES" com instrução para abrir a câmera/reconectar.
- Vídeo congelado com socket aberto: a UI pede reconexão após 6 s sem dados.
- `adb reverse` caía ao replugar o cabo sem o aparelho desconectar: o companion verifica a cada 10 s e repõe os túneis.
- Câmera do serviço podia fechar sem ninguém religar: watchdog de 3 s sem frames reatribui o CameraX.
- Escrita de vídeo travada após o PC fechar: watchdog de 5 s derruba o socket e reconecta.
- Fila de comandos antigos aplicando zoom/webcam fora de hora.
- Nomes longos de aparelhos na lista agora terminam em reticências em vez de serem cortados no meio.
- Abrir o app logo após fechá-lo podia não mostrar nada: o travamento de instância única agora espera até 6 s pelo processo anterior terminar (dois cliques seguidos abrem uma janela só).

### Validado

```text
Device: Motorola Moto G22 (Android 12 / API 31)
USB: 29.5-30 FPS, 640x480, avc1.64001f, ~2.6 Mbps
Wi-Fi fallback: túnel USB removido -> reconecta em 192.168.2.121
Botão reconectar: nova sessão (geração +1) e vídeo volta
Zoom: slider 1.44x no PC -> Android aplicou 1.441
Portable: dist/LUCAO-LINK-1.2.0-portable.exe (116 MB) com APK embutido
Primeiro run do portable: instala o APK, abre o app no celular e o vídeo aparece sozinho
Fechar e reabrir o app: câmera/vídeo voltam sem tocar no celular
Túnel removido em execução: companion repõe o adb reverse em até 10 s
```

## Checkpoint 25 — Foreground Service + Stable 30 FPS USB

### Adicionado

- `CameraForegroundService` para manter o pipeline de câmera fora da Activity.
- `LifecycleRegistry` para permitir o uso do CameraX dentro do Foreground Service.
- Notificação persistente do LUCAO LINK durante o funcionamento da webcam.
- Detecção das faixas de FPS disponíveis pela Camera2 API.
- Solicitação explícita de `30 FPS` usando a faixa `[30,30]`.
- Integração Camera2Interop com o CameraX para controlar o FPS da captura.
- Estrutura inicial do Windows Companion para o modo dedicado via USB.

### Alterado

- O pipeline de vídeo passou a pertencer exclusivamente ao `CameraForegroundService`.
- `MainActivity` deixou de iniciar uma segunda conexão TCP e um segundo encoder H.264.
- O encoder H.264 passou a ser selecionado dinamicamente pelo Android.
- No Moto G22, o encoder selecionado é:

```text
```

### Corrigido

- Corrigida a criação duplicada do pipeline de vídeo entre `MainActivity` e `CameraForegroundService`.
- Eliminada a segunda conexão TCP e o segundo encoder H.264.
- Identificado que os aproximadamente `14 FPS` não eram causados pelo encoder ou pelo transporte USB.
- Identificado que o CameraX estava selecionando automaticamente uma faixa de FPS inferior.
- Confirmado pela Camera2 que a câmera traseira do Moto G22 suporta a faixa `[30,30]`.
- CameraX passou a solicitar explicitamente `30 FPS`.

### Validado

Configuração atual:

```text
Dispositivo: Motorola Moto G22
Android: 12 / API 31
Resolução: 640x480
FPS solicitado: 30
Codec: H.264 / AVC
Bitrate: ~2 Mbps
Encoder: c2.mtk.avc.encoder
Transporte: USB / ADB Reverse
Porta de vídeo: 5051
```

Desempenho medido após estabilização:

```text
Entrada H.264: 29.5 - 29.8 FPS
Saída H.264:   29.5 - 29.8 FPS
```

O encoder acompanhou praticamente todos os frames entregues pela câmera.

O transporte TCP permaneceu estável durante o teste:

```text
VIDEO TCP CONECTADO: 127.0.0.1:5051
SPS ENVIADO: 23 bytes
PPS ENVIADO: 8 bytes
```

Nenhum `Broken pipe` foi observado durante o teste final.

### Estado Atual

Pipeline validado:

```text
Moto G22
   ↓
CameraX
640x480 @ 30 FPS
   ↓
YUV_420_888
   ↓
NV12
   ↓
MediaCodec
c2.mtk.avc.encoder
   ↓
H.264 ~2 Mbps
   ↓
TCP 5051
   ↓
ADB Reverse
   ↓
USB
   ↓
Windows
   ↓
Node.js Receiver
   ↓
FFplay
```

O pipeline USB de vídeo está funcionando de forma estável em aproximadamente 30 FPS.

### Próximos passos

- Finalizar a reconexão automática persistente do TCP de vídeo.
- Consolidar o Windows Companion.
- Automatizar completamente a recuperação após desconectar e reconectar o USB.
- Remover logs temporários de diagnóstico.
- Continuar o modo de câmera dedicada sem necessidade de interação com a tela.
- Implementar câmera virtual no Windows.
- Iniciar posteriormente o pipeline de áudio/microfone.

---c2.mtk.avc.encoder

## Checkpoint 24 — Dedicated USB Mode

### Adicionado
- Modo experimental de câmera dedicada via USB.
- Transporte por ADB Reverse nas portas 5050 e 5051.
- Android usando `127.0.0.1` para comunicação com o PC no modo USB.
- Auto Connect aproximadamente 1 segundo após iniciar o aplicativo.
- Seleção dinâmica do encoder H.264.
- Suporte validado no Motorola Moto G22 com Android 12.
- Encoder de hardware detectado e utilizado: `c2.mtk.avc.encoder`.

### Alterado
- Removida a dependência do encoder Qualcomm `OMX.qcom.video.encoder.avc`.
- `live-receiver.js` passou a encaminhar o socket H.264 usando `socket.pipe(ffplay.stdin)`.
- O botão CONECTAR permanece disponível como fallback manual.

### Validado
- CameraX em 640x480 @ 30 FPS.
- H.264 em aproximadamente 2 Mbps.
- Streaming de vídeo completamente via USB, sem depender de Wi-Fi.
- Auto Connect funcionando sem interação com a tela do Moto G22.
- Uma inicialização controlada gera uma única conexão e uma única janela FFplay.
- FFplay direto apresentou latência praticamente imperceptível.

### Pendências
- Pequena latência adicional no caminho `Node -> FFplay`.
- Recriação automática dos túneis ADB Reverse após reconexão USB.
- Reconexão automática completa.
- Windows Companion para automatizar o modo dedicado.

### Próximo passo
Criar o Windows Companion responsável por detectar o dispositivo ADB, recriar os túneis 5050/5051, iniciar os serviços do LUCAO LINK e abrir automaticamente o aplicativo Android.

# Changelog

All notable development changes to LUCAO LINK will be documented in this file.

The project is currently in active development.

---

## [Unreleased]

### Checkpoint 22 - Camera Controls and Low-Latency H.264 Playback

#### Added

- Dynamic Android camera capability detection using Camera2 APIs.
- Detection of available logical front and rear cameras.
- Detection of zoom range, focal length, sensor size and flash support.
- CameraX CameraControl integration.
- Dynamic zoom control through the Android interface.
- Zoom state synchronization using CameraX ZoomState.
- Zoom value and minimum/maximum zoom indicators.
- Encoder latency hint using MediaFormat.KEY_LATENCY.

#### Device Capabilities Verified

On the current Redmi device, Android exposes two logical cameras:

```text
Rear camera
Zoom: 1.0x - 10.0x
Flash: supported

Front camera
Zoom: 1.0x - 10.0x
Flash: not supported

### Checkpoint 21 - Live H.264 Streaming at 30 FPS

#### Added

- Live Android -> Windows H.264 video streaming over Wi-Fi.
- New Windows live receiver:
  `pc/live-receiver.js`
- Direct H.264 playback through FFplay.
- SPS and PPS transmission when the H.264 output format becomes available.
- Dedicated network executor for video transport.
- Updated Android interface with independent webcam and microphone controls.
- Webcam ON/OFF state without destroying the camera pipeline.
- H.264 keyframe request support when the webcam stream is enabled again.

#### Verified

Complete live video pipeline:

```text
Android Camera
      |
      v
CameraX
640x480 @ 30 FPS
      |
      v
YUV_420_888
      |
      v
Optimized NV12 conversion
      |
      v
Qualcomm MediaCodec H.264
      |
      v
TCP / Wi-Fi
Port 5051
      |
      v
Windows Node.js Receiver
      |
      v
FFplay
      |
      v
LIVE VIDEO
```

FFprobe successfully recognized the received stream:

```text
codec_name=h264
width=640
height=480
```

Final measured performance:

```text
Input:  29.9 - 30.1 FPS
Output: 29.9 - 30.1 FPS
Bitrate target: 2 Mbps
Resolution: 640x480
```

#### Performance

During initial live TCP testing, video processing dropped to approximately:

```text
25.5 FPS
```

The main bottleneck was identified in the
YUV_420_888 -> NV12 conversion.

The Y plane was being copied pixel by pixel, requiring approximately
307,200 individual Y-plane reads for every 640x480 frame.

A fast path was implemented for devices where:

```text
yPixelStride == 1
```

The optimized implementation copies complete Y rows instead of individual
pixels.

Performance improved from approximately:

```text
~25.5 FPS
```

to:

```text
~30.0 FPS
```

with stable encoder input and output rates.

#### Improved

- Removed per-frame `H264 ENVIADO` debug logging from the critical video path.
- Reduced unnecessary work during live video streaming.
- Separated TCP video writes from the main video processing path.
- Preserved a fallback NV12 conversion path for devices with different
  Y-plane layouts.
- Improved perceived live-stream latency.
- Changed the Windows H.264 capture receiver to append data during
  development tests.
- Added clean-capture testing to distinguish current video from previous
  H.264 test data.

#### Fixed

- Restored missing CameraX analyzer executor initialization.
- Restored the MediaCodec input-buffer queue path after it was accidentally
  lost during development.
- Fixed Android -> Windows H.264 transport validation.
- Confirmed that previously displayed old footage was caused by appended
  test captures rather than stale camera frames.
- Confirmed continuous H.264 payload delivery over TCP.

#### Current State

LUCAO LINK can now transmit the Android camera to a Windows PC over Wi-Fi
using hardware-encoded H.264 with live FFplay playback at approximately
30 FPS.

The core live video transport is operational.

#### Next Development Targets

- Dynamic Android camera capability detection.
- Front/rear and physical lens selection.
- Ultra-wide / telephoto options only when supported by the device.
- Zoom and exposure controls.
- Brightness, contrast, saturation, sharpness and color filters.
- Camera controls from both Android and Windows.
- Automatic reconnection after Wi-Fi interruptions.
- Android microphone capture.
- Microphone sensitivity and digital gain controls.
- Audio level monitoring.
- Noise reduction / AGC / echo cancellation when supported.
- Windows virtual webcam.
- Windows virtual microphone.

---


### Checkpoint 20 - Windows TCP Transport

#### Added

- Windows TCP receiver using Node.js.
- Video receiver listening on TCP port `5051`.
- Output prepared for raw H.264 data in `pc/capture.h264`.
- Android TCP socket connection method.
- TCP_NODELAY enabled for the video connection.

#### Fixed

- Fixed missing call to `conectarVideoTcp()` after the Android successfully connects to the PC control server.

#### Current State

The existing HTTP connection on port `5050` is working.

The Android device successfully reaches the Windows PC over the local network.

The new TCP video connection on port `5051` has been implemented and compiled successfully.

Final Android -> Windows TCP validation is still pending.

---

### Checkpoint 19 - Continuous H.264 Encoding

#### Added

- Continuous H.264 encoding using Android MediaCodec.
- Qualcomm hardware AVC encoder:
  `OMX.qcom.video.encoder.avc`
- H.264 output monitoring.
- Encoder input/output FPS statistics.
- H.264 output byte statistics.
- Codec configuration detection (`csd-0` and `csd-1`).

#### Verified

Stable video pipeline:

```text
CameraX
640x480 @ 30 FPS
      |
      v
YUV_420_888
      |
      v
NV12
      |
      v
Qualcomm MediaCodec
      |
      v
H.264
```

Measured performance:

```text
Input:  ~30 FPS
Output: ~30 FPS
Bitrate target: 2 Mbps
Resolution: 640x480
```

#### Fixed

- Removed the JPEG/HTTP path from the CameraX analyzer.
- Removed the previous ~5 FPS JPEG bottleneck.
- Improved CameraX capture from approximately 13 FPS to approximately 30 FPS.
- Added a 30 FPS target frame-rate request.
- Fixed a MediaCodec startup race condition where camera frames could reach the encoder before `MediaCodec.start()` completed.
- Encoder reference is now published only after successful startup.

---

### Earlier Development

#### Android Camera

- Created Android application using Java.
- Integrated CameraX.
- Added camera preview.
- Added camera permission handling.
- Added local network access.
- Tested Android -> Windows HTTP communication.

#### Video Processing

- Implemented YUV_420_888 frame processing.
- Implemented NV12 conversion.
- Tested JPEG frame transmission as an early prototype.
- Confirmed correct image colors.
- Replaced JPEG transport experiments with H.264 hardware encoding.

#### Encoder Investigation

Detected available H.264 encoders on the Android device:

```text
OMX.qcom.video.encoder.avc
c2.android.avc.encoder
OMX.google.h264.encoder
```

The Qualcomm hardware encoder was selected for the main video pipeline.

#### Resolution Investigation

Several CameraX resolution combinations were tested.

The stable configuration selected for the first implementation is:

```text
640x480
30 FPS
H.264
2 Mbps
```

---

## Planned

```text
Android Camera
      |
      v
H.264 Encoder
      |
      v
Persistent TCP Connection
      |
      v
Windows Receiver
      |
      v
H.264 Decoder
      |
      v
Virtual Webcam
```

Future development also includes:

```text
Android Microphone
      |
      v
AudioRecord
      |
      v
Network Transport
      |
      v
Windows Receiver
      |
      v
Virtual Microphone
```

Automatic reconnection and independent webcam/microphone controls are also planned.

---

## Checkpoint 26 - Automatic USB Video Reconnection

### Added

- Automatic Android TCP video reconnection.
- Persistent H.264 SPS/PPS for decoder recovery.
- Automatic SPS/PPS resend after reconnect.
- Automatic H.264 keyframe request after reconnect.
- Dedicated ordered H.264 writer executor.
- Windows Companion ownership of the video receiver process.
- Automatic recovery after physical USB disconnect/reconnect.

### Fixed

- Removed port 5051 health probing that created false receiver connections.
- Fixed FFplay receiver window being hidden.
- Fixed receiver lifecycle handling during USB reconnection.

### Validated

Physical USB disconnect/reconnect successfully restores the complete video pipeline automatically.

```text
Moto G22 -> H.264 -> ADB Reverse -> USB -> Windows -> FFplay
640x480 @ ~30 FPS

---

## Checkpoint 27 - Terminal Interface and PC Camera Control

### Added

- New Android interface based on the terminal.css design system
  (dark background, monospace typography, cyan accents, square borders,
  window title bar with status lights).
- Live camera preview inside the interface (Preview use case).
- Terminal-style square toggle for WEBCAM and MICROPHONE.
- Zoom slider now reflects the real CameraX zoom state.
- PC -> Android zoom control over HTTP:
  `GET /control/zoom/:value` and `GET /control/poll`.
- Android control polling every 150 ms while the PC is connected.

### Fixed

- Status badge now updates immediately when the H.264 socket connects
  or reconnects (`broadcastStatus()` after a successful connect).
- Zoom slider thumb no longer clipped at the edges.
- Removed unused legacy drawables and switch color selectors.

### Validated

```text
Device: Xiaomi Redmi Note 11 (2201117TL)
Android: 13 / API 33
SoC: Qualcomm bengal
Resolution: 640x480
Frame rate: ~30 FPS with live preview
Transport: USB / ADB Reverse
```

Zoom command sent from the PC (`/control/zoom/0.5`) was applied to the
running camera (linear zoom 0.5 -> 1.8x) and reflected on the Android
interface without restarting the stream.

---

## Checkpoint 28 - Multi-Device Sync (USB + Wi-Fi)

### Added

- Persistent device identity (UUID + model name) stored in
  SharedPreferences.
- Video handshake sent before the H.264 stream:

  ```text
  LUCAO/1 <deviceId> <deviceName> <usb|wifi>\n
  ```

  The receiver uses it to map each ffplay window to a device.
  Clients from previous checkpoints (raw H.264) still work.
- Windows control panel at `http://<pc>:5050/control`, built with the
  real `terminal.css` (copied to `pc/web/terminal.css`).
- Multiple simultaneous devices: one session and one ffplay window per
  device (`-window_title`). Reconnecting the same device replaces only
  its own session.
- Android -> Windows state sync: `POST /api/status` about once per
  second (device, transport, IP, zoom, zoom range, webcam, FPS, kbps).
- Windows -> Android commands per device: `GET /api/poll?id=<device>`,
  `POST /api/devices/:id/zoom`, `POST /api/devices/:id/webcam`.
- Wi-Fi fallback: the service tries USB first (`127.0.0.1` via
  `adb reverse`) and then the saved `pcHost`. The Android UI has a
  `pc host` field and the companion pushes the LAN IP when opening the
  app.
- `pc/companion/companion.js` now:
  - installs/updates the APK when the local build changes
    (mtime + size recorded in `pc/companion/.state.json`);
  - configures `adb reverse` per serial;
  - handles several devices at once;
  - starts `server.js` and `pc/live-receiver.js` when needed;
  - retries the install after waking the device when MIUI blocks it.

### Fixed

- Handshake detection failed when the first TCP packet carried the
  handshake plus more than 4 KB of video; the size limit now runs after
  protocol detection.
- Receiver re-announces active sessions every 3 seconds, so the panel
  recovers after a `server.js` restart.
- Panel webcam button kept losing its `webcam` class on every refresh.
- FPS/bitrate now drop to zero when the webcam is turned off instead of
  showing the last value forever.
- Zoom values shown by the slider use the real CameraX linear mapping
  (`1 / (1 - linear * (1 - 1 / max))`).

### Validated

```text
Device: Motorola Moto G22 (hawaiip_g)
Android: 12 / API 31
Video: 640x480 @ ~30 FPS
USB: 127.0.0.1:5050 + 127.0.0.1:5051 (adb reverse)
Wi-Fi: 192.168.2.121:5050 + 192.168.2.121:5051
```

- Panel zoom `0.4` -> `1.54x` on the running camera over Wi-Fi.
- WEBCAM OFF -> `fps 0` / `kbps 0`; WEBCAM ON -> ~29.6 FPS.
- Three simultaneous sessions (one real device + two simulated
  clients), each with its own ffplay window.
- Companion installed the new build automatically and restored the USB
  pipeline without manual commands.
