package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@ExperimentalCoroutinesApi
class ExtensionUnitTest {

    private val extension = LrcLibExtension()
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")
    private val settings = MockedSettings()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(settings)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    @Test
    fun testSettings() = runBlocking {
        val items = extension.getSettingItems()
        assertTrue(items.isNotEmpty())
        assertEquals(4, items.size)

        val keys = items.map { it.key }
        assertTrue(keys.contains(LrcLibExtension.SETTING_CLEAN_HEADERS))
        assertTrue(keys.contains(LrcLibExtension.SETTING_PREFER_SYNCED))
        assertTrue(keys.contains(LrcLibExtension.SETTING_SHOW_INSTRUMENTAL))
        assertTrue(keys.contains(LrcLibExtension.SETTING_CUSTOM_INSTANCE))
    }

    @Test
    fun testTitleCleaner() {
        assertEquals("Song Name", extension.cleanTrackTitle("Song Name (Official Video)"))
        assertEquals("Song Name", extension.cleanTrackTitle("Song Name [Official Music Video]"))
        assertEquals("Song Name", extension.cleanTrackTitle("Song Name (Remastered 2021)"))
        assertEquals("Song Name", extension.cleanTrackTitle("Song Name [MV]"))
        assertEquals("Song Name", extension.cleanTrackTitle("Song Name [4K]"))
        assertEquals("Song Name", extension.cleanTrackTitle("Song Name feat. Someone"))
        assertEquals("Song Name", extension.cleanTrackTitle("Song Name ft. Someone Else"))
        assertEquals("Song Name", extension.cleanTrackTitle("Song Name (feat. Someone)"))
    }

    @Test
    fun testSyncedLrcParsing() {
        val sampleLrc = """
            [ti:Test Title]
            [ar:Test Artist]
            [00:10.50]First line
            [00:15.500]Second line with 3 digits
            [00:20.10]Third line without space
            [00:25.00][00:30.00]Multi-timestamp chorus
            [00:35.00]
            [00:40.00]Last line
        """.trimIndent()

        val parsed = extension.parseSyncedLyrics(sampleLrc)
        assertNotNull(parsed)
        assertTrue(parsed is Lyrics.Timed)

        val timed = parsed as Lyrics.Timed
        val items = timed.list
        assertTrue(items.isNotEmpty())

        // Verify metadata tags were skipped
        assertFalse(items.any { it.text.contains("Test Title") || it.text.contains("Test Artist") })

        // Check first line: 10.50 seconds = 10,500 ms
        val item1 = items.first { it.startTime == 10500L }
        assertEquals("First line", item1.text)
        assertEquals(15500L, item1.endTime)

        // Check 3-digit millisecond: 15.500 seconds = 15,500 ms
        val item2 = items.first { it.startTime == 15500L }
        assertEquals("Second line with 3 digits", item2.text)

        // Check multi-timestamp line expanded to two instances
        assertTrue(items.any { it.startTime == 25000L && it.text == "Multi-timestamp chorus" })
        assertTrue(items.any { it.startTime == 30000L && it.text == "Multi-timestamp chorus" })

        // Check pause / empty line converted to indicator
        val pauseItem = items.first { it.startTime == 35000L }
        assertEquals("♪", pauseItem.text)

        // Check last line duration
        val lastItem = items.first { it.startTime == 40000L }
        assertEquals("Last line", lastItem.text)
        assertEquals(44000L, lastItem.endTime)
    }

    @Test
    fun testPlainLyricsCleaning() {
        val plain = """
            [Verse 1]
            Is this the real life?
            [Chorus]
            Mama, just killed a man
        """.trimIndent()

        // With clean headers enabled (default)
        settings.putBoolean(LrcLibExtension.SETTING_CLEAN_HEADERS, true)
        val cleaned = extension.parsePlainLyrics(plain)
        assertNotNull(cleaned)
        assertTrue(cleaned is Lyrics.Simple)
        val text = (cleaned as Lyrics.Simple).text
        assertFalse(text.contains("[Verse 1]"))
        assertFalse(text.contains("[Chorus]"))
        assertTrue(text.contains("Is this the real life?"))
        assertTrue(text.contains("Mama, just killed a man"))

        // With clean headers disabled
        settings.putBoolean(LrcLibExtension.SETTING_CLEAN_HEADERS, false)
        val raw = extension.parsePlainLyrics(plain)
        assertNotNull(raw)
        assertTrue((raw as Lyrics.Simple).text.contains("[Verse 1]"))
    }

    @Test
    fun testLiveExactTrackMatch() = runBlocking {
        val track = Track(
            id = "test-track-1",
            title = "Bohemian Rhapsody",
            artists = listOf(Artist(id = "queen", name = "Queen")),
            duration = 354_000L
        )

        val feed = extension.searchTrackLyrics("test-client", track)
        val list = feed.loadAll()
        assertTrue("Expected at least one matching lyric", list.isNotEmpty())

        val first = list.first()
        assertTrue(first.title.contains("Bohemian Rhapsody", ignoreCase = true))
        assertNotNull(first.lyrics)
        assertTrue(first.lyrics is Lyrics.Timed)
    }

    @Test
    fun testLiveMessyTitleTrackMatch() = runBlocking {
        // Track with messy title and featuring text
        val track = Track(
            id = "test-track-2",
            title = "Bohemian Rhapsody (Official Video) [Remastered]",
            artists = listOf(Artist(id = "queen", name = "Queen")),
            duration = 354_000L
        )

        val feed = extension.searchTrackLyrics("test-client", track)
        val list = feed.loadAll()
        assertTrue("Expected messy title to resolve via cleaner", list.isNotEmpty())
        assertTrue(list.first().title.contains("Bohemian Rhapsody", ignoreCase = true))
    }

    @Test
    fun testLiveSearchLyrics() = runBlocking {
        val feed = extension.searchLyrics("End of Beginning Djo")
        val list = feed.loadAll()
        assertTrue("Expected search results for End of Beginning", list.isNotEmpty())

        val first = list.first()
        assertNotNull(first.title)
        assertNotNull(first.lyrics)
    }

    @Test
    fun testLoadLyricsDeferred() = runBlocking {
        // Lyrics object with only ID
        val deferred = Lyrics(
            id = "18238251",
            title = "Bohemian Rhapsody",
            subtitle = "Peter Hollens",
            lyrics = null
        )

        val loaded = extension.loadLyrics(deferred)
        assertNotNull(loaded.lyrics)
    }
}
