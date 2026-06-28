# StreamCam

Real-time, ultra-low-latency video streaming from an Android camera to a desktop machine, featuring an optimized multi-threaded processing architecture and dual transport protocols.

<div align="center">

<img src="https://github.com/user-attachments/assets/63d5a55a-ceca-46a9-bb05-377030100c57" width="150">

</div>

# App Interface

<p align="center">
  <img src="https://github.com/user-attachments/assets/34b043f4-7463-4690-b4d9-750109d64d01" width="23%" />
  <img src="https://github.com/user-attachments/assets/51fe2808-07ad-4b5d-99d6-ebd8ee87561e" width="23%" />
  <img src="https://github.com/user-attachments/assets/c28174ea-f1bf-4f01-aa94-60ce9d30e07e" width="23%" />
  <img src="https://github.com/user-attachments/assets/1a0820eb-66a1-4c64-bf76-777e0ab98355" width="23%" />
</p>

---
# 🎥 Demo Video

<p align="center">
  <a href="https://drive.google.com/file/d/1WSrp0WbR6oczmZ886-D5AUNLE6jLw76z/view?usp=drive_link">
    <img src="https://img.shields.io/badge/▶️%20Watch%20Demo-Google%20Drive-4285F4?style=for-the-badge&logo=google-drive&logoColor=white" alt="Watch Demo">
  </a>
</p>

The demo video demonstrates:

- 📱 Android application interface  
- 📡 TCP/JPEG streaming  
- 🎥 RTSP/H.264 streaming  
- 🖥️ Desktop receiver  
- ⚡ Real-time streaming performance  
- 🔄 Switching between streaming modes  
---

## App UI Reference

| Element         | Description                                                                    |
| --------------- | ------------------------------------------------------------------------------ |
| **Status Dot**  | 🔘 Gray = Idle • 🟠 Amber = Connecting • 🟢 Green = Streaming • 🔴 Red = Error |
| **Device IP**   | Displays your phone's current IP address on the connected Wi-Fi network.       |
| **Mode Toggle** | Switch between **TCP/JPEG** and **RTSP/H.264** streaming modes.                |
| **Resolution**  | Select **480p**, **720p**, or **1080p** before starting a stream.              |
| **FPS Counter** | Displays the live frames-per-second in the top-right corner while streaming.   |

---

# Troubleshooting

| Problem                    | Solution                                                                                                               |
| -------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| **Connection failed**      | Ensure both devices are connected to the same Wi-Fi network and verify that your firewall allows the selected port.    |
| **Black camera preview**   | Grant camera permission via **Settings → Apps → StreamCam → Permissions**.                                             |
| **VLC shows no video**     | Wait **3–4 seconds** after tapping **Start Streaming**. The encoder needs time to generate the initial SPS/PPS frames. |
| **Low FPS in TCP mode**    | Lower the resolution to **480p** or switch to **RTSP/H.264** to use hardware encoding.                                 |
| **RTSP connection failed** | Confirm that **port 8554** is not blocked by your firewall, router, or mobile hotspot settings.                        |

# Transport Protocols

## TCP/JPEG

* Raw JPEG frame streaming over a local network.
* Optimized three-thread desktop receiver:

  * Network I/O Thread
  * Parallel Media Processing Thread
  * 60 FPS Rendering Thread
* Designed for minimal latency without frame accumulation.

## RTSP/H.264

* Hardware-accelerated H.264 encoding using `MediaCodec`.
* Zero-copy GPU encoding pipeline.
* Camera2Interop frame-rate overrides.
* Stable native 60 FPS streaming.

---

# Requirements

## Android

* Android 8.0 (API 26)+
* Android Studio Jellyfish / Ladybug or newer
* Device supporting high frame-rate capture
* Both devices connected to the same LAN
* 5 GHz Wi-Fi recommended

## Desktop

* Python 3.8+
* Python added to system PATH
* Windows (for the batch launcher)

---

# Build the Android App

## 1. Clone the Repository

```bash
git clone https://github.com/akshit-singhh/StreamCam.git
cd StreamCam
```

## 2. Open in Android Studio

Open the project root directory inside Android Studio.

## 3. Build & Install

### Android Studio

```
Run → Run 'app'
```

### Terminal

```bash
./gradlew installDebug
```

---

# TCP/JPEG Streaming

## Step 1 — Launch the Desktop Receiver

Navigate to:

```text
desktop-receiver/
```

Run:

```text
Start_Receiver.bat
```

The launcher automatically installs the required packages if they are missing:

* customtkinter
* opencv-python
* numpy
* pillow
* pyinstaller

After dependency verification you'll see:

```
1 → Run Python receiver

2 → Build standalone executable
```

The receiver automatically:

* Detects the desktop LAN IP
* Opens port **5000**
* Waits for incoming connections

---

## Step 2 — Start Android Streaming

1. Launch **StreamCam**
2. Select **TCP/JPEG**
3. Enter the desktop IP address
4. Press **Start Streaming**

The desktop receiver immediately begins displaying the live camera feed.

---

# RTSP/H.264 Streaming

## Step 1 — Start the RTSP Server

1. Launch StreamCam
2. Select **RTSP/H.264**
3. Press **Start Streaming**

The application displays:

```text
rtsp://<device-ip>:8554/live
```

---

## VLC Media Player

```bash
vlc --network-caching=100 rtsp://192.168.1.x:8554/live
```

Or:

```
Media
    └── Open Network Stream
```

Recommended cache:

```
100 ms
```

---

## FFplay

```bash
ffplay -fflags nobuffer -flags low_delay -framedrop rtsp://192.168.1.x:8554/live
```

---

## FFmpeg

```bash
ffmpeg -rtsp_transport tcp \
-i rtsp://192.168.1.x:8554/live \
-c copy recorded_stream.mp4
```
---
# Performance Results

Measured on a **mid-range Android device (Snapdragon 778G)** over a **5 GHz Wi-Fi** connection.

| Mode       | Resolution | Average FPS | Latency | CPU Usage |
| ---------- | ---------- | ----------: | ------: | --------: |
| TCP/JPEG   | 480p       |     ~28 FPS | ~120 ms |      ~35% |
| TCP/JPEG   | 720p       |     ~20 FPS | ~180 ms |      ~55% |
| RTSP/H.264 | 720p       |      30 FPS |  ~80 ms |      ~15% |
| RTSP/H.264 | 1080p      |      30 FPS |  ~90 ms |      ~18% |

> **Note**
>
> **RTSP/H.264** leverages Android's **MediaCodec** hardware encoder, providing significantly lower CPU utilization, reduced latency, and improved video quality compared to the TCP/JPEG pipeline. Hardware encoding also enables more stable frame rates and better power efficiency during extended streaming sessions.
---

# Project Structure

```text
StreamCam/
├── app/
│   └── src/main/
│       ├── java/com/streamcam/
│       │   ├── camera/
│       │   │   └── StreamCameraManager.kt
│       │   ├── streaming/
│       │   │   ├── TcpStreamer.kt
│       │   │   ├── H264Encoder.kt
│       │   │   ├── RtpPacketizer.kt
│       │   │   └── RtspServer.kt
│       │   ├── ui/
│       │   │   ├── MainActivity.kt
│       │   │   └── StreamViewModel.kt
│       │   └── utils/
│       │       └── NetworkUtils.kt
│       ├── res/
│       │   └── layout/
│       │       └── activity_main.xml
│       └── AndroidManifest.xml
│
├── desktop-receiver/
│   ├── receiver.py
│   ├── requirements.txt
│   └── Start_Receiver.bat
│
├── ARCHITECTURE.md
└── README.md
```

---

# Architecture

## Android

* CameraX capture pipeline
* Camera2Interop frame-rate overrides
* MediaCodec hardware encoder
* RTP packetizer
* Embedded RTSP server
* TCP JPEG streamer

## Desktop

* Dedicated socket thread
* Parallel JPEG decoding
* OpenCV rendering pipeline
* Independent 60 FPS rendering loop
* Zero frame buffering

---

<div align="center">

# StreamCam

Ultra-Low-Latency Android Camera Streaming

## Developer

**Akshit Singh**

GitHub: [akshit-singhh](https://github.com/akshit-singhh)  
Email: akshitsingh658@gmail.com  
LinkedIn: https://linkedin.com/in/akshit-singhh  

</div>
