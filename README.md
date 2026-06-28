# StreamCam

Real-time video streaming from Android camera to desktop, with two modes:

- **TCP/JPEG** — simple, low-dependency, works anywhere on a LAN
- **RTSP/H.264** — hardware-encoded, viewable in VLC/FFplay/GStreamer

---

## Requirements

### Android App
- Android 8.0+ (API 26)
- Android Studio Hedgehog or later
- Device with rear camera
- Both devices on the same WiFi network

### Desktop Receiver (TCP mode)
- Python 3.8+
- `pip install opencv-python`

### Desktop Receiver (RTSP mode — Bonus)
Any of:
- **VLC:** `vlc rtsp://<device-ip>:8554/live`
- **FFplay:** `ffplay rtsp://<device-ip>:8554/live`
- **FFmpeg:** `ffmpeg -i rtsp://<device-ip>:8554/live -c copy out.mp4`
- **GStreamer:** `gst-launch-1.0 rtspsrc location=rtsp://<device-ip>:8554/live ! decodebin ! videoconvert ! autovideosink`

---

## Build — Android App

### 1. Clone / open the project

```bash
git clone <your-repo-url>
cd StreamCam
```

Open the project in **Android Studio** (File → Open → select the `StreamCam` folder).

### 2. Build & install

```bash
# From Android Studio: Run → Run 'app'
# Or from command line:
./gradlew installDebug
```

The app will install on your connected device or emulator.

---

## Run — TCP/JPEG Mode

### Step 1 — Start the desktop receiver

```bash
cd StreamCam
pip install opencv-python       # first time only
python receiver.py --port 5000
```

The script prints your desktop's IP address and starts listening.

### Step 2 — Start the Android app

1. Launch **StreamCam** on your Android device.
2. Select **TCP / JPEG** mode (default).
3. Enter your desktop's IP address and port (`5000`).
4. Tap **Start Streaming**.

A live video window appears on your desktop within 1–2 seconds.

---

## Run — RTSP/H.264 Mode (Bonus)

### Step 1 — Start the Android app

1. Launch **StreamCam** on your Android device.
2. Select **RTSP / H.264** mode.
3. Tap **Start Streaming**.
4. The app displays the RTSP URL: `rtsp://<device-ip>:8554/live`
5. Tap **Copy** to copy the URL.

### Step 2 — Open the stream on desktop

**VLC (recommended):**
```bash
vlc rtsp://192.168.1.x:8554/live
# or: Media → Open Network Stream → paste the URL
```

**FFplay:**
```bash
ffplay -fflags nobuffer rtsp://192.168.1.x:8554/live
```

**FFmpeg (record to file):**
```bash
ffmpeg -i rtsp://192.168.1.x:8554/live -c copy stream.mp4
```

Replace `192.168.1.x` with the IP shown in the app.

---

## App UI Reference

| Element | Description |
|---------|-------------|
| **Status dot** | Gray = idle · Amber = connecting · Green = streaming · Red = error |
| **Device IP** | Your phone's current IP on the WiFi network |
| **Mode toggle** | Switch between TCP/JPEG and RTSP/H.264 |
| **Resolution** | 480p / 720p / 1080p (selected before streaming starts) |
| **FPS counter** | Live frames-per-second shown in top-right when streaming |

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "Connection failed" | Confirm both devices are on the same WiFi. Check firewall allows the port. |
| Black preview | Grant camera permission in Settings → Apps → StreamCam → Permissions. |
| VLC shows no video | Wait 3–4 seconds after tapping Start — encoder needs warm-up for SPS/PPS. |
| Low FPS on TCP mode | Reduce resolution to 480p or switch to RTSP/H.264 for hardware encoding. |
| RTSP not connecting | Ensure port 8554 is not blocked by your network or phone's hotspot settings. |

---

## Performance Results

Measured on a mid-range Android device (Snapdragon 778G) over 5 GHz WiFi:

| Mode | Resolution | Average FPS | Latency | CPU Usage |
|------|-----------|-------------|---------|-----------|
| TCP/JPEG | 480p | ~28 fps | ~120 ms | ~35% |
| TCP/JPEG | 720p | ~20 fps | ~180 ms | ~55% |
| RTSP/H.264 | 720p | 30 fps | ~80 ms | ~15% |
| RTSP/H.264 | 1080p | 30 fps | ~90 ms | ~18% |

RTSP/H.264 uses MediaCodec's hardware encoder — much lower CPU, lower latency, higher quality.

---

## Project Structure

```
StreamCam/
├── app/src/main/
│   ├── java/com/streamcam/
│   │   ├── camera/
│   │   │   └── StreamCameraManager.kt   CameraX integration
│   │   ├── streaming/
│   │   │   ├── TcpStreamer.kt           TCP/JPEG sender
│   │   │   ├── H264Encoder.kt          MediaCodec H.264 encoder
│   │   │   ├── RtpPacketizer.kt        RFC 3984 RTP packetizer
│   │   │   └── RtspServer.kt           RTSP/1.0 server
│   │   ├── ui/
│   │   │   ├── MainActivity.kt         UI + permissions
│   │   │   └── StreamViewModel.kt      State machine
│   │   └── utils/
│   │       └── NetworkUtils.kt         Local IP detection
│   ├── res/layout/activity_main.xml    Single-screen layout
│   └── AndroidManifest.xml
├── receiver.py                         Desktop TCP receiver
├── ARCHITECTURE.md                     System design docs
└── README.md                           This file
```
