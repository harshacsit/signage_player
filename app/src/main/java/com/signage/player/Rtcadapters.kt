package com.signage.player

import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer

/** Empty-by-default SdpObserver so we only override the callbacks we actually need. */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {
        Log.e("CamStream", "SDP create failed: $error")
    }
    override fun onSetFailure(error: String?) {
        Log.e("CamStream", "SDP set failed: $error")
    }
}

/** Empty-by-default PeerConnection.Observer so we only override onIceCandidate. */
open class PeerConnectionObserverAdapter : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
    override fun onAddStream(stream: MediaStream?) {}
    override fun onRemoveStream(stream: MediaStream?) {}
    override fun onDataChannel(channel: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
}

/**
 * Bridges UVCCamera's raw frame callback into WebRTC's VideoCapturer interface.
 * This is the piece that replaces getUserMedia() from the browser version —
 * it takes each frame the USB webcam produces and hands it to WebRTC as if
 * it came from a normal camera.
 *
 * NOTE: not modified. startCapture()/stopCapture() are intentionally no-ops —
 * frames are pushed manually via pushFrame() from the UVCCamera callback in
 * LiveViewService instead of WebRTC's normal capture loop. This still needs
 * review/testing against real hardware for exact buffer/rotation handling —
 * left as-is rather than guessing at behavior I can't verify without a device.
 */
class UvcFrameVideoCapturer : VideoCapturer {

    private var capturerObserver: CapturerObserver? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var started = false

    fun setupCapturer(
        helper: SurfaceTextureHelper,
        context: android.content.Context,
        observer: CapturerObserver
    ) {
        surfaceHelper = helper
        capturerObserver = observer

        Log.d("CamStream", "CAPTURER OBSERVER SET")

        if (!started) {
            observer.onCapturerStarted(true)
            started = true

            Log.d(
                "CamStream",
                "CAPTURER STARTED SUCCESSFULLY"
            )
        }
    }

    fun pushFrame(
        frameBuffer: ByteBuffer,
        width: Int,
        height: Int
    ) {
        val observer = capturerObserver

        if (observer == null) {
            Log.e("CamStream", "CAPTURER OBSERVER IS NULL")
            return
        }

        if (!started) {
            Log.e("CamStream", "CAPTURER NOT STARTED")
            return
        }

        val nv21Data = ByteArray(frameBuffer.remaining())
        frameBuffer.get(nv21Data)

        Log.d(
            "CamStream",
            "WEBRTC FRAME PUSHING: ${width}x${height}, bytes=${nv21Data.size}"
        )

        val buffer = NV21Buffer(
            nv21Data,
            width,
            height,
            null
        )

        val frame = VideoFrame(
            buffer,
            0,
            System.nanoTime()
        )

        try {
            Log.d(
                "CamStream",
                "SENDING FRAME TO WEBRTC"
            )

            observer.onFrameCaptured(frame)

            Log.d(
                "CamStream",
                "FRAME SENT TO WEBRTC"
            )
        } catch (e: Exception) {
            Log.e(
                "CamStream",
                "FRAME DELIVERY FAILED",
                e
            )
        } finally {
            frame.release()
        }
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: android.content.Context?,
        capturerObserver: CapturerObserver?
    ) {
        this.surfaceHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver

        Log.d(
            "CamStream",
            "CAPTURER INITIALIZED"
        )
    }

    override fun startCapture(
        width: Int,
        height: Int,
        framerate: Int
    ) {
        Log.d(
            "CamStream",
            "startCapture: ${width}x${height} @ ${framerate}fps"
        )

        if (!started) {
            capturerObserver?.onCapturerStarted(true)
            started = true

            Log.d(
                "CamStream",
                "CAPTURER STARTED FROM startCapture"
            )
        }
    }

    override fun stopCapture() {
        Log.d(
            "CamStream",
            "stopCapture"
        )

        if (started) {
            capturerObserver?.onCapturerStopped()
            started = false
        }
    }

    override fun changeCaptureFormat(
        width: Int,
        height: Int,
        framerate: Int
    ) {
        Log.d(
            "CamStream",
            "changeCaptureFormat: ${width}x${height} @ ${framerate}fps"
        )
    }

    override fun dispose() {
        Log.d(
            "CamStream",
            "CAPTURER DISPOSE"
        )

        if (started) {
            capturerObserver?.onCapturerStopped()
            started = false
        }

        capturerObserver = null
        surfaceHelper = null
    }

    override fun isScreencast(): Boolean = false
}