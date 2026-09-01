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

    // FIXED: was hardcoded in source (and the key's signature segment looked
    // corrupted/repeating, not a real JWT signature). Now pulled from
    // BuildConfig, which app/build.gradle.kts populates from local.properties
    // / gradle -P properties / CI secrets — never committed to git.
    // Add to your local.properties:
    //   SUPABASE_URL=https://pltujqldjcjqnvfijlup.supabase.co
    //   SUPABASE_ANON_KEY=<a real anon key from your Supabase project settings>
    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY

    private lateinit var pairingId: String
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var usbMonitor: USBMonitor
    private var uvcCamera: UVCCamera? = null

    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var customCapturer: UvcFrameVideoCapturer? = null

    private lateinit var surfaceHelper: SurfaceTextureHelper
    private val connections = mutableMapOf<String, PeerConnection>()

    private val supabase by lazy {
        createSupabaseClient(supabaseUrl, supabaseAnonKey) { install(Realtime) }
    }
    private val channel by lazy { supabase.channel("signal-$pairingId") }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "LIVEVIEW: onStartCommand")
        Log.d(TAG, "SUPABASE URL present = ${supabaseUrl.isNotBlank()}")
        Log.d(TAG, "SUPABASE KEY present = ${supabaseAnonKey.isNotBlank()}")

        // FIXED: Always call startForeground first. 
        // ForegroundServiceDidNotStartInTimeException occurs if onStartCommand returns 
        // or the service is stopped before startForeground() is called.
        startForegroundWithNotification()

        // FIXED: guard against missing config instead of crashing deep inside
        // the Supabase client with a confusing error when local.properties
        // hasn't been set up on a given build machine / device.
        if (supabaseUrl.isBlank() || supabaseAnonKey.isBlank()) {
            Log.e(TAG, "Supabase URL/key not configured (check local.properties) — LiveView disabled.")
            stopSelf()
            return START_NOT_STICKY
        }

        pairingId = intent?.getStringExtra(EXTRA_SCREEN_ID) ?: "unknown-screen"
        initWebRtc()
        Log.d(TAG, "LIVEVIEW: WebRTC initialized")

        initUsbMonitor()
        Log.d(TAG, "LIVEVIEW: USB monitor initialized")
        serviceScope.launch { setupSignaling() }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "liveview_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // FIXED: was IMPORTANCE_MIN — some OEM skins (Realme/Kodak included)
            // can suppress IMPORTANCE_MIN notifications entirely, which puts the
            // foreground service at risk of being treated as improperly
            // foregrounded and killed. LOW keeps it silent but reliable.
            val nc = NotificationChannel(channelId, "Live Camera", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(nc)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Live View Active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
        surfaceHelper = SurfaceTextureHelper.create("LiveViewCapture", eglBase.eglBaseContext)
        videoSource = peerConnectionFactory.createVideoSource(false)
        customCapturer!!.setupCapturer(surfaceHelper, applicationContext, videoSource!!.capturerObserver)
        videoTrack = peerConnectionFactory.createVideoTrack("cam_track_$pairingId", videoSource)
    }

    private fun initUsbMonitor() {
        Log.d(TAG, "LIVEVIEW: initUsbMonitor ENTERED")

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

            Log.d(TAG, "UVC CAMERA OPENED")

            camera.setPreviewSize(
                640,
                480,
                UVCCamera.FRAME_FORMAT_MJPEG
            )

            Log.d(TAG, "UVC PREVIEW SIZE SET")

            val texture = surfaceHelper.surfaceTexture

            Log.d(TAG, "UVC SURFACE TEXTURE = $texture")

            if (texture == null) {
                Log.e(TAG, "UVC ERROR: SurfaceTexture is NULL")
                camera.close()
                camera.destroy()
                return
            }

            camera.setPreviewTexture(texture)

            Log.d(TAG, "UVC PREVIEW TEXTURE SET")

            camera.setFrameCallback(
                { frameData ->
                    Log.d(
                        TAG,
                        "UVC FRAME RECEIVED: ${frameData.remaining()} bytes"
                    )

                    customCapturer?.pushFrame(
                        frameData,
                        640,
                        480
                    )
                },
                UVCCamera.PIXEL_FORMAT_NV21
            )

            Log.d(TAG, "UVC FRAME CALLBACK SET")

            camera.startPreview()

            Log.d(TAG, "UVC startPreview() CALLED")

            uvcCamera = camera

        } catch (e: Exception) {
            Log.e(TAG, "UVC CAMERA ERROR", e)
        }
    }
    private suspend fun setupSignaling() {
        channel.subscribe()

        serviceScope.launch {
            channel.broadcastFlow<JsonObject>("viewer-ready").collect { payload ->
                val viewerId = payload["viewerId"]?.jsonPrimitive?.content ?: return@collect
                Log.d(TAG, "VIEWER-READY RECEIVED: $viewerId")
                Log.d(TAG, "CREATING CONNECTION FOR VIEWER: $viewerId")
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
        Log.d("CamStream", "CREATE CONNECTION START: $viewerId")
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
        Log.d("CamStream", "VIDEO TRACK ADDED")
        connections[viewerId] = pc
        serviceScope.launch {
            while (connections[viewerId] === pc) {
                delay(2000)

                pc.getStats { stats ->
                    for (report in stats.statsMap.values) {
                        if (report.type == "outbound-rtp") {
                            Log.d(
                                "CamStream",
                                "ANDROID OUTBOUND RTP: " +
                                        "kind=${report.members["kind"]} " +
                                        "mediaType=${report.members["mediaType"]} " +
                                        "packetsSent=${report.members["packetsSent"]} " +
                                        "bytesSent=${report.members["bytesSent"]} " +
                                        "framesEncoded=${report.members["framesEncoded"]}"
                            )
                        }
                    }
                }
            }
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return

                Log.d("CamStream", "OFFER CREATED")

                pc.setLocalDescription(SdpObserverAdapter(), desc)

                serviceScope.launch {
                    Log.d("CamStream", "SENDING OFFER: $viewerId")

                    channel.broadcast("offer", buildJsonObject {
                        put("viewerId", viewerId)
                        put("offer", buildJsonObject {
                            put("type", "offer")
                            put("sdp", desc.description)
                        })
                    })
                }
            }
        }, MediaConstraints())
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        connections.values.forEach { it.close() }
        uvcCamera?.destroy()
        if (::usbMonitor.isInitialized) {
            usbMonitor.unregister()
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
