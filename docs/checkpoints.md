# Historical README (archive)

> Snapshot of the README before the Checkpoint 29 rewrite, kept for reference.
> Current documentation: [../README.md](../README.md).

---

# LUCAO LINK

LUCAO LINK is an Android + Windows project for streaming a phone's
**camera and microphone to a Windows PC over the local network**.

The goal is to provide a lightweight alternative to using separate
applications for webcam and microphone streaming.

The final application is intended to provide:

- Low-latency Android camera streaming
- Android microphone streaming
- Independent webcam and microphone controls
- Automatic reconnection after network interruptions
- Camera controls from Android and Windows
- Virtual webcam output on Windows
- Virtual microphone output on Windows

> Project currently under active development.

---

## Current Status

### Video - LIVE

The Android -> Windows video pipeline is now working live.

Current tested configuration:

```text
Resolution: 640x480
Frame rate: 30 FPS
Codec: H.264 / AVC
Bitrate: 2 Mbps
Encoder: Qualcomm hardware encoder
Transport: TCP / Wi-Fi
```

Measured performance:

```text
Input:  29.9 - 30.1 FPS
Output: 29.9 - 30.1 FPS
```

Current pipeline:

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
MediaCodec
Qualcomm Hardware H.264 Encoder
      |
      v
H.264
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

The complete Android -> Windows H.264 path has been validated.

The received H.264 stream was also validated with FFprobe:

```text
codec_name=h264
width=640
height=480
```

---

## Network

LUCAO LINK currently uses two ports during development.

```text
Port 5050
Android -> HTTP -> Windows
Connection/control server

Port 5051
Android -> TCP -> Windows
Live H.264 video transport
```

Windows receivers:

```text
pc/receiver.js
```

Receives raw H.264 and can save the stream for testing.

```text
pc/live-receiver.js
```

Receives the H.264 stream and sends it directly to FFplay for
live low-latency video testing.

---

## Video Performance Optimization

During live streaming, the pipeline initially dropped from approximately
30 FPS to approximately 25.5 FPS.

Investigation showed that the main bottleneck was the
YUV_420_888 -> NV12 conversion.

The Y plane was originally copied pixel by pixel.

At 640x480 this required approximately:

```text
307,200 individual Y-plane reads per frame
```

The converter was optimized to copy complete Y rows when:

```text
yPixelStride == 1
```

After this optimization, performance returned to:

```text
29.9 - 30.1 FPS input
29.9 - 30.1 FPS output
```

A fallback path remains available for devices with different image layouts.

---

## Android Interface

The Android application currently includes:

```text
LUCAO LINK
Android -> Windows

Camera preview

WEBCAM
ON / OFF

MICROPHONE
ON / OFF
(currently disabled until audio is implemented)

CONNECT TO PC
```

The camera connection remains designed around independent webcam and
microphone controls.

---

## Audio

Microphone transport has not been implemented yet.

Planned architecture:

```text
Android Microphone
      |
      v
AudioRecord
      |
      v
Audio Processing
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

Planned microphone controls include:

```text
Microphone ON / OFF
Input sensitivity
Digital gain
Input level meter
Noise reduction
Automatic gain control when supported
Echo cancellation when supported
```

---

## Planned Camera Controls

LUCAO LINK will detect the capabilities of each Android device instead
of assuming that every phone has the same cameras.

Planned controls include:

```text
Camera selection
Front / rear camera
Ultra-wide camera when available
Telephoto camera when available
Zoom
Exposure / brightness
Focus
Flash / torch

Image processing:
Brightness
Contrast
Saturation
Sharpness
Color temperature
Color filters
```

For example, a `0.5x` option should only be displayed when the device
actually exposes an ultra-wide camera.

Camera capabilities will be detected dynamically so the application can
adapt to different Android devices.

Controls are planned to be available from both:

```text
Android application
Windows application
```

---

## Automatic Reconnection

Automatic reconnection is a core planned feature.

The final behavior should be:

```text
Wi-Fi connection lost
        |
        v
LUCAO LINK detects disconnect
        |
        v
RECONNECTING
        |
        v
PC becomes available
        |
        v
TCP reconnect
        |
        v
SPS / PPS + new keyframe
        |
        v
Video resumes automatically
```

The user should not need to manually press CONNECT again after a
temporary network interruption.

---

## Windows

Current Windows development stack:

```text
Node.js
TCP sockets
HTTP
FFmpeg / FFplay
```

Current live receiver:

```text
pc/live-receiver.js
```

Future Windows components:

```text
LUCAO LINK Windows application
Camera controls
Microphone controls
Virtual webcam
Virtual microphone
Automatic reconnect management
```

---

## Project Structure

```text
LUCAO-CAM/
|
|-- android/              Android application
|-- pc/
|   |-- receiver.js       H.264 capture receiver
|   `-- live-receiver.js  Live H.264 -> FFplay receiver
|
|-- shared/               Shared project resources
|-- server.js             HTTP connection/control server
|-- package.json
|-- package-lock.json
|-- README.md
|-- CHANGELOG.md
`-- .gitignore
```

---

## Dedicated Webcam Mode

LUCAO LINK is also planned to support Android phones used exclusively
as dedicated webcam devices.

The goal is to allow an old or damaged-screen Android phone to operate
without requiring normal interaction with the phone display.

Planned architecture:

```text
Dedicated Android Phone
        |
        | USB or Wi-Fi
        v
LUCAO LINK Windows
        |
        +-- Automatic device detection
        +-- Camera controls from the PC
        +-- Automatic connection
        +-- Automatic reconnection
        +-- Headless / minimal phone interaction
        |
        v
Virtual Webcam
        |
        +-- OBS
        +-- Discord
        +-- Browser
        `-- Other Windows applications

## Development Roadmap

```text
[x] Android CameraX capture
[x] 640x480 video
[x] 30 FPS target
[x] YUV_420_888 -> NV12 conversion
[x] Optimized Y-plane conversion
[x] Qualcomm hardware H.264 encoder
[x] H.264 SPS/PPS handling
[x] Windows TCP receiver
[x] Android -> Windows H.264 transport
[x] H.264 validation with FFprobe
[x] Live H.264 playback with FFplay
[x] Stable ~30 FPS live video
[x] Independent webcam switch UI

[x] Dynamic camera capability detection
[ ] Camera/lens selection
[x] Zoom controls
[x] Low-latency H.264 playback timing
[ ] Exposure controls
[ ] Image filters and adjustments
[x] Automatic network reconnection
[x] PC camera zoom control (HTTP)
[x] Terminal-style interface
[x] Multi-device (USB + Wi-Fi)
[x] Windows control panel (terminal.css)
[ ] Microphone capture
[ ] Microphone sensitivity / gain
[ ] Audio transport
[x] Windows camera control interface
[ ] Windows microphone control interface
[ ] Virtual webcam
[ ] Virtual microphone
[ ] Final Windows application
```

---

## Security

Sensitive files and local development artifacts are excluded from
version control.

Examples:

```text
.env
*.key
*.jks
*.keystore
local.properties
node_modules/
build/
*.apk
*.bak
*.h264
```

Raw H.264 captures generated during development are intentionally ignored
and must not be committed to the repository.

Never commit API keys, tokens, passwords, signing keys, or credentials.

---

## Development State

LUCAO LINK is an experimental project under active development.

The current milestone proves that an Android device can capture,
hardware-encode and transmit H.264 video over Wi-Fi to a Windows PC
at approximately 30 FPS with low-latency live playback.

Dynamic camera capability detection and CameraX zoom control are
implemented. Zoom changes are preserved through the complete
CameraX -> NV12 -> H.264 -> TCP -> Windows -> FFplay pipeline.

Network protocols, interfaces and implementation details may continue
to change during development.

## Checkpoint 25 — Stable Dedicated USB Pipeline

The dedicated USB architecture has now been validated on a Motorola Moto G22.

Current tested configuration:

```text
Device: Motorola Moto G22
Android: 12 / API 31
Resolution: 640x480
Frame rate: 30 FPS
Codec: H.264 / AVC
Bitrate: ~2 Mbps
Encoder: c2.mtk.avc.encoder
Transport: USB / ADB Reverse
Video port: 5051
```

### Current Pipeline

```text
Moto G22
    |
    v
CameraForegroundService
    |
    v
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
MediaCodec H.264
c2.mtk.avc.encoder
    |
    v
TCP 127.0.0.1:5051
    |
    v
ADB Reverse
    |
    v
USB
    |
    v
Windows :5051
    |
    v
Node.js Live Receiver
    |
    v
FFplay
```

### Foreground Camera Service

The video pipeline is now owned by `CameraForegroundService`.

This separates camera capture and H.264 streaming from `MainActivity`
and prepares LUCAO LINK for dedicated webcam operation with minimal
interaction with the Android device.

A previous duplicate pipeline condition was fixed. `MainActivity` no
longer starts a second TCP connection or a second H.264 encoder.

### Stable 30 FPS Capture

Camera2 capability detection on the Moto G22 confirmed support for:

```text
[10,10]
[15,15]
[15,20]
[20,20]
[5,30]
[30,30]
```

CameraX now explicitly requests:

```text
[30,30]
```

Before this change, the service pipeline operated at approximately
14 FPS.

After explicitly requesting the supported 30 FPS range:

```text
Input:  29.5 - 29.8 FPS
Output: 29.5 - 29.8 FPS
```

The H.264 encoder is therefore keeping pace with the frames delivered
by CameraX.

### USB Transport Validation

The final validated transport is:

```text
Android
127.0.0.1:5051
    |
    v
ADB Reverse
    |
    v
USB
    |
    v
Windows :5051
```

H.264 initialization was successfully transmitted:

```text
SPS: 23 bytes
PPS: 8 bytes
```

No `Broken pipe` errors were observed during the final validation.

Current dedicated USB video milestone:

```text
640x480
~30 FPS
H.264
~2 Mbps
USB / ADB Reverse
STABLE
```

---

## Checkpoint 24 — Dedicated USB Mode

O LUCAO LINK agora possui uma implementação experimental de modo dedicado via USB, testada em um Motorola Moto G22 com Android 12.

### Hardware testado

- Motorola Moto G22
- Android 12 / API 31
- MediaTek MT6765
- Conexão USB com ADB
- CameraX em 640x480 @ 30 FPS
- H.264 em aproximadamente 2 Mbps

### Transporte USB

O Android utiliza localhost:

- HTTP: `127.0.0.1:5050`
- Vídeo H.264: `127.0.0.1:5051`

O ADB Reverse cria os túneis:

```text
Android 127.0.0.1:5050
        ↓ USB / ADB Reverse
Windows :5050

Android 127.0.0.1:5051
        ↓ USB / ADB Reverse
Windows :5051
```

---


## Checkpoint 26 - Automatic USB Video Reconnection

Automatic recovery of the dedicated USB video pipeline was implemented and validated.

### Android

- Detects H.264 TCP connection loss automatically.
- Retries the video connection while the service remains active.
- Stores H.264 SPS/PPS for reconnection.
- Resends SPS/PPS after the TCP connection returns.
- Requests a new H.264 keyframe after reconnection.
- Uses a dedicated single-thread executor for ordered H.264 writes.

### Windows Receiver

- FFplay output is now visible for the dedicated video receiver.
- FFplay runs with low-latency H.264 options.
- Video playback was validated with near-instant perceived latency.

### Windows Companion

- The Companion now owns the video receiver process.
- Removed TCP probing of port 5051 that was creating false Android connections.
- Tracks the receiver process directly.
- Recreates ADB Reverse mappings after USB reconnection.
- Restarts the Android connection flow automatically.

### Physical USB Reconnection Test

Validated sequence:

```text
VIDEO RUNNING
      |
      v
USB DISCONNECTED
      |
      v
ADB DEVICE DISAPPEARS
      |
      v
USB RECONNECTED
      |
      v
COMPANION DETECTS DEVICE
      |
      v
ADB REVERSE RESTORED
      |
      v
ANDROID TCP RECONNECTS
      |
      v
SPS/PPS + KEYFRAME
      |
      v
VIDEO RETURNS AUTOMATICALLY
```

---

## Checkpoint 27 — Terminal Interface and PC Camera Control

The Android interface was rebuilt following the `terminal.css` design
system, and camera zoom can now be controlled from the PC.

### Interface

```text
Background        #050505
Surface           #000000
Primary accent    #8BE9FD (cyan)
Success / danger  #50FA7B / #FF5555
Borders           #333333
Typography        monospace, uppercase labels, letter spacing
```

The screen is a single terminal window:

```text
[ o o o ]  lucao@link:~$                    STREAM ATIVO
   LUCAO LINK
   android -> windows webcam

   > PC                                    ONLINE
   > PREVIEW        (live camera preview)
   > WEBCAM                                 [x]
   > ZOOM                                    1.0x
   > MICROFONE                     [ em desenvolvimento ]
   [ CONECTAR AO PC ]
   5050/http - 5051/h264 - usb
   lucao@link:~$ _
```

The pipeline still belongs exclusively to `CameraForegroundService`;
`MainActivity` only supplies the `PreviewView` surface.

### PC -> Android control

```text
GET  /control/zoom/:value     PC asks for a zoom change (0..1)
GET  /control/poll            Android polls pending commands (150 ms)
```

The service converts the value with `CameraControl.setLinearZoom()` and
broadcasts the real `zoomRatio` back to the interface.

### Validated

```text
Device: Xiaomi Redmi Note 11 (2201117TL)
Android: 13 / API 33
Video: 640x480 @ ~30 FPS with live preview
Zoom: PC 0.5 linear -> 1.8x on the running camera
```

---

## Checkpoint 28 — Multi-Device Sync (USB + Wi-Fi)

Several phones can now send video to the same Windows machine while the
panel controls each one independently.

### Architecture

```text
Android app (camera -> H.264)
   |  handshake: LUCAO/1 <id> <name> <usb|wifi>\n
   v
pc/live-receiver.js :5051  ->  one ffplay per device
   |  POST /api/video
   v
server.js :5050  ->  panel http://<pc>:5050/control
   ^  GET /api/poll?id=<id>  (commands)
   |  POST /api/status       (state)
Android app
```

### Running

```text
cd pc/companion
node companion.js
```

The companion starts `server.js` and `live-receiver.js`, configures
`adb reverse` for every connected device, installs/updates the APK when
the local build changes, and opens the app with the LAN IP already
filled in (`--es pcHost <ip>`).

### Panel

Open `http://localhost:5050/control` (or `http://<lan-ip>:5050/control`
from another machine). Each card shows transport, video state, FPS,
bitrate and offers the zoom slider and the WEBCAM button.

### Connection priority

```text
1. USB / adb reverse -> 127.0.0.1:5050 + 127.0.0.1:5051
2. Wi-Fi             -> <pcHost>:5050 + <pcHost>:5051
```

If USB is not routed, the app automatically falls back to the saved
`pc host` (the field is editable in the app).

### Validated

```text
Device: Motorola Moto G22
Android: 12 / API 31
USB: 29.6-30 FPS
Wi-Fi: 29.6 FPS (PC 192.168.2.121, phone 192.168.2.158)
Panel zoom: 0.4 -> 1.54x
Simultaneous sessions: 3 (1 real + 2 simulated)
```

## Checkpoint 29 — Desktop App (one window, no ffplay)

The PC side is now a real Windows app: open it, plug the phone (USB) or
let it connect over Wi-Fi, and the camera appears inside the window.

### Architecture

```text
Android app (camera -> H.264 Annex B)
   |  handshake: LUCAO/1 <id> <name> <usb|wifi>\n
   v
video-bridge.js tcp :5051   -> one ffmpeg per device (-c:v copy)
   |  fMP4 (frag_keyframe + empty_moov)
   v
http :5052 /info/:id, /stream/:id
   |  MSE
   v
Electron window -> http://127.0.0.1:5050/app/
   ^
server.js :5050 (API + panel + same UI in a browser)
```

### Running

```text
npm install
npm start          # electron .
```

Requirements:

```text
- Node.js 20+
- ffmpeg in PATH (or FFMPEG_PATH); without it the app warns and keeps the socket open
- adb in PATH for USB (adb reverse 5050/5051)
```

### Window

- Frameless terminal look, custom titlebar with the three dots.
- Device list on the left, video on the right, zoom/webcam/reconnect/fullscreen
  controls below.
- The video starts by itself (MSE autoplay policy is relaxed for the app).
- `F12` / `Ctrl+Shift+I` opens DevTools.

### Reconnection layers

```text
Android: 5s without writing video -> drop socket and reconnect
Android: 3s without camera frames   -> rebind CameraX (max every 10s)
Android: "reconectar" command       -> drop socket on demand
PC:      vigiarVideo every 4s       -> asks /reconectar when the session is
                                       missing or has no codec for 15s
PC:      companion every 10s        -> restores adb reverse if the tunnel drops
UI:      no data for 6s             -> asks /reconectar without killing the player
UI:      buffer starting mid-session -> seeks to the live edge
```

### Packaging

```text
npm run dist       # electron-builder --win portable
```

The portable exe is written to `dist/` with the APK bundled as a resource
(`extraResources`), so a fresh machine only needs adb + ffmpeg installed.

### Notes

- The Android SPS from CameraX carries a broken VUI (~1.200.000 fps). The
  bridge passes `-r 30` before `-i` so the fMP4 gets 30 fps timestamps
  without re-encoding.
- The codec is read from the `avcC` box (profile/compat/level) and the MSE
  gets the full MIME (`video/mp4; codecs="avc1.64001f"`).
- Bridge ports are fixed: 5050 control/UI, 5051 H.264, 5052 fMP4.

### Validated

```text
Device: Motorola Moto G22 (Android 12 / API 31)
USB: 29.5-30 FPS, avc1.64001f, 640x480, ~2.6 Mbps
Wi-Fi fallback: USB tunnel removed -> reconnects at 192.168.2.121
Reconnect button: new session (generation +1) and playback resumes
Zoom: PC slider 1.44x -> Android applied 1.441
Portable: dist/LUCAO-LINK-1.2.0-portable.exe (116 MB), APK bundled
First run of the portable: installs the bundled APK, opens the Android app
and the video appears on its own
Close and reopen the app: the camera comes back without touching the phone
Tunnel removed while running: companion restores adb reverse within 10s
UI without codec for 12s: shows "SEM FRAMES" instead of "CONECTANDO"
```

