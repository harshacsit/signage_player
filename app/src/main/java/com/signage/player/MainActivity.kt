package com.signage.player

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import android.net.Uri
import coil.load
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import kotlin.random.Random
import android.view.Gravity
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

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
                    LeastRecentlyUsedCacheEvictor(6L * 1024 * 1024 * 1024), // 2GB cap, oldest evicted first
                    StandaloneDatabaseProvider(context)
                ).also { simpleCache = it }
            }
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var db: FirebaseFirestore
    private lateinit var screenId: String

    private lateinit var pairingLayout: LinearLayout
    private lateinit var playerLayout: FrameLayout
    private lateinit var pairingCodeText: TextView
    private lateinit var videoView: PlayerView
    private lateinit var imageView: ImageView

    private lateinit var cacheDataSourceFactory: CacheDataSource.Factory
    private var exoPlayer: ExoPlayer? = null
    private var screenListener: ListenerRegistration? = null
    private var playlistListener: ListenerRegistration? = null

    private var playlistItems: List<Map<String, Any>> = emptyList()
    private var currentIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeatIntervalMs = 30_000L
    private var advanceRunnable: Runnable? = null

    // track which playlist we're already subscribed to, so a heartbeat-triggered
    // screen doc update doesn't tear down and rebuild the playlist listener every 30s.
    private var lastPlaylistId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enableImmersiveKiosk()

        // ---- STEP 1: everything below must run BEFORE any Firestore call or
        // any watchScreenDoc()/registerScreenIfNeeded()/startHeartbeat() call.
        // Those functions touch screenId, pairingLayout, playerLayout, videoView,
        // imageView, and exoPlayer — if Firebase auth is already cached from a
        // previous launch, its check below is SYNCHRONOUS, so if those functions
        // ran first, this would crash instantly every time the app reopens.

        prefs = getSharedPreferences("signage_prefs", Context.MODE_PRIVATE)
        db = FirebaseFirestore.getInstance()

        // Must be set before ANY other Firestore call (get/set/update/listener) —
        // Firestore throws if you try to change settings after first use.
        db.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .build()

        pairingLayout = findViewById(R.id.pairingLayout)
        playerLayout = findViewById(R.id.playerLayout)
        pairingCodeText = findViewById(R.id.pairingCodeText)
        videoView = findViewById(R.id.videoView)
        imageView = findViewById(R.id.imageView)

        screenId = getOrCreateScreenId()
        pairingCodeText.text = screenId

        // Wraps network requests in a disk cache: first play downloads + saves,
        // every play after that (including with no network at all) reads from disk.
        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(getCache(this))
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR) // don't crash playback if a cache write fails

        // attach the playback-state / error listener ONCE here, instead of
        // re-adding a new listener every time playCurrentItem() runs. Previously
        // every video start stacked another listener on top of the old ones,
        // so advance() eventually fired multiple times per event -> rapid
        // skipping through the playlist ("blinking").
        val renderersFactory = DefaultRenderersFactory(this)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(cacheDataSourceFactory))
            .build().also { player ->
                videoView.player = player
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) advance()
                    }
                    // Watchdog: if playback errors out, skip to the next item instead of freezing.
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("SignagePlayer", "ExoPlayer error: ${error.errorCodeName} - ${error.message}", error)
                        Toast.makeText(
                            this@MainActivity,
                            "Playback error: ${error.errorCodeName}",
                            Toast.LENGTH_LONG
                        ).show()
                        handler.postDelayed({ advance() }, 3000) // wait 3s so you can read the toast before it skips
                    }
                })
            }

        // ---- STEP 2: now it's safe to sign in and start talking to Firestore,
        // since every variable/view these functions touch is already set up.
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    registerScreenIfNeeded()
                    watchScreenDoc()
                    startHeartbeat()
                }
                .addOnFailureListener { e ->
                    Log.e("SignagePlayer", "Anonymous sign-in failed", e)
                }
        } else {
            registerScreenIfNeeded()
            watchScreenDoc()
            startHeartbeat()
        }
    }

    /** Keeps the display awake and fully hides system/nav bars for true kiosk fullscreen. */
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
    }
    /**
     * Rotates just the current media view (video or image) inside playerLayout,
     */
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
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveKiosk() // re-apply if a system dialog stole focus
    }

    /** Persistent per-device pairing code, survives app restarts (not just reboots). */
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
                        Log.d("SignageDebug", "Loaded ${playlistItems.size} items for playlist $playlistId")
                        currentIndex = 0
                        playCurrentItem()
                        prefetchPlaylistItems(items)
                    }
                }
            }
    }

    private fun playCurrentItem() {
        Log.d("SignageDebug", "playCurrentItem called, items=${playlistItems.size}, index=$currentIndex")

        if (playlistItems.isEmpty()) return
        if (currentIndex >= playlistItems.size) currentIndex = 0

        val item = playlistItems[currentIndex]
        val url = item["url"] as? String ?: return
        val type = item["type"] as? String ?: "video"
        val duration = (item["durationSeconds"] as? Long ?: 8L) * 1000L
        val resizeMode = item["resizeMode"] as? String ?: "fit"
        val itemRotation = ((item["rotation"] as? Long) ?: 0L).toInt()
        advanceRunnable?.let { handler.removeCallbacks(it) }

        if (type == "video") {
            imageView.visibility = View.GONE
            videoView.visibility = View.VISIBLE
            applyItemRotation(videoView, itemRotation)
            videoView.resizeMode = when (resizeMode) {
                "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            exoPlayer?.let { player ->
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.play()
            }
            if (item.containsKey("durationSeconds")) {
                val runnable = Runnable { advance() }
                advanceRunnable = runnable
                handler.postDelayed(runnable, duration)
            }
        } else {
            videoView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            exoPlayer?.stop()
            applyItemRotation(imageView, itemRotation)
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
    /**
     * Downloads every item in a newly-loaded playlist into the disk cache in the
     * background, so a cold boot with no network can still play the playlist
     * immediately instead of needing to have played it once online already.
     */
    private fun prefetchPlaylistItems(items: List<Map<String, Any>>) {
        Thread {
            for (item in items) {
                val url = item["url"] as? String ?: continue
                try {
                    val dataSpec = DataSpec(Uri.parse(url))
                    val cacheWriter = CacheWriter(
                        cacheDataSourceFactory.createDataSource(),
                        dataSpec,
                        null,
                        null
                    )
                    cacheWriter.cache()
                    Log.d("SignageDebug", "Pre-cached: $url")
                } catch (e: Exception) {
                    Log.e("SignageDebug", "Pre-cache failed for $url", e)
                }
            }
        }.start()
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
        screenListener?.remove()
        playlistListener?.remove()
        exoPlayer?.release()
        advanceRunnable?.let { handler.removeCallbacks(it) }
    }
}