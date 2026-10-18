package io.github.abrahampaulj.pocketfuse.live

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import io.github.abrahampaulj.pocketfuse.api.ApiException
import io.github.abrahampaulj.pocketfuse.api.UnreachableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface RtcState {
    data object Idle : RtcState
    data object Connecting : RtcState
    data object Connected : RtcState
    data class Failed(val message: String) : RtcState
}

sealed interface VideoInput {
    data class Camera(val front: Boolean) : VideoInput
    /** A still pushed as video: used by the headless on-phone test and when no camera is wanted. */
    data class Still(val bitmap: Bitmap, val fps: Int = 15) : VideoInput
}

/**
 * The phone's camera to the PC over WebRTC and the processed video back, as FaceFusion's `POST /stream`
 * expects it:
 *
 * - no trickle ICE, no STUN, no TURN: the offer is posted only once ICE gathering is complete, so it
 *   carries every host candidate, and the phone must reach the PC directly (LAN or tailnet);
 * - separate send and receive transceivers, in the order the server hard-codes as mids: video it
 *   receives (0), video it sends (1), then audio the same way (2, 3);
 * - VP8 / VP9 only, VP8 first (see [SdpTools.PREFERRED_VIDEO]).
 */
class RtcSession(
    private val context: Context,
    private val postOffer: suspend (String) -> String,
    private val deleteStream: suspend () -> Unit,
) {
    val eglBase: EglBase = EglBase.create()

    private val _state = MutableStateFlow<RtcState>(RtcState.Idle)
    val state: StateFlow<RtcState> = _state.asStateFlow()

    private val _receivedFps = MutableStateFlow(0f)
    val receivedFps: StateFlow<Float> = _receivedFps.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val factory: PeerConnectionFactory by lazy { createFactory() }
    private var peer: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localTrack: VideoTrack? = null
    private var remoteTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var stillPump: Job? = null
    private var fpsMeter: Job? = null
    private val framesReceived = AtomicInteger()
    private val pendingSinks = mutableListOf<Pair<VideoSink, Boolean>>()
    @Volatile private var gatheringDone = CompletableDeferred<Unit>()

    private val countingSink = VideoSink { framesReceived.incrementAndGet() }

    suspend fun start(input: VideoInput, sendAudio: Boolean) {
        if (_state.value == RtcState.Connecting || _state.value == RtcState.Connected) return
        _state.value = RtcState.Connecting
        try {
            connect(input, sendAudio)
            _state.value = RtcState.Connected
        } catch (exception: ApiException) {
            fail(exception.userMessage)
        } catch (exception: UnreachableException) {
            fail(exception.userMessage)
        } catch (exception: RtcSetupException) {
            fail(exception.message ?: "The live stream could not start.")
        }
    }

    private suspend fun connect(input: VideoInput, sendAudio: Boolean) {
        gatheringDone = CompletableDeferred()
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
        val connection = factory.createPeerConnection(configuration, observer) ?: throw RtcSetupException("Could not create the WebRTC connection on this phone.")
        peer = connection

        val source = factory.createVideoSource(false).also { videoSource = it }
        when (input) {
            is VideoInput.Camera -> startCamera(source, input.front)
            is VideoInput.Still -> startStill(source, input)
        }
        val track = factory.createVideoTrack("pocketfuse-camera", source).also { localTrack = it }

        // Order matters: the server answers with fixed mids 0..3 (see the class comment).
        val sendVideo = connection.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        val receiveVideo = connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        preferCodecs(sendVideo, sender = true)
        preferCodecs(receiveVideo, sender = false)
        if (sendAudio) {
            val audio = factory.createAudioSource(MediaConstraints()).also { audioSource = it }
            val microphone = factory.createAudioTrack("pocketfuse-microphone", audio).also { audioTrack = it }
            connection.addTransceiver(microphone, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
            connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        }

        (receiveVideo.receiver.track() as? VideoTrack)?.let { remote ->
            remoteTrack = remote
            remote.addSink(countingSink)
            synchronized(pendingSinks) { pendingSinks.filter { !it.second }.forEach { remote.addSink(it.first) } }
        }
        synchronized(pendingSinks) { pendingSinks.filter { it.second }.forEach { track.addSink(it.first) } }

        val offer = connection.awaitOffer()
        connection.awaitLocal(offer)
        // Without trickle ICE the offer must carry every candidate before it leaves the phone.
        withTimeoutOrNull(GATHER_TIMEOUT_MS) { gatheringDone.await() }
        val local = connection.localDescription?.description ?: throw RtcSetupException("The phone produced no offer.")
        if (SdpTools.countHostCandidates(local) == 0) {
            throw RtcSetupException("This phone has no network address for the live stream. Is it on Wi-Fi or Tailscale?")
        }
        val answer = postOffer(SdpTools.keepVideoCodecs(local, SdpTools.PREFERRED_VIDEO.toSet()))
        connection.awaitRemote(SessionDescription(SessionDescription.Type.ANSWER, answer))
        startFpsMeter()
    }

    private fun preferCodecs(transceiver: RtpTransceiver, sender: Boolean) {
        runCatching {
            val capabilities = if (sender) factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            else factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            val order = SdpTools.orderCodecs(capabilities.codecs.map { it.name })
            val chosen = order.flatMap { name -> capabilities.codecs.filter { it.name.equals(name, ignoreCase = true) } }
            if (chosen.isNotEmpty()) transceiver.setCodecPreferences(chosen)
        }
        // If the platform refuses, SdpTools.keepVideoCodecs still trims what the server sees.
    }

    private fun startCamera(source: VideoSource, front: Boolean) {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == front }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw RtcSetupException("This phone has no camera available.")
        val helper = SurfaceTextureHelper.create("pocketfuse-capture", eglBase.eglBaseContext).also { textureHelper = it }
        val camera = enumerator.createCapturer(name, null) ?: throw RtcSetupException("Could not open the camera.")
        camera.initialize(helper, context, source.capturerObserver)
        camera.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
        capturer = camera
    }

    private fun startStill(source: VideoSource, input: VideoInput.Still) {
        val buffer = StillFrames.toI420(input.bitmap)
        source.capturerObserver.onCapturerStarted(true)
        stillPump = scope.launch {
            val interval = 1000L / input.fps.coerceIn(1, 30)
            while (isActive) {
                buffer.retain()
                val frame = VideoFrame(buffer, 0, TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime()))
                source.capturerObserver.onFrameCaptured(frame)
                frame.release()
                delay(interval)
            }
        }
    }

    private fun startFpsMeter() {
        fpsMeter?.cancel()
        fpsMeter = scope.launch {
            while (isActive) {
                framesReceived.set(0)
                delay(2000)
                _receivedFps.value = framesReceived.get() / 2f
            }
        }
    }

    fun switchCamera() {
        capturer?.switchCamera(null)
    }

    fun attachLocal(renderer: SurfaceViewRenderer) = attach(renderer, local = true)
    fun attachRemote(renderer: SurfaceViewRenderer) = attach(renderer, local = false)

    private fun attach(sink: VideoSink, local: Boolean) {
        synchronized(pendingSinks) { pendingSinks += sink to local }
        (if (local) localTrack else remoteTrack)?.addSink(sink)
    }

    fun detach(renderer: SurfaceViewRenderer) {
        synchronized(pendingSinks) { pendingSinks.removeAll { it.first === renderer } }
        localTrack?.removeSink(renderer)
        remoteTrack?.removeSink(renderer)
    }

    suspend fun stop() {
        val wasLive = peer != null
        teardown()
        if (wasLive) runCatching { deleteStream() }
        _receivedFps.value = 0f
        if (_state.value !is RtcState.Failed) _state.value = RtcState.Idle
    }

    private suspend fun fail(message: String) {
        teardown()
        runCatching { deleteStream() }
        _state.value = RtcState.Failed(message)
    }

    private fun teardown() {
        stillPump?.cancel()
        fpsMeter?.cancel()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        remoteTrack?.removeSink(countingSink)
        peer?.dispose()
        peer = null
        localTrack = null
        remoteTrack = null
        audioTrack = null
        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null
        textureHelper?.dispose()
        textureHelper = null
    }

    fun release() {
        teardown()
        scope.cancel()
        eglBase.release()
    }

    private fun createFactory(): PeerConnectionFactory {
        initializeOnce(context)
        val audioDevice = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDevice)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gatheringDone.complete(Unit)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED) {
                scope.launch { fail("The live stream lost its connection to your PC.") }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    companion object {
        private const val GATHER_TIMEOUT_MS = 3000L
        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720
        private const val CAPTURE_FPS = 30
        @Volatile private var initialized = false

        private fun initializeOnce(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
                    initialized = true
                }
            }
        }
    }
}

private suspend fun PeerConnection.awaitOffer(): SessionDescription = suspendCancellableCoroutine { continuation ->
    createOffer(object : SimpleSdpObserver() {
        override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
        override fun onCreateFailure(error: String?) {
            continuation.resumeWithException(RtcSetupException("WebRTC refused the offer: $error"))
        }
    }, MediaConstraints())
}

private suspend fun PeerConnection.awaitLocal(description: SessionDescription) = suspendCancellableCoroutine { continuation ->
    setLocalDescription(object : SimpleSdpObserver() {
        override fun onSetSuccess() = continuation.resume(Unit)
        override fun onSetFailure(error: String?) {
            continuation.resumeWithException(RtcSetupException("WebRTC refused the offer: $error"))
        }
    }, description)
}

private suspend fun PeerConnection.awaitRemote(description: SessionDescription) = suspendCancellableCoroutine { continuation ->
    setRemoteDescription(object : SimpleSdpObserver() {
        override fun onSetSuccess() = continuation.resume(Unit)
        override fun onSetFailure(error: String?) {
            continuation.resumeWithException(RtcSetupException("The PC's answer was not accepted: $error"))
        }
    }, description)
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}

internal class RtcSetupException(message: String?) : Exception(message)

/** Converts a still to an I420 buffer once; the pump re-sends the same buffer. */
internal object StillFrames {
    fun toI420(source: Bitmap): JavaI420Buffer {
        val width = source.width and 1.inv()
        val height = source.height and 1.inv()
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val buffer = JavaI420Buffer.allocate(width, height)
        val y = buffer.dataY
        val u = buffer.dataU
        val v = buffer.dataV
        for (row in 0 until height) {
            for (column in 0 until width) {
                val color = pixels[row * width + column]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                y.put(row * buffer.strideY + column, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (row % 2 == 0 && column % 2 == 0) {
                    val index = (row / 2) * buffer.strideU + column / 2
                    u.put(index, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                    v.put((row / 2) * buffer.strideV + column / 2, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                }
            }
        }
        return buffer
    }
}
