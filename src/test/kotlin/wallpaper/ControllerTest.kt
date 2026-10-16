package wallpaper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControllerTest {
    @TempDir lateinit var directory: Path

    private fun controllerTest(test: suspend (Controller, Store, Job) -> Unit) = runBlocking {
        withTimeout(10_000) {
            val store = Store(directory.resolve("controller.db"))
            val lifetime = SupervisorJob()
            val controller = Controller(store, CoroutineScope(lifetime + Dispatchers.IO))
            try {
                test(controller, store, lifetime)
            } finally {
                controller.close()
                lifetime.join()
            }
        }
    }

    @Test fun actionsPublishLibraryMembershipAvailabilityAndSettingsTogether() = controllerTest { controller, store, _ ->
        val image = Files.write(directory.resolve("present.png"), byteArrayOf(1, 2, 3))
        controller.perform {
            store.importMedia(image, MediaKind.IMAGE, 100, 50, 3)
            store.importMedia(directory.resolve("missing.png"), MediaKind.IMAGE, null, null, 3)
            store.createPlaylist("Morning", OrderMode.SEQUENTIAL, 60)
            val item = store.listMedia().first { it.path == image }
            store.addToPlaylist(store.listPlaylists().single().id, item.id)
            store.setSettings("current_path" to image.toString(), "appearance" to "light", "style" to "invalid")
            "Ready"
        }.join()

        val state = controller.state
        assertEquals(2, state.media.size)
        assertEquals(image, state.currentMedia?.path)
        assertEquals(setOf(state.currentMedia!!.id), state.availableMediaIds)
        assertEquals(listOf(image), state.members.getValue(state.playlists.single().id).map { it.path })
        assertEquals(WallpaperStyle.FILL, state.style)
        assertFalse(state.dark)
        assertEquals("Ready", state.notice)
    }

    @Test fun cancellationIsNotReportedAsAnOperationFailure() = controllerTest { controller, _, lifetime ->
        controller.perform { "Keep this notice" }.join()
        val cancelled = controller.perform { throw CancellationException("cancelled action") }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertTrue(lifetime.isActive)
        assertEquals("Keep this notice", controller.state.notice)
        controller.perform { "Next action succeeded" }.join()
        assertEquals("Next action succeeded", controller.state.notice)
    }

    @Test fun nativeAndOrdinaryFailuresStillRefreshPersistedState() = controllerTest { controller, store, _ ->
        controller.perform {
            store.setSetting("appearance", "light")
            throw UnsatisfiedLinkError("Native backend unavailable")
        }.join()
        assertFalse(controller.state.dark)
        assertEquals("Native backend unavailable", controller.state.notice)
        controller.perform { throw IllegalArgumentException("Invalid action") }.join()
        assertEquals("Invalid action", controller.state.notice)
    }

    @Test fun shutdownClosesTheDatabaseAfterAnInFlightActionFinishes() = controllerTest { controller, store, lifetime ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        controller.perform {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test action was not released" }
            store.setSetting("finished", "true")
            null
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.close()
            // Cancellation cannot interrupt a blocking native call; its database must stay usable.
            assertEquals("", store.setting("finished"))
        } finally {
            release.countDown()
        }
        lifetime.join()
        assertFailsWith<SQLException> { store.setting("finished") }
        Store(directory.resolve("controller.db")).use { reopened ->
            assertEquals("true", reopened.setting("finished"))
        }
    }
}
