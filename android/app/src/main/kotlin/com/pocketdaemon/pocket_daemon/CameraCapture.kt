package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraCapture(private val context: Context) {

    companion object {
        private const val TAG = "CameraCapture"
        private const val OPEN_TIMEOUT_S = 10L
        private const val SESSION_TIMEOUT_S = 10L
        private const val CAPTURE_TIMEOUT_S = 15L
        private const val MAX_DELAY_SECONDS = 30
        private const val WARMUP_FRAMES = 8
        private const val WARMUP_TIMEOUT_S = 5L
    }

    fun takePhoto(useBackCamera: Boolean = true, delaySeconds: Int = 0): JSONObject {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val facing = if (useBackCamera) CameraCharacteristics.LENS_FACING_BACK
                     else CameraCharacteristics.LENS_FACING_FRONT
        val cameraLabel = if (useBackCamera) "back" else "front"

        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return JSONObject().put("error", "no $cameraLabel camera found")

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return JSONObject().put("error", "camera configuration unavailable")

        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
        val size = jpegSizes?.maxByOrNull { it.width * it.height }
            ?: return JSONObject().put("error", "no JPEG output available")

        val handlerThread = HandlerThread("CameraCapture").also { it.start() }
        val handler = Handler(handlerThread.looper)

        try {
            return doCapture(cameraManager, cameraId, size, handler, handlerThread,
                useBackCamera, delaySeconds.coerceIn(0, MAX_DELAY_SECONDS), cameraLabel)
        } catch (e: Exception) {
            Log.e(TAG, "takePhoto failed: ${e.message}", e)
            handlerThread.quitSafely()
            return JSONObject().put("error", "capture failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun doCapture(
        cameraManager: CameraManager,
        cameraId: String,
        size: android.util.Size,
        handler: Handler,
        handlerThread: HandlerThread,
        useBackCamera: Boolean,
        delaySeconds: Int,
        cameraLabel: String,
    ): JSONObject {

        // --- open camera ---
        val openLatch = CountDownLatch(1)
        var cameraDevice: CameraDevice? = null
        var openError: String? = null

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { cameraDevice = camera; openLatch.countDown() }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); openError = "disconnected"; openLatch.countDown() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); openError = "error $error"; openLatch.countDown() }
        }, handler)

        if (!openLatch.await(OPEN_TIMEOUT_S, TimeUnit.SECONDS)) {
            handlerThread.quitSafely()
            return JSONObject().put("error", "timeout opening camera")
        }
        if (openError != null || cameraDevice == null) {
            handlerThread.quitSafely()
            return JSONObject().put("error", openError ?: "camera open failed")
        }

        val dev = cameraDevice!!

        // --- create ImageReader ---
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val imageLatch = CountDownLatch(1)
        var imageBytes: ByteArray? = null

        imageReader.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.use { image ->
                val buffer = image.planes[0].buffer
                imageBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
            imageLatch.countDown()
        }, handler)

        // --- create capture session ---
        val sessionLatch = CountDownLatch(1)
        var captureSession: CameraCaptureSession? = null
        var sessionError: String? = null

        dev.createCaptureSession(
            listOf(imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { captureSession = session; sessionLatch.countDown() }
                override fun onConfigureFailed(session: CameraCaptureSession) { sessionError = "session config failed"; sessionLatch.countDown() }
            },
            handler,
        )

        if (!sessionLatch.await(SESSION_TIMEOUT_S, TimeUnit.SECONDS)) {
            dev.close(); imageReader.close(); handlerThread.quitSafely()
            return JSONObject().put("error", "timeout configuring capture session")
        }
        if (sessionError != null) {
            dev.close(); imageReader.close(); handlerThread.quitSafely()
            return JSONObject().put("error", sessionError!!)
        }

        val session = captureSession!!

        // --- warm-up: let AE/AF converge ---
        val warmupLatch = CountDownLatch(WARMUP_FRAMES)
        val warmupRequest = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(imageReader.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }.build()

        session.setRepeatingRequest(warmupRequest, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                warmupLatch.countDown()
            }
        }, handler)

        warmupLatch.await(WARMUP_TIMEOUT_S, TimeUnit.SECONDS)
        session.stopRepeating()

        // drain warmup images so they don't trigger our final listener
        imageReader.setOnImageAvailableListener(null, null)
        while (imageReader.acquireNextImage()?.also { it.close() } != null) { /* drain */ }
        imageReader.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.use { image ->
                val buffer = image.planes[0].buffer
                imageBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
            imageLatch.countDown()
        }, handler)

        // --- delay timer ---
        if (delaySeconds > 0) {
            Log.i(TAG, "Delay: ${delaySeconds}s before capture")
            Thread.sleep(delaySeconds * 1000L)
        }

        // --- still capture ---
        val captureRequest = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(imageReader.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.JPEG_QUALITY, 92.toByte())
        }.build()

        session.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                Log.e(TAG, "Capture failed: reason=${failure.reason}")
                imageLatch.countDown()
            }
        }, handler)

        if (!imageLatch.await(CAPTURE_TIMEOUT_S, TimeUnit.SECONDS)) {
            session.close(); dev.close(); imageReader.close(); handlerThread.quitSafely()
            return JSONObject().put("error", "timeout waiting for image")
        }

        session.close()
        dev.close()
        imageReader.close()
        handlerThread.quitSafely()

        val bytes = imageBytes
            ?: return JSONObject().put("error", "no image data captured")

        val photosDir = File(PocketDaemonApp.instance!!.persistentDir, "photos").also { it.mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(photosDir, "photo_${timestamp}_${cameraLabel}.jpg")

        FileOutputStream(file).use { it.write(bytes) }
        Log.i(TAG, "Photo saved: ${file.absolutePath} (${bytes.size} bytes, ${size.width}x${size.height})")

        return JSONObject()
            .put("status", "captured")
            .put("path", file.absolutePath)
            .put("size_bytes", bytes.size)
            .put("resolution", "${size.width}x${size.height}")
            .put("camera", cameraLabel)
    }
}
