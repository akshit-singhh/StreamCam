package com.streamcam.ui

import android.app.Application
import android.content.Context
import android.media.MediaFormat
import android.util.Log
import android.util.Size
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.streamcam.camera.StreamCameraManager
import com.streamcam.streaming.H264Encoder
import com.streamcam.streaming.RtspServer
import com.streamcam.streaming.TcpStreamer
import com.streamcam.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StreamViewModel(application: Application) : AndroidViewModel(application) {

    enum class StreamState { IDLE, CONNECTING, STREAMING, ERROR }
    enum class StreamMode  { TCP_JPEG, RTSP_H264 }

    private val prefs = application.getSharedPreferences("streamcam_prefs", Context.MODE_PRIVATE)

    private val _state    = MutableLiveData(StreamState.IDLE)
    private val _status   = MutableLiveData("Ready")
    private val _rtspUrl  = MutableLiveData("")
    private val _deviceIp = MutableLiveData(NetworkUtils.getLocalIpAddress(application))
    private val _fps      = MutableLiveData(0)
    private val _recentIps = MutableLiveData(loadRecentIps())
    private val _showSwipeHint = MutableLiveData(prefs.getBoolean("show_swipe_hint", true))

    val state:    LiveData<StreamState> = _state
    val status:   LiveData<String>      = _status
    val rtspUrl:  LiveData<String>      = _rtspUrl
    val deviceIp: LiveData<String>      = _deviceIp
    val fps:      LiveData<Int>         = _fps
    val recentIps: LiveData<List<String>> = _recentIps
    val showSwipeHint: LiveData<Boolean>  = _showSwipeHint

    var streamMode = StreamMode.TCP_JPEG
    var tcpHost    = ""
    var tcpPort    = 5000
    var rtspPort   = 8554
    var resolution = Resolution.HD_720
    var targetFps  = 30 // NEW: Target FPS state

    enum class Resolution(val size: Size, val label: String) {
        SD_480(Size(640, 480),   "480p"),
        HD_720(Size(1280, 720),  "720p"),
        FHD_1080(Size(1920, 1080), "1080p")
    }

    private var cameraManager: StreamCameraManager? = null
    private var tcpStreamer: TcpStreamer? = null
    private var rtspServer: RtspServer? = null
    private var h264Encoder: H264Encoder? = null

    private var frameCount = 0
    private var fpsWindowStart = System.currentTimeMillis()

    private fun loadRecentIps(): List<String> {
        val saved = prefs.getString("recent_ips", "") ?: ""
        return if (saved.isEmpty()) emptyList() else saved.split(",")
    }

    private fun saveRecentIp(ip: String) {
        if (ip.isBlank()) return
        val current = loadRecentIps().toMutableList()
        current.remove(ip)
        current.add(0, ip)
        val limited = current.take(3)
        prefs.edit().putString("recent_ips", limited.joinToString(",")).apply()
        _recentIps.postValue(limited)
    }

    fun dismissSwipeHint() {
        prefs.edit().putBoolean("show_swipe_hint", false).apply()
        _showSwipeHint.value = false
    }

    fun startPreview(lifecycleOwner: androidx.lifecycle.LifecycleOwner, previewView: PreviewView) {
        if (cameraManager == null) {
            cameraManager = StreamCameraManager(getApplication(), lifecycleOwner, previewView)
        }
        cameraManager?.startCamera(StreamCameraManager.Mode.PREVIEW_ONLY, resolution.size, targetFps)
    }

    fun flipCamera(lifecycleOwner: androidx.lifecycle.LifecycleOwner, previewView: PreviewView) {
        val camManager = cameraManager ?: return
        val currentLens = camManager.lensFacing

        camManager.lensFacing = if (currentLens == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
            androidx.camera.core.CameraSelector.LENS_FACING_FRONT
        } else {
            androidx.camera.core.CameraSelector.LENS_FACING_BACK
        }

        val currentMode = when (_state.value) {
            StreamState.STREAMING, StreamState.CONNECTING -> {
                if (streamMode == StreamMode.TCP_JPEG) StreamCameraManager.Mode.TCP_JPEG
                else StreamCameraManager.Mode.RTSP_H264
            }
            else -> StreamCameraManager.Mode.PREVIEW_ONLY
        }

        camManager.startCamera(currentMode, resolution.size, targetFps)
    }

    fun startStreaming(lifecycleOwner: androidx.lifecycle.LifecycleOwner, previewView: PreviewView) {
        if (_state.value == StreamState.STREAMING || _state.value == StreamState.CONNECTING) return
        dismissSwipeHint()

        _state.value = StreamState.CONNECTING
        _status.value = "Connecting…"

        viewModelScope.launch {
            when (streamMode) {
                StreamMode.TCP_JPEG  -> startTcpStream(lifecycleOwner, previewView)
                StreamMode.RTSP_H264 -> startRtspStream(lifecycleOwner, previewView)
            }
        }
    }

    private suspend fun startTcpStream(lifecycleOwner: androidx.lifecycle.LifecycleOwner, previewView: PreviewView) {
        val streamer = TcpStreamer()
        val connected = streamer.connect(tcpHost, tcpPort)
        if (!connected) {
            _state.postValue(StreamState.ERROR)
            _status.postValue("Connection failed — check IP & port")
            return
        }

        saveRecentIp(tcpHost)
        tcpStreamer = streamer

        val cam = cameraManager ?: StreamCameraManager(getApplication(), lifecycleOwner, previewView).also { cameraManager = it }
        cam.onJpegFrame = { jpegBytes ->
            if (streamer.isStreaming) {
                streamer.sendFrame(jpegBytes)
                tickFps()
            } else {
                stopStreaming()
            }
        }
        cam.startCamera(StreamCameraManager.Mode.TCP_JPEG, resolution.size, targetFps)

        _state.postValue(StreamState.STREAMING)
        _status.postValue("TCP → $tcpHost:$tcpPort")
        _rtspUrl.postValue("")
    }

    private fun startRtspStream(lifecycleOwner: androidx.lifecycle.LifecycleOwner, previewView: PreviewView) {
        val server = RtspServer(
            port = rtspPort,
            width = resolution.size.width,
            height = resolution.size.height,
            frameRate = targetFps // NEW: Use selected target FPS
        )
        server.start()
        rtspServer = server

        val deviceIpStr = _deviceIp.value ?: "0.0.0.0"
        val url = "rtsp://$deviceIpStr:$rtspPort/live"
        _rtspUrl.postValue(url)
        _status.postValue("RTSP ready — waiting for client")

        var lastPts = -1L

        val encoder = H264Encoder(
            width  = resolution.size.width,
            height = resolution.size.height,
            frameRate = targetFps, // NEW: Use selected target FPS for MediaCodec
            onFormatChanged = { format ->
                val spsBuffer = format.getByteBuffer("csd-0")
                val ppsBuffer = format.getByteBuffer("csd-1")

                if (spsBuffer != null && ppsBuffer != null) {
                    val spsArray = ByteArray(spsBuffer.remaining())
                    spsBuffer.get(spsArray)
                    val ppsArray = ByteArray(ppsBuffer.remaining())
                    ppsBuffer.get(ppsArray)

                    val sps = stripStartCode(spsArray)
                    val pps = stripStartCode(ppsArray)

                    server.updateSpsPps(sps, pps)
                }
            },
            onEncodedFrame = { nalData, isKeyFrame, timestampUs ->
                if (server.isPlaying) {
                    server.sendNal(nalData, timestampUs)
                }
                if (timestampUs != lastPts) {
                    tickFps()
                    lastPts = timestampUs
                }
            }
        )
        h264Encoder = encoder
        val encoderSurface = encoder.start()

        val cam = cameraManager ?: StreamCameraManager(getApplication(), lifecycleOwner, previewView).also { cameraManager = it }
        cam.encoderSurface = encoderSurface
        cam.startCamera(StreamCameraManager.Mode.RTSP_H264, resolution.size, targetFps)

        _state.postValue(StreamState.STREAMING)
        _status.postValue("RTSP: $url")
    }

    fun stopStreaming() {
        viewModelScope.launch(Dispatchers.Main) {
            cameraManager?.startCamera(StreamCameraManager.Mode.PREVIEW_ONLY, resolution.size, targetFps)

            withContext(Dispatchers.IO) {
                tcpStreamer?.disconnect()
                h264Encoder?.stop()
                rtspServer?.stop()
                tcpStreamer = null
                h264Encoder = null
                rtspServer = null
            }

            _state.value = StreamState.IDLE
            _status.value = "Ready"
            _rtspUrl.value = ""
            _fps.value = 0
            frameCount = 0
        }
    }

    private fun tickFps() {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            val calculatedFps = (frameCount * 1000L / elapsed).toInt()
            _fps.postValue(calculatedFps)
            frameCount = 0
            fpsWindowStart = now
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager?.shutdown()
    }

    private fun stripStartCode(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data.copyOfRange(4, data.size)
        }
        if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            return data.copyOfRange(3, data.size)
        }
        return data
    }

    companion object { private const val TAG = "StreamViewModel" }
}