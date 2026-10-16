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

    @Test fun removingMediaCascadesToItsDirectScheduleRules() {
        val image = directory.resolve("scheduled.png")
        Files.write(image, byteArrayOf(1, 2, 3))
        Store(directory.resolve("cascade.db")).use { store ->
            store.importMedia(image, MediaKind.IMAGE, 100, 100, 3)
            val item = store.listMedia().single()
            store.addRule("07:00", item.id, null)
            assertEquals(1, store.listRules().size)
            store.removeMedia(item.id)
            assertTrue(store.listRules().isEmpty())
            assertTrue(Files.exists(image))
        }
    }

    @Test fun deletingPlaylistCascadesOnlyRulesTargetingThatPlaylist() {
        Store(directory.resolve("playlist-rules.db")).use { store ->
            store.createPlaylist("Delete", OrderMode.SEQUENTIAL, 60)
            store.createPlaylist("Keep", OrderMode.RANDOM, 120)
            val doomed = store.listPlaylists().first { it.name == "Delete" }
            val keeper = store.listPlaylists().first { it.name == "Keep" }
            store.addRule("07:00", null, doomed.id)
            store.addRule("09:00", null, doomed.id)
            store.addRule("13:00", null, keeper.id)
            assertEquals(2, store.listRules().count { it.playlistId == doomed.id })
            store.deletePlaylist(doomed.id)
            assertEquals(listOf(keeper.id), store.listRules().mapNotNull { it.playlistId })
        }
    }

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
