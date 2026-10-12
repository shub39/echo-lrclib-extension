package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.Serializable

@Serializable
data class LrcGetDto(
    val id: Long = 0L,
    val name: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val hasWordSync: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val lyricsfile: String? = null
)
