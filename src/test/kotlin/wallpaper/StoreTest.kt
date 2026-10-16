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

    @Test fun reimportChangesRevisionEvenWhenPathSizeAndTimestampAreUnchanged() {
        val image = Files.write(directory.resolve("replaced.png"), byteArrayOf(1, 2, 3))
        val modifiedAt = Files.getLastModifiedTime(image)
        val database = directory.resolve("revision.db")
        Store(database).use { store ->
            store.importMedia(image, MediaKind.IMAGE, 100, 50, 3)
            val original = store.listMedia().single()
            store.favorite(original.id, true)
            store.createPlaylist("Keep membership", OrderMode.SEQUENTIAL, 60)
            val playlist = store.listPlaylists().single()
            store.addToPlaylist(playlist.id, original.id)
            store.addRule("07:00", original.id, null)

            Files.write(image, byteArrayOf(3, 2, 1))
            Files.setLastModifiedTime(image, modifiedAt)
            store.importMedia(image, MediaKind.IMAGE, 100, 50, 3)
            val updated = store.listMedia().single()
            assertEquals(original.id, updated.id)
            assertEquals(original.addedAt, updated.addedAt)
            assertEquals(original.bytes, updated.bytes)
            assertEquals(modifiedAt, Files.getLastModifiedTime(image))
            assertEquals(original.revision + 1, updated.revision)
            assertTrue(updated.favorite)
            assertEquals(listOf(updated), store.playlistItems(playlist.id))
            assertEquals(original.id, store.listRules().single().mediaId)
        }
        Store(database).use { store ->
            assertEquals(1L, store.listMedia().single().revision)
        }
    }

    @Test fun existingLibraryGainsRevisionsWithoutLosingMedia() {
        val database = directory.resolve("legacy.db")
        val image = directory.resolve("existing.png")
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use {
                it.execute("""
                    CREATE TABLE media (
                        id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, kind TEXT NOT NULL,
                        width INTEGER, height INTEGER, bytes INTEGER NOT NULL,
                        favorite INTEGER NOT NULL DEFAULT 0, added_at INTEGER NOT NULL
                    )
                """.trimIndent())
            }
            connection.prepareStatement("""
                INSERT INTO media(id,path,kind,bytes,favorite,added_at) VALUES (7,?,'IMAGE',3,1,123)
            """.trimIndent()).use {
                it.setString(1, image.toString())
                it.executeUpdate()
            }
        }
        Store(database).use { store ->
            val item = store.listMedia().single()
            assertEquals(7L, item.id)
            assertEquals(123L, item.addedAt)
            assertEquals(0L, item.revision)
            assertTrue(item.favorite)
            store.importMedia(image, MediaKind.IMAGE, null, null, 3)
            assertEquals(1L, store.mediaById(item.id)?.revision)
        }
        Store(database).use { store ->
            assertEquals(1L, store.listMedia().single().revision)
        }
    }
}
