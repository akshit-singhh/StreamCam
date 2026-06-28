package com.streamcam.streaming

import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

class RtpPacketizer(
    private val destAddress: InetAddress?,
    private val rtpPort: Int,
    private val tcpOutputStream: OutputStream? = null
) {
    private val socket = if (tcpOutputStream == null) DatagramSocket() else null
    private var sequenceNumber: Int = Random.nextInt(0xFFFF)
    private val ssrc: Int = Random.nextInt()
    private val sendBuffer = ByteArray(MAX_RTP_PACKET)

    fun sendNalUnit(nalData: ByteArray, timestampUs: Long) {
        val rtpTimestamp = (timestampUs * 90L / 1_000L).toInt()
        val nalSize = nalData.size

        if (nalSize == 0) return

        if (nalSize <= MAX_RTP_PAYLOAD) {
            sendSingleNal(nalData, nalSize, rtpTimestamp, marker = true)
        } else {
            sendFuA(nalData, nalSize, rtpTimestamp)
        }
    }

    private fun sendSingleNal(data: ByteArray, size: Int, ts: Int, marker: Boolean) {
        val packetSize = RTP_HEADER_SIZE + size
        writeRtpHeader(sendBuffer, marker, ++sequenceNumber and 0xFFFF, ts)
        System.arraycopy(data, 0, sendBuffer, RTP_HEADER_SIZE, size)
        send(packetSize)
    }

    private fun sendFuA(data: ByteArray, size: Int, ts: Int) {
        val nalHeader = data[0].toInt() and 0xFF
        val fuIndicator = (nalHeader and 0xE0) or 28
        var pos = 1
        var remaining = size - 1
        var first = true

        while (remaining > 0) {
            val chunk = minOf(remaining, MAX_RTP_PAYLOAD - 2)
            val isLast = remaining == chunk
            val packetSize = RTP_HEADER_SIZE + 2 + chunk

            writeRtpHeader(sendBuffer, isLast, ++sequenceNumber and 0xFFFF, ts)
            sendBuffer[RTP_HEADER_SIZE] = fuIndicator.toByte()

            var fuHeader = nalHeader and 0x1F
            if (first) fuHeader = fuHeader or 0x80
            if (isLast) fuHeader = fuHeader or 0x40
            sendBuffer[RTP_HEADER_SIZE + 1] = fuHeader.toByte()

            System.arraycopy(data, pos, sendBuffer, RTP_HEADER_SIZE + 2, chunk)
            send(packetSize)

            pos += chunk
            remaining -= chunk
            first = false
        }
    }

    private fun writeRtpHeader(buf: ByteArray, marker: Boolean, seq: Int, ts: Int) {
        buf[0] = 0x80.toByte()
        // 128 + 96 = 224. This guarantees the video player never thinks the frame is an audio packet.
        buf[1] = (if (marker) 224 else 96).toByte()
        buf[2] = (seq shr 8).toByte()
        buf[3] = (seq and 0xFF).toByte()
        buf[4] = (ts shr 24).toByte()
        buf[5] = (ts shr 16).toByte()
        buf[6] = (ts shr 8).toByte()
        buf[7] = (ts and 0xFF).toByte()
        buf[8]  = (ssrc shr 24).toByte()
        buf[9]  = (ssrc shr 16).toByte()
        buf[10] = (ssrc shr 8).toByte()
        buf[11] = (ssrc and 0xFF).toByte()
    }

    private fun send(size: Int) {
        if (tcpOutputStream != null) {
            try {
                val header = byteArrayOf(
                    0x24.toByte(),
                    0x00.toByte(),
                    (size shr 8).toByte(),
                    (size and 0xFF).toByte()
                )
                tcpOutputStream.write(header)
                tcpOutputStream.write(sendBuffer, 0, size)
                tcpOutputStream.flush()
            } catch (_: Exception) {}
        } else {
            val packet = destAddress?.let { DatagramPacket(sendBuffer, size, it, rtpPort) }
            try { packet?.let { socket?.send(it) } } catch (_: Exception) {}
        }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
    }

    companion object {
        private const val RTP_HEADER_SIZE = 12
        private const val MAX_RTP_PACKET = 1500
        private const val MAX_RTP_PAYLOAD = MAX_RTP_PACKET - RTP_HEADER_SIZE
    }
}