# Changelog

All notable development changes to LUCAO LINK will be documented in this file.

The project is currently in active development.

---

## [Unreleased]

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