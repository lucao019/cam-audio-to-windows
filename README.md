# LUCAO LINK

**Turn an Android phone into a webcam for your Windows PC — over USB or Wi‑Fi,
inside a single window.**

![LUCAO LINK desktop app](docs/screenshot.jpg)

[![Platform](https://img.shields.io/badge/platform-Windows%2010%2F11%20%7C%20Android%208%2B-1f6feb?style=flat-square)](#requirements)
[![Node](https://img.shields.io/badge/node-20%2B-339933?style=flat-square&logo=node.js&logoColor=white)](#requirements)
[![Version](https://img.shields.io/badge/version-1.2.0-50fa7b?style=flat-square)](CHANGELOG.md)
[![License](https://img.shields.io/badge/license-MIT-8be9fd?style=flat-square)](LICENSE)

LUCAO LINK streams the phone camera to an Electron desktop app. Plug the phone
in and the image appears — no `ffplay`, no browser tab, no manual connection.

The same control server feeds the Android panel and the desktop window, so zoom,
webcam state and connection status stay in sync on both sides.

---

## Highlights

- **Plug and play over USB.** The companion installs the bundled APK, creates
  the `adb reverse` tunnels and opens the app on the phone.
- **Wi‑Fi fallback.** Without USB, the phone connects straight to the PC.
- **Embedded low-latency player.** H.264 is remuxed to fragmented MP4 and played
  with Media Source Extensions; the player always seeks to the live edge.
- **Control from the PC.** Zoom (1x–8x), webcam on/off, reconnect and fullscreen.
- **Several phones at once**, each with its own session, HUD and controls.
- **Self-healing.** Watchdogs on both sides recover from a frozen camera, a
  stuck socket or a dropped `adb reverse`.

## Requirements

| Component | Requirement |
|---|---|
| Windows | 10 / 11 x64 |
| Android | 8.0+ (API 26), USB debugging for the USB mode |
| Node.js | 20+ (development and build only) |
| ffmpeg | in `PATH`, or set `FFMPEG_PATH` |
| adb | platform-tools in `PATH` (USB mode) |

## Quick start

### Portable build

1. Get `dist/LUCAO-LINK-1.2.0-portable.exe` (see [building](#building-the-portable)).
2. Make sure `adb` and `ffmpeg` are in `PATH`.
3. Double-click it and plug the phone in.

The first run installs the bundled APK and opens LUCAO LINK on the phone. The
video appears on its own.

### From source

```bash
npm install
npm start        # electron .
```

The same UI also works in a browser at <http://127.0.0.1:5050/app/>.

### Building the portable

```bash
npm run dist     # -> dist/LUCAO-LINK-<version>-portable.exe
```

The Android APK is bundled as a resource, so a fresh machine only needs `adb`
and `ffmpeg`.

### Building the Android app

```bash
cd android
gradlew.bat :app:assembleDebug --offline
# -> android/app/build/outputs/apk/debug/app-debug.apk
```

Rebuild the APK before `npm run dist` if you changed the Android side — the
electron-builder step only packages the existing file.

## How it works

```text
Android · CameraForegroundService
  CameraX 640x480 @ 30 fps  ->  MediaCodec (H.264)  ->  TCP
        │  handshake: LUCAO/1 <deviceId> <name> <usb|wifi>
        ▼
PC · video-bridge  (:5051)  ->  ffmpeg -c:v copy  ->  fragmented MP4
        │
        ▼
PC · HTTP  (:5052)  /info/:id  /stream/:id  /health
        │
        ▼
Electron window  (MSE player)  ←  server.js  (:5050)  API + UI
```

### Ports

| Port | Protocol | Purpose |
|---|---|---|
| 5050 | HTTP | Control API, Android panel and desktop UI |
| 5051 | TCP | Raw H.264 from the phone |
| 5052 | HTTP | Fragmented MP4 for the player |

### USB vs Wi‑Fi

- **USB (preferred).** The companion runs `adb reverse tcp:5050` and
  `tcp:5051`, so the phone talks to `127.0.0.1`. The tunnel is re-checked every
  10 s and restored if it drops.
- **Wi‑Fi.** The phone connects to the PC's LAN address directly. Use the
  `pc host` field on the phone if auto-detection picks the wrong interface.

The bridge accepts both transports and reports which one is in use, so switching
between them is transparent to the player.

## Using the app

| Control | Action |
|---|---|
| Device list (left) | Select the phone to watch |
| ZOOM | 1x–8x, applied to CameraX on the phone |
| WEBCAM | Cameras on/off on the phone |
| RECONECTAR | Drops the session and reconnects |
| TELA CHEIA | Fullscreen player (`Esc` leaves) |
| `F12` / `Ctrl+Shift+I` | DevTools |

### Player states

| State | Meaning |
|---|---|
| `AGUARDANDO VÍDEO` | Phone is online but not streaming |
| `CONECTANDO` | Session open, waiting for the first SPS/PPS |
| `SEM FRAMES` | Session open, no codec after 12 s — open the camera or reconnect |
| `AO VIVO` | Playing at the live edge |

The player also detects a frozen image: 6 s without new data triggers a
reconnect request without tearing down the page.

## Resilience

| Layer | Rule |
|---|---|
| Android | 5 s without writing video → drop the socket and reconnect |
| Android | 3 s without camera frames → rebind CameraX (at most every 10 s) |
| Android | `reconectar` command → drop the socket on demand |
| PC · `main.js` | Every 4 s: session missing or no codec for 15 s → ask for reconnect |
| PC · companion | Every 10 s: restore `adb reverse` if the tunnel disappeared |
| Player | No data for 6 s → request reconnect; keep the current player |

Closing and reopening the desktop app needs no action on the phone: the
companion restores the tunnel and the phone reconnects by itself.

## Troubleshooting

| Symptom | What to do |
|---|---|
| `A porta 5050 já está em uso` | Close another LUCAO LINK or `node server.js` |
| Phone not listed | Enable USB debugging, accept the authorization, check `adb devices` |
| `adb device unauthorized` | Accept the dialog on the phone, or `adb kill-server` and retry |
| Stuck on `CONECTANDO` / `SEM FRAMES` | Open the camera on the phone, or press RECONECTAR |
| `ffmpeg` shows as missing in the footer | Install ffmpeg and put it in `PATH` (or `FFMPEG_PATH`) |
| Video works on USB but not on Wi‑Fi | Fill in the PC address in the `pc host` field |
| Phone disappeared after replugging the cable | Wait ~10 s; the companion restores the tunnels |

Logs are written to `%APPDATA%\LUCAO LINK\lucao-link.log`.

## Project structure

```text
LUCAO-CAM/
├── android/                 Android app (Kotlin, CameraX, MediaCodec)
│   └── app/src/main/java/com/lucao/link/
├── pc/
│   ├── app/                 Electron desktop app
│   │   ├── main.js          window, ffmpeg detection, video watcher, IPC
│   │   ├── video-bridge.js  TCP 5051 -> ffmpeg -> fMP4 on 5052
│   │   └── ui/              terminal-themed UI and MSE player
│   └── companion/           adb: install APK, open app, adb reverse
├── docs/
│   ├── screenshot.jpg
│   └── checkpoints.md       archived milestone notes
├── server.js                control API + Android panel (:5050)
├── package.json             Electron and electron-builder config
├── CHANGELOG.md
└── README.md
```

## Notes

- The SPS produced by CameraX carries a broken VUI (~1,200,000 fps). The bridge
  passes `-r 30` before `-i`, so the fragmented MP4 gets real 30 fps timestamps
  without re-encoding.
- The codec string is read from the `avcC` box; MSE needs the full MIME type
  (`video/mp4; codecs="avc1.64001f"`).
- Ports are fixed. The app holds a single-instance lock and retries for a few
  seconds when the previous process is still shutting down.
- Screen capture is not implemented yet; only the camera is streamed.

## Tested with

```text
Device        Motorola Moto G22 (Android 12 / API 31)
USB           29.5–30 fps · 640x480 · avc1.64001f · ~2 Mbps
Wi-Fi         29.5 fps on the same LAN
Encoder       c2.mtk.avc.encoder
Desktop       Windows 11 · Electron 45 · ffmpeg 9
```

## Roadmap

- [x] CameraX capture, hardware H.264 encoding
- [x] USB transport with `adb reverse`
- [x] Wi‑Fi transport
- [x] Stable ~30 fps, automatic reconnection
- [x] Terminal UI with zoom and webcam control from the PC
- [x] Multi-device sessions
- [x] Desktop app with embedded player and portable build
- [ ] Microphone capture and transport
- [ ] Camera/lens selection (front, ultra-wide, telephoto)
- [ ] Exposure, focus and image adjustments
- [ ] Virtual webcam and virtual microphone for other Windows apps

## Security

LUCAO LINK is meant for trusted local networks. The API has no authentication
or encryption: do not expose ports 5050–5052 to the internet.

Never commit keystores, tokens or credentials. Build artifacts (`dist/`,
`build/`, `*.apk`) and raw captures (`*.h264`) are ignored.

## Documentation

- [CHANGELOG.md](CHANGELOG.md) — what changed in each checkpoint
- [docs/checkpoints.md](docs/checkpoints.md) — full history of earlier milestones

## License

[MIT](LICENSE)
