package wallpaper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            assertEquals(1, store.removeMedia(item.id))
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
            assertEquals(2, store.deletePlaylist(doomed.id))
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

    @Test fun groupedMembershipPreservesEachPlaylistsOrder() {
        Store(directory.resolve("membership.db")).use { store ->
            for (name in listOf("first.png", "second.png", "third.png")) {
                store.importMedia(directory.resolve(name), MediaKind.IMAGE, null, null, 3)
            }
            store.createPlaylist("First", OrderMode.SEQUENTIAL, 60)
            store.createPlaylist("Second", OrderMode.SEQUENTIAL, 60)
            store.createPlaylist("Empty", OrderMode.SEQUENTIAL, 60)
            val playlists = store.listPlaylists().associateBy { it.name }
            val first = playlists.getValue("First").id
            val second = playlists.getValue("Second").id
            val media = store.listMedia().associateBy { it.name }
            store.addToPlaylist(first, media.getValue("second.png").id)
            store.addToPlaylist(first, media.getValue("first.png").id)
            store.addToPlaylist(second, media.getValue("third.png").id)
            store.addToPlaylist(second, media.getValue("second.png").id)

            val members = store.playlistMembers()
            assertEquals(listOf("second.png", "first.png"), members.getValue(first).map { it.name })
            assertEquals(listOf("third.png", "second.png"), members.getValue(second).map { it.name })
            assertEquals(2, store.playlist(first)?.count)
            assertEquals(0, store.playlist(playlists.getValue("Empty").id)?.count)
            assertTrue(members[playlists.getValue("Empty").id].orEmpty().isEmpty())
        }
    }

    @Test fun settingsUpdatesRollbackTogetherAndConnectionRemainsUsable() {
        val database = directory.resolve("settings.db")
        Store(database).use { store ->
            store.setSetting("active_playlist", "original")
            // Simulate a database write failure midway through a playback-state update.
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use {
                    it.execute("""
                        CREATE TRIGGER reject_setting BEFORE INSERT ON settings
                        WHEN NEW.key='reject' BEGIN SELECT RAISE(ABORT,'test failure'); END
                    """.trimIndent())
                }
            }
            assertFailsWith<SQLException> {
                store.setSettings("active_playlist" to "new", "reject" to "value")
            }
            assertEquals("original", store.setting("active_playlist"))
            store.setSettings("playlist_due" to "1000", "playlist_bag" to "1,2")
            assertEquals(mapOf("active_playlist" to "original", "playlist_due" to "1000",
                "playlist_bag" to "1,2"), store.allSettings())
        }
        Store(database).use { store ->
            assertEquals("1000", store.setting("playlist_due"))
            assertEquals("fallback", store.setting("missing", "fallback"))
        }
    }
}
