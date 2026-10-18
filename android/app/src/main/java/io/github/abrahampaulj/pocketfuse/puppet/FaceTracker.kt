package io.github.abrahampaulj.pocketfuse.puppet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * The user's face on the front camera, as an [EditorPose]. MediaPipe runs entirely on the phone and only
 * the 14 resulting numbers ever leave it: no camera frame is sent anywhere by the puppet.
 */
class FaceTracker(private val context: Context) {
    val mapper = ExpressionMapper()

    private val _pose = MutableStateFlow<EditorPose?>(null)
    val pose: StateFlow<EditorPose?> = _pose.asStateFlow()

    private val _faceVisible = MutableStateFlow(false)
    val faceVisible: StateFlow<Boolean> = _faceVisible.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()
    private var landmarker: FaceLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    @Volatile private var calibrateNext = false

    fun start(owner: LifecycleOwner, previewView: PreviewView?) {
        if (landmarker == null) {
            landmarker = FaceLandmarker.createFromOptions(
                context,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .setOutputFacialTransformationMatrixes(true)
                    .setResultListener { result, _ -> onResult(result) }
                    .setErrorListener { _faceVisible.value = false }
                    .build(),
            )
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = future.get().also { provider = it }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor, ::analyze)
            val useCases = buildList {
                add(analysis)
                previewView?.let { view -> add(Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }) }
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases.toTypedArray())
        }, ContextCompat.getMainExecutor(context))
    }

    fun calibrateNow() {
        calibrateNext = true
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        landmarker?.close()
        landmarker = null
        _faceVisible.value = false
    }

    private fun analyze(proxy: ImageProxy) {
        proxy.use { image ->
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(image.planes[0].buffer)
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, false)
            landmarker?.detectAsync(BitmapImageBuilder(rotated).build(), SystemClock.uptimeMillis())
        }
    }

    private fun onResult(result: FaceLandmarkerResult) {
        val shapes = result.faceBlendshapes().orElse(null)?.firstOrNull()
        val matrix = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()
        if (shapes == null || matrix == null) {
            _faceVisible.value = false
            return
        }
        val (yaw, pitch, roll) = ExpressionMapper.anglesFromMatrix(matrix)
        val reading = FaceReading(shapes.associate { it.categoryName() to it.score() }, yaw, pitch, roll)
        if (calibrateNext) {
            calibrateNext = false
            mapper.calibrate(reading)
        }
        _faceVisible.value = true
        _pose.value = mapper.map(reading)
    }
}
