package com.example.automotivemediaserviceprranit.automotive

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.automotivemediaserviceprranit.R
import com.example.automotivemediaserviceprranit.shared.MusicService

/**
 * Step 2 — Automotive / AAOS UI
 *
 * Runs on the car's Android Automotive OS.  Connects to [MusicService] via
 * [MediaBrowserCompat] — the same service that powers the phone app — so the
 * car's media controller drives the playback engine on whatever device the APK
 * is installed on.
 *
 * For full AAOS production apps you would extend this with the Car App Library
 * (androidx.car.app) for a larger-format, distraction-optimised layout.  This
 * activity intentionally keeps the UI simple so the architecture is clear.
 *
 * The system's built-in AAOS Media Center also connects to [MusicService]
 * automatically via the MediaBrowserService intent filter — this activity is an
 * optional companion that lets you launch the app directly from the car launcher.
 */
class AutomotiveMainActivity : AppCompatActivity() {

    // ── MediaBrowser / Controller ─────────────────────────────────────────────

    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var tvNowPlayingTitle:  TextView
    private lateinit var tvNowPlayingArtist: TextView
    private lateinit var tvAlbum:            TextView
    private lateinit var tvCurrentTime:      TextView
    private lateinit var tvTotalTime:        TextView
    private lateinit var seekBar:            SeekBar
    private lateinit var ibPrevious:         ImageButton
    private lateinit var ibPlayPause:        ImageButton
    private lateinit var ibNext:             ImageButton

    // ── Seek-bar ticker ───────────────────────────────────────────────────────

    private val seekHandler = Handler(Looper.getMainLooper())
    private val seekTicker  = object : Runnable {
        override fun run() {
            val ctrl  = mediaController ?: run { seekHandler.postDelayed(this, 500); return }
            val state = ctrl.playbackState
            val meta  = ctrl.metadata

            if (state != null && meta != null &&
                state.state == PlaybackStateCompat.STATE_PLAYING) {

                val duration   = meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
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
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_automotive_main)

        bindViews()
        setupControls()

        // Connect to MusicService — the service is declared in the :shared manifest
        // and merged into this APK, so it runs in this process.
        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, MusicService::class.java),
            connectionCallbacks,
            null
        )
        mediaBrowser.connect()
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
            mediaBrowser.disconnect()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        tvNowPlayingTitle  = findViewById(R.id.tvAutoNowPlayingTitle)
        tvNowPlayingArtist = findViewById(R.id.tvAutoNowPlayingArtist)
        tvAlbum            = findViewById(R.id.tvAutoAlbum)
        tvCurrentTime      = findViewById(R.id.tvAutoCurrentTime)
        tvTotalTime        = findViewById(R.id.tvAutoTotalTime)
        seekBar            = findViewById(R.id.autoSeekBar)
        ibPrevious         = findViewById(R.id.ibAutoPrevious)
        ibPlayPause        = findViewById(R.id.ibAutoPlayPause)
        ibNext             = findViewById(R.id.ibAutoNext)
    }

    private fun setupControls() {
        ibPlayPause.setOnClickListener {
            val ctrl = mediaController ?: return@setOnClickListener
            when (ctrl.playbackState?.state) {
                PlaybackStateCompat.STATE_PLAYING -> ctrl.transportControls.pause()
                else                              -> ctrl.transportControls.play()
            }
        }
        ibPrevious.setOnClickListener { mediaController?.transportControls?.skipToPrevious() }
        ibNext.setOnClickListener     { mediaController?.transportControls?.skipToNext()     }

        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = mediaController?.metadata
                    ?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: return
                tvCurrentTime.text = formatMs((progress / 1000f * duration).toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val duration = mediaController?.metadata
                    ?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: return
                mediaController?.transportControls?.seekTo(
                    (sb.progress / 1000f * duration).toLong()
                )
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaBrowser callbacks
    // ─────────────────────────────────────────────────────────────────────────

    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val ctrl = MediaControllerCompat(this@AutomotiveMainActivity, mediaBrowser.sessionToken)
            MediaControllerCompat.setMediaController(this@AutomotiveMainActivity, ctrl)
            mediaController = ctrl
            ctrl.registerCallback(controllerCallback)
            syncUIFromController(ctrl)
        }
        override fun onConnectionSuspended() { mediaController = null }
        override fun onConnectionFailed()    { /* show error */ }
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
            tvAlbum.text            = metadata
                ?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)  ?: ""
            val duration = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0
            tvTotalTime.text   = formatMs(duration)
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
    }

    private fun syncUIFromController(ctrl: MediaControllerCompat) {
        controllerCallback.onMetadataChanged(ctrl.metadata)
        controllerCallback.onPlaybackStateChanged(ctrl.playbackState)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
