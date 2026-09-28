# LUCAO LINK

LUCAO LINK is a project for streaming an Android device's **camera and microphone to Windows** over the local network.

The goal is to build a lightweight alternative to using separate applications for webcam and microphone streaming, with independent camera/microphone controls and automatic reconnection.

> Project currently under development.

## Current Status

### Video

The Android video pipeline is working at:

- 640x480
- 30 FPS
- H.264
- 2 Mbps
- Hardware encoding

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
NV12
      |
      v
Qualcomm Hardware H.264 Encoder
OMX.qcom.video.encoder.avc
      |
      v
H.264
```

Continuous encoding has been tested at approximately **30 FPS input and 30 FPS output**.

### Network

Two communication paths are currently being developed:

```text
Port 5050
Android -> HTTP -> Windows
Connection/control prototype

Port 5051
Android -> TCP -> Windows
H.264 video transport
```

The Windows TCP receiver is implemented in:

```text
pc/receiver.js
```

The Android TCP connection is currently being integrated and tested.

### Audio

Microphone streaming is planned but has not been implemented yet.

Planned pipeline:

```text
Android AudioRecord
      |
      v
Audio Stream
      |
      v
Network
      |
      v
Windows Receiver
      |
      v
Virtual Microphone
```

## Planned Features

- Android camera streaming to Windows
- Android microphone streaming to Windows
- H.264 hardware video encoding
- Persistent network connection
- Automatic reconnection after Wi-Fi/network interruptions
- Independent WEBCAM ON/OFF control
- Independent MICROPHONE ON/OFF control
- Windows virtual webcam output
- Windows virtual microphone output
- Low-latency operation

## Project Structure

```text
LUCAO-CAM/
|
|-- android/          Android application
|-- pc/               Windows receiver
|-- shared/           Shared project resources
|-- server.js         HTTP connection/control prototype
|-- package.json
|-- package-lock.json
|-- README.md
|-- CHANGELOG.md
`-- .gitignore
```

## Android

Current Android stack:

- Java
- CameraX
- MediaCodec
- H.264 / AVC
- Qualcomm hardware encoder
- TCP sockets

Current tested configuration:

```text
Resolution: 640x480
Frame rate: 30 FPS
Video codec: H.264 / AVC
Bitrate: 2 Mbps
Encoder: OMX.qcom.video.encoder.avc
```

## Windows

Current prototype stack:

- Node.js
- TCP sockets
- HTTP
- FFmpeg planned for decoding/testing

The Windows side currently listens for the Android device and is being prepared to receive the continuous H.264 stream.

## Development Roadmap

```text
[✓] Android CameraX capture
[✓] 640x480 @ 30 FPS
[✓] YUV_420_888 -> NV12 conversion
[✓] Qualcomm hardware H.264 encoder
[✓] Continuous H.264 encoding at ~30 FPS
[✓] Windows TCP receiver
[ ] Android -> Windows H.264 transport validation
[ ] H.264 decoding on Windows
[ ] Automatic reconnect
[ ] Microphone capture
[ ] Audio transport
[ ] Independent webcam/microphone controls
[ ] Virtual webcam
[ ] Virtual microphone
[ ] Final Windows application
```

## Security

Sensitive files and local development artifacts are excluded from version control.

Examples include:

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

Never commit API keys, tokens, passwords, signing keys, or other credentials.

## Development State

This repository contains an experimental project under active development.

Interfaces, network protocols, architecture, and implementation details may change as development progresses.