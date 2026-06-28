import socket
import struct
import threading
import time
import os
import sys
import importlib
import customtkinter as ctk
from datetime import datetime

# Static imports so PyInstaller bundles them correctly
import cv2
import numpy as np
from PIL import Image

# Modern aesthetic initialization
ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("blue")

HEADER_SIZE = 4

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(0.3)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        try:
            chunk = sock.recv(n - len(buf))
        except Exception:
            return None
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)

class StreamCamReceiver(ctk.CTk):
    def __init__(self):
        super().__init__()
        self.title("StreamCam — Receiver")
        self.geometry("1280x760")
        self.minsize(1000, 600)
        
        # Deep dark modern background
        self.configure(fg_color="#09090B") 

        # State
        self.server_socket      = None
        self.client_socket      = None
        self.is_running         = False
        self.is_paused          = False
        self.receive_thread     = None
        self.local_ip           = "Detecting..."
        self.current_port       = 5000 

        # Telemetry
        self.frame_count        = 0
        self.bytes_received     = 0
        self.last_stat_time     = time.time()
        self.peak_fps           = 0
        self.total_frames       = 0
        self.session_start      = None
        self._total_bytes       = 0

        # Recording
        self.is_recording       = False
        self.video_writer       = None
        self.record_start       = None
        self.record_frame_count = 0

        # Rendering
        self.last_frame         = None 
        self._current_video_size = (960, 580)
        self.snapshot_dir       = os.path.join(os.path.expanduser("~"), "StreamCam_Snapshots")
        os.makedirs(self.snapshot_dir, exist_ok=True)
        self._session_id        = 0

        self._build_ui()
        self.after(10, self._resolve_local_ip_async)
        self._track_video_size()
        self.protocol("WM_DELETE_WINDOW", self.on_closing)

    # ── UI ────────────────────────────────────────────────────────────────────

    def _resolve_local_ip_async(self):
        def _worker():
            ip = get_local_ip()
            self.after(0, lambda: self._apply_local_ip(ip))
        threading.Thread(target=_worker, daemon=True).start()

    def _apply_local_ip(self, ip):
        self.local_ip = ip
        if hasattr(self, "ip_label"):
            self.ip_label.configure(text=ip)

    def _build_ui(self):
        self.grid_rowconfigure(0, weight=1)
        self.grid_columnconfigure(1, weight=1)

        # ── Sidebar ──
        sidebar = ctk.CTkFrame(self, width=340, corner_radius=0, fg_color="#111827")
        sidebar.grid(row=0, column=0, sticky="nsew")
        sidebar.grid_propagate(False)
        sidebar.grid_columnconfigure(0, weight=1)

        # Title Area
        title_frame = ctk.CTkFrame(sidebar, fg_color="transparent")
        title_frame.grid(row=0, column=0, padx=22, pady=(26, 18), sticky="ew")
        ctk.CTkLabel(title_frame, text="S T R E A M C A M", font=ctk.CTkFont(family="Segoe UI", size=24, weight="bold"), text_color="#F9FAFB").pack(anchor="w")
        ctk.CTkLabel(title_frame, text="Receiver Console", font=ctk.CTkFont(family="Segoe UI", size=12, weight="normal"), text_color="#9CA3AF").pack(anchor="w")

        # Connection Card
        conn_card = ctk.CTkFrame(sidebar, fg_color="#1F2937", corner_radius=14)
        conn_card.grid(row=1, column=0, padx=20, pady=(0, 20), sticky="ew")
        conn_card.grid_columnconfigure(0, weight=1)

        ctk.CTkLabel(conn_card, text="TARGET IP ADDRESS", font=ctk.CTkFont(size=10, weight="bold"), text_color="#9CA3AF").grid(row=0, column=0, padx=16, pady=(16, 0), sticky="w")
        self.ip_label = ctk.CTkLabel(conn_card, text=self.local_ip, font=ctk.CTkFont(family="Consolas", size=20, weight="bold"), text_color="#7DD3FC")
        self.ip_label.grid(row=1, column=0, padx=16, pady=(0, 12), sticky="w")

        ctk.CTkLabel(conn_card, text="BIND PORT", font=ctk.CTkFont(size=10, weight="bold"), text_color="#9CA3AF").grid(row=2, column=0, padx=16, pady=(4, 0), sticky="w")
        self.port_entry = ctk.CTkEntry(conn_card, font=ctk.CTkFont(family="Consolas", size=14), fg_color="#111827", border_width=1, border_color="#374151", corner_radius=10, height=38)
        self.port_entry.insert(0, "5000")
        self.port_entry.grid(row=3, column=0, padx=16, pady=(4, 16), sticky="ew")

        # Controls Area
        self.start_btn = ctk.CTkButton(sidebar, text="START LISTENING", command=self.start_server, fg_color="#0284C7", hover_color="#0369A1", font=ctk.CTkFont(weight="bold", size=13), corner_radius=10, height=46)
        self.start_btn.grid(row=2, column=0, padx=20, pady=(0, 10), sticky="ew")

        self.stop_btn = ctk.CTkButton(sidebar, text="STOP", command=self.stop_server, fg_color="#374151", hover_color="#B91C1C", font=ctk.CTkFont(weight="bold", size=13), corner_radius=10, height=46, state="disabled")
        self.stop_btn.grid(row=3, column=0, padx=20, pady=(0, 30), sticky="ew")

        # Media Tools Card
        media_card = ctk.CTkFrame(sidebar, fg_color="#1F2937", corner_radius=14)
        media_card.grid(row=4, column=0, padx=20, pady=(0, 20), sticky="ew")
        
        self.pause_btn = ctk.CTkButton(media_card, text="PAUSE FEED", command=self.toggle_pause, fg_color="transparent", hover_color="#374151", text_color="#E5E7EB", anchor="w", state="disabled")
        self.pause_btn.pack(fill="x", padx=10, pady=(10, 5))
        
        self.snap_btn = ctk.CTkButton(media_card, text="SNAPSHOT", command=self.take_snapshot, fg_color="transparent", hover_color="#374151", text_color="#E5E7EB", anchor="w", state="disabled")
        self.snap_btn.pack(fill="x", padx=10, pady=5)
        
        self.record_btn = ctk.CTkButton(media_card, text="RECORD VIDEO", command=self.toggle_recording, fg_color="transparent", hover_color="#7F1D1D", text_color="#FCA5A5", anchor="w", state="disabled")
        self.record_btn.pack(fill="x", padx=10, pady=(5, 10))

        self.folder_btn = ctk.CTkButton(sidebar, text="OPEN CAPTURE FOLDER", command=self.open_save_folder, fg_color="#111827", hover_color="#1F2937", border_width=1, border_color="#334155", text_color="#CBD5E1", font=ctk.CTkFont(size=12, weight="bold"), corner_radius=10, height=40)
        self.folder_btn.grid(row=5, column=0, padx=20, pady=(0, 14), sticky="ew")

        # Telemetry Area
        self.client_status_lbl = ctk.CTkLabel(sidebar, text="● No Connection", font=ctk.CTkFont(size=12, weight="bold"), text_color="#6B7280")
        self.client_status_lbl.grid(row=6, column=0, padx=20, pady=(10, 0), sticky="w")

        telemetry_frame = ctk.CTkFrame(sidebar, fg_color="transparent")
        telemetry_frame.grid(row=7, column=0, padx=20, pady=10, sticky="ew")
        telemetry_frame.grid_columnconfigure((0, 1), weight=1)

        pairs = [
            ("LIVE FPS", "fps_val", "#34D399"), ("PEAK FPS", "peak_fps_val", "#A7F3D0"),
            ("BANDWIDTH", "bw_val", "#818CF8"), ("DATA USED", "data_val", "#C7D2FE"),
            ("FRAMES", "frames_val", "#FBBF24"), ("UPTIME", "session_val", "#FDE68A")
        ]
        
        for i, (label, attr, color) in enumerate(pairs):
            col = i % 2
            row = (i // 2) * 2
            ctk.CTkLabel(telemetry_frame, text=label, font=ctk.CTkFont(size=9, weight="bold"), text_color="#94A3B8").grid(row=row, column=col, sticky="w", pady=(10, 0))
            lbl = ctk.CTkLabel(telemetry_frame, text="—", font=ctk.CTkFont(family="Consolas", size=14, weight="bold"), text_color=color)
            lbl.grid(row=row+1, column=col, sticky="w")
            setattr(self, attr, lbl)

        self.rec_label = ctk.CTkLabel(sidebar, text="", font=ctk.CTkFont(size=12, weight="bold"), text_color="#EF4444")
        self.rec_label.grid(row=8, column=0, padx=20, pady=10, sticky="w")

        # ── Video Panel ──
        self.video_panel = ctk.CTkFrame(self, corner_radius=18, fg_color="#020617", border_width=1, border_color="#1E293B")
        self.video_panel.grid(row=0, column=1, padx=24, pady=24, sticky="nsew")
        self.video_panel.pack_propagate(False)

        self.video_label = ctk.CTkLabel(self.video_panel, text="C A M E R A   O F F L I N E", text_color="#334155", font=ctk.CTkFont(size=24, weight="bold"))
        self.video_label.pack(expand=True, fill="both")

        self.res_label = ctk.CTkLabel(self.video_panel, text="", font=ctk.CTkFont(family="Consolas", size=12, weight="bold"), text_color="#94A3B8", fg_color="#0F172A", corner_radius=6)
        self.res_label.place(relx=1.0, rely=1.0, anchor="se", x=-16, y=-16)

    # ── Safe Threading Handlers ───────────────────────────────────────────────

    def _track_video_size(self):
        if not self.winfo_exists(): return
        if hasattr(self, 'video_label') and self.video_label.winfo_width() > 10:
            self._current_video_size = (self.video_label.winfo_width(), self.video_label.winfo_height())
        self.after(200, self._track_video_size)

    # ── Server lifecycle ──────────────────────────────────────────────────────

    def start_server(self):
        port_str = self.port_entry.get().strip()
        if not port_str.isdigit():
            self.client_status_lbl.configure(text="● Invalid Port", text_color="#EF4444")
            return

        self.current_port = int(port_str)
        try:
            self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server_socket.bind(("0.0.0.0", self.current_port))
            self.server_socket.listen(1)
        except Exception as e:
            self.client_status_lbl.configure(text=f"● Bind Error", text_color="#EF4444")
            return

        self.is_running = True
        self.start_btn.configure(state="disabled", fg_color="#3F3F46")
        self.stop_btn.configure(state="normal", fg_color="#DC2626")
        self.client_status_lbl.configure(text=f"● Listening on :{self.current_port}", text_color="#60A5FA")

        self.receive_thread = threading.Thread(target=self._network_loop, daemon=True)
        self.receive_thread.start()

    def stop_server(self):
        self.is_running = False
        self._stop_recording_internal()
        try:
            if self.client_socket: 
                self.client_socket.close()
            if self.server_socket: 
                self.server_socket.close()
        except Exception:
            pass

        try:
            self.start_btn.configure(state="normal", fg_color="#0284C7")
            self.stop_btn.configure(state="disabled", fg_color="#374151")
            self.pause_btn.configure(state="disabled", text="PAUSE FEED")
            self.snap_btn.configure(state="disabled")
            self.record_btn.configure(state="disabled", text="RECORD VIDEO", text_color="#FCA5A5")
            self._set_client_status("● Offline", "#6B7280")
            self._reset_telemetry()
            self._clear_video_screen()
        except Exception:
            pass

    # ── Network loop ──────────────────────────────────────────────────────────

    def _network_loop(self):
        while self.is_running:
            self.server_socket.settimeout(1.0)
            try:
                client, addr = self.server_socket.accept()
            except socket.timeout:
                continue
            except Exception:
                break

            self._session_id += 1
            my_session = self._session_id

            self.client_socket = client
            self.client_socket.settimeout(10)
            self.session_start = time.time()
            
            self.frame_count = self.bytes_received = self.total_frames = self._total_bytes = self.peak_fps = 0
            self.last_stat_time = time.time()
            self.last_frame = None
            self.is_paused = False

            if self.winfo_exists():
                self.after(0, lambda ip=addr[0]: (
                    self._set_client_status(f"● Connected: {ip}", "#34D399"),
                    self.pause_btn.configure(state="normal", text="PAUSE FEED"),
                    self.snap_btn.configure(state="normal"),
                    self.record_btn.configure(state="normal"),
                ))

            try:
                while self.is_running:
                    header = recv_exact(self.client_socket, HEADER_SIZE)
                    if header is None:
                        break
                    (frame_size,) = struct.unpack(">I", header)
                    if frame_size == 0 or frame_size > 8 * 1024 * 1024:
                        continue 

                    jpeg_data = recv_exact(self.client_socket, frame_size)
                    if jpeg_data is None:
                        break

                    self.bytes_received += frame_size
                    self.total_frames   += 1

                    arr   = np.frombuffer(jpeg_data, dtype=np.uint8)
                    frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                    if frame is None:
                        continue

                    self.last_frame = frame

                    if not self.is_paused:
                        self._push_frame(frame)

                    if self.is_recording and self.video_writer is not None:
                        self._write_frame(frame)

                    self.frame_count += 1

                    now = time.time()
                    elapsed = now - self.last_stat_time
                    if elapsed >= 1.0:
                        fps  = int(self.frame_count / elapsed)
                        kbps = int((self.bytes_received / 1024) / elapsed)
                        self.peak_fps = max(self.peak_fps, fps)
                        self._total_bytes += kbps
                        if self.winfo_exists():
                            self._update_telemetry(fps, kbps)
                        self.frame_count    = 0
                        self.bytes_received = 0
                        self.last_stat_time = now

            except Exception:
                pass
            finally:
                self._stop_recording_internal()
                try: self.client_socket.close()
                except Exception: pass

                def _on_disconnect(sid=my_session, port=self.current_port):
                    if not self.winfo_exists() or self._session_id != sid:
                        return
                    self._set_client_status(f"● Listening on :{port}", "#7DD3FC")
                    self.pause_btn.configure(state="disabled", text="PAUSE FEED")
                    self.snap_btn.configure(state="disabled")
                    self.record_btn.configure(state="disabled", text="RECORD VIDEO")
                    self._reset_telemetry()
                    self._clear_video_screen()

                if self.winfo_exists():
                    self.after(0, _on_disconnect)

    # ── Frame display ─────────────────────────────────────────────────────────

    def _push_frame(self, bgr_frame):
        if not self.winfo_exists(): return
        rgb  = cv2.cvtColor(bgr_frame, cv2.COLOR_BGR2RGB)
        pil  = Image.fromarray(rgb)
        h, w = bgr_frame.shape[:2]

        fw, fh = self._current_video_size
        if fw > 10 and fh > 10:
            pil.thumbnail((fw, fh), Image.Resampling.LANCZOS)

        res_text = f" {w}×{h} "
        self.after(0, self._render_frame, pil, res_text)

    def _render_frame(self, pil_image, res_text):
        if not self.winfo_exists(): return
        try:
            ctk_img = ctk.CTkImage(light_image=pil_image, dark_image=pil_image, size=pil_image.size)
            self.video_label.configure(image=ctk_img, text="")
            self.video_label._image_ref = ctk_img 
            self.res_label.configure(text=res_text)
        except Exception:
            pass

    # CRITICAL BUG FIX: Use an explicitly empty 1x1 transparent image instead of None
    def _clear_video_screen(self):
        if not self.winfo_exists(): return
        self.last_frame = None
        try:
            blank_pil = Image.new("RGBA", (1, 1), (0, 0, 0, 0))
            blank_ctk = ctk.CTkImage(light_image=blank_pil, dark_image=blank_pil, size=(1, 1))
            self.video_label.configure(image=blank_ctk, text="C A M E R A   O F F L I N E", text_color="#334155")
            self.video_label._image_ref = blank_ctk
            self.res_label.configure(text="")
        except Exception:
            pass

    # ── Stream controls ───────────────────────────────────────────────────────

    def toggle_pause(self):
        self.is_paused = not self.is_paused
        if self.is_paused:
            self.pause_btn.configure(text="RESUME FEED", text_color="#34D399")
        else:
            self.pause_btn.configure(text="PAUSE FEED", text_color="#E5E7EB")
            if self.last_frame is not None:
                self._push_frame(self.last_frame)

    def take_snapshot(self):
        frame = self.last_frame
        if frame is None: return
        ts   = datetime.now().strftime("%Y%m%d_%H%M%S")
        path = os.path.join(self.snapshot_dir, f"snapshot_{ts}.jpg")
        cv2.imwrite(path, frame, [cv2.IMWRITE_JPEG_QUALITY, 95])
        self.snap_btn.configure(text="SAVED", text_color="#34D399")
        self.after(2000, lambda: self.snap_btn.configure(text="SNAPSHOT", text_color="#E5E7EB"))

    def toggle_recording(self):
        if self.is_recording:
            self._stop_recording_internal()
            self.record_btn.configure(text="RECORD VIDEO", text_color="#FCA5A5")
            self.rec_label.configure(text="")
        else:
            self._start_recording()

    def _start_recording(self):
        frame = self.last_frame
        if frame is None: return
        h, w  = frame.shape[:2]
        ts    = datetime.now().strftime("%Y%m%d_%H%M%S")
        path  = os.path.join(self.snapshot_dir, f"recording_{ts}.avi")
        fourcc = cv2.VideoWriter_fourcc(*"XVID")
        self.video_writer       = cv2.VideoWriter(path, fourcc, 25, (w, h))
        self.is_recording       = True
        self.record_start       = time.time()
        self.record_frame_count = 0
        self.record_btn.configure(text="STOP RECORDING", text_color="#FCA5A5")
        self._tick_recording()

    def _tick_recording(self):
        if not self.is_recording or not self.winfo_exists(): return
        elapsed = int(time.time() - self.record_start)
        m, s   = divmod(elapsed, 60)
        self.rec_label.configure(text=f"⏺ REC {m:02d}:{s:02d} ({self.record_frame_count} frames)")
        self.after(1000, self._tick_recording)

    def _write_frame(self, frame):
        if self.video_writer and self.video_writer.isOpened():
            self.video_writer.write(frame)
            self.record_frame_count += 1

    def _stop_recording_internal(self):
        self.is_recording = False
        if self.video_writer:
            self.video_writer.release()
            self.video_writer = None

    def open_save_folder(self):
        import subprocess
        if sys.platform == "win32":
            os.startfile(self.snapshot_dir)
        elif sys.platform == "darwin":
            subprocess.Popen(["open", self.snapshot_dir])
        else:
            subprocess.Popen(["xdg-open", self.snapshot_dir])

    # ── Telemetry helpers ─────────────────────────────────────────────────────

    def _update_telemetry(self, fps, kbps):
        if not self.winfo_exists(): return
        elapsed = int(time.time() - self.session_start) if self.session_start else 0
        m, s    = divmod(elapsed, 60)
        def mb(kb): return f"{kb/1024:.1f} MB" if kb > 1024 else f"{kb} KB"
        
        self.after(0, lambda f=fps, k=kbps, tf=self.total_frames, sess=f"{m:02d}:{s:02d}", tb=self._total_bytes: (
            self.fps_val.configure(text=f"{f}"),
            self.peak_fps_val.configure(text=f"{self.peak_fps}"),
            self.bw_val.configure(text=f"{k} KB/s"),
            self.data_val.configure(text=mb(tb)),
            self.frames_val.configure(text=str(tf)),
            self.session_val.configure(text=sess),
        ))

    def _reset_telemetry(self):
        self._total_bytes = 0
        for attr in ("fps_val", "peak_fps_val", "bw_val", "data_val", "frames_val", "session_val"):
            try:
                getattr(self, attr).configure(text="—")
            except Exception:
                pass

    def _set_client_status(self, text, color):
        if not self.winfo_exists(): return
        self.client_status_lbl.after(0, lambda t=text, c=color: self.client_status_lbl.configure(text=t, text_color=c))

    # ── Cleanup ───────────────────────────────────────────────────────────────
    def on_closing(self):
        try:
            self.stop_server()
        except Exception:
            pass
        self.destroy()

if __name__ == "__main__":
    app = StreamCamReceiver()
    app.mainloop()