package com.example.automotivemediaserviceprranit.shared

/**
 * A named collection of [Song]s that corresponds to a single sub-folder
 * inside the device's Music directory.
 */
data class Playlist(
    /** The folder name (e.g. "Jazz", "Rock"), used as a human-readable label. */
    val name: String,
    val songs: List<Song>
) {
    val songCount: Int get() = songs.size
}
