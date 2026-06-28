package com.streamcam.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams JPEG-compressed camera frames over a TCP socket.
 *
 * Protocol (simple length-prefixed framing):
 * [4 bytes big-endian int: frame size] [N bytes: JPEG data]
 *
 * The desktop receiver reads the 4-byte length, then reads exactly that
 * many bytes to reconstruct and display each frame.
 */
class TcpStreamer {

    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private val streaming = AtomicBoolean(false)

    val isStreaming: Boolean get() = streaming.get()

    /**
     * Opens a TCP connection to [host]:[port].
     * Returns true on success, false on failure.
     */
    suspend fun connect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val s = Socket(host, port)
            s.tcpNoDelay = true
            s.sendBufferSize = 512 * 1024  // 512 KB send buffer
            outputStream = DataOutputStream(s.getOutputStream())
            socket = s
            streaming.set(true)
            Log.i(TAG, "TCP connected to $host:$port")
            true
        } catch (e: IOException) {
            Log.e(TAG, "TCP connect failed: ${e.message}")
            false
        }
    }

    /**
     * Sends a single JPEG frame. Call from the camera image analysis callback.
     * Non-blocking: if the socket is not connected it returns immediately.
     */
    @Synchronized // CRITICAL FIX: Prevents concurrent thread writes from scrambling the JPEG bytes in the pipe
    fun sendFrame(jpegBytes: ByteArray) {
        if (!streaming.get()) return
        val out = outputStream ?: return
        try {
            out.writeInt(jpegBytes.size)   // 4-byte big-endian frame length
            out.write(jpegBytes)           // raw JPEG payload
            out.flush()
        } catch (e: SocketException) {
            Log.w(TAG, "Socket error while sending frame: ${e.message}")
            streaming.set(false)
        } catch (e: IOException) {
            Log.e(TAG, "IO error while sending frame: ${e.message}")
        }
    }

    /**
     * Closes the TCP connection gracefully.
     */
    fun disconnect() {
        streaming.set(false)
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: IOException) {}
        outputStream = null
        socket = null
        Log.i(TAG, "TCP disconnected")
    }

    companion object {
        private const val TAG = "TcpStreamer"
    }
}