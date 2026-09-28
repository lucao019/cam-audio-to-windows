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

[ ] Dynamic camera capability detection
[ ] Camera/lens selection
[ ] Zoom controls
[ ] Exposure controls
[ ] Image filters and adjustments
[ ] Automatic network reconnection
[ ] Microphone capture
[ ] Microphone sensitivity / gain
[ ] Audio transport
[ ] Windows camera control interface
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
at approximately 30 FPS with live playback.

Network protocols, interfaces and implementation details may continue
to change during development.