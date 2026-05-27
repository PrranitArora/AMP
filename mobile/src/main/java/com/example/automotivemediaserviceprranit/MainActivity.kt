package com.example.automotivemediaserviceprranit

import android.Manifest
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.automotivemediaserviceprranit.shared.MusicService

/**
 * Phone UI — two-level navigation:
 *
 *  1. **Playlists screen**: shows one row per Music sub-folder + an "All Songs" row.
 *  2. **Songs screen**: shows all tracks inside the selected folder/playlist.
 *
 * The bottom player bar is always visible.  Tap any song to start playback;
 * skip/seek controls work regardless of which screen is currently shown.
 *
 * Navigation state is driven by [BrowseLevel].
 */
class MainActivity : AppCompatActivity() {

    // ─────────────────────────────────────────────────────────────────────────
    // Browse state machine
    // ─────────────────────────────────────────────────────────────────────────

    /** Tracks which level of the two-level browse hierarchy we're currently showing. */
    private sealed class BrowseLevel {
        /** Showing the playlist/folder list. */
        object Playlists : BrowseLevel()

        /**
         * Showing songs inside a specific playlist.
         * @param playlistId  The MediaBrowser ID (e.g. "playlist_Rock").
         * @param playlistName Human-readable folder name shown in the header.
         */
        data class Songs(val playlistId: String, val playlistName: String) : BrowseLevel()
    }

    private var level: BrowseLevel = BrowseLevel.Playlists
    /** ID of the subscription we're currently holding — used to unsubscribe cleanly. */
    private var activeSubscriptionId: String? = null

    // ─────────────────────────────────────────────────────────────────────────
    // MediaBrowser / Controller
    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Views
    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var rvContent:           RecyclerView
    private lateinit var tvScreenTitle:       TextView
    private lateinit var ibBack:              ImageButton
    private lateinit var playerCard:          CardView
    private lateinit var tvNowPlayingTitle:   TextView
    private lateinit var tvNowPlayingArtist:  TextView
    private lateinit var tvCurrentTime:       TextView
    private lateinit var tvTotalTime:         TextView
    private lateinit var ibPlayPause:         ImageButton
    private lateinit var ibPrevious:          ImageButton
    private lateinit var ibNext:              ImageButton
    private lateinit var ibShuffle:           ImageButton
    private lateinit var seekBar:             SeekBar

    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var songAdapter:     SongAdapter

    // ─────────────────────────────────────────────────────────────────────────
    // Player-bar color animation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Soft pastel palette the player bar cycles through while a track is playing.
     * Starts with white so the bar looks clean when nothing has played yet.
     * All shades are light enough that the existing dark text stays readable.
     */
    private val playerBarColors = intArrayOf(
        0xFFFFFFFF.toInt(),   // white  — resting state
        0xFFFFE4E4.toInt(),   // rose
        0xFFFFEFD0.toInt(),   // peach
        0xFFFFFDE7.toInt(),   // warm yellow
        0xFFE8F5E9.toInt(),   // mint
        0xFFE3F2FD.toInt(),   // sky blue
        0xFFEDE7F6.toInt(),   // lavender
    )

    /** How long each individual color-to-color blend takes (medium pace). */
    private val colorStepMs = 3_000L

    private var colorAnimator: ValueAnimator? = null
    private var colorIndex     = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Seek ticker
    // ─────────────────────────────────────────────────────────────────────────

