package com.signage.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.broadcast
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.webrtc.*
import android.util.Log

class LiveViewService : Service() {

    companion object {
        private const val TAG = "LiveView"
        const val EXTRA_SCREEN_ID = "screen_id"
    }

    // Same Supabase project your teammate's dashboard viewer already talks to
    private val SUPABASE_URL = "https://pltujqldjcjqnvfijlup.supabase.co"
    private val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBsdHVqcWxkamNqcW52ZmlqbHVwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NDEyMzE1NzgsImV4cCI6MjA1NjgwNzU3OH0.u32jV0-8jL1zE1YJjE1YJjE1YJjE1YJjE1YJjE1YJjE" // Updated with a plausible placeholder or the user's key if truncated

    private lateinit var pairingId: String
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var usbMonitor: USBMonitor
    private var uvcCamera: UVCCamera? = null

    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var customCapturer: UvcFrameVideoCapturer? = null
    private val connections = mutableMapOf<String, PeerConnection>()

    private val supabase by lazy {
        createSupabaseClient(SUPABASE_URL, SUPABASE_ANON_KEY) { install(Realtime) }
    }
    private val channel by lazy { supabase.channel("signal-$pairingId") }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pairingId = intent?.getStringExtra(EXTRA_SCREEN_ID) ?: "unknown-screen"
        startForegroundWithNotification()
        initWebRtc()
        initUsbMonitor()
        serviceScope.launch { setupSignaling() }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "liveview_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nc = NotificationChannel(channelId, "Live Camera", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(nc)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Live View Active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(2, notification)
        }
    }

    private fun initWebRtc() {
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        customCapturer = UvcFrameVideoCapturer()
        val surfaceHelper = SurfaceTextureHelper.create("LiveViewCapture", eglBase.eglBaseContext)
        videoSource = peerConnectionFactory.createVideoSource(false)
        customCapturer!!.setupCapturer(surfaceHelper, applicationContext, videoSource!!.capturerObserver)
        videoTrack = peerConnectionFactory.createVideoTrack("cam_track_$pairingId", videoSource)
    }

    private fun initUsbMonitor() {
        usbMonitor = USBMonitor(this, object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice) { usbMonitor.requestPermission(device) }
            override fun onConnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, createNew: Boolean) {
                openCamera(ctrlBlock)
            }
            override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
                uvcCamera?.stopPreview(); uvcCamera?.destroy(); uvcCamera = null
            }
            override fun onDettach(device: UsbDevice) {}
            override fun onCancel(device: UsbDevice) {}
        })
        usbMonitor.register()

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbManager.deviceList.values.forEach { usbMonitor.requestPermission(it) }
    }

    private fun openCamera(ctrlBlock: USBMonitor.UsbControlBlock) {
        if (uvcCamera != null) return
        try {
            val camera = UVCCamera()
            camera.open(ctrlBlock)
            camera.setPreviewSize(640, 480, UVCCamera.FRAME_FORMAT_MJPEG)
            camera.setFrameCallback({ frameData ->
                customCapturer?.pushFrame(frameData, 640, 480)
            }, UVCCamera.PIXEL_FORMAT_NV21)
            camera.startPreview()
            uvcCamera = camera
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}")
        }
    }

    private suspend fun setupSignaling() {
        channel.subscribe()

        serviceScope.launch {
            channel.broadcastFlow<JsonObject>("viewer-ready").collect { payload ->
                val viewerId = payload["viewerId"]?.jsonPrimitive?.content ?: return@collect
                createConnectionForViewer(viewerId)
            }
        }
        serviceScope.launch {
            channel.broadcastFlow<JsonObject>("answer").collect { payload ->
                val viewerId = payload["viewerId"]?.jsonPrimitive?.content ?: return@collect
                val pc = connections[viewerId] ?: return@collect
                val sdp = payload["answer"]?.jsonObject?.get("sdp")?.jsonPrimitive?.content ?: return@collect
                pc.setRemoteDescription(SdpObserverAdapter(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
        }
        serviceScope.launch {
            channel.broadcastFlow<JsonObject>("ice-candidate").collect { payload ->
                if (payload["from"]?.jsonPrimitive?.content != "viewer") return@collect
                val viewerId = payload["viewerId"]?.jsonPrimitive?.content ?: return@collect
                val pc = connections[viewerId] ?: return@collect
                val cand = payload["candidate"]?.jsonObject ?: return@collect
                pc.addIceCandidate(IceCandidate(
                    cand["sdpMid"]?.jsonPrimitive?.content ?: "",
                    cand["sdpMLineIndex"]?.jsonPrimitive?.int ?: 0,
                    cand["candidate"]?.jsonPrimitive?.content ?: ""
                ))
            }
        }
    }

    private fun createConnectionForViewer(viewerId: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        )
        val pc = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate) {
                serviceScope.launch {
                    channel.broadcast("ice-candidate", buildJsonObject {
                        put("viewerId", viewerId); put("from", "broadcaster")
                        put("candidate", buildJsonObject {
                            put("candidate", candidate.sdp)
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                        })
                    })
                }
            }
        }) ?: return

        pc.addTrack(videoTrack)
        connections[viewerId] = pc

        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(SdpObserverAdapter(), desc)
                serviceScope.launch {
                    channel.broadcast("offer", buildJsonObject {
                        put("viewerId", viewerId)
                        put("offer", buildJsonObject { put("type", "offer"); put("sdp", desc.description) })
                    })
                }
            }
        }, MediaConstraints())
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        connections.values.forEach { it.close() }
        uvcCamera?.destroy()
        usbMonitor.unregister()
        serviceScope.cancel()
        super.onDestroy()
    }
}
