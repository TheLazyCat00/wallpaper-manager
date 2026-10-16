package wallpaper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreTest {
    @TempDir lateinit var directory: Path

    @Test fun savesLibraryFavoritesPlaylistsAndRules() {
        val image = directory.resolve("picture.png")
        Files.write(image, byteArrayOf(1, 2, 3))
        Store(directory.resolve("test.db")).use { store ->
            store.importMedia(image, MediaKind.IMAGE, 1920, 1080, 3)
            store.importMedia(image, MediaKind.IMAGE, 1920, 1080, 3)
            val item = store.listMedia().single()
            assertEquals(1920, item.width)
            store.favorite(item.id, true)
            assertTrue(store.listMedia().single().favorite)
            store.createPlaylist("Daytime", OrderMode.RANDOM, 300)
            val playlist = store.listPlaylists().single()
            store.addToPlaylist(playlist.id, item.id)
            store.addToPlaylist(playlist.id, item.id)
            assertEquals(1, store.playlistItems(playlist.id).size)
            store.addRule("07:00", null, playlist.id)
            assertEquals(1, store.listRules().size)
            store.removeMedia(item.id)
            assertTrue(store.playlistItems(playlist.id).isEmpty())
            assertFalse(store.listRules().isEmpty()) // This rule points at the playlist, not the image.
        }
    }
}
