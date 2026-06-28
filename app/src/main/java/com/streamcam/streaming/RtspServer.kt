package com.streamcam.streaming

import android.util.Log
import java.io.InputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.math.min

class RtspServer(
    private val port: Int = 8554,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false
    private var spsPpsBase64: String = ""
    private var rtpPacketizer: RtpPacketizer? = null

    val isPlaying: Boolean get() = rtpPacketizer != null

    fun updateSpsPps(spsBytes: ByteArray, ppsBytes: ByteArray) {
        val sps = Base64.getEncoder().encodeToString(spsBytes)
        val pps = Base64.getEncoder().encodeToString(ppsBytes)
        spsPpsBase64 = "sprop-parameter-sets=${sps.trim()},${pps.trim()}"
        Log.i(TAG, "SPS/PPS configuration successfully loaded into SDP properties.")
    }

    fun start() {
        running = true
        acceptThread = Thread({
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "RTSP server listening on port $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    Thread({ handleClient(client) }, "RTSP-Client").start()
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Accept error: ${e.message}")
            }
        }, "RTSP-Accept").also { it.start() }
    }

    fun sendNal(nalData: ByteArray, timestampUs: Long) {
        rtpPacketizer?.sendNalUnit(nalData, timestampUs)
    }

    private fun handleClient(client: Socket) {
        Log.i(TAG, "RTSP client connected: ${client.inetAddress.hostAddress}")

        client.tcpNoDelay = true

        val inputStream = client.inputStream
        val writer = PrintWriter(client.outputStream, true)
        var clientRtpPort = 0
        var isTcpInterleaved = false

        try {
            while (running && !client.isClosed) {
                val firstByte = inputStream.read()
                if (firstByte == -1) break

                if (firstByte == 0x24) {
                    val channel = inputStream.read()
                    val lenHigh = inputStream.read()
                    val lenLow = inputStream.read()
                    if (channel == -1 || lenHigh == -1 || lenLow == -1) break

                    val length = (lenHigh shl 8) or lenLow
                    var skipped = 0
                    val skipBuffer = ByteArray(1024)
                    while (skipped < length) {
                        val toRead = min(length - skipped, skipBuffer.size)
                        val r = inputStream.read(skipBuffer, 0, toRead)
                        if (r == -1) break
                        skipped += r
                    }
                    continue
                }

                val requestLine = readLineSafe(inputStream, firstByte)
                if (requestLine.isBlank()) continue

                val lines = mutableListOf<String>()
                lines.add(requestLine)
                while (true) {
                    val headerLine = readLineSafe(inputStream, -1)
                    if (headerLine.isBlank()) break
                    lines.add(headerLine)
                }

                val method = requestLine.substringBefore(" ")
                val cSeq = lines.firstOrNull { it.startsWith("CSeq", ignoreCase = true) }?.substringAfter(":")?.trim() ?: "0"

                Log.d(TAG, "RTSP << $requestLine")

                when (method.uppercase()) {
                    "OPTIONS" -> writer.print(buildResponse(200, cSeq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n"))
                    "DESCRIBE" -> {
                        val sdp = buildSdp(client.localAddress)
                        writer.print(buildResponse(200, cSeq, "Content-Type: application/sdp\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp"))
                    }
                    "SETUP" -> {
                        val transportHeader = lines.firstOrNull { it.startsWith("Transport", ignoreCase = true) } ?: ""
                        isTcpInterleaved = transportHeader.contains("interleaved", ignoreCase = true)

                        if (isTcpInterleaved) {
                            val match = Regex("interleaved=(\\d+-\\d+)").find(transportHeader)
                            val interleavedChannels = match?.groupValues?.get(1) ?: "0-1"
                            writer.print(buildResponse(200, cSeq, "Transport: RTP/AVP/TCP;unicast;interleaved=$interleavedChannels\r\nSession: 12345678\r\n"))
                        } else {
                            clientRtpPort = parseClientRtpPort(transportHeader)
                            writer.print(buildResponse(200, cSeq, "Transport: RTP/AVP/UDP;unicast;client_port=$clientRtpPort-${clientRtpPort+1};server_port=5004-5005\r\nSession: 12345678\r\n"))
                        }
                    }
                    "PLAY" -> {
                        writer.print(buildResponse(200, cSeq, "Session: 12345678\r\nRange: npt=0.000-\r\n"))
                        rtpPacketizer = if (isTcpInterleaved) {
                            Log.i(TAG, "RTSP PLAY active — Sending RTP streams over TCP (Interleaved)")
                            RtpPacketizer(null, 0, client.outputStream)
                        } else {
                            Log.i(TAG, "RTSP PLAY active — Sending RTP streams over UDP to port: $clientRtpPort")
                            RtpPacketizer(client.inetAddress, clientRtpPort, null)
                        }
                    }
                    "TEARDOWN" -> {
                        writer.print(buildResponse(200, cSeq, "Session: 12345678\r\n"))
                        break
                    }
                    else -> writer.print(buildResponse(501, cSeq, ""))
                }
                writer.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client session error: ${e.message}")
        } finally {
            rtpPacketizer?.close()
            rtpPacketizer = null
            try { client.close() } catch (_: Exception) {}
            Log.i(TAG, "RTSP client disconnected cleanly")
        }
    }

    private fun readLineSafe(input: InputStream, firstByte: Int): String {
        val sb = StringBuilder()
        if (firstByte != -1 && firstByte != '\r'.code && firstByte != '\n'.code) {
            sb.append(firstByte.toChar())
        }
        while (true) {
            val c = input.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun buildSdp(localAddress: InetAddress): String {
        val spsPpsLine = if (spsPpsBase64.isNotEmpty()) ";$spsPpsBase64" else ""
        return "v=0\r\n" +
                "o=- 0 0 IN IP4 ${localAddress.hostAddress}\r\n" +
                "s=StreamCam Live\r\n" +
                "c=IN IP4 ${localAddress.hostAddress}\r\n" +
                "t=0 0\r\n" +
                "a=tool:StreamCam Android\r\n" +
                "m=video 0 RTP/AVP 96\r\n" +
                "a=rtpmap:96 H264/90000\r\n" +
                "a=fmtp:96 profile-level-id=42e01f;packetization-mode=1$spsPpsLine\r\n" +
                "a=framerate:$frameRate\r\n" +
                "a=control:*\r\n" +
                "a=recvonly\r\n"
    }

    private fun buildResponse(code: Int, cSeq: String, headers: String): String {
        val reason = when (code) { 200 -> "OK"; 501 -> "Not Implemented"; else -> "Error" }
        return "RTSP/1.0 $code $reason\r\nCSeq: $cSeq\r\n$headers" + (if (!headers.endsWith("\r\n\r\n")) "\r\n" else "")
    }

    private fun parseClientRtpPort(transportHeader: String): Int {
        val match = Regex("client_port=(\\d+)").find(transportHeader)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 5000
    }

    fun stop() {
        running = false
        rtpPacketizer?.close()
        rtpPacketizer = null
        try { serverSocket?.close() } catch (_: Exception) {}
        acceptThread?.join(2000)
        Log.i(TAG, "RTSP server offline")
    }

    companion object {
        private const val TAG = "RtspServer"
    }
}