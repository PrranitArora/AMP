package com.example.automotivemediaserviceprranit.shared

import android.net.Uri

/**
 * A single audio track loaded from the device's Music folder.
 *
 * [folderName] is the *immediate* child directory of the Music folder that
 * contains this file, e.g. "Jazz" for `Music/Jazz/track.mp3`.
 * Files sitting directly in `Music/` receive folderName = "Music".
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    /** Duration in milliseconds */
    val duration: Long,
    val contentUri: Uri,
    val albumArtUri: Uri?,
    /** Immediate sub-folder of the device's Music directory */
    val folderName: String
)
