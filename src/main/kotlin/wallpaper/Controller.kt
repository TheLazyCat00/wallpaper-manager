package wallpaper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.time.LocalDateTime

/** Serializes wallpaper changes, scheduled jobs and user actions on an I/O dispatcher. */
class Controller internal constructor(
    private val store: Store = Store(AppFolders.home.resolve("library.db")),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : AutoCloseable {
    private val desktop = DesktopWallpaper(store)
    private val playback = PlaylistPlayback(store, apply = ::apply)
    private val gate = Mutex()

    internal var state by mutableStateOf(AppState())
        private set

    init {
        // The owned scope completes only after its children finish unwinding.
        scope.coroutineContext.job.invokeOnCompletion { store.close() }
        scope.launch {
            execute { null }
            while (isActive) {
                execute("Automation: ") { tick(); null }
                delay(5_000)
            }
        }
    }

    private fun refresh(notice: String?) {
        val media = store.listMedia()
        val settings = store.allSettings()
        state = AppState(
            media = media,
            playlists = store.listPlaylists(),
            members = store.playlistMembers(),
            rules = store.listRules(),
            availableMediaIds = media.filter { it.present }.mapTo(mutableSetOf()) { it.id },
            currentPath = settings["current_path"].orEmpty(),
            runningPlaylist = settings["active_playlist"]?.toLongOrNull(),
            dark = settings.getOrDefault("appearance", "dark") == "dark",
            scheduleEnabled = settings.getOrDefault("schedule_enabled", "true") == "true",
            closeToTray = settings.getOrDefault("close_to_tray", "true") == "true",
            style = WallpaperStyle.fromSetting(settings["style"]),
            canRestore = desktop.canRestore(),
            notice = notice ?: state.notice
        )
    }

    /** Actions and automation share the same lock, error handling, and state publication. */
    private suspend fun execute(errorPrefix: String = "", action: () -> String?) = gate.withLock {
        var notice: String? = null
        reportFailure(errorPrefix) { notice = action() }
        currentCoroutineContext().ensureActive()
        reportFailure(errorPrefix) { refresh(notice) }
    }

    private inline fun reportFailure(prefix: String, action: () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            state = state.copy(notice = prefix + (failure.message ?: failure.javaClass.simpleName))
        } catch (failure: LinkageError) {
            state = state.copy(notice = prefix + (failure.message ?: failure.javaClass.simpleName))
        }
    }

    /** Returns a job immediately; blocking I/O stays on the controller's owned scope. */
    internal fun perform(action: () -> String?) = scope.launch { execute(action = action) }

    fun importFiles(paths: List<Path>) = perform {
        val result = MediaFiles.importPaths(store, paths)
        "Imported or updated ${result.imported} media files" +
            if (result.failed > 0) " (${result.failed} failed)" else ""
    }
    fun toggleFavorite(item: Media) = perform {
        store.favorite(item.id, !item.favorite)
        null
    }
    fun removeFromLibrary(item: Media) = perform {
        val deletedRules = store.removeMedia(item.id)
        "Removed from library (source file kept; $deletedRules matching daily rules removed)"
    }
    fun setAppearance(isDark: Boolean) = perform {
        store.setSetting("appearance", if (isDark) "dark" else "light")
        null
    }
    fun configureCloseToTray(enabled: Boolean) = perform {
        store.setSetting("close_to_tray", enabled.toString())
        null
    }
    fun selectStyle(newStyle: WallpaperStyle) = perform {
        store.setSetting("style", newStyle.name)
        null
    }

    private fun apply(item: Media) {
        require(item.kind == MediaKind.IMAGE) {
            "Live video wallpaper is not implemented in this Kotlin port yet"
        }
        val selectedStyle = WallpaperStyle.fromSetting(store.setting("style", WallpaperStyle.FILL.name))
        desktop.set(item.path, selectedStyle)
    }

    fun applyMedia(item: Media) = perform {
        playback.stop()
        apply(item)
        "Desktop wallpaper: " + item.name
    }
    fun restoreOriginal() = perform {
        playback.stop()
        desktop.restore()
        "Restored the original Windows desktop wallpaper"
    }
    fun random() = perform {
        val candidates = store.listMedia().filter { it.kind == MediaKind.IMAGE && it.present }
        require(candidates.isNotEmpty()) { "Import some images first" }
        val next = candidates.random()
        playback.stop()
        apply(next)
        "Random wallpaper: " + next.name
    }
    fun next() = perform {
        if (store.setting("active_playlist").isNotBlank()) {
            playback.advance()
        } else {
            val images = store.listMedia().filter { it.kind == MediaKind.IMAGE && it.present }
            require(images.isNotEmpty()) { "Import some images first" }
            val currentPath = store.setting("current_path")
            val index = images.indexOfFirst { it.path.toString() == currentPath }
            val next = images[(index + 1) % images.size]
            playback.stop()
            apply(next)
        }
        "Advanced to next wallpaper"
    }

    fun createPlaylist(name: String, mode: OrderMode, intervalMinutes: Int) = perform {
        store.createPlaylist(name, mode, intervalMinutes.coerceAtLeast(1) * 60)
        "Created playlist: " + name.trim()
    }
    fun addToPlaylist(playlistId: Long, item: Media) = perform {
        require(item.kind == MediaKind.IMAGE) { "Video wallpapers are not yet supported for playback" }
        store.addToPlaylist(playlistId, item.id)
        "Added " + item.name + " to playlist"
    }
    fun removeFromPlaylist(playlistId: Long, mediaId: Long) = perform {
        store.removeFromPlaylist(playlistId, mediaId)
        "Removed item from playlist"
    }
    fun deletePlaylist(id: Long) = perform {
        if (store.setting("active_playlist").toLongOrNull() == id) playback.stop()
        val deletedRules = store.deletePlaylist(id)
        "Playlist deleted ($deletedRules matching daily schedule rules removed)"
    }
    fun startPlaylist(id: Long) = perform {
        playback.start(id)
        "Playlist started"
    }
    fun stop() = perform {
        playback.stop()
        "Playlist stopped"
    }

    fun addRule(time: String, itemId: Long?, playlistId: Long?) = perform {
        store.addRule(time, itemId, playlistId)
        markScheduleCurrent()
        "Added daily rule for " + time
    }
    fun removeRule(id: Long) = perform {
        store.removeRule(id)
        markScheduleCurrent()
        "Rule deleted"
    }
    fun toggleRule(rule: DailyRule) = perform {
        store.ruleEnabled(rule.id, !rule.enabled)
        markScheduleCurrent()
        "Rule updated"
    }
    fun enableSchedule(enabled: Boolean) = perform {
        store.setSetting("schedule_enabled", enabled.toString())
        markScheduleCurrent()
        null
    }
    fun applyRuleNow(rule: DailyRule) = perform {
        applyRule(rule)
        markScheduleCurrent()
        "Applied schedule rule for " + rule.time
    }

    private fun markScheduleCurrent() {
        val active = DailySchedule.active(store.listRules(), LocalDateTime.now())
        store.setSetting("schedule_last", active?.key ?: "")
    }

    private fun applyRule(rule: DailyRule) {
        if (rule.playlistId != null) {
            playback.start(rule.playlistId)
        } else {
            val item = store.mediaById(requireNotNull(rule.mediaId)) ?: error("Scheduled file missing")
            playback.stop()
            apply(item)
        }
    }

    private fun tick() {
        if (store.setting("schedule_enabled", "true") == "true") {
            val occurrence = DailySchedule.active(store.listRules(), LocalDateTime.now())
            if (occurrence != null && occurrence.key != store.setting("schedule_last")) {
                // Mark before applying so broken files aren't retried continuously.
                store.setSetting("schedule_last", occurrence.key)
                applyRule(occurrence.rule)
            }
        }
        if (store.setting("active_playlist").isNotBlank()) {
            val due = store.setting("playlist_due", "0").toLongOrNull() ?: 0L
            if (System.currentTimeMillis() >= due) playback.advance()
        }
    }

    override fun close() {
        scope.cancel()
    }
}
