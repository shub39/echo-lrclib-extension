package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class LrcLibExtension : ExtensionClient, LyricsClient, LyricsSearchClient {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun onExtensionSelected() {}

    override val settingItems: List<Setting> = emptyList()

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    override fun searchTrackLyrics(
        clientId: String,
        track: Track
    ): PagedData<Lyrics> = PagedData.Single {
        val url =
            "$LRC_BASE_URL/api/search?track_name=${track.title}&artist_name=${track.artists.firstOrNull()?.name ?: ""}"
        resultToLyrics(url)
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = lyrics

    override fun searchLyrics(query: String): PagedData<Lyrics> = PagedData.Single {
        val url = "$LRC_BASE_URL/api/search?q=${query}"
        resultToLyrics(url)
    }

    private suspend inline fun resultToLyrics(url: String): List<Lyrics> {
        val request = Request.Builder()
            .url(url)
            .build()

        val response = client.newCall(request).await()
        val parsedResponse: List<LrcGetDto> = json.decodeFromString(response.body.string())

        return parsedResponse.filter { !it.instrumental }.map { dto ->
            Lyrics(
                id = dto.id.toString(),
                title = dto.trackName,
                subtitle = dto.artistName,
                lyrics = parseSyncedLyrics(dto.syncedLyrics) ?: Lyrics.Simple(dto.plainLyrics ?: "")
            )
        }
    }

    private fun parseSyncedLyrics(lyricsString: String?): Lyrics.Lyric? {
        if (lyricsString == null) return null

        val seenTimes = mutableSetOf<Long>()

        return Lyrics.Timed(
            list = lyricsString.lines().mapNotNull { line ->
                val parts = line.split("] ")
                if (parts.size == 2) {
                    val time = parts[0].replace("[", "").split(":").let { (minutes, seconds) ->
                       try {
                           minutes.toLong() * 60 * 1000 + (seconds.toDouble() * 1000).toLong()
                       } catch (e: Exception) {
                           println("Current Input: $minutes")
                           e.printStackTrace()
                           0
                       }
                    }
                    if (time in seenTimes) {
                        null
                    } else {
                        seenTimes.add(time)
                        val text = parts[1]
                        Lyrics.Item(
                            startTime = time,
                            text = text,
                            endTime = time
                        )
                    }
                } else {
                    null
                }
            },
            fillTimeGaps = true
        )
    }

    private companion object {
        private const val LRC_BASE_URL = "https://lrclib.net"
    }
}