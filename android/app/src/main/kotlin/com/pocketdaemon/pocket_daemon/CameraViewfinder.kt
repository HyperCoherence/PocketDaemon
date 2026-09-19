package com.pocketdaemon.pocket_daemon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import io.flutter.view.TextureRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Live camera preview for the in-conversation camera button.
 *
 * The Camera2 preview is rendered into a Flutter texture, so the viewfinder lives inside the app and the
 * voice session keeps running underneath it. Stills come from the same camera device, are rotated upright
 * and scaled for the model, and are saved under photos/ like agent-triggered captures.
 *
 * Camera work runs on a dedicated handler thread and results are delivered on it; callers hop back to the
 * main thread themselves. Texture registry calls happen on the main thread, as the Flutter engine requires.
 */
class CameraViewfinder(
    private val context: Context,
    private val textureRegistry: TextureRegistry,
) {
    companion object {
        private const val TAG = "CameraViewfinder"

        /** Preview buffer target in sensor (landscape) coordinates. */
        private const val PREVIEW_TARGET_WIDTH = 1280
        private const val PREVIEW_TARGET_HEIGHT = 960
        private const val PREVIEW_MAX_WIDTH = 1920
        private const val PREVIEW_MAX_HEIGHT = 1440

        /** Longest edge of the JPEG requested from the camera; bigger stills only slow the capture down. */
        private const val CAPTURE_MAX_EDGE = 2048
        private const val CAPTURE_TIMEOUT_MS = 8_000L

        /** Longest edge and quality of the image handed to the model, matching the gallery picker. */
        const val MODEL_MAX_EDGE = 1024
        const val MODEL_JPEG_QUALITY = 85

        private fun error(message: String): Map<String, Any?> = mapOf("error" to message)
    }

    class Photo(val jpeg: ByteArray, val width: Int, val height: Int)

    /** Delivers an open result exactly once, whichever camera callback settles it first. */
    private class PendingOpen(private val onResult: (Map<String, Any?>) -> Unit) {
        private var delivered = false
        fun deliver(result: Map<String, Any?>) {
            if (delivered) return
            delivered = true
            onResult(result)
        }
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("CameraViewfinder").also { it.start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var textureEntry: TextureRegistry.SurfaceTextureEntry? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sensorOrientation = 0
    private var facingFront = false

    /** Bumped on every open/close so callbacks from a superseded camera are ignored. */
    private var generation = 0

    @Volatile var isOpen = false
        private set
    @Volatile private var capturing = false

    /** Opens the camera and starts the preview. Must be called on the main thread. */
    fun open(useBackCamera: Boolean, onResult: (Map<String, Any?>) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            onResult(error("camera permission not granted"))
            return
        }
        val entry = try {
            textureRegistry.createSurfaceTexture()
        } catch (e: Exception) {
            Log.e(TAG, "createSurfaceTexture failed: ${e.message}", e)
            onResult(error("could not create preview texture: ${e.message}"))
            return
        }
        handler.post {
            releaseCamera()
            val gen = ++generation
            textureEntry = entry
            val pending = PendingOpen(onResult)
            try {
                startOpen(useBackCamera, entry, gen, pending)
            } catch (e: Exception) {
                Log.e(TAG, "open failed: ${e.message}", e)
                releaseCamera()
                pending.deliver(error(e.message ?: "camera open failed"))
            }
        }
    }

    private fun startOpen(
        useBackCamera: Boolean,
        entry: TextureRegistry.SurfaceTextureEntry,
        gen: Int,
        pending: PendingOpen,
    ) {
        val facing = if (useBackCamera) CameraCharacteristics.LENS_FACING_BACK
                     else CameraCharacteristics.LENS_FACING_FRONT
        val label = if (useBackCamera) "back" else "front"
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        }
        if (cameraId == null) {
            releaseCamera()
            pending.deliver(error("no $label camera found"))
            return
        }
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            releaseCamera()
            pending.deliver(error("camera configuration unavailable"))
            return
        }
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        facingFront = !useBackCamera

        val captureSize = chooseCaptureSize(map.getOutputSizes(ImageFormat.JPEG))
        val previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture::class.java), captureSize)
        if (captureSize == null || previewSize == null) {
            releaseCamera()
            pending.deliver(error("no usable camera output sizes"))
            return
        }

        entry.surfaceTexture().setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(entry.surfaceTexture()).also { previewSurface = it }
        val reader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 2)
            .also { imageReader = it }
        Log.i(TAG, "Opening $label camera $cameraId: preview ${previewSize.width}x${previewSize.height}, " +
                "capture ${captureSize.width}x${captureSize.height}, sensorOrientation=$sensorOrientation")

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (gen != generation) { camera.close(); return }
                device = camera
                configureSession(camera, entry, surface, reader, previewSize, gen, pending)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Camera disconnected")
                camera.close()
                if (gen != generation) return
                releaseCamera()
                pending.deliver(error("camera disconnected"))
            }

            override fun onError(camera: CameraDevice, code: Int) {
                Log.e(TAG, "Camera error $code")
                camera.close()
                if (gen != generation) return
                releaseCamera()
                pending.deliver(error("camera error $code"))
            }
        }, handler)
    }

    @Suppress("DEPRECATION")
    private fun configureSession(
        camera: CameraDevice,
        entry: TextureRegistry.SurfaceTextureEntry,
        surface: Surface,
        reader: ImageReader,
        previewSize: Size,
        gen: Int,
        pending: PendingOpen,
    ) {
        camera.createCaptureSession(listOf(surface, reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != generation || device == null) { s.close(); return }
                session = s
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    }.build()
                    s.setRepeatingRequest(request, null, handler)
                } catch (e: Exception) {
                    Log.e(TAG, "preview request failed: ${e.message}", e)
                    releaseCamera()
                    pending.deliver(error("preview failed: ${e.message}"))
                    return
                }
                isOpen = true
                pending.deliver(mapOf(
                    "textureId" to entry.id(),
                    "previewWidth" to previewSize.width,
                    "previewHeight" to previewSize.height,
                    "sensorOrientation" to sensorOrientation,
                    "facing" to if (facingFront) "front" else "back",
                ))
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (gen != generation) return
                releaseCamera()
                pending.deliver(error("camera session configuration failed"))
            }
        }, handler)
    }

    /** Takes a still, rotates and scales it for the model, saves it, and reports the JPEG bytes and path. */
    fun capture(onResult: (Map<String, Any?>) -> Unit) {
        handler.post {
            val camera = device
            val s = session
            val reader = imageReader
            if (camera == null || s == null || reader == null || !isOpen) {
                onResult(error("viewfinder is not open"))
                return@post
            }
            if (capturing) {
                onResult(error("capture already in progress"))
                return@post
            }
            capturing = true
            val label = if (facingFront) "front" else "back"

            var done = false
            var timeout: Runnable? = null
            val finish: (() -> Unit) -> Unit = { block ->
                if (!done) {
                    done = true
                    capturing = false
                    timeout?.let { handler.removeCallbacks(it) }
                    reader.setOnImageAvailableListener(null, null)
                    block()
                }
            }
            val timeoutRunnable = Runnable { finish { onResult(error("timeout waiting for image")) } }
            timeout = timeoutRunnable
            handler.postDelayed(timeoutRunnable, CAPTURE_TIMEOUT_MS)

            reader.setOnImageAvailableListener({ r ->
                val raw = r.acquireLatestImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
                finish {
                    if (raw == null) {
                        onResult(error("no image data captured"))
                    } else {
                        // Decode, rotate and re-encode off the camera thread so the preview keeps flowing.
                        Thread({
                            try {
                                val photo = processForModel(raw)
                                val file = savePhoto(photo.jpeg, label)
                                onResult(mapOf(
                                    "path" to file.absolutePath,
                                    "jpeg" to photo.jpeg,
                                    "width" to photo.width,
                                    "height" to photo.height,
                                ))
                            } catch (e: Exception) {
                                Log.e(TAG, "photo processing failed: ${e.message}", e)
                                onResult(error("photo processing failed: ${e.message}"))
                            }
                        }, "viewfinder-photo").start()
                    }
                }
            }, handler)

            try {
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                    set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                }.build()
                s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        sess: CameraCaptureSession, req: CaptureRequest, failure: CaptureFailure,
                    ) {
                        Log.e(TAG, "Capture failed: reason=${failure.reason}")
                        finish { onResult(error("capture failed (reason ${failure.reason})")) }
                    }
                }, handler)
            } catch (e: Exception) {
                Log.e(TAG, "capture request failed: ${e.message}", e)
                finish { onResult(error("capture failed: ${e.message}")) }
            }
        }
    }

    /** Stops the preview and releases the camera. [onDone] runs on the camera thread. */
    fun close(onDone: (() -> Unit)? = null) {
        handler.post {
            generation++
            releaseCamera()
            onDone?.invoke()
        }
    }

    /** Releases everything and stops the camera thread. */
    fun destroy() {
        close { thread.quitSafely() }
    }

    private fun releaseCamera() {
        isOpen = false
        capturing = false
        try { session?.stopRepeating() } catch (e: Exception) { /* already closed */ }
        try { session?.close() } catch (e: Exception) { /* already closed */ }
        session = null
        try { device?.close() } catch (e: Exception) { /* already closed */ }
        device = null
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (e: Exception) { /* already closed */ }
        imageReader = null
        previewSurface?.release()
        previewSurface = null
        val entry = textureEntry
        textureEntry = null
        if (entry != null) mainHandler.post { entry.release() }
    }

    /** Largest JPEG size whose long edge fits [CAPTURE_MAX_EDGE]; the smallest one if none does. */
    private fun chooseCaptureSize(sizes: Array<Size>?): Size? {
        val all = sizes?.toList().orEmpty()
        if (all.isEmpty()) return null
        return all.filter { max(it.width, it.height) <= CAPTURE_MAX_EDGE }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: all.minByOrNull { it.width.toLong() * it.height }
    }

    /** Preview size sharing the capture aspect ratio, closest to the target, so the viewfinder shows what the photo will contain. */
    private fun choosePreviewSize(sizes: Array<Size>?, capture: Size?): Size? {
        val all = sizes?.toList().orEmpty()
        if (all.isEmpty()) return null
        val target = PREVIEW_TARGET_WIDTH.toLong() * PREVIEW_TARGET_HEIGHT
        val fitting = all.filter { it.width <= PREVIEW_MAX_WIDTH && it.height <= PREVIEW_MAX_HEIGHT }
        val ratio = capture?.let { it.width.toFloat() / it.height }
        val sameRatio = if (ratio == null) emptyList() else fitting.filter {
            abs(it.width.toFloat() / it.height - ratio) < 0.02f
        }
        return (sameRatio.ifEmpty { fitting }.ifEmpty { all })
            .minByOrNull { abs(it.width.toLong() * it.height - target) }
    }

    private fun jpegOrientation(): Int {
        val rotation = displayRotationDegrees()
        return if (facingFront) (sensorOrientation + rotation) % 360
               else (sensorOrientation - rotation + 360) % 360
    }

    private fun displayRotationDegrees(): Int {
        val display = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display
            else @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        } catch (e: Exception) {
            null
        }
        return when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /** Decodes a camera JPEG, applies its EXIF rotation, and scales the long edge to [maxEdge]. */
    fun processForModel(raw: ByteArray, maxEdge: Int = MODEL_MAX_EDGE): Photo {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("could not decode photo")

        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge > maxEdge) {
            val scale = maxEdge.toFloat() / longEdge
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }

        val matrix = exifMatrix(raw)
        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, MODEL_JPEG_QUALITY, out)
        val photo = Photo(out.toByteArray(), bitmap.width, bitmap.height)
        bitmap.recycle()
        return photo
    }

    @Suppress("DEPRECATION")
    private fun exifMatrix(raw: ByteArray): Matrix {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(raw))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
    }

    private fun savePhoto(jpeg: ByteArray, label: String): File {
        val photosDir = File(PocketDaemonApp.instance!!.persistentDir, "photos").also { it.mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(photosDir, "photo_${timestamp}_${label}_shared.jpg")
        FileOutputStream(file).use { it.write(jpeg) }
        Log.i(TAG, "Photo saved: ${file.absolutePath} (${jpeg.size} bytes)")
        return file
    }
}
