# StreamCam Architecture

## Overview

StreamCam is a two-component live video streaming system:

1. **Android App** — Captures camera frames, encodes/compresses them, and streams them over a local network.
2. **Desktop Receiver / RTSP Client** — Receives and displays the live video stream.

Two streaming pipelines are supported:

| Mode           | Transport            | Encoding                            | Receiver                                   |
| -------------- | -------------------- | ----------------------------------- | ------------------------------------------ |
| **TCP/JPEG**   | TCP Socket           | JPEG (CPU)                          | `receiver.py` (OpenCV)                     |
| **RTSP/H.264** | RTSP + RTP (UDP/TCP) | H.264 (MediaCodec Hardware Encoder) | VLC, FFplay, GStreamer, or any RTSP client |

---

# System Architecture

```text
┌──────────────────────────────────────────────────────────────┐
│                         Android App                          │
├──────────────────────────────────────────────────────────────┤
│                        MainActivity                          │
│                                                              │
│  ┌────────────────┐      ┌──────────────────────────────┐    │
│  │  PreviewView   │      │      StreamViewModel         │    │
│  │   (CameraX)    │      │      UI State Machine        │    │
│  └────────┬───────┘      └──────────────┬───────────────┘    │
│           │                             │                    │
│           └──────────────┬──────────────┘                    │
│                          ▼                                   │
│               StreamCameraManager                            │
│        CameraX + Camera2Interop (60 FPS Override)            │
│                          │                                   │
│             ┌────────────┴────────────┐                      │
│             │                         │                      │
│             ▼                         ▼                      │
│      TCP/JPEG Pipeline         RTSP/H.264 Pipeline           │
│             │                         │                      │
│      ┌──────▼───────┐         ┌───────▼────────┐             │
│      │ TcpStreamer  │         │  H264Encoder   │             │
│      │ JPEG → TCP   │         │ MediaCodec GPU │             │
│      └──────┬───────┘         └───────┬────────┘             │
│             │                         │                      │
│      TCP Socket                Annex-B NAL Units             │
│             │                         │                      │
│             │                 ┌───────▼────────┐             │
│             │                 │   RtspServer   │             │
│             │                 │ RTP Packetizer │             │
│             │                 └───────┬────────┘             │
└─────────────┼─────────────────────────┼──────────────────────┘
              │                         │
              ▼                         ▼
      receiver.py                VLC / FFplay / GStreamer
```

---

# Data Flow — TCP/JPEG Mode

```text
Camera Sensor
      │
      │ YUV_420_888 Frames (CameraX ImageAnalysis)
      ▼
StreamCameraManager.processFrameForTcp()
      │
      │ YuvImage.compressToJpeg()
      ▼
JPEG Byte Array
      │
      ▼
TcpStreamer.sendFrame()
      │
      │ Raw JPEG bytes over TCP
      ▼
receiver.py
      │
      │ Buffer incoming bytes
      │ Detect:
      │   SOI = FF D8
      │   EOI = FF D9
      ▼
Extract JPEG Frame
      │
      ▼
cv2.imdecode()
      │
      ▼
cv2.imshow()
```

### Frame Protocol

Unlike traditional framed protocols, the Android application continuously streams raw JPEG bytes without explicit frame headers.

The desktop receiver reconstructs individual frames by detecting the standard JPEG markers:

* **Start of Image (SOI):** `0xFFD8`
* **End of Image (EOI):** `0xFFD9`

This approach keeps the protocol lightweight while allowing the receiver to dynamically recover complete JPEG images from the continuous TCP stream.

---

# Data Flow — RTSP/H.264 Mode

```text
Camera Sensor
      │
      │ CameraX Preview Surface
      │ Camera2Interop → AE Target FPS = 60
      ▼
MediaCodec H.264 Encoder
      │
      │ GPU Surface Input
      │ Zero CPU Copy
      ▼
Annex-B H.264 NAL Units
      │
      ▼
RtspServer.sendNal()
      │
      ▼
RTP Packetizer (RFC 3984)
      │
      ├── Small NAL
      │      │
      │      ▼
      │  Single RTP Packet
      │
      └── Large NAL
             │
             ▼
       FU-A Fragmentation
             │
             ▼
      RTP over UDP / TCP
             │
             ▼
RTSP Client
(VLC / FFplay / GStreamer)

URL:
rtsp://<device-ip>:8554/live
```

---

# RTSP Session Lifecycle

```text
Client                         RTSP Server
  │                                  │
  ├──── OPTIONS ───────────────────► │
  │ ◄────────────── 200 OK ───────── │
  │
  ├──── DESCRIBE ──────────────────► │
  │ ◄──── SDP + 200 OK ───────────── │
  │
  ├──── SETUP ─────────────────────► │
  │ ◄──── 200 OK (Transport) ─────── │
  │
  ├──── PLAY ──────────────────────► │
  │ ◄────────────── 200 OK ───────── │
  │
  │ ===== RTP Video Streaming ======►
  │
  ├──── TEARDOWN ──────────────────► │
  │ ◄────────────── 200 OK ───────── │
```

---

# Threading Model

To maintain stable **1080p60** streaming with minimal latency, the application distributes work across dedicated threads.

| Thread / Pool               | Responsibility                                         |
| --------------------------- | ------------------------------------------------------ |
| **Main (UI)**               | Compose rendering, PreviewView, UI state updates       |
| **cameraExecutor**          | CameraX callbacks, JPEG compression, camera processing |
| **H264 Encoder Thread**     | MediaCodec output buffer draining                      |
| **RTSP Accept Thread**      | `ServerSocket.accept()` loop                           |
| **RTSP Client Threads**     | Per-client RTSP request handling and RTP transmission  |
| **Coroutine IO Dispatcher** | TCP socket connection and lifecycle management         |

---

# Core Optimizations

### Camera2Interop 60 FPS Override

CameraX commonly defaults to **30 FPS**. StreamCam injects a `CONTROL_AE_TARGET_FPS_RANGE` request through Camera2Interop to request **60 FPS** directly from the camera hardware.

---

### Zero-Copy Hardware Encoding

The RTSP pipeline renders camera frames directly into the MediaCodec input surface, eliminating CPU memory copies.

```
Camera GPU Surface
        │
        ▼
MediaCodec Surface
        │
        ▼
Hardware H.264 Encoder
```

This significantly reduces CPU utilization and improves battery efficiency.

---

### Adaptive H.264 Profile Selection

Rather than forcing the encoder to use **AVC Baseline**, StreamCam allows Android to automatically negotiate the highest supported profile and level (typically Level 4.1–4.2), enabling reliable **1080p60** encoding on capable devices.

---

# Limitations

### Thermal Throttling

Sustained 60 FPS hardware encoding produces substantial heat. On many mid-range devices, the operating system may reduce camera or encoder performance to protect the hardware, resulting in lower frame rates.

---

### TCP/JPEG Performance

TCP/JPEG streaming requires CPU-based JPEG compression and reliable TCP delivery. Consequently, it typically achieves **12–15 FPS** on standard Wi-Fi networks and exhibits higher latency compared to the RTSP/H.264 pipeline.

---

### Single RTSP Client

The current RTSP server implementation supports **one active client** at a time. When a second client connects, the RTP packetizer is reassigned, replacing the existing streaming session.

---

# Summary

* **TCP/JPEG Mode**

    * Simple implementation
    * OpenCV desktop receiver
    * CPU JPEG compression
    * Higher latency
    * Approximately 12–15 FPS

* **RTSP/H.264 Mode**

    * Hardware-accelerated MediaCodec encoding
    * Zero-copy GPU pipeline
    * RTP packetization compliant with RFC 3984
    * Compatible with VLC, FFplay, and GStreamer
    * Designed for low-latency 1080p60 streaming over a local network
