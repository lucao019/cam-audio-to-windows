# Changelog

All notable development changes to LUCAO LINK will be documented in this file.

The project is currently in active development.

---

## [Unreleased]

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