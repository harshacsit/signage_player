package com.signage.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.webrtc.*

class LiveViewService : Service() {

    companion object {
        private const val TAG = "LiveView"
        private const val CAM_TAG = "CamStream"

        const val EXTRA_SCREEN_ID = "screen_id"
    }

    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY

    private lateinit var pairingId: String

    private val serviceScope =
        CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Cloudflare Worker endpoint that returns short-lived ICE/TURN credentials.
    // Replace this with your deployed Worker URL or pull it from BuildConfig (via local.properties).
    private val turnCredentialsUrl = BuildConfig.TURN_WORKER_URL.ifBlank {
        "https://turn-credentials-worker.bhimavaram-signage.workers.dev"
    }

    private val httpClient by lazy { OkHttpClient() }

    private lateinit var usbMonitor: USBMonitor
    private var uvcCamera: UVCCamera? = null

    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory

    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var customCapturer: UvcFrameVideoCapturer? = null

    private lateinit var surfaceHelper: SurfaceTextureHelper

    private val connections =
        mutableMapOf<String, PeerConnection>()

    private val supabase by lazy {
        createSupabaseClient(
            supabaseUrl,
            supabaseAnonKey
        ) {
            install(Realtime)
        }
    }

    private val channel by lazy {
        supabase.channel("signal-$pairingId")
    }

    // ------------------------------------------------------------
    // SERVICE START
    // ------------------------------------------------------------

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(TAG, "LIVEVIEW: onStartCommand")

        Log.d(
            TAG,
            "SUPABASE URL present = ${supabaseUrl.isNotBlank()}"
        )

        Log.d(
            TAG,
            "SUPABASE KEY present = ${supabaseAnonKey.isNotBlank()}"
        )

        startForegroundWithNotification()

        try {
            if (
                supabaseUrl.isBlank() ||
                supabaseAnonKey.isBlank()
            ) {
                Log.e(
                    TAG,
                    "Supabase URL/key not configured"
                )

                stopSelf()

                return START_NOT_STICKY
            }

            pairingId =
                intent?.getStringExtra(EXTRA_SCREEN_ID)
                    ?: "unknown-screen"

            Log.d(
                TAG,
                "PAIRING ID = $pairingId"
            )

            // Core WebRTC objects are created here.
            // ICE/TURN servers are fetched later for each viewer.
            initWebRtcCore()

            Log.d(
                TAG,
                "LIVEVIEW: WebRTC core initialized"
            )

            initUsbMonitor()

            Log.d(
                TAG,
                "LIVEVIEW: USB monitor initialized"
            )

            serviceScope.launch {
                setupSignaling()
            }

        } catch (e: Exception) {
            Log.e(
                TAG,
                "LiveView failed to start",
                e
            )

            stopSelf()
        }

        return START_STICKY
    }

    // ------------------------------------------------------------
    // FOREGROUND SERVICE
    // ------------------------------------------------------------

    private fun startForegroundWithNotification() {

        val channelId = "liveview_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val nc = NotificationChannel(
                channelId,
                "Live Camera",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(nc)
        }

        val notification =
            NotificationCompat.Builder(
                this,
                channelId
            )
                .setContentTitle("Live View Active")
                .setSmallIcon(
                    android.R.drawable.ic_menu_camera
                )
                .setPriority(
                    NotificationCompat.PRIORITY_LOW
                )
                .build()

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {

            startForeground(
                2,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_CAMERA
            )

        } else {

            startForeground(
                2,
                notification
            )
        }
    }

    // ------------------------------------------------------------
    // WEBRTC INITIALIZATION
    // ------------------------------------------------------------

    private fun initWebRtcCore() {

        Log.d(
            CAM_TAG,
            "========== WEBRTC CORE INITIALIZATION =========="
        )

        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory
                .InitializationOptions
                .builder(applicationContext)
                .createInitializationOptions()
        )

        peerConnectionFactory =
            PeerConnectionFactory.builder()

                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(
                        eglBase.eglBaseContext,
                        true,
                        true
                    )
                )

                .setVideoDecoderFactory(
                    DefaultVideoDecoderFactory(
                        eglBase.eglBaseContext
                    )
                )

                .createPeerConnectionFactory()

        Log.d(
            CAM_TAG,
            "PEER CONNECTION FACTORY CREATED"
        )

        customCapturer =
            UvcFrameVideoCapturer()

        surfaceHelper =
            SurfaceTextureHelper.create(
                "LiveViewCapture",
                eglBase.eglBaseContext
            )

        Log.d(
            CAM_TAG,
            "SURFACE TEXTURE HELPER CREATED"
        )

        videoSource =
            peerConnectionFactory.createVideoSource(false)

        Log.d(
            CAM_TAG,
            "VIDEO SOURCE CREATED"
        )

        customCapturer!!.setupCapturer(
            surfaceHelper,
            applicationContext,
            videoSource!!.capturerObserver
        )

        Log.d(
            CAM_TAG,
            "CAPTURER OBSERVER CONNECTED"
        )

        videoTrack =
            peerConnectionFactory.createVideoTrack(
                "cam_track_$pairingId",
                videoSource
            )

        Log.d(
            CAM_TAG,
            "VIDEO TRACK CREATED: " +
                    "enabled=${videoTrack?.enabled()}, " +
                    "id=${videoTrack?.id()}"
        )
    }

    // ------------------------------------------------------------
    // USB MONITOR
    // ------------------------------------------------------------

    private fun initUsbMonitor() {

        Log.d(
            TAG,
            "LIVEVIEW: initUsbMonitor ENTERED"
        )

        usbMonitor =
            USBMonitor(
                this,
                object : USBMonitor.OnDeviceConnectListener {

                    override fun onAttach(
                        device: UsbDevice
                    ) {

                        Log.d(
                            TAG,
                            "USB DEVICE ATTACHED: ${device.deviceName}"
                        )

                        usbMonitor.requestPermission(
                            device
                        )
                    }

                    override fun onConnect(
                        device: UsbDevice,
                        ctrlBlock: USBMonitor.UsbControlBlock,
                        createNew: Boolean
                    ) {

                        Log.d(
                            TAG,
                            "USB DEVICE CONNECTED: ${device.deviceName}"
                        )

                        openCamera(ctrlBlock)
                    }

                    override fun onDisconnect(
                        device: UsbDevice,
                        ctrlBlock: USBMonitor.UsbControlBlock
                    ) {

                        Log.d(
                            TAG,
                            "USB DEVICE DISCONNECTED"
                        )

                        uvcCamera?.stopPreview()
                        uvcCamera?.destroy()
                        uvcCamera = null
                    }

                    override fun onDettach(
                        device: UsbDevice
                    ) {

                        Log.d(
                            TAG,
                            "USB DEVICE DETACHED"
                        )
                    }

                    override fun onCancel(
                        device: UsbDevice
                    ) {

                        Log.d(
                            TAG,
                            "USB PERMISSION CANCELLED"
                        )
                    }
                }
            )

        usbMonitor.register()

        val usbManager =
            getSystemService(
                Context.USB_SERVICE
            ) as UsbManager

        usbManager.deviceList.values.forEach {

            Log.d(
                TAG,
                "REQUESTING USB PERMISSION: ${it.deviceName}"
            )

            usbMonitor.requestPermission(it)
        }
    }

    // ------------------------------------------------------------
    // OPEN UVC CAMERA
    // ------------------------------------------------------------

    private fun openCamera(
        ctrlBlock: USBMonitor.UsbControlBlock
    ) {

        if (uvcCamera != null) {

            Log.d(
                TAG,
                "UVC CAMERA ALREADY OPEN"
            )

            return
        }

        try {

            val camera = UVCCamera()

            camera.open(ctrlBlock)

            Log.d(
                TAG,
                "UVC CAMERA OPENED"
            )

            camera.setPreviewSize(
                640,
                480,
                UVCCamera.FRAME_FORMAT_MJPEG
            )

            Log.d(
                TAG,
                "UVC PREVIEW SIZE SET: 640x480"
            )

            val texture =
                surfaceHelper.surfaceTexture

            Log.d(
                TAG,
                "UVC SURFACE TEXTURE = $texture"
            )

            if (texture == null) {

                Log.e(
                    TAG,
                    "UVC ERROR: SurfaceTexture IS NULL"
                )

                camera.close()
                camera.destroy()

                return
            }

            camera.setPreviewTexture(texture)

            Log.d(
                TAG,
                "UVC PREVIEW TEXTURE SET"
            )

            camera.setFrameCallback(
                { frameData ->

                    Log.d(
                        CAM_TAG,
                        "UVC FRAME RECEIVED: " +
                                "${frameData.remaining()} bytes"
                    )

                    customCapturer?.pushFrame(
                        frameData,
                        640,
                        480
                    )
                },
                UVCCamera.PIXEL_FORMAT_NV21
            )

            Log.d(
                TAG,
                "UVC FRAME CALLBACK SET"
            )

            camera.startPreview()

            Log.d(
                TAG,
                "UVC startPreview() CALLED"
            )

            uvcCamera = camera

        } catch (e: Exception) {

            Log.e(
                TAG,
                "UVC CAMERA ERROR",
                e
            )
        }
    }

    // ------------------------------------------------------------
    // SIGNALING
    // ------------------------------------------------------------

    private suspend fun setupSignaling() {

        Log.d(
            TAG,
            "SIGNALING: SUBSCRIBING"
        )

        channel.subscribe()

        Log.d(
            TAG,
            "SIGNALING: SUBSCRIBE CALLED"
        )

        // --------------------------------------------------------
        // VIEWER READY
        // --------------------------------------------------------

        serviceScope.launch {

            channel
                .broadcastFlow<JsonObject>(
                    "viewer-ready"
                )
                .collect { payload ->

                    val viewerId =
                        payload["viewerId"]
                            ?.jsonPrimitive
                            ?.content
                            ?: return@collect

                    Log.d(
                        TAG,
                        "VIEWER-READY RECEIVED: $viewerId"
                    )

                    if (connections.containsKey(viewerId)) {

                        Log.d(
                            TAG,
                            "CONNECTION ALREADY EXISTS: $viewerId"
                        )

                        return@collect
                    }

                    Log.d(
                        TAG,
                        "CREATING CONNECTION FOR VIEWER: $viewerId"
                    )

                    createConnectionForViewer(
                        viewerId
                    )
                }
        }

        // --------------------------------------------------------
        // ANSWER
        // --------------------------------------------------------

        serviceScope.launch {

            channel
                .broadcastFlow<JsonObject>(
                    "answer"
                )
                .collect { payload ->

                    Log.d(
                        TAG,
                        "ANSWER RECEIVED: $payload"
                    )

                    val viewerId =
                        payload["viewerId"]
                            ?.jsonPrimitive
                            ?.content
                            ?: return@collect

                    val pc =
                        connections[viewerId]

                    if (pc == null) {

                        Log.e(
                            CAM_TAG,
                            "ANSWER RECEIVED BUT PC NOT FOUND: $viewerId"
                        )

                        return@collect
                    }

                    /*
                     * Dashboard sends:
                     *
                     * {
                     *   viewerId: "...",
                     *   sdp: JSON.stringify(localDescription)
                     * }
                     *
                     * Therefore Android must read payload["sdp"].
                     */

                    val sdpElement = payload["sdp"]

                    if (sdpElement == null) {

                        Log.e(
                            CAM_TAG,
                            "ANSWER SDP NOT FOUND"
                        )

                        Log.e(
                            CAM_TAG,
                            "ANSWER PAYLOAD = $payload"
                        )

                        return@collect
                    }

                    Log.d(
                        CAM_TAG,
                        "ANSWER SDP RECEIVED"
                    )

                    /*
                     * Dashboard JSON.stringify() produces:
                     *
                     * {
                     *   "type":"answer",
                     *   "sdp":"..."
                     * }
                     */

                    try {

                        val answerJson = when (sdpElement) {
                            is JsonObject -> sdpElement
                            is JsonPrimitive -> Json.parseToJsonElement(sdpElement.content) as? JsonObject
                            else -> null
                        }

                        if (answerJson == null) {
                            Log.e(
                                CAM_TAG,
                                "ANSWER SDP COULD NOT BE PARSED AS JSON OBJECT"
                            )
                            return@collect
                        }

                        val actualSdp =
                            answerJson["sdp"]
                                ?.jsonPrimitive
                                ?.content

                        if (actualSdp.isNullOrBlank()) {

                            Log.e(
                                CAM_TAG,
                                "PARSED ANSWER DOES NOT CONTAIN SDP"
                            )

                            return@collect
                        }

                        Log.d(
                            CAM_TAG,
                            "SETTING REMOTE DESCRIPTION"
                        )

                        pc.setRemoteDescription(
                            object : SdpObserverAdapter() {

                                override fun onSetSuccess() {

                                    Log.d(
                                        CAM_TAG,
                                        "REMOTE DESCRIPTION SET SUCCESS"
                                    )
                                }

                                override fun onSetFailure(
                                    error: String?
                                ) {

                                    Log.e(
                                        CAM_TAG,
                                        "REMOTE DESCRIPTION SET FAILED: $error"
                                    )
                                }
                            },
                            SessionDescription(
                                SessionDescription.Type.ANSWER,
                                actualSdp
                            )
                        )

                    } catch (e: Exception) {

                        Log.e(
                            CAM_TAG,
                            "ANSWER PARSE ERROR",
                            e
                        )
                    }
                }
        }

        // --------------------------------------------------------
        // ICE CANDIDATE
        // --------------------------------------------------------

        serviceScope.launch {

            channel
                .broadcastFlow<JsonObject>(
                    "ice-candidate"
                )
                .collect { payload ->



                    val viewerId =
                        payload["viewerId"]
                            ?.jsonPrimitive
                            ?.content
                            ?: return@collect

                    val pc =
                        connections[viewerId]
                            ?: return@collect

                    val candidateElement = payload["candidate"]
                    if (candidateElement == null) {
                        Log.e(
                            CAM_TAG,
                            "ICE CANDIDATE NOT FOUND IN PAYLOAD"
                        )
                        return@collect
                    }

                    val cand = try {
                        when (candidateElement) {
                            is JsonObject -> candidateElement
                            is JsonPrimitive -> Json.parseToJsonElement(candidateElement.content) as? JsonObject
                            else -> null
                        }
                    } catch (e: Exception) {
                        Log.e(CAM_TAG, "Failed to parse candidate element", e)
                        null
                    }

                    if (cand == null) {
                        Log.e(
                            CAM_TAG,
                            "ICE CANDIDATE COULD NOT BE PARSED AS JSON OBJECT"
                        )
                        return@collect
                    }

                    val candidate =
                        cand["candidate"]
                            ?.jsonPrimitive
                            ?.content

                    val sdpMid =
                        cand["sdpMid"]
                            ?.jsonPrimitive
                            ?.content
                            ?: ""

                    val sdpMLineIndex =
                        cand["sdpMLineIndex"]
                            ?.jsonPrimitive
                            ?.int
                            ?: 0

                    if (candidate.isNullOrBlank()) {

                        Log.e(
                            CAM_TAG,
                            "EMPTY VIEWER ICE CANDIDATE"
                        )

                        return@collect
                    }

                    Log.d(
                        CAM_TAG,
                        "VIEWER ICE RECEIVED: $viewerId"
                    )

                    pc.addIceCandidate(
                        IceCandidate(
                            sdpMid,
                            sdpMLineIndex,
                            candidate
                        )
                    )
                }
        }
    }

    // ------------------------------------------------------------
    // CREATE PEER CONNECTION
    // ------------------------------------------------------------

    private fun createConnectionForViewer(
        viewerId: String
    ) {

        // Fetch fresh ICE/TURN credentials before creating this viewer's
        // PeerConnection. The network request runs off the main thread.
        serviceScope.launch {

            Log.d(
                CAM_TAG,
                "========================================"
            )

            Log.d(
                CAM_TAG,
                "CREATE CONNECTION START: $viewerId"
            )

            val iceServers = fetchIceServers()

            Log.d(
                CAM_TAG,
                "ICE SERVERS READY: ${iceServers.size} entries for $viewerId"
            )

            val rtcConfig =
                PeerConnection.RTCConfiguration(iceServers).apply {
                    iceTransportsType =
                        PeerConnection.IceTransportsType.ALL

                    bundlePolicy =
                        PeerConnection.BundlePolicy.MAXBUNDLE

                    rtcpMuxPolicy =
                        PeerConnection.RtcpMuxPolicy.REQUIRE
                }

            val track =
                videoTrack

            if (track == null) {

                Log.e(
                    CAM_TAG,
                    "VIDEO TRACK IS NULL - CANNOT CREATE CONNECTION"
                )

                return@launch
            }

            Log.d(
                CAM_TAG,
                "VIDEO TRACK BEFORE ADD: " +
                        "enabled=${track.enabled()}, " +
                        "id=${track.id()}"
            )

            val pc =
                peerConnectionFactory
                    .createPeerConnection(
                        rtcConfig,
                        object :
                            PeerConnectionObserverAdapter() {

                            override fun onIceCandidate(
                                candidate: IceCandidate
                            ) {

                                Log.d(
                                    CAM_TAG,
                                    "ANDROID ICE CANDIDATE CREATED"
                                )

                                serviceScope.launch {

                                    channel.broadcast(
                                        "ice-candidate",
                                        buildJsonObject {

                                            put(
                                                "viewerId",
                                                viewerId
                                            )

                                            put(
                                                "from",
                                                "broadcaster"
                                            )

                                            put(
                                                "candidate",
                                                buildJsonObject {

                                                    put(
                                                        "candidate",
                                                        candidate.sdp
                                                    )

                                                    put(
                                                        "sdpMid",
                                                        candidate.sdpMid
                                                    )

                                                    put(
                                                        "sdpMLineIndex",
                                                        candidate.sdpMLineIndex
                                                    )
                                                }
                                            )
                                        }
                                    )

                                    Log.d(
                                        CAM_TAG,
                                        "ANDROID ICE CANDIDATE SENT: $viewerId"
                                    )
                                }
                            }

                            override fun onIceConnectionChange(
                                state: PeerConnection.IceConnectionState?
                            ) {

                                Log.d(
                                    CAM_TAG,
                                    "ANDROID ICE STATE: $state"
                                )
                            }

                            override fun onConnectionChange(
                                newState: PeerConnection.PeerConnectionState?
                            ) {

                                Log.d(
                                    CAM_TAG,
                                    "ANDROID CONNECTION STATE: $newState"
                                )
                            }

                            override fun onSignalingChange(
                                state: PeerConnection.SignalingState?
                            ) {

                                Log.d(
                                    CAM_TAG,
                                    "ANDROID SIGNALING STATE: $state"
                                )
                            }

                            override fun onIceGatheringChange(
                                state: PeerConnection.IceGatheringState?
                            ) {

                                Log.d(
                                    CAM_TAG,
                                    "ANDROID ICE GATHERING: $state"
                                )
                            }
                        }
                    )

            if (pc == null) {

                Log.e(
                    CAM_TAG,
                    "PEER CONNECTION CREATION FAILED"
                )

                return@launch
            }

            Log.d(
                CAM_TAG,
                "PEER CONNECTION CREATED"
            )

            // --------------------------------------------------------
            // ADD VIDEO TRACK
            // --------------------------------------------------------

            val sender =
                pc.addTrack(track)

            Log.d(
                CAM_TAG,
                "VIDEO TRACK ADDED: " +
                        "enabled=${track.enabled()}, " +
                        "id=${track.id()}"
            )

            Log.d(
                CAM_TAG,
                "RTP SENDER CREATED: $sender"
            )

            connections[viewerId] = pc

            // --------------------------------------------------------
            // RTP STATS
            // --------------------------------------------------------

            serviceScope.launch {

                while (
                    connections[viewerId] === pc
                ) {

                    delay(2000)

                    try {

                        pc.getStats { stats ->

                            var foundOutbound =
                                false

                            for (
                            report
                            in stats.statsMap.values
                            ) {

                                if (
                                    report.type ==
                                    "outbound-rtp"
                                ) {

                                    foundOutbound = true

                                    Log.d(
                                        CAM_TAG,
                                        "ANDROID OUTBOUND RTP: " +
                                                "kind=${report.members["kind"]} " +
                                                "mediaType=${report.members["mediaType"]} " +
                                                "packetsSent=${report.members["packetsSent"]} " +
                                                "bytesSent=${report.members["bytesSent"]} " +
                                                "framesEncoded=${report.members["framesEncoded"]} " +
                                                "framesSent=${report.members["framesSent"]}"
                                    )
                                }

                                if (
                                    report.type ==
                                    "media-source"
                                ) {

                                    Log.d(
                                        CAM_TAG,
                                        "ANDROID MEDIA SOURCE: " +
                                                "kind=${report.members["kind"]} " +
                                                "width=${report.members["width"]} " +
                                                "height=${report.members["height"]} " +
                                                "frames=${report.members["frames"]}"
                                    )
                                }
                            }

                            if (!foundOutbound) {

                                Log.d(
                                    CAM_TAG,
                                    "NO OUTBOUND RTP REPORT FOUND"
                                )
                            }
                        }

                    } catch (e: Exception) {

                        Log.e(
                            CAM_TAG,
                            "ANDROID STATS ERROR",
                            e
                        )
                    }
                }
            }

            // --------------------------------------------------------
            // CREATE OFFER
            // --------------------------------------------------------

            pc.createOffer(
                object : SdpObserverAdapter() {

                    override fun onCreateSuccess(
                        desc: SessionDescription?
                    ) {

                        if (desc == null) {

                            Log.e(
                                CAM_TAG,
                                "OFFER CREATED BUT DESC IS NULL"
                            )

                            return
                        }

                        Log.d(
                            CAM_TAG,
                            "OFFER CREATED"
                        )

                        Log.d(
                            CAM_TAG,
                            "OFFER SDP LENGTH = ${desc.description.length}"
                        )

                        pc.setLocalDescription(
                            object : SdpObserverAdapter() {

                                override fun onSetSuccess() {

                                    Log.d(
                                        CAM_TAG,
                                        "LOCAL DESCRIPTION SET SUCCESS"
                                    )
                                }

                                override fun onSetFailure(
                                    error: String?
                                ) {

                                    Log.e(
                                        CAM_TAG,
                                        "LOCAL DESCRIPTION SET FAILED: $error"
                                    )
                                }
                            },
                            desc
                        )

                        serviceScope.launch {

                            Log.d(
                                CAM_TAG,
                                "SENDING OFFER: $viewerId"
                            )

                            channel.broadcast(
                                "offer",
                                buildJsonObject {

                                    put(
                                        "viewerId",
                                        viewerId
                                    )

                                    put(
                                        "offer",
                                        buildJsonObject {

                                            put(
                                                "type",
                                                "offer"
                                            )

                                            put(
                                                "sdp",
                                                desc.description
                                            )
                                        }
                                    )
                                }
                            )

                            Log.d(
                                CAM_TAG,
                                "OFFER SENT: $viewerId"
                            )
                        }
                    }

                    override fun onCreateFailure(
                        error: String?
                    ) {

                        Log.e(
                            CAM_TAG,
                            "OFFER CREATE FAILED: $error"
                        )
                    }
                },
                MediaConstraints()
            )
        }
    }

    // ------------------------------------------------------------
    // TURN / ICE SERVER CREDENTIALS
    // ------------------------------------------------------------

    /**
     * Fetch short-lived ICE/TURN credentials from the Cloudflare Worker.
     *
     * Expected Worker response:
     *
     * {
     *   "iceServers": [
     *     {
     *       "urls": [
     *         "turn:...",
     *         "turns:..."
     *       ],
     *       "username": "...",
     *       "credential": "..."
     *     }
     *   ]
     * }
     *
     * The TURN username/password are never hardcoded in this Android app.
     * If the Worker cannot be reached or returns invalid data, we fall back
     * to Google STUN so direct WebRTC connections can still be attempted.
     */
    private suspend fun fetchIceServers():
            List<PeerConnection.IceServer> {

        return withContext(Dispatchers.IO) {

            try {

                Log.d(
                    CAM_TAG,
                    "FETCHING ICE SERVERS FROM WORKER"
                )

                val request =
                    Request.Builder()
                        .url(turnCredentialsUrl)
                        .get()
                        .build()

                httpClient
                    .newCall(request)
                    .execute()
                    .use { response ->

                        if (!response.isSuccessful) {

                            throw IllegalStateException(
                                "TURN credential request failed: " +
                                        "HTTP ${response.code}"
                            )
                        }

                        val body =
                            response.body?.string()
                        Log.d(CAM_TAG, "TURN WORKER RAW RESPONSE = $body")

                        if (body.isNullOrBlank()) {

                            throw IllegalStateException(
                                "TURN credential response body is empty"
                            )
                        }

                        val json =
                            JSONObject(body)

                        val serversArray =
                            json.optJSONArray("iceServers")
                                ?: throw IllegalStateException(
                                    "Response does not contain iceServers"
                                )

                        val result =
                            mutableListOf<PeerConnection.IceServer>()

                        for (i in 0 until serversArray.length()) {

                            val entry =
                                serversArray.optJSONObject(i)
                                    ?: continue

                            val username =
                                entry.optString(
                                    "username",
                                    null
                                )

                            val credential =
                                entry.optString(
                                    "credential",
                                    null
                                )

                            val urls =
                                entry.optJSONArray("urls")

                            if (urls == null) {

                                // Accept a single string URL as well.
                                val singleUrl =
                                    entry.optString(
                                        "urls",
                                        ""
                                    )

                                if (singleUrl.isNotBlank()) {

                                    val builder =
                                        PeerConnection.IceServer
                                            .builder(singleUrl)

                                    if (!username.isNullOrBlank()) {
                                        builder.setUsername(username)
                                    }

                                    if (!credential.isNullOrBlank()) {
                                        builder.setPassword(credential)
                                    }

                                    result.add(
                                        builder.createIceServer()
                                    )
                                }

                                continue
                            }

                            // PeerConnection.IceServer.builder() accepts one
                            // URI, so create one IceServer for every URL.
                            for (j in 0 until urls.length()) {

                                val url =
                                    urls.optString(
                                        j,
                                        ""
                                    )

                                if (url.isBlank()) {
                                    continue
                                }

                                val builder =
                                    PeerConnection.IceServer
                                        .builder(url)

                                if (!username.isNullOrBlank()) {
                                    builder.setUsername(username)
                                }

                                if (!credential.isNullOrBlank()) {
                                    builder.setPassword(credential)
                                }

                                result.add(
                                    builder.createIceServer()
                                )
                            }
                        }

                        if (result.isEmpty()) {

                            throw IllegalStateException(
                                "Worker returned no usable ICE servers"
                            )
                        }

                        Log.d(
                            CAM_TAG,
                            "ICE SERVERS FETCHED SUCCESSFULLY: ${result.size}"
                        )

                        result
                    }

            } catch (e: Exception) {

                Log.e(
                    CAM_TAG,
                    "FAILED TO FETCH TURN CREDENTIALS - FALLING BACK TO STUN ONLY",
                    e
                )

                listOf(
                    PeerConnection.IceServer
                        .builder(
                            "stun:stun.l.google.com:19302"
                        )
                        .createIceServer()
                )
            }
        }
    }

    // ------------------------------------------------------------
    // SERVICE BINDING
    // ------------------------------------------------------------

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    // ------------------------------------------------------------
    // DESTROY
    // ------------------------------------------------------------

    override fun onDestroy() {

        Log.d(
            TAG,
            "LIVEVIEW SERVICE DESTROYING"
        )

        connections.values.forEach {

            try {
                it.close()
            } catch (_: Exception) {
            }
        }

        connections.clear()

        try {
            uvcCamera?.stopPreview()
        } catch (_: Exception) {
        }

        try {
            uvcCamera?.destroy()
        } catch (_: Exception) {
        }

        uvcCamera = null

        if (::usbMonitor.isInitialized) {

            try {
                usbMonitor.unregister()
            } catch (_: Exception) {
            }
        }

        if (::surfaceHelper.isInitialized) {

            try {
                surfaceHelper.dispose()
            } catch (_: Exception) {
}
        }

        try {
            peerConnectionFactory.dispose()
        } catch (_: Exception) {
        }

        try {
            eglBase.release()
        } catch (_: Exception) {
        }

        serviceScope.cancel()

        super.onDestroy()
    }
}
