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

    fun setupCapturer(helper: SurfaceTextureHelper, context: android.content.Context, observer: CapturerObserver) {
        this.surfaceHelper = helper
        this.capturerObserver = observer
    }

    /** Call this from the UVCCamera frame callback with each new frame. */
    fun pushFrame(frameBuffer: ByteBuffer, width: Int, height: Int) {
        val nv21Data = ByteArray(frameBuffer.remaining())
        frameBuffer.get(nv21Data)

        // Use NV21Buffer to wrap the raw data for WebRTC consumption.
        // If NV21Buffer is missing in your WebRTC version, you may need a manual implementation.
        val buffer = NV21Buffer(nv21Data, width, height, null)
        val frame = VideoFrame(buffer, 0, System.nanoTime())
        if (capturerObserver != null) {
            Log.d("CamStream", "WEBRTC FRAME PUSHING: ${width}x${height}")
            capturerObserver!!.onFrameCaptured(frame)
        } else {
            Log.e("CamStream", "WEBRTC ERROR: capturerObserver is NULL")
        }

        frame.release()
    }

    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper?, applicationContext: android.content.Context?, capturerObserver: CapturerObserver?) {
        this.capturerObserver = capturerObserver
        this.surfaceHelper = surfaceTextureHelper
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {}
    override fun stopCapture() {}
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {}
    override fun dispose() {}
    override fun isScreencast(): Boolean = false
}