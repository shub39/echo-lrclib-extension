package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.Serializable

@Serializable
data class LrcGetDto (
    val id: Long,
    val name: String,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val duration: Double,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?
)