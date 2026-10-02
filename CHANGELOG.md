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