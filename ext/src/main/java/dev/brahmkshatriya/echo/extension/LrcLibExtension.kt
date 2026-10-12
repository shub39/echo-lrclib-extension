package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LrcLibExtension : ExtensionClient, LyricsClient, LyricsSearchClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private var setting: Settings? = null

    override suspend fun onExtensionSelected() {}

    override fun setSettings(settings: Settings) {
        setting = settings
    }

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingSwitch(
            title = "Strip LRC Metadata Tags",
            key = SETTING_CLEAN_HEADERS,
            summary = "Removes [ti:], [ar:], [al:], [by:] tags and section headers from lyrics",
            defaultValue = SETTING_CLEAN_HEADERS_DEFAULT
        ),
        SettingSwitch(
            title = "Prefer Synced Lyrics",
            key = SETTING_PREFER_SYNCED,
            summary = "Prefer time-synced lyrics over plain text when available",
            defaultValue = SETTING_PREFER_SYNCED_DEFAULT
        ),
        SettingSwitch(
            title = "Show Instrumental Notice",
            key = SETTING_SHOW_INSTRUMENTAL,
            summary = "Display ♪ Instrumental ♪ for instrumental tracks",
            defaultValue = SETTING_SHOW_INSTRUMENTAL_DEFAULT
        ),
        SettingTextInput(
            title = "Custom LRCLIB Instance",
            key = SETTING_CUSTOM_INSTANCE,
            summary = "Optional: Custom self-hosted LRCLIB instance URL (defaults to https://lrclib.net)",
            defaultValue = ""
        )
    )

    override suspend fun searchTrackLyrics(
        clientId: String,
        track: Track
    ): Feed<Lyrics> = withContext(Dispatchers.IO) {
        val rawTitle = track.title.trim()
        val cleanTitle = cleanTrackTitle(rawTitle)
        val artist = track.artists.firstOrNull()?.name?.trim().orEmpty()
        val album = track.album?.title?.trim()
        val durationSeconds = track.duration?.takeIf { it > 0 }?.let { (it / 1000).toInt() }

        val collected = mutableListOf<LrcGetDto>()
        val seenIds = mutableSetOf<Long>()

        fun addDto(dto: LrcGetDto?) {
            if (dto != null && seenIds.add(dto.id)) {
                collected.add(dto)
            }
        }

        // Tier 1: Exact match lookup via /api/get with clean metadata and duration
        if (cleanTitle.isNotBlank() && artist.isNotBlank()) {
            addDto(getExactLyrics(cleanTitle, artist, album, durationSeconds))

            // Tier 2: If not found with duration, try without duration/album
            if (collected.isEmpty() && durationSeconds != null) {
                addDto(getExactLyrics(cleanTitle, artist, null, null))
            }
        }

        // Tier 3: If clean metadata differed from raw title, try raw title exact lookup
        if (collected.isEmpty() && rawTitle.isNotBlank() && rawTitle != cleanTitle && artist.isNotBlank()) {
            addDto(getExactLyrics(rawTitle, artist, album, durationSeconds))
        }

        // Tier 4: Targeted search by track_name and artist_name
        if (collected.isEmpty() && cleanTitle.isNotBlank()) {
            val targetedSearch = searchTargetedLyrics(cleanTitle, artist)
            for (dto in targetedSearch) {
                addDto(dto)
            }
        }

        // Tier 5: General query search fallback
        if (collected.isEmpty()) {
            val query = if (artist.isNotBlank()) "$cleanTitle $artist" else cleanTitle
            if (query.isNotBlank()) {
                val searchResults = searchQueryLyrics(query)
                for (dto in searchResults) {
                    addDto(dto)
                }
            }
        }

        // Tier 6: Fallback to raw title search if clean search was empty
        if (collected.isEmpty() && rawTitle != cleanTitle) {
            val rawQuery = if (artist.isNotBlank()) "$rawTitle $artist" else rawTitle
            if (rawQuery.isNotBlank()) {
                val searchResults = searchQueryLyrics(rawQuery)
                for (dto in searchResults) {
                    addDto(dto)
                }
            }
        }

        val lyricsList = collected.mapNotNull { dtoToLyrics(it) }
        lyricsList.toFeed()
    }

    override suspend fun searchLyrics(query: String): Feed<Lyrics> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return@withContext emptyList<Lyrics>().toFeed()

        val results = searchQueryLyrics(trimmedQuery)
        val lyricsList = results.mapNotNull { dtoToLyrics(it) }

        if (lyricsList.isEmpty()) {
            val cleanedQuery = cleanTrackTitle(trimmedQuery)
            if (cleanedQuery.isNotBlank() && cleanedQuery != trimmedQuery) {
                val fallbackResults = searchQueryLyrics(cleanedQuery)
                return@withContext fallbackResults.mapNotNull { dtoToLyrics(it) }.toFeed()
            }
        }

        lyricsList.toFeed()
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = withContext(Dispatchers.IO) {
        if (lyrics.lyrics != null) return@withContext lyrics

        val id = lyrics.id.toLongOrNull() ?: return@withContext lyrics
        val dto = getLyricsById(id) ?: return@withContext lyrics
        dtoToLyrics(dto) ?: lyrics
    }

    private suspend fun getExactLyrics(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSeconds: Int?
    ): LrcGetDto? {
        val httpUrlBuilder = getBaseUrl().toHttpUrlOrNull()?.newBuilder() ?: return null
        httpUrlBuilder.addPathSegments("api/get")
        httpUrlBuilder.addQueryParameter("track_name", trackName)
        httpUrlBuilder.addQueryParameter("artist_name", artistName)
        if (!albumName.isNullOrBlank()) {
            httpUrlBuilder.addQueryParameter("album_name", albumName)
        }
        if (durationSeconds != null && durationSeconds > 0) {
            httpUrlBuilder.addQueryParameter("duration", durationSeconds.toString())
        }

        val request = Request.Builder()
            .url(httpUrlBuilder.build())
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json")
            .build()

        return runCatching {
            val response = client.newCall(request).await()
            if (response.isSuccessful) {
                val body = response.body.string()
                json.decodeFromString<LrcGetDto>(body)
            } else {
                null
            }
        }.getOrNull()
    }

    private suspend fun getLyricsById(id: Long): LrcGetDto? {
        val httpUrlBuilder = getBaseUrl().toHttpUrlOrNull()?.newBuilder() ?: return null
        httpUrlBuilder.addPathSegments("api/get/$id")

        val request = Request.Builder()
            .url(httpUrlBuilder.build())
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json")
            .build()

        return runCatching {
            val response = client.newCall(request).await()
            if (response.isSuccessful) {
                val body = response.body.string()
                json.decodeFromString<LrcGetDto>(body)
            } else {
                null
            }
        }.getOrNull()
    }

    private suspend fun searchTargetedLyrics(
        trackName: String,
        artistName: String
    ): List<LrcGetDto> {
        val httpUrlBuilder = getBaseUrl().toHttpUrlOrNull()?.newBuilder() ?: return emptyList()
        httpUrlBuilder.addPathSegments("api/search")
        httpUrlBuilder.addQueryParameter("track_name", trackName)
        if (artistName.isNotBlank()) {
            httpUrlBuilder.addQueryParameter("artist_name", artistName)
        }

        return executeSearchRequest(httpUrlBuilder.build())
    }

    private suspend fun searchQueryLyrics(query: String): List<LrcGetDto> {
        val httpUrlBuilder = getBaseUrl().toHttpUrlOrNull()?.newBuilder() ?: return emptyList()
        httpUrlBuilder.addPathSegments("api/search")
        httpUrlBuilder.addQueryParameter("q", query)

        return executeSearchRequest(httpUrlBuilder.build())
    }

    private suspend fun executeSearchRequest(url: okhttp3.HttpUrl): List<LrcGetDto> {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json")
            .build()

        return runCatching {
            val response = client.newCall(request).await()
            if (response.isSuccessful) {
                val body = response.body.string()
                json.decodeFromString<List<LrcGetDto>>(body)
            } else {
                emptyList()
            }
        }.getOrElse { emptyList() }
    }

    private fun dtoToLyrics(dto: LrcGetDto): Lyrics? {
        val title = dto.trackName?.ifBlank { null }
            ?: dto.name?.ifBlank { null }
            ?: return null

        val subtitle = listOfNotNull(
            dto.artistName?.takeIf { it.isNotBlank() },
            dto.albumName?.takeIf { it.isNotBlank() }
        ).joinToString(" • ")

        val lyricsContent = when {
            dto.instrumental -> {
                if (getShowInstrumental()) {
                    Lyrics.Simple("♪ Instrumental ♪")
                } else {
                    null
                }
            }
            getPreferSynced() && !dto.syncedLyrics.isNullOrBlank() -> {
                parseSyncedLyrics(dto.syncedLyrics) ?: dto.plainLyrics?.let { parsePlainLyrics(it) }
            }
            !dto.plainLyrics.isNullOrBlank() -> {
                parsePlainLyrics(dto.plainLyrics) ?: dto.syncedLyrics?.let { parseSyncedLyrics(it) }
            }
            !dto.syncedLyrics.isNullOrBlank() -> {
                parseSyncedLyrics(dto.syncedLyrics)
            }
            else -> null
        }

        if (lyricsContent == null && !dto.instrumental) {
            return null
        }

        return Lyrics(
            id = dto.id.toString(),
            title = title,
            subtitle = subtitle.ifBlank { null },
            lyrics = lyricsContent
        )
    }

    fun parseSyncedLyrics(lyricsString: String?): Lyrics.Lyric? {
        if (lyricsString.isNullOrBlank()) return null

        val parsed = mutableListOf<Pair<Long, String>>()

        for (rawLine in lyricsString.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (METADATA_TAG_REGEX.matcher(line).matches()) {
                continue
            }

            val matcher = TIMESTAMP_REGEX.matcher(line)
            val lineTimestamps = mutableListOf<Long>()
            var lastMatchEnd = 0

            while (matcher.find()) {
                val minutes = matcher.group(1)?.toLongOrNull() ?: 0L
                val seconds = matcher.group(2)?.toLongOrNull() ?: 0L
                val fracStr = matcher.group(3)
                val millis = when (fracStr?.length) {
                    1 -> fracStr.toLong() * 100L
                    2 -> fracStr.toLong() * 10L
                    3 -> fracStr.toLong()
                    else -> 0L
                }
                val totalMs = minutes * 60_000L + seconds * 1_000L + millis
                lineTimestamps.add(totalMs)
                lastMatchEnd = matcher.end()
            }

            if (lineTimestamps.isEmpty()) {
                continue
            }

            var text = line.substring(lastMatchEnd).trim()
            if (getCleanHeaders() && SECTION_HEADER_REGEX.matcher(text).matches()) {
                text = ""
            }

            val lyricText = text.ifBlank { "♪" }
            for (time in lineTimestamps) {
                parsed.add(Pair(time, lyricText))
            }
        }

        if (parsed.isEmpty()) return null

        parsed.sortBy { it.first }

        val items = mutableListOf<Lyrics.Item>()
        for (i in parsed.indices) {
            val current = parsed[i]
            val startTime = current.first
            val endTime = if (i < parsed.size - 1) {
                val nextStart = parsed[i + 1].first
                if (nextStart > startTime) nextStart else startTime + 1000L
            } else {
                startTime + 4000L
            }
            items.add(
                Lyrics.Item(
                    text = current.second,
                    startTime = startTime,
                    endTime = endTime
                )
            )
        }

        return Lyrics.Timed(items, fillTimeGaps = false)
    }

    fun parsePlainLyrics(plainLyrics: String): Lyrics.Lyric? {
        val trimmed = plainLyrics.trim()
        if (trimmed.isEmpty()) return null

        val lines = trimmed.lines().map { it.trim() }
        val filteredLines = if (getCleanHeaders()) {
            lines.filterNot { line ->
                SECTION_HEADER_REGEX.matcher(line).matches() || METADATA_TAG_REGEX.matcher(line).matches()
            }
        } else {
            lines
        }

        val cleanedText = filteredLines.joinToString("\n").trim()
        return if (cleanedText.isNotEmpty()) Lyrics.Simple(cleanedText) else null
    }

    fun cleanTrackTitle(title: String): String {
        return title
            .let { NOISE_REGEX.matcher(it).replaceAll("") }
            .let { FEATURE_REGEX.matcher(it).replaceAll("") }
            .trim()
            .removeSuffix("()")
            .removeSuffix("[]")
            .trim()
    }

    private fun getCleanHeaders(): Boolean =
        setting?.getBoolean(SETTING_CLEAN_HEADERS) ?: SETTING_CLEAN_HEADERS_DEFAULT

    private fun getPreferSynced(): Boolean =
        setting?.getBoolean(SETTING_PREFER_SYNCED) ?: SETTING_PREFER_SYNCED_DEFAULT

    private fun getShowInstrumental(): Boolean =
        setting?.getBoolean(SETTING_SHOW_INSTRUMENTAL) ?: SETTING_SHOW_INSTRUMENTAL_DEFAULT

    private fun getBaseUrl(): String =
        setting?.getString(SETTING_CUSTOM_INSTANCE)?.trim()?.takeIf { it.isNotBlank() }?.removeSuffix("/")
            ?: DEFAULT_BASE_URL

    companion object {
        const val DEFAULT_BASE_URL = "https://lrclib.net"
        const val USER_AGENT = "Echo-LrcLib-Extension/1.0.0 (https://github.com/shub39/echo-lrclib-extension)"

        const val SETTING_CLEAN_HEADERS = "clean_headers"
        const val SETTING_CLEAN_HEADERS_DEFAULT = true

        const val SETTING_PREFER_SYNCED = "prefer_synced"
        const val SETTING_PREFER_SYNCED_DEFAULT = true

        const val SETTING_SHOW_INSTRUMENTAL = "show_instrumental"
        const val SETTING_SHOW_INSTRUMENTAL_DEFAULT = true

        const val SETTING_CUSTOM_INSTANCE = "custom_instance"

        private val NOISE_REGEX = Pattern.compile(
            "(?i)\\s*[\\[(](?:official\\s*(?:video|audio|music\\s*video|lyric\\s*video|visualizer)?|remastered(?:\\s*\\d{4})?|remaster|hd|4k|audio|lyrics|mv|m/v|live|bonus\\s*track)[\\])]"
        )
        private val FEATURE_REGEX = Pattern.compile(
            "(?i)\\s*(?:[\\(\\[]\\s*)?(?:feat|ft|featuring)\\.?\\s+.*?(?:[\\]\\)]|$)"
        )
        private val METADATA_TAG_REGEX = Pattern.compile(
            "^\\[(ti|ar|al|by|offset|re|ve|length):.*?\\]$",
            Pattern.CASE_INSENSITIVE
        )
        private val SECTION_HEADER_REGEX = Pattern.compile(
            "^\\[(Verse|Chorus|Bridge|Outro|Intro|Pre-Chorus|Hook|Interlude|Refrain|Solo|Drop).*?\\]$",
            Pattern.CASE_INSENSITIVE
        )
        private val TIMESTAMP_REGEX = Pattern.compile(
            "\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]"
        )
    }
}
