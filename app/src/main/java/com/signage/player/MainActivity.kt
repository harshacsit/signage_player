package com.signage.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.load
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    companion object {
        // Single shared ExoPlayer cache for the whole app lifetime. Built lazily,
        // guarded so only one instance ever gets created even if onCreate() runs
        // again (e.g. after being relaunched by BootReceiver).
        @Volatile private var simpleCache: SimpleCache? = null

        fun getCache(context: Context): SimpleCache =
            simpleCache ?: synchronized(this) {
                simpleCache ?: SimpleCache(
                    File(context.cacheDir, "media_cache"),
                    LeastRecentlyUsedCacheEvictor(6L * 1024 * 1024 * 1024), // 6GB cap
                    StandaloneDatabaseProvider(context)
                ).also { simpleCache = it }
            }

        // Default bottom-zone height as a percentage, used when a screen doc
        // has layoutMode == "split" but no explicit splitRatio field yet.
        private const val DEFAULT_SPLIT_RATIO_PERCENT = 20
        private const val CAMERA_PERMISSION_REQUEST_CODE = 2001
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var db: FirebaseFirestore
    private lateinit var screenId: String

    private lateinit var pairingLayout: LinearLayout
    private lateinit var playerLayout: FrameLayout
    private lateinit var pairingCodeText: TextView

    // Top zone (full playlist: video / image / web / YouTube / HLS)
    private lateinit var topZone: FrameLayout
    private lateinit var videoView: PlayerView
    private lateinit var imageView: ImageView
    private lateinit var webView: WebView

    // Bottom zone (URL-based web strip, only present when layoutMode == "split")
    private lateinit var bottomZone: FrameLayout
    private lateinit var bottomWebView: WebView

    // Tracks the URL currently loaded into bottomWebView so a heartbeat-triggered
    // screen doc snapshot doesn't reload the page every ~5 minutes.
    private var lastBottomWebUrl: String? = null
    private var currentLayoutMode: String = "single"

    private lateinit var cacheDataSourceFactory: CacheDataSource.Factory

    private lateinit var liveDataSourceFactory: androidx.media3.datasource.DataSource.Factory
    private var exoPlayer: ExoPlayer? = null
    private var screenListener: ListenerRegistration? = null
    private var playlistListener: ListenerRegistration? = null

    private var playlistItems: List<Map<String, Any>> = emptyList()
    private var currentIndex = 0
    private val handler = Handler(Looper.getMainLooper())

    // Corrected interval: at ~40 screens, a 30s heartbeat blows past the
    // Firestore Spark free-tier write limit. 5 minutes keeps us well under it.
    private val heartbeatIntervalMs = 300_000L
    private var advanceRunnable: Runnable? = null

    // track which playlist we're already subscribed to, so a heartbeat-triggered
    // screen doc update doesn't tear down and rebuild the playlist listener every 5 min.
    private var lastPlaylistId: String? = null

    private var currentItemStartTime: Long = 0L
    private var currentItemForLogging: Map<String, Any>? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enableImmersiveKiosk()

        prefs = getSharedPreferences("signage_prefs", Context.MODE_PRIVATE)
        db = FirebaseFirestore.getInstance()

        db.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .build()

        pairingLayout = findViewById(R.id.pairingLayout)
        playerLayout = findViewById(R.id.playerLayout)
        pairingCodeText = findViewById(R.id.pairingCodeText)

        topZone = findViewById(R.id.topZone)
        videoView = findViewById(R.id.videoView)
        imageView = findViewById(R.id.imageView)
        webView = findViewById(R.id.webView)

        bottomZone = findViewById(R.id.bottomZone)
        bottomWebView = findViewById(R.id.bottomWebView)

        webView.setBackgroundColor(Color.BLACK)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        // Same setup as the main webView above, for the bottom strip. Bottom
        // zone visibility/height is driven entirely by explicit pixel values
        // set in Kotlin (applySplitLayout), NOT by XML layout_weight — a
        // weight-driven zero-height container makes WebView compositing fail
        // on Realme hardware. Keep this the way it is.
        bottomWebView.setBackgroundColor(Color.BLACK)
        bottomWebView.settings.javaScriptEnabled = true
        bottomWebView.settings.domStorageEnabled = true
        bottomWebView.settings.mediaPlaybackRequiresUserGesture = false
        bottomWebView.settings.loadWithOverviewMode = true
        bottomWebView.settings.useWideViewPort = true
        bottomWebView.webViewClient = WebViewClient()
        bottomWebView.webChromeClient = WebChromeClient()

        screenId = getOrCreateScreenId()
        pairingCodeText.text = screenId

        val userAgent = Util.getUserAgent(this, "SignagePlayer")

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(userAgent)

        liveDataSourceFactory = httpDataSourceFactory

        // Wraps network requests in a disk cache: first play downloads + saves,
        // every play after that (including with no network at all) reads from disk.
        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(getCache(this))
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val renderersFactory = DefaultRenderersFactory(this)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(cacheDataSourceFactory))
            .build().also { player ->
                videoView.player = player
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) advance()
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("SignagePlayer", "ExoPlayer error: \${error.errorCodeName} (\${error.errorCode}) - \${error.message}", error)
                        val cause = error.cause
                        if (cause != null) {
                            Log.e("SignagePlayer", "Caused by: \${cause.message}", cause)
                        }

                        Toast.makeText(
                            this@MainActivity,
                            "Playback error: \${error.errorCodeName}",
                            Toast.LENGTH_LONG
                        ).show()
                        handler.postDelayed({ advance() }, 3000)
                    }
                })
            }

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    registerScreenIfNeeded()
                    watchScreenDoc()
                    startHeartbeat()
                    startLiveView()
                }
                .addOnFailureListener { e ->
                    Log.e("SignagePlayer", "Anonymous sign-in failed", e)
                }
        } else {
            registerScreenIfNeeded()
            watchScreenDoc()
            startHeartbeat()
            startLiveView()
        }
    }

    private fun startLiveView() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
            return
        }
        val intent = Intent(this, LiveViewService::class.java)
            .putExtra(LiveViewService.EXTRA_SCREEN_ID, screenId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLiveView()
        }
    }

    private fun enableImmersiveKiosk() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
    }

    private fun applyScreenRotation(rotationDegrees: Int) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val params = playerLayout.layoutParams as FrameLayout.LayoutParams
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            params.width = screenHeight
            params.height = screenWidth
        } else {
            params.width = screenWidth
            params.height = screenHeight
        }
        params.gravity = Gravity.CENTER
        playerLayout.layoutParams = params
        playerLayout.pivotX = params.width / 2f
        playerLayout.pivotY = params.height / 2f
        playerLayout.rotation = rotationDegrees.toFloat()

        // Re-apply the split sizing every time rotation changes, since
        // topZone/bottomZone pixel heights are derived from playerLayout's
        // (post-rotation) height.
        applySplitLayout(currentLayoutMode)
    }

    private fun applyItemRotation(view: View, rotationDegrees: Int) {
        val playerParams = playerLayout.layoutParams as FrameLayout.LayoutParams
        val containerWidth = if (playerParams.width > 0) playerParams.width else resources.displayMetrics.widthPixels
        val containerHeight = if (playerParams.height > 0) playerParams.height else resources.displayMetrics.heightPixels

        val params = view.layoutParams as FrameLayout.LayoutParams
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            params.width = containerHeight
            params.height = containerWidth
        } else {
            params.width = containerWidth
            params.height = containerHeight
        }
        params.gravity = Gravity.CENTER
        view.layoutParams = params
        view.pivotX = params.width / 2f
        view.pivotY = params.height / 2f
        view.rotation = rotationDegrees.toFloat()
    }

    /**
     * Sizes topZone/bottomZone with EXPLICIT pixel heights computed here in
     * Kotlin. Do not switch this to XML layout_weight — a weight-driven
     * zero-height container makes WebView compositing silently fail on
     * Realme boxes (bottom strip renders blank/black even though the page
     * loads fine). splitRatioPercent is "percent of the layout the BOTTOM
     * zone occupies" — matches the dashboard's splitRatio field (10/20/30/40).
     */
    private fun applySplitLayout(layoutMode: String, splitRatioPercent: Int = DEFAULT_SPLIT_RATIO_PERCENT) {
        currentLayoutMode = layoutMode

        val playerParams = playerLayout.layoutParams as? FrameLayout.LayoutParams
        val totalHeight = playerParams?.height?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val totalWidth = playerParams?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

        // topZone/bottomZone sit inside the vertical LinearLayout in
        // activity_main.xml, so their layoutParams are LinearLayout.LayoutParams
        // — NOT FrameLayout.LayoutParams. Casting to the wrong type here was
        // throwing a ClassCastException on every launch once a screen was
        // already paired (crash right on open). Do not change this back.
        val topParams = topZone.layoutParams as LinearLayout.LayoutParams
        val bottomParams = bottomZone.layoutParams as LinearLayout.LayoutParams

        if (layoutMode == "split") {
            val clampedPercent = splitRatioPercent.coerceIn(10, 40)
            val bottomHeight = (totalHeight * clampedPercent) / 100
            val topHeight = totalHeight - bottomHeight

            topParams.width = totalWidth
            topParams.height = topHeight
            bottomParams.width = totalWidth
            bottomParams.height = bottomHeight

            bottomZone.visibility = View.VISIBLE
        } else {
            topParams.width = totalWidth
            topParams.height = totalHeight
            bottomParams.width = totalWidth
            bottomParams.height = 0

            bottomZone.visibility = View.GONE
            if (lastBottomWebUrl != null) {
                bottomWebView.loadUrl("about:blank")
                lastBottomWebUrl = null
            }
        }

        topZone.layoutParams = topParams
        bottomZone.layoutParams = bottomParams
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveKiosk()
    }

    private fun getOrCreateScreenId(): String {
        prefs.getString("screen_id", null)?.let { return it }
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        prefs.edit().putString("screen_id", code).apply()
        return code
    }

    private fun registerScreenIfNeeded() {
        val ref = db.collection("screens").document(screenId)
        ref.get().addOnSuccessListener { doc ->
            if (!doc.exists()) {
                ref.set(
                    mapOf(
                        "status" to "unpaired",
                        "createdAt" to Timestamp.now(),
                        "lastSeen" to Timestamp.now()
                    )
                )
            }
        }
    }

    private fun watchScreenDoc() {
        screenListener?.remove()
        screenListener = db.collection("screens").document(screenId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status")
                    if (status == "paired") {
                        pairingLayout.visibility = View.GONE
                        playerLayout.visibility = View.VISIBLE
                        val rotation = (snapshot.getLong("rotation") ?: 0L).toInt()
                        applyScreenRotation(rotation)

                        // ===== Bottom zone (split screen) =====
                        val layoutMode = snapshot.getString("layoutMode") ?: "single"
                        val bottomWebUrl = snapshot.getString("bottomWebUrl")
                        val splitRatio = (snapshot.getLong("splitRatio") ?: DEFAULT_SPLIT_RATIO_PERCENT.toLong()).toInt()

                        applySplitLayout(layoutMode, splitRatio)

                        if (layoutMode == "split" && bottomWebUrl != null) {
                            if (bottomWebUrl != lastBottomWebUrl) {
                                lastBottomWebUrl = bottomWebUrl
                                bottomWebView.loadUrl(bottomWebUrl)
                            }
                        } else if (lastBottomWebUrl != null) {
                            bottomWebView.loadUrl("about:blank")
                            lastBottomWebUrl = null
                        }

                        val playlistId = snapshot.getString("currentPlaylist")
                        if (playlistId != null && playlistId != lastPlaylistId) {
                            lastPlaylistId = playlistId
                            watchPlaylistDoc(playlistId)
                        }
                    } else {
                        pairingLayout.visibility = View.VISIBLE
                        playerLayout.visibility = View.GONE
                        exoPlayer?.stop()
                        lastPlaylistId = null
                        playlistListener?.remove()
                    }
                }
            }
    }

    private fun watchPlaylistDoc(playlistId: String) {
        playlistListener?.remove()
        playlistListener = db.collection("playlists").document(playlistId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                if (snapshot != null && snapshot.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val items = snapshot.get("items") as? List<Map<String, Any>>
                    if (items != null) {
                        playlistItems = items
                        Log.d("SignageDebug", "Loaded \${playlistItems.size} items for playlist \$playlistId")
                        currentIndex = 0
                        playCurrentItem()
                        prefetchPlaylistItems(items)
                    }
                }
            }
    }

    private fun playCurrentItem() {
        Log.d(
            "SignageDebug",
            "playCurrentItem called, items=\${playlistItems.size}, index=\$currentIndex"
        )
        logPreviousItemPlayback()

        if (playlistItems.isEmpty()) return
        if (currentIndex >= playlistItems.size) currentIndex = 0

        val item = playlistItems[currentIndex]
        currentItemStartTime = System.currentTimeMillis()
        currentItemForLogging = item

        val url = item["url"] as? String ?: return
        val type = item["type"] as? String ?: "video"
        val duration = (item["durationSeconds"] as? Long ?: 8L) * 1000L
        val resizeMode = item["resizeMode"] as? String ?: "fit"
        val itemRotation = ((item["rotation"] as? Long) ?: 0L).toInt()
        advanceRunnable?.let { handler.removeCallbacks(it) }

        if (type == "video") {
            imageView.visibility = View.GONE
            webView.visibility = View.GONE
            webView.loadUrl("about:blank")
            videoView.visibility = View.VISIBLE
            videoView.resizeMode = when (resizeMode) {
                "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            val isLive = item["isLive"] as? Boolean ?: false
            val isHls = url.contains(".m3u8") || isLive

            exoPlayer?.let { player ->
                val mediaItem = MediaItem.fromUri(url)
                val mediaSource = if (isHls) {
                    androidx.media3.exoplayer.hls.HlsMediaSource.Factory(liveDataSourceFactory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(mediaItem)
                } else {
                    DefaultMediaSourceFactory(this).setDataSourceFactory(cacheDataSourceFactory)
                        .createMediaSource(mediaItem)
                }
                player.setMediaSource(mediaSource)
                player.prepare()
                player.play()
            }
            if (item.containsKey("durationSeconds") && !isLive) {
                val runnable = Runnable { advance() }
                advanceRunnable = runnable
                handler.postDelayed(runnable, duration)
            }
        } else if (type == "web") {
            videoView.visibility = View.GONE
            imageView.visibility = View.GONE
            exoPlayer?.stop()
            webView.visibility = View.VISIBLE

            val runnable = Runnable { advance() }
            advanceRunnable = runnable
            handler.postDelayed(runnable, duration)

            try {
                val videoId = extractYoutubeVideoId(url)
                Log.d("SignageDebug", "web item url=\$url extracted videoId=\$videoId")
                if (videoId != null) {
                    val isLive = url.contains("youtube.com/live/")
                    webView.loadDataWithBaseURL("https://www.youtube.com", buildYoutubeEmbedHtml(videoId, isLive), "text/html", "utf-8", null)
                } else {
                    webView.loadUrl(url)
                }
            } catch (e: Exception) {
                Log.e("SignageDebug", "WebView load failed for \$url", e)
            }
        } else {
            videoView.visibility = View.GONE
            webView.visibility = View.GONE
            webView.loadUrl("about:blank")
            imageView.visibility = View.VISIBLE
            exoPlayer?.stop()
            imageView.scaleType = when (resizeMode) {
                "fill" -> android.widget.ImageView.ScaleType.CENTER_CROP
                "stretch" -> android.widget.ImageView.ScaleType.FIT_XY
                else -> android.widget.ImageView.ScaleType.FIT_CENTER
            }
            imageView.load(url)
            val runnable = Runnable { advance() }
            advanceRunnable = runnable
            handler.postDelayed(runnable, duration)
        }
    }

    private fun prefetchPlaylistItems(items: List<Map<String, Any>>) {
        Thread {
            for (item in items) {
                val url = item["url"] as? String ?: continue
                val type = item["type"] as? String ?: "video"
                if (type != "video") continue

                try {
                    val dataSpec = DataSpec(Uri.parse(url))
                    val cacheWriter = CacheWriter(
                        cacheDataSourceFactory.createDataSource(),
                        dataSpec,
                        null,
                        null
                    )
                    cacheWriter.cache()
                    Log.d("SignageDebug", "Pre-cached: \$url")
                } catch (e: Exception) {
                    Log.e("SignageDebug", "Pre-cache failed for \$url", e)
                }
            }
        }.start()
    }

    private fun toDisplayUrl(rawUrl: String): String {
        val videoId = extractYoutubeVideoId(rawUrl)
        return if (videoId != null) {
            "https://www.youtube.com/embed/\$videoId?autoplay=1&mute=1&controls=0&loop=1&playlist=\$videoId&rel=0&playsinline=1"
        } else {
            rawUrl
        }
    }

    private fun buildYoutubeEmbedHtml(videoId: String, isLive: Boolean): String {
        val loopParams = if (isLive) "" else "&loop=1&playlist=\$videoId"
        return """
        <html><body style="margin:0;padding:0;background:#000;">
        <iframe width="100%" height="100%" style="position:fixed;top:0;left:0;border:0;"
          src="https://www.youtube.com/embed/\$videoId?autoplay=1&mute=1&controls=0\$loopParams&rel=0&playsinline=1"
          allow="autoplay; encrypted-media" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
    }

    private fun extractYoutubeVideoId(url: String): String? {
        val pattern = Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/|youtube\\.com/live/|youtube\\.com/shorts/)([a-zA-Z0-9_-]{11})")
        pattern.find(url)?.groupValues?.get(1)?.let { return it }
        if (url.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return url
        return null
    }

    private fun logPreviousItemPlayback() {
        val item = currentItemForLogging ?: return
        if (currentItemStartTime == 0L) return

        val playedMs = System.currentTimeMillis() - currentItemStartTime
        currentItemStartTime = 0L
        if (playedMs < 500) return

        logPlaybackEvent(item, playedMs)
    }

    private fun logPlaybackEvent(item: Map<String, Any>, playedMs: Long) {
        val url = item["url"] as? String ?: return
        val type = item["type"] as? String ?: "video"
        val playedSeconds = playedMs / 1000.0

        val dateKey = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val dayDocId = "\${screenId}_\$dateKey"
        val itemDocId = java.net.URLEncoder.encode(url, "UTF-8").take(300)

        db.collection("analytics").document(dayDocId)
            .collection("items").document(itemDocId)
            .set(
                mapOf(
                    "screenId" to screenId,
                    "date" to dateKey,
                    "url" to url,
                    "type" to type,
                    "playCount" to com.google.firebase.firestore.FieldValue.increment(1),
                    "totalSeconds" to com.google.firebase.firestore.FieldValue.increment(playedSeconds),
                    "lastPlayed" to Timestamp.now()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .addOnFailureListener { e ->
                Log.e("SignageDebug", "Analytics log failed for \$url", e)
            }
    }

    private fun advance() {
        if (playlistItems.isNotEmpty()) {
            currentIndex = (currentIndex + 1) % playlistItems.size
            playCurrentItem()
        }
    }

    private fun startHeartbeat() {
        val runnable = object : Runnable {
            override fun run() {
                db.collection("screens").document(screenId)
                    .update("lastSeen", Timestamp.now())
                    .addOnFailureListener { e ->
                        Log.e("SignagePlayer", "Heartbeat update failed", e)
                    }
                handler.postDelayed(this, heartbeatIntervalMs)
            }
        }
        handler.post(runnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        logPreviousItemPlayback()
        screenListener?.remove()
        playlistListener?.remove()
        exoPlayer?.release()
        webView.loadUrl("about:blank")
        webView.destroy()
        bottomWebView.loadUrl("about:blank")
        bottomWebView.destroy()
        advanceRunnable?.let { handler.removeCallbacks(it) }
    }
}
