#!/usr/bin/env python3
"""
StreamCam Desktop Receiver
===========================
Receives the TCP/JPEG stream from the Android app and displays it live.

Requirements:
    pip install opencv-python

Usage:
    python receiver.py [--port 5000]
"""

import argparse
import socket
import struct
import sys
import threading
import time
from typing import Optional

try:
    import cv2
    import numpy as np
except ImportError:
    print("❌  OpenCV not found. Install with:  pip install opencv-python")
    sys.exit(1)


# ──────────────────────────────────────────────────────────────────────────────
# Protocol
# ──────────────────────────────────────────────────────────────────────────────
#   Each frame is sent as:
#       [4 bytes, big-endian uint32: frame_size]  [frame_size bytes: JPEG data]
# ──────────────────────────────────────────────────────────────────────────────

HEADER_SIZE = 4   # struct.pack(">I", size)


def recv_exact(sock: socket.socket, n: int) -> Optional[bytes]:
    """Read exactly n bytes from sock, or return None on disconnect."""
    data = bytearray()
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            return None
        data.extend(chunk)
    return bytes(data)


def receive_stream(host: str, port: int) -> None:
    """Accept one client, display frames until disconnect."""
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((host, port))
    server.listen(1)

    local_ip = socket.gethostbyname(socket.gethostname())
    print(f"✅  Receiver listening on  {local_ip}:{port}")
    print("    Open the Android app, enter this IP, and tap Start Streaming.")
    print("    Press  Q  in the video window to quit.\n")

    while True:
        try:
            client, addr = server.accept()
        except KeyboardInterrupt:
            break

        print(f"📱  Android connected from {addr[0]}:{addr[1]}")
        client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        client.settimeout(10)

        window = "StreamCam — press Q to quit"
        cv2.namedWindow(window, cv2.WINDOW_NORMAL)

        frame_count = 0
        fps_start   = time.time()
        fps_display = 0.0

        try:
            while True:
                # Read 4-byte length header
                header = recv_exact(client, HEADER_SIZE)
                if header is None:
                    print("📴  Client disconnected.")
                    break

                (frame_size,) = struct.unpack(">I", header)

                if frame_size == 0 or frame_size > 10 * 1024 * 1024:
                    print(f"⚠️  Suspicious frame size {frame_size}; skipping.")
                    continue

                # Read JPEG payload
                jpeg_data = recv_exact(client, frame_size)
                if jpeg_data is None:
                    print("📴  Client disconnected mid-frame.")
                    break

                # Decode JPEG → BGR frame
                arr   = np.frombuffer(jpeg_data, dtype=np.uint8)
                frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                if frame is None:
                    continue

                # FPS counter
                frame_count += 1
                elapsed = time.time() - fps_start
                if elapsed >= 1.0:
                    fps_display = frame_count / elapsed
                    frame_count = 0
                    fps_start   = time.time()

                # Overlay FPS
                cv2.putText(frame, f"{fps_display:.1f} fps",
                            (12, 32), cv2.FONT_HERSHEY_SIMPLEX,
                            1.0, (0, 200, 0), 2, cv2.LINE_AA)

                cv2.imshow(window, frame)
                if cv2.waitKey(1) & 0xFF == ord('q'):
                    print("Quit requested.")
                    raise SystemExit

        except (socket.timeout, ConnectionResetError):
            print("📴  Connection lost.")
        finally:
            client.close()
            cv2.destroyAllWindows()
            print("Waiting for next connection…\n")

    server.close()
    cv2.destroyAllWindows()


# ──────────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="StreamCam TCP/JPEG desktop receiver"
    )
    parser.add_argument("--host", default="0.0.0.0",
                        help="Interface to listen on (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=5000,
                        help="TCP port to listen on (default: 5000)")
    args = parser.parse_args()

    try:
        receive_stream(args.host, args.port)
    except KeyboardInterrupt:
        print("\nReceiver stopped.")


if __name__ == "__main__":
    main()