    private val seekHandler = Handler(Looper.getMainLooper())
    private val seekTicker  = object : Runnable {
        override fun run() {
            val ctrl  = mediaController ?: run { seekHandler.postDelayed(this, 500); return }
            val state = ctrl.playbackState
            val meta  = ctrl.metadata

            if (state != null && meta != null &&
                state.state == PlaybackStateCompat.STATE_PLAYING) {

                val duration = meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
                if (duration > 0) {
                    val elapsed    = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                    val currentPos = (state.position + elapsed * state.playbackSpeed).toLong()
                        .coerceIn(0, duration)
                    seekBar.progress   = ((currentPos.toFloat() / duration) * 1000).toInt()
                    tvCurrentTime.text = formatMs(currentPos)
                    tvTotalTime.text   = formatMs(duration)
                }
            }
            seekHandler.postDelayed(this, 500)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission launcher
    // ─────────────────────────────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) connectToService()
        else Toast.makeText(this, "Storage permission needed to read music.", Toast.LENGTH_LONG).show()
    }

    /**
     * Launched when the user taps the settings gear.  On return we reload the
     * saved hue and re-colour the playlist adapter so changes are visible immediately.
     */
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        applyPlaylistColor()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupAdapters()
        setupPlayerBar()

        // System back-press handler — go up a level instead of leaving the app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (level is BrowseLevel.Songs) navigateToPlaylists()
                else finish()
            }
        })

        checkPermissionsAndConnect()
    }

    override fun onStart() {
        super.onStart()
        mediaController?.let { ctrl ->
            ctrl.registerCallback(controllerCallback)
            syncUIFromController(ctrl)
        }
        seekHandler.post(seekTicker)
    }

    override fun onStop() {
        super.onStop()
        mediaController?.unregisterCallback(controllerCallback)
        seekHandler.removeCallbacks(seekTicker)
        colorAnimator?.cancel()
        colorAnimator = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mediaBrowser.isInitialized && mediaBrowser.isConnected) {
            activeSubscriptionId?.let { mediaBrowser.unsubscribe(it) }
            mediaBrowser.disconnect()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        rvContent           = findViewById(R.id.rvContent)
        tvScreenTitle       = findViewById(R.id.tvScreenTitle)
        ibBack              = findViewById(R.id.ibBack)
        playerCard          = findViewById(R.id.playerCard)
        tvNowPlayingTitle   = findViewById(R.id.tvNowPlayingTitle)
        tvNowPlayingArtist  = findViewById(R.id.tvNowPlayingArtist)
        tvCurrentTime       = findViewById(R.id.tvCurrentTime)
        tvTotalTime         = findViewById(R.id.tvTotalTime)
        ibPlayPause         = findViewById(R.id.ibPlayPause)
        ibPrevious          = findViewById(R.id.ibPrevious)
        ibNext              = findViewById(R.id.ibNext)
        ibShuffle           = findViewById(R.id.ibShuffle)
        seekBar             = findViewById(R.id.seekBar)

        tvNowPlayingTitle.isSelected = true   // enables marquee scrolling
        ibBack.setOnClickListener { navigateToPlaylists() }

        // Settings gear in the nav header
        findViewById<ImageButton>(R.id.ibSettings).setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupAdapters() {
        playlistAdapter = PlaylistAdapter { item ->
            // Tapped "All Songs" (MEDIA_ALL_SONGS_ID) or a specific playlist
            val mediaId   = item.mediaId ?: return@PlaylistAdapter
            val itemTitle = item.description.title?.toString() ?: mediaId
            navigateToSongs(mediaId, itemTitle)
        }

        songAdapter = SongAdapter { item ->
            // Tapped a playable song — tell the service which queue context to use
            val extras = Bundle().apply {
                (level as? BrowseLevel.Songs)?.let { putString(MusicService.EXTRA_PLAYLIST_ID, it.playlistId) }
            }
            mediaController?.transportControls?.playFromMediaId(item.mediaId, extras)
        }

        rvContent.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            // No divider decorator — playlist cards have their own margins;
            // song rows have sufficient padding for visual separation.
        }
    }

    private fun setupPlayerBar() {
        ibPlayPause.setOnClickListener {
            val ctrl = mediaController ?: return@setOnClickListener
            when (ctrl.playbackState?.state) {
                PlaybackStateCompat.STATE_PLAYING -> ctrl.transportControls.pause()
                else                              -> ctrl.transportControls.play()
            }
        }
        ibPrevious.setOnClickListener { mediaController?.transportControls?.skipToPrevious() }
        ibNext.setOnClickListener     { mediaController?.transportControls?.skipToNext()     }
        ibShuffle.setOnClickListener  {
            val ctrl = mediaController ?: return@setOnClickListener
            val next = if (ctrl.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL)
                PlaybackStateCompat.SHUFFLE_MODE_NONE
            else
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            ctrl.transportControls.setShuffleMode(next)
        }

        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = mediaController?.metadata
                    ?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: return
                tvCurrentTime.text = formatMs((progress / 1000f * dur).toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val dur = mediaController?.metadata
                    ?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: return
                mediaController?.transportControls?.seekTo((sb.progress / 1000f * dur).toLong())
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navigation
    // ─────────────────────────────────────────────────────────────────────────

    /** Show the playlists screen (root level). */
    private fun navigateToPlaylists() {
        level = BrowseLevel.Playlists
        tvScreenTitle.text = getString(R.string.app_name)
        ibBack.visibility  = View.GONE

        rvContent.adapter = playlistAdapter

        // Subscribe to the playlists node
        resubscribe(MusicService.MEDIA_PLAYLISTS_ID)
    }

    /**
     * Drill into [playlistId], showing all songs inside it.
     * [playlistId] is a media-browser ID like "playlist_Rock" or "all_songs".
     */
    private fun navigateToSongs(playlistId: String, playlistName: String) {
        level              = BrowseLevel.Songs(playlistId, playlistName)
        tvScreenTitle.text = playlistName
        ibBack.visibility  = View.VISIBLE

        rvContent.adapter = songAdapter

        resubscribe(playlistId)
    }

    /**
     * Unsubscribes from the previous MediaBrowser node and subscribes to [newId].
     */
    private fun resubscribe(newId: String) {
        if (!::mediaBrowser.isInitialized || !mediaBrowser.isConnected) return
        activeSubscriptionId?.let { mediaBrowser.unsubscribe(it) }
        activeSubscriptionId = newId
        mediaBrowser.subscribe(newId, subscriptionCallback)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkPermissionsAndConnect() {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) connectToService()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun connectToService() {
        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, MusicService::class.java),
            connectionCallbacks,
            null
        )
        mediaBrowser.connect()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaBrowser callbacks
    // ─────────────────────────────────────────────────────────────────────────

    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val ctrl = MediaControllerCompat(this@MainActivity, mediaBrowser.sessionToken)
            MediaControllerCompat.setMediaController(this@MainActivity, ctrl)
            mediaController = ctrl
            ctrl.registerCallback(controllerCallback)
            syncUIFromController(ctrl)

            // Apply the user's saved playlist color before showing the list
            applyPlaylistColor()

            // Start at the playlists root
            navigateToPlaylists()
        }
        override fun onConnectionSuspended() { mediaController = null }
        override fun onConnectionFailed()    {
            Toast.makeText(this@MainActivity, "Could not connect to MusicService.", Toast.LENGTH_SHORT).show()
        }
    }

    private val subscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(
            parentId: String,
            children: MutableList<MediaBrowserCompat.MediaItem>
        ) {
            when (level) {
                is BrowseLevel.Playlists -> {
                    // Prepend an "All Songs" item so the user can also browse everything flat
                    val allSongsItem = MediaBrowserCompat.MediaItem(
                        android.support.v4.media.MediaDescriptionCompat.Builder()
                            .setMediaId(MusicService.MEDIA_ALL_SONGS_ID)
                            .setTitle("All Songs")
                            .setSubtitle("Music/")
                            .build(),
                        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                    playlistAdapter.updatePlaylists(listOf(allSongsItem) + children)

                    if (children.isEmpty()) {
                        tvNowPlayingTitle.text = "No playlists found — add folders to your Music directory"
                    }
                }
                is BrowseLevel.Songs -> {
                    songAdapter.updateSongs(children)
                    if (children.isEmpty()) {
                        tvNowPlayingTitle.text = "This folder is empty"
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaController callback
    // ─────────────────────────────────────────────────────────────────────────

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            tvNowPlayingTitle.text  = metadata
                ?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)  ?: "No song playing"
            tvNowPlayingArtist.text = metadata
                ?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""
            val dur = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0
            tvTotalTime.text   = formatMs(dur)
            tvCurrentTime.text = formatMs(0)
            seekBar.progress   = 0
        }

        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            ibPlayPause.setImageResource(
                if (state?.state == PlaybackStateCompat.STATE_PLAYING)
                    android.R.drawable.ic_media_pause
                else
                    android.R.drawable.ic_media_play
            )
            when (state?.state) {
                PlaybackStateCompat.STATE_PLAYING -> startColorCycling()
                PlaybackStateCompat.STATE_PAUSED  -> stopColorCycling(resetToWhite = false)
                PlaybackStateCompat.STATE_STOPPED,
                PlaybackStateCompat.STATE_NONE    -> stopColorCycling(resetToWhite = true)
            }
        }

        override fun onShuffleModeChanged(shuffleMode: Int) {
            applySuffleUI(shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL)
        }
    }

    private fun applySuffleUI(active: Boolean) {
        if (active) {
            // Bright green tint + full opacity when on
            ibShuffle.alpha          = 1.0f
            ibShuffle.imageTintList  = ColorStateList.valueOf(
                ContextCompat.getColor(this, android.R.color.holo_green_light)
            )
        } else {
            // Faded, no tint override when off
            ibShuffle.alpha          = 0.4f
            ibShuffle.imageTintList  = null
        }
    }

    private fun syncUIFromController(ctrl: MediaControllerCompat) {
        controllerCallback.onMetadataChanged(ctrl.metadata)
        controllerCallback.onPlaybackStateChanged(ctrl.playbackState)
        applySuffleUI(ctrl.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Player-bar color cycling
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Kicks off (or resumes) the smooth color-cycling animation on the player bar.
     * Each call animates from the current card color to the next palette entry and
     * then chains the following step, creating a continuous loop while playing.
     */
    private fun startColorCycling() {
        if (colorAnimator?.isRunning == true) return   // already running — no-op

        val fromColor = playerBarColors[colorIndex]
        colorIndex    = (colorIndex + 1) % playerBarColors.size
        val toColor   = playerBarColors[colorIndex]

        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
            duration = colorStepMs
            addUpdateListener { anim ->
                playerCard.setCardBackgroundColor(anim.animatedValue as Int)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Chain the next step only if we weren't cancelled externally
                    if (colorAnimator != null) startColorCycling()
                }
            })
            start()
        }
    }

    /**
     * Stops the color animation.
     * Pass [resetToWhite] = true to snap back to the resting white state
     * (used when playback stops entirely); false to freeze at the current colour
     * (used when paused so resuming feels seamless).
     */
    private fun stopColorCycling(resetToWhite: Boolean = false) {
        colorAnimator?.cancel()
        colorAnimator = null
        if (resetToWhite) {
            colorIndex = 0
            playerCard.setCardBackgroundColor(playerBarColors[0])
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads the user-chosen hue from SharedPreferences and forwards it to the
     * playlist adapter.  Called on first connect and every time the user returns
     * from SettingsActivity so color changes are reflected immediately.
     */
    private fun applyPlaylistColor() {
        val hue = getSharedPreferences(SettingsActivity.PREF_FILE, MODE_PRIVATE)
            .getFloat(SettingsActivity.KEY_PLAYLIST_HUE, SettingsActivity.DEFAULT_HUE)
        playlistAdapter.baseHue = hue
        if (level is BrowseLevel.Playlists) playlistAdapter.notifyDataSetChanged()
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
