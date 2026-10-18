package io.github.abrahampaulj.pocketfuse.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.github.abrahampaulj.pocketfuse.api.ApiException
import io.github.abrahampaulj.pocketfuse.api.FrameStream
import io.github.abrahampaulj.pocketfuse.api.StreamClosedException
import io.github.abrahampaulj.pocketfuse.api.UnreachableException
import io.github.abrahampaulj.pocketfuse.media.toJpeg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live over the `/stream` WebSocket instead of WebRTC: camera frames go up as JPEG, one at a time, and
 * processed JPEGs come back. Slower than WebRTC, but it needs only the HTTP port, so it works where
 * WebRTC cannot (UDP blocked, or a server whose WebRTC path is broken, see the README).
 */
class SocketLive(private val context: Context, private val frames: FrameStream) {
    private val _output = MutableStateFlow<Bitmap?>(null)
    val output: StateFlow<Bitmap?> = _output.asStateFlow()

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var provider: ProcessCameraProvider? = null
    private var count = 0
    private var windowStart = System.currentTimeMillis()

    fun start(owner: LifecycleOwner, front: Boolean) {
        _error.value = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = future.get().also { provider = it }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor, ::onFrame)
            cameraProvider.unbindAll()
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.bindToLifecycle(owner, selector, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun onFrame(proxy: ImageProxy) {
        // One frame in flight: frames that arrive while the PC works are dropped, never queued.
        if (!busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val jpeg = proxy.use { image ->
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(image.planes[0].buffer)
            val scale = MAX_SIDE.toFloat() / maxOf(image.width, image.height)
            val matrix = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
                if (scale < 1f) postScale(scale, scale)
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).toJpeg(80)
        }
        scope.launch {
            try {
                val reply = frames.process(jpeg)
                _output.value = BitmapFactory.decodeByteArray(reply, 0, reply.size)
                tick()
            } catch (exception: StreamClosedException) {
                _error.value = exception.userMessage
            } catch (exception: ApiException) {
                _error.value = exception.userMessage
            } catch (exception: UnreachableException) {
                _error.value = exception.userMessage
            } finally {
                busy.set(false)
            }
        }
    }

    private fun tick() {
        count += 1
        val elapsed = System.currentTimeMillis() - windowStart
        if (elapsed >= 2000) {
            _fps.value = count * 1000f / elapsed
            count = 0
            windowStart = System.currentTimeMillis()
        }
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        _output.value = null
        _fps.value = 0f
    }

    fun release() {
        stop()
        scope.cancel()
        executor.shutdown()
    }

    private companion object {
        const val MAX_SIDE = 640
    }
}
