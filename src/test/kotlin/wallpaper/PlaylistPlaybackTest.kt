package wallpaper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlaylistPlaybackTest {
    @TempDir lateinit var directory: Path

    private fun fixture(): Pair<Store, Long> {
        val image = directory.resolve("wallpaper.png")
        Files.write(image, byteArrayOf(1, 2, 3))
        val store = Store(directory.resolve("playback.db"))
        store.importMedia(image, MediaKind.IMAGE, 1920, 1080, 3)
        store.createPlaylist("Morning", OrderMode.SEQUENTIAL, 300)
        val id = store.listPlaylists().single().id
        store.addToPlaylist(id, store.listMedia().single().id)
        return store to id
    }

    @Test fun failedInitialApplicationNeverRemainsScheduled() {
        val (store, id) = fixture()
        store.use {
            val runner = PlaylistPlayback(store, apply = {
                throw IllegalStateException("Wallpaper command failed")
            })
            assertFailsWith<IllegalStateException> { runner.start(id) }
            assertEquals("", store.setting("active_playlist"))
            assertEquals("", store.setting("playlist_due"))
            // Calling advance in the next five-second scheduler tick is now inert.
            runner.advance()
            assertEquals("", store.setting("active_playlist"))
        }
    }

    @Test fun failureDuringLaterAdvanceAlsoStopsPlayback() {
        val (store, id) = fixture()
        store.use {
            var calls = 0
            val runner = PlaylistPlayback(store, apply = {
                calls++
                if (calls == 2) throw IllegalStateException("Native command unavailable")
            }, nowMillis = { 1000L })
            runner.start(id)
            assertEquals(id.toString(), store.setting("active_playlist"))
            assertEquals("301000", store.setting("playlist_due"))
            assertFailsWith<IllegalStateException> { runner.advance() }
            assertEquals("", store.setting("active_playlist"))
            assertEquals("", store.setting("playlist_due"))
            runner.advance()
            assertEquals(2, calls)
        }
    }

    @Test fun linkageFailureAlsoClearsActivePlaylist() {
        val (store, id) = fixture()
        store.use {
            val runner = PlaylistPlayback(store, apply = {
                throw UnsatisfiedLinkError("Native integration unavailable")
            })
            assertFailsWith<UnsatisfiedLinkError> { runner.start(id) }
            assertEquals("", store.setting("active_playlist"))
            assertTrue(store.listPlaylists().any { it.id == id })
        }
    }
}
