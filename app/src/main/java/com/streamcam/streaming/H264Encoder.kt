package com.streamcam.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val onFormatChanged: (MediaFormat) -> Unit,
    private val onEncodedFrame: (ByteArray, Boolean, Long) -> Unit
) {
    private var codec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private var encoderThread: Thread? = null
    @Volatile private var running = false

    fun start(): Surface {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, selectBitrate(width, height))
            // This is now respected because we aren't clamping the level
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)

            // FIX: Removed the hardcoded AVCProfileBaseline and AVCLevel31.
            // By letting the OS handle this, it will automatically scale up to Level 4.1 or 4.2
            // required to push 60 FPS video.

            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()

        codec = encoder
        inputSurface = surface
        running = true

        encoderThread = Thread({ drainOutput() }, "H264-Encoder").also { it.start() }
        return surface
    }

    private fun drainOutput() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running) {
            val encoder = codec ?: break
            val outputIndex = try {
                encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            } catch (e: IllegalStateException) {
                break
            }

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) continue

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                onFormatChanged(newFormat)
                continue
            }
            if (outputIndex < 0) continue

            val buffer = encoder.getOutputBuffer(outputIndex) ?: continue
            buffer.position(bufferInfo.offset)
            buffer.limit(bufferInfo.offset + bufferInfo.size)

            if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)) {
                val isKey = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val data = ByteArray(bufferInfo.size)
                buffer.get(data)

                val individualNals = splitNals(data)
                for (nal in individualNals) {
                    onEncodedFrame(nal, isKey, bufferInfo.presentationTimeUs)
                }
            }

            encoder.releaseOutputBuffer(outputIndex, false)
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    private fun splitNals(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var i = 0
        var lastStart = -1

        while (i < data.size - 2) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    if (lastStart != -1) nals.add(data.copyOfRange(lastStart, i))
                    lastStart = i + 3
                    i += 3
                    continue
                }
                else if (i < data.size - 3 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    if (lastStart != -1) nals.add(data.copyOfRange(lastStart, i))
                    lastStart = i + 4
                    i += 4
                    continue
                }
            }
            i++
        }
        if (lastStart != -1 && lastStart < data.size) {
            nals.add(data.copyOfRange(lastStart, data.size))
        }
        return nals
    }

    fun stop() {
        running = false
        encoderThread?.join(2000)
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        inputSurface?.release()
        codec = null; inputSurface = null
    }

    private fun selectBitrate(w: Int, h: Int): Int = (2_000_000 * (w * h).toFloat() / (1920 * 1080)).toInt().coerceIn(500_000, 6_000_000)

    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME_TYPE = "video/avc"
        private const val I_FRAME_INTERVAL_SEC = 1
        private const val TIMEOUT_US = 10_000L
    }
}