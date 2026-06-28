# StreamCam Desktop Receiver

This is the desktop counterpart to the StreamCam Android application. It is a high-performance, multi-threaded Python TCP server designed to receive, decode, and render raw JPEG frames with near-zero latency.

## Core Architecture

This application abandons standard single-threaded UI limitations to handle high-throughput 60 FPS video streams. It utilizes a strictly decoupled 3-thread pipeline:

* **Thread 1 (Network I/O):** Dedicated purely to pulling byte streams off the TCP socket, managing packet boundaries, and handling connection state safely.
* **Thread 2 (Media Processing):** Offloads OpenCV decoding (`cv2.imdecode`) and matrix resizing to a background worker to prevent interface locking.
* **Thread 3 (Main UI Loop):** Polls the render queue at 16ms intervals (60 FPS) to push frames to the CustomTkinter canvas, dropping stale frames dynamically if the pipeline backs up.

## Requirements

* Python 3.8+ (Must be added to your system environment `PATH`).
* A Windows environment (for the automated batch launcher).

## Quick Start

You do not need to manually install dependencies or configure the environment.

1. Navigate into the `desktop-receiver` directory.
2. Double-click `Start_Receiver.bat`.
3. The script will automatically verify and install the required packages (`customtkinter`, `opencv-python`, `numpy`, `pillow`, `pyinstaller`).
4. Select **Option 1** from the terminal menu to launch the receiver instantly from the source code.

## Building a Standalone Executable

If you need to deploy this to a machine without a Python environment, the batch script handles compilation.

1. Run `Start_Receiver.bat`.
2. Select **Option 2**. 
3. PyInstaller will bundle the application and generate a standalone application folder. You will find the final executable inside `dist/receiver/`.

## Application Features

* **Auto-IP Detection:** Bypasses manual `ipconfig` checks by polling the local network interface and displaying the exact bind IP required for the Android client.
* **Live Telemetry:** Tracks dynamic bandwidth utilization, total session data, and renders a live FPS tick rate.
* **DVR Capabilities:** Built-in tools to capture high-quality `.jpg` snapshots and record live `.avi` video streams directly to the local disk.

## Troubleshooting

* **"Bind Error" on Startup:** Port 5000 is already in use by another application. Change the port in the UI before clicking Start.
* **Connected, but Camera is Offline:** Your Windows Defender Firewall is silently blocking inbound TCP traffic on port 5000. Allow the Python executable through the firewall.
* **Media Module Error:** If the UI opens but flashes a red media error, verify that `opencv-python` installed correctly.