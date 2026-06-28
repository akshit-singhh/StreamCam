package com.streamcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StreamCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewSurface: androidx.camera.view.PreviewView
) {
    enum class Mode { PREVIEW_ONLY, TCP_JPEG, RTSP_H264 }

    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newFixedThreadPool(3)

    var onJpegFrame: ((ByteArray) -> Unit)? = null
    var encoderSurface: Surface? = null

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    @OptIn(ExperimentalCamera2Interop::class)
    @SuppressLint("RestrictedApi")
    fun startCamera(mode: Mode, resolution: Size = Size(1280, 720), targetFps: Int = 30) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCamera(mode, resolution, targetFps)
        }, ContextCompat.getMainExecutor(context))
    }

    @ExperimentalCamera2Interop
    private fun bindCamera(mode: Mode, resolution: Size, targetFps: Int) {
        val provider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(resolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            // Dynamically force the camera hardware to capture at the selected frame rate
            .setTargetFrameRate(Range(targetFps, targetFps))
            .build()
            .also { it.setSurfaceProvider(previewSurface.surfaceProvider) }

        provider.unbindAll()

        when (mode) {
            Mode.PREVIEW_ONLY -> {
                try {
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                    Log.i(TAG, "Camera bound in PREVIEW_ONLY mode")
                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind failed: ${e.message}")
                }
            }

            Mode.TCP_JPEG -> {
                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processFrameForTcp(imageProxy)
                        }
                    }
                try {
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                    Log.i(TAG, "Camera bound in TCP/JPEG mode")
                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind failed: ${e.message}")
                }
            }

            Mode.RTSP_H264 -> {
                val surface = encoderSurface
                if (surface != null) {

                    // 1. Create a baseline Preview Builder
                    val previewBuilder = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)

                    // 2. NEW: Forcefully override Auto Exposure FPS parameters at the Camera2 hardware layer
                    val camera2Extender = Camera2Interop.Extender(previewBuilder)

                    // This forces the sensor's physical clock to open up to 60fps speeds
                    camera2Extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(60, 60) // Or Range(30, 60) if your phone prefers variable high-speed
                    )

                    val encoderPreview = previewBuilder.build().also { prev ->
                        prev.setSurfaceProvider { request: SurfaceRequest ->
                            request.provideSurface(surface, cameraExecutor) { _ -> }
                        }
                    }

                    try {
                        provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, encoderPreview)
                        Log.i(TAG, "Camera successfully forced into low-level Camera2 60FPS mode")
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera bind (H264) failed: ${e.message}")
                    }
                } else {
                    Log.e(TAG, "Encoder surface is null — cannot start H264 mode")
                }
            }
        }
    }

    private fun processFrameForTcp(imageProxy: ImageProxy) {
        try {
            val jpegBytes = imageProxy.toJpegBytes(JPEG_QUALITY)
            onJpegFrame?.invoke(jpegBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress frame: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    fun shutdown() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        Log.i(TAG, "Camera hardware shut down")
    }

    companion object {
        private const val TAG = "StreamCameraManager"
        private const val JPEG_QUALITY = 80
    }
}

private fun ImageProxy.toJpegBytes(quality: Int): ByteArray {
    if (format == ImageFormat.JPEG) {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
    val bitmap = this.toBitmap()
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}