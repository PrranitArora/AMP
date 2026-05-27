package com.example.automotivemediaserviceprranit.shared

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * Scans the device's standard **Music** folder and its immediate sub-folders.
 *
 * Each immediate sub-folder of `Music/` becomes a named playlist.  Files
 * sitting directly in `Music/` (no sub-folder) are assigned the playlist
 * name `"Music"`.
 *
 * Only the first level of sub-directories is considered; deeper nesting is
 * collapsed into the first-level folder name (e.g. `Music/Rock/Sub/` → "Rock").
 *
 * API compatibility:
 *  - API 29+ uses [MediaStore.MediaColumns.RELATIVE_PATH] for clean, path-safe queries.
 *  - API 28 falls back to the legacy [MediaStore.Audio.Media.DATA] full-path column.
 */
object MusicScanner {

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns every audio file found in the device's Music folder, sorted
     * alphabetically by title.  Each [Song.folderName] reflects the playlist
     * (sub-folder) it belongs to.
     */
    fun loadSongs(context: Context): List<Song> = querySongs(context)

    /**
     * Returns songs grouped by their [Song.folderName], forming one
     * [Playlist] per folder.  The list is sorted alphabetically by name.
     */
    fun loadPlaylists(context: Context): List<Playlist> {
        return querySongs(context)
            .groupBy { it.folderName }
            .entries
            .sortedBy { it.key }
            .map { (name, songs) -> Playlist(name, songs) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Query logic
    // ─────────────────────────────────────────────────────────────────────────

    private fun querySongs(context: Context): List<Song> {
        // Absolute path to /storage/emulated/0/Music (or device equivalent)
        val musicDirPath = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            .absolutePath

        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // Base columns available on all API levels we support
        val baseProjection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val (projection, selection, selectionArgs) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: use RELATIVE_PATH
                Triple(
                    baseProjection + MediaStore.MediaColumns.RELATIVE_PATH,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("Music%")      // matches "Music/" and "Music/Folder/"
                )
            } else {
                // API 28: use DATA full path
                Triple(
                    baseProjection + MediaStore.Audio.Media.DATA,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                    "${MediaStore.Audio.Media.DATA} LIKE ?",
                    arrayOf("$musicDirPath/%")
                )
            }

        val songs = mutableListOf<Song>()

        context.contentResolver.query(
            collection, projection, selection, selectionArgs,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { cursor ->
            val idCol      = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val folderCol  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            }

            while (cursor.moveToNext()) {
                val id       = cursor.getLong(idCol)
                val title    = cursor.getString(titleCol)   ?: "Unknown Title"
                val artist   = cursor.getString(artistCol)  ?: "Unknown Artist"
                val album    = cursor.getString(albumCol)   ?: "Unknown Album"
                val duration = cursor.getLong(durCol)
                val albumId  = cursor.getLong(albumIdCol)
                val rawPath  = cursor.getString(folderCol)  ?: ""

                val folderName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    folderFromRelativePath(rawPath)
                } else {
                    folderFromDataPath(rawPath, musicDirPath)
                }

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )
                val albumArtUri = Uri.parse("content://media/external/audio/albumart/$albumId")

                songs.add(Song(id, title, artist, album, duration, contentUri, albumArtUri, folderName))
            }
        }

        return songs
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Path helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * `RELATIVE_PATH` examples:
     *   "Music/"          → file directly in Music/  → returns "Music"
     *   "Music/Rock/"     → first-level sub-folder   → returns "Rock"
     *   "Music/Rock/Sub/" → deeper nesting           → returns "Rock"
     */
    private fun folderFromRelativePath(relativePath: String): String {
        val parts = relativePath.trimEnd('/').split("/")
        // parts[0] = "Music", parts[1] = immediate subfolder (if any)
        return if (parts.size >= 2 && parts[1].isNotBlank()) parts[1] else "Music"
    }

    /**
     * `DATA` examples:
     *   ".../Music/song.mp3"         → file directly in Music  → returns "Music"
     *   ".../Music/Rock/song.mp3"    → subfolder               → returns "Rock"
     *   ".../Music/Rock/Sub/song.mp3"→ deeper nesting          → returns "Rock"
     */
    private fun folderFromDataPath(dataPath: String, musicDirPath: String): String {
        val relative = dataPath.removePrefix("$musicDirPath/")
        val parts    = relative.split("/")
        // If only one part the file sits directly in Music/; otherwise parts[0] is the folder
        return if (parts.size >= 2 && parts[0].isNotBlank()) parts[0] else "Music"
    }
}
