package com.example.automotivemediaserviceprranit.shared

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import java.io.IOException

/**
 * Core media service — shared by both the :mobile and :automotive modules.
 *
 * Browse tree exposed to MediaBrowser clients (Android Auto, AAOS Media Center,
 * and the in-app MediaBrowserCompat):
 *
 *   root
 *   ├── all_songs        (browsable) → all songs, flat
 *   └── playlists        (browsable)
 *       ├── playlist_FolderA  (browsable) → songs in Music/FolderA/
 *       ├── playlist_FolderB  (browsable) → songs in Music/FolderB/
 *       └── …
 *
 * Playback context:
 *   - [currentQueue] holds the songs that should be cycled by skip-next/prev.
 *     It is set when a song is started via [onPlayFromMediaId].
 *   - Pass [EXTRA_PLAYLIST_ID] in the extras to tell the service which playlist
 *     to use as the queue context; omit it (or send [MEDIA_ALL_SONGS_ID]) to
 *     use the flat all-songs list.
 */
class MusicService : MediaBrowserServiceCompat() {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val MEDIA_ROOT_ID         = "root"
        const val MEDIA_ALL_SONGS_ID    = "all_songs"
        const val MEDIA_PLAYLISTS_ID    = "playlists"
        const val MEDIA_PLAYLIST_PREFIX = "playlist_"

        /** Bundle key: value is the media-browser ID of the active playlist. */
        const val EXTRA_PLAYLIST_ID = "extra_playlist_id"

        private const val CHANNEL_ID      = "music_playback_channel"
        private const val NOTIFICATION_ID = 101

        const val ACTION_PLAY_PAUSE = "com.example.automotivemediaserviceprranit.PLAY_PAUSE"
        const val ACTION_NEXT       = "com.example.automotivemediaserviceprranit.NEXT"
        const val ACTION_PREVIOUS   = "com.example.automotivemediaserviceprranit.PREVIOUS"
        const val ACTION_STOP       = "com.example.automotivemediaserviceprranit.STOP"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var stateBuilder: PlaybackStateCompat.Builder
    private lateinit var audioManager: AudioManager

    private var mediaPlayer: MediaPlayer? = null

    /** All songs in the Music folder, loaded lazily. */
    @Volatile private var allSongs: List<Song> = emptyList()

    /** folderName → songs — populated after the first load. */
    @Volatile private var playlists: Map<String, List<Song>> = emptyMap()

    /**
     * The songs that prev/next navigate through.  Set when playback starts;
     * defaults to [allSongs] if no playlist context is provided.
     * When shuffle is on this list is a randomised copy of [originalQueue].
     */
    @Volatile private var currentQueue: List<Song> = emptyList()

    /** Unshuffled snapshot of [currentQueue], used to restore order when shuffle is turned off. */
    @Volatile private var originalQueue: List<Song> = emptyList()

    /** Index within [currentQueue] for the currently playing track. */
    private var queueIndex: Int = -1

    /** Whether shuffle mode is currently active. */
    private var shuffleEnabled: Boolean = false

    /**
     * The media-browser ID of the deepest node that [onLoadChildren] was most recently
     * called for — either [MEDIA_ALL_SONGS_ID] or a "playlist_*" ID.
     *
     * Android Auto's system Media UI calls [onLoadChildren] for a playlist before it
     * calls [onPlayFromMediaId] for a song inside that playlist, but it does NOT
     * forward a custom [EXTRA_PLAYLIST_ID] extra.  We store the last browsed parent
     * here so [onPlayFromMediaId] can fall back to it and keep the queue scoped to
     * the correct playlist instead of defaulting to all songs.
     */
    @Volatile private var lastBrowsedParentId: String? = null

    private var audioFocusReq: AudioFocusRequest? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY               or
                PlaybackStateCompat.ACTION_PAUSE              or
                PlaybackStateCompat.ACTION_PLAY_PAUSE         or
                PlaybackStateCompat.ACTION_STOP               or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT       or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS   or
                PlaybackStateCompat.ACTION_SEEK_TO            or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH   or
                PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
            )

        mediaSession = MediaSessionCompat(this, "MusicService").apply {
            setPlaybackState(stateBuilder.setState(PlaybackStateCompat.STATE_NONE, 0, 1f).build())
            setCallback(sessionCallback)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        // Pre-load song catalogue in the background
        Thread {
            val loaded = MusicScanner.loadSongs(this)
            allSongs  = loaded
            playlists = loaded.groupBy { it.folderName }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> if (mediaPlayer?.isPlaying == true) sessionCallback.onPause()
                                 else sessionCallback.onPlay()
            ACTION_NEXT       -> sessionCallback.onSkipToNext()
            ACTION_PREVIOUS   -> sessionCallback.onSkipToPrevious()
            ACTION_STOP       -> sessionCallback.onStop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releasePlayer()
        mediaSession.release()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaBrowserServiceCompat — browsing
    // ─────────────────────────────────────────────────────────────────────────

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        // When Android Auto (phone-projection) connects as a MediaBrowser client,
        // self-start the service so it stays alive and appears in Android Auto's
        // media carousel automatically — without the user having to open the app first.
        if (clientPackageName == "com.google.android.projection.gearhead") {
            startService(Intent(this, MusicService::class.java))
        }
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        // Track the deepest playlist/all-songs node that any browser (mobile app or
        // Android Auto's system UI) has loaded.  Android Auto always calls
        // onLoadChildren for the playlist before calling onPlayFromMediaId for a song
        // inside it, but it never forwards EXTRA_PLAYLIST_ID — so we use this as the
        // fallback queue context in onPlayFromMediaId.
        if (parentId == MEDIA_ALL_SONGS_ID || parentId.startsWith(MEDIA_PLAYLIST_PREFIX)) {
            lastBrowsedParentId = parentId
        }

        result.detach()
        Thread { result.sendResult(buildChildren(parentId)) }.start()
    }

    private fun buildChildren(parentId: String): MutableList<MediaBrowserCompat.MediaItem> {
        ensureSongsLoaded()
        return when {

            // ── Root: top-level browsable folders ────────────────────────────
            parentId == MEDIA_ROOT_ID -> mutableListOf(
                browsableItem(MEDIA_ALL_SONGS_ID, "All Songs", "Music/"),
                browsableItem(MEDIA_PLAYLISTS_ID, "Playlists", "Music/")
            )

            // ── All songs: flat list ──────────────────────────────────────────
            parentId == MEDIA_ALL_SONGS_ID -> allSongs.toMediaItems()

            // ── Playlists root: one item per folder ───────────────────────────
            parentId == MEDIA_PLAYLISTS_ID -> playlists.entries
                .sortedBy { it.key }
                .mapTo(mutableListOf()) { (name, _) ->
                    browsableItem(
                        "$MEDIA_PLAYLIST_PREFIX$name",
                        name,
                        "Music/$name/"   // folder path shown below the playlist name
                    )
                }

            // ── Individual playlist: songs in that folder ─────────────────────
            parentId.startsWith(MEDIA_PLAYLIST_PREFIX) -> {
                val folderName = parentId.removePrefix(MEDIA_PLAYLIST_PREFIX)
                (playlists[folderName] ?: emptyList()).toMediaItems()
            }

            else -> mutableListOf()
        }
    }

    /** Blocks until songs are available (used inside background threads only). */
    private fun ensureSongsLoaded() {
        if (allSongs.isEmpty()) {
            val loaded = MusicScanner.loadSongs(this)
            allSongs  = loaded
            playlists = loaded.groupBy { it.folderName }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaSession callback — transport controls
    // ─────────────────────────────────────────────────────────────────────────

    private val sessionCallback = object : MediaSessionCompat.Callback() {

        override fun onPlay() {
            if (!requestAudioFocus()) return
            if (queueIndex < 0) {
                // Nothing queued yet — start from all songs (shuffled if enabled)
                if (allSongs.isEmpty()) ensureSongsLoaded()
                if (allSongs.isEmpty()) return
                originalQueue = allSongs
                currentQueue  = if (shuffleEnabled) allSongs.shuffled() else allSongs
                startPlayingAt(0)
                return
            }
            mediaPlayer?.let { mp ->
                mp.start()
                publishState(PlaybackStateCompat.STATE_PLAYING, mp.currentPosition.toLong())
                pushToForeground()
            }
        }

        override fun onSetShuffleMode(shuffleMode: Int) {
            val enabling = shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
            if (enabling == shuffleEnabled) return   // no change
            shuffleEnabled = enabling
            mediaSession.setShuffleMode(shuffleMode)

            if (enabling) {
                // Snapshot current unshuffled order, then shuffle keeping current song first
                if (currentQueue.isNotEmpty()) {
                    val current = currentQueue.getOrNull(queueIndex)
                    originalQueue = currentQueue.toList()
                    val rest = currentQueue.toMutableList().also {
                        if (current != null) it.remove(current)
                        it.shuffle()
                    }
                    currentQueue = if (current != null) listOf(current) + rest else rest
                    queueIndex = 0
                }
            } else {
                // Restore original order, keep playing the same song
                if (originalQueue.isNotEmpty()) {
                    val current = currentQueue.getOrNull(queueIndex)
                    currentQueue  = originalQueue.toList()
                    originalQueue = emptyList()
                    queueIndex    = if (current != null) currentQueue.indexOf(current).coerceAtLeast(0) else 0
                }
            }
        }

        override fun onPause() {
            mediaPlayer?.pause()
            publishState(PlaybackStateCompat.STATE_PAUSED,
                mediaPlayer?.currentPosition?.toLong() ?: 0)
            stopForeground(false)
            refreshNotification()
            abandonAudioFocus()
        }

        override fun onStop() {
            releasePlayer()
            queueIndex = -1
            publishState(PlaybackStateCompat.STATE_STOPPED, 0)
            stopForeground(true)
            abandonAudioFocus()
            stopSelf()
        }

        override fun onSkipToNext() {
            if (currentQueue.isEmpty()) return
            startPlayingAt((queueIndex + 1) % currentQueue.size)
        }

        override fun onSkipToPrevious() {
            if (currentQueue.isEmpty()) return
            val posMs = mediaPlayer?.currentPosition ?: 0
            if (posMs > 3_000) {
                // Within the first 3 s → restart; otherwise go back
                mediaPlayer?.seekTo(0)
                publishState(PlaybackStateCompat.STATE_PLAYING, 0)
            } else {
                startPlayingAt(if (queueIndex > 0) queueIndex - 1 else currentQueue.size - 1)
            }
        }

        override fun onSeekTo(pos: Long) {
            mediaPlayer?.seekTo(pos.toInt())
            val playing = mediaPlayer?.isPlaying == true
            publishState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                pos
            )
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId == null) return
            ensureSongsLoaded()

            // Determine which queue to use.
            //
            // The mobile UI always sends EXTRA_PLAYLIST_ID in the extras Bundle so the
            // service knows which playlist the user is browsing.
            //
            // Android Auto's system Media UI does NOT forward custom extras — it calls
            // onPlayFromMediaId with a null/empty Bundle after the user taps a song while
            // browsing a playlist.  We fall back to lastBrowsedParentId, which was set by
            // onLoadChildren the moment Android Auto loaded that playlist's song list.
            // This keeps the queue scoped to the correct playlist instead of defaulting
            // to all songs (which caused next-song to jump outside the playlist in the car).
            val playlistId = extras?.getString(EXTRA_PLAYLIST_ID)
                ?: lastBrowsedParentId
            val baseQueue: List<Song> = when {
                playlistId == null || playlistId == MEDIA_ALL_SONGS_ID -> allSongs
                playlistId.startsWith(MEDIA_PLAYLIST_PREFIX) -> {
                    val folder = playlistId.removePrefix(MEDIA_PLAYLIST_PREFIX)
                    playlists[folder] ?: allSongs
                }
                else -> allSongs
            }
            originalQueue = baseQueue

            if (shuffleEnabled) {
                // Tapped song goes first; rest is shuffled randomly
                val tapped = baseQueue.firstOrNull { it.id.toString() == mediaId }
                val rest   = baseQueue.toMutableList().also { if (tapped != null) it.remove(tapped); it.shuffle() }
                currentQueue = if (tapped != null) listOf(tapped) + rest else rest
                startPlayingAt(0)
            } else {
                currentQueue = baseQueue
                val idx = baseQueue.indexOfFirst { it.id.toString() == mediaId }
                if (idx >= 0) startPlayingAt(idx)
            }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            ensureSongsLoaded()
            if (query.isNullOrBlank()) { onPlay(); return }
            currentQueue = allSongs
            val idx = allSongs.indexOfFirst {
                it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
            }
            if (idx >= 0) startPlayingAt(idx) else onPlay()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Playback helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun startPlayingAt(index: Int) {
        if (!requestAudioFocus()) return
        queueIndex = index
        val song = currentQueue[index]

        releasePlayer()
        publishState(PlaybackStateCompat.STATE_BUFFERING, 0)

        // Push metadata immediately so the UI shows the incoming track title
        mediaSession.setMetadata(song.toMetadata())
        pushToForeground()

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            try {
                setDataSource(applicationContext, song.contentUri)
            } catch (e: IOException) {
                releasePlayer(); return
            }
            setOnPreparedListener { mp ->
                mp.start()
                publishState(PlaybackStateCompat.STATE_PLAYING, 0)
                pushToForeground()
            }
            setOnCompletionListener { sessionCallback.onSkipToNext() }
            setOnErrorListener { _, _, _ -> sessionCallback.onStop(); true }
            prepareAsync()
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }
        mediaPlayer = null
    }

    private fun publishState(
        state: Int,
        positionMs: Long,
        speed: Float = if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f
    ) {
        mediaSession.setPlaybackState(
            stateBuilder.setState(state, positionMs, speed).build()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Audio focus
    // ─────────────────────────────────────────────────────────────────────────

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS                    -> sessionCallback.onPause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT          -> mediaPlayer?.pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaPlayer?.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1f, 1f)
                if (mediaPlayer?.isPlaying == false) mediaPlayer?.start()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusReq = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusReq?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Controls for music playback" }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification {
        val isPlaying = mediaPlayer?.isPlaying == true
        val meta      = mediaSession.controller?.metadata
        val title     = meta?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)  ?: "Not Playing"
        val artist    = meta?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""

        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun pi(action: String, reqCode: Int) = PendingIntent.getService(
            this, reqCode,
            Intent(this, MusicService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(android.R.drawable.ic_media_previous, "Previous", pi(ACTION_PREVIOUS, 1))
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                pi(ACTION_PLAY_PAUSE, 2)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", pi(ACTION_NEXT, 3))
            .build()
    }

    private fun pushToForeground()   { startForeground(NOTIFICATION_ID, buildNotification()) }
    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Extension helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun browsableItem(id: String, title: String, subtitle: String) =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(subtitle)
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )

    private fun List<Song>.toMediaItems(): MutableList<MediaBrowserCompat.MediaItem> =
        mapTo(mutableListOf()) { song ->
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(song.id.toString())
                    .setTitle(song.title)
                    .setSubtitle(song.artist)
                    .setDescription(song.album)
                    .setMediaUri(song.contentUri)
                    .setIconUri(song.albumArtUri)
                    .build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        }

    private fun Song.toMetadata(): MediaMetadataCompat =
        MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID,     id.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,        title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,       artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,        album)
            .putLong  (MediaMetadataCompat.METADATA_KEY_DURATION,     duration)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,albumArtUri?.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI,    contentUri.toString())
            .build()
}
