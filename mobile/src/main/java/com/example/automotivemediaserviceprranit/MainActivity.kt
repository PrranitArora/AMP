package com.example.automotivemediaserviceprranit

import android.Manifest
import android.content.ComponentName
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
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
