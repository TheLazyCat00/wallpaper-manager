package wallpaper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.time.LocalDateTime

/** Serializes wallpaper changes, scheduled jobs and user actions on an I/O dispatcher. */
class Controller : AutoCloseable {
    private val store = Store(AppFolders.home.resolve("library.db"))
    private val desktop = DesktopWallpaper(store)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()

    var media by mutableStateOf<List<Media>>(emptyList())
        private set
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    var rules by mutableStateOf<List<DailyRule>>(emptyList())
        private set
    var notice by mutableStateOf("Import images or folders to get started.")
        private set
    var currentPath by mutableStateOf("")
        private set
    var runningPlaylist by mutableStateOf<Long?>(null)
        private set
    var dark by mutableStateOf(true)
        private set
    var scheduleEnabled by mutableStateOf(true)
        private set
    var style by mutableStateOf(WallpaperStyle.FILL)
        private set
    val canRestore get() = desktop.canRestore()

    init {
        refresh()
        scope.launch {
            while (true) {
                gate.withLock {
                    runCatching { tick() }.onFailure { notice = "Automation: " + (it.message ?: "failed") }
                    refresh()
                }
                delay(5_000)
            }
        }
    }

    private fun refresh() {
        media = store.listMedia()
        playlists = store.listPlaylists()
        rules = store.listRules()
        currentPath = store.setting("current_path")
        runningPlaylist = store.setting("active_playlist").toLongOrNull()
        dark = store.setting("appearance", "dark") == "dark"
        scheduleEnabled = store.setting("schedule_enabled", "true") == "true"
        style = runCatching {
            WallpaperStyle.valueOf(store.setting("style", WallpaperStyle.FILL.name))
        }.getOrDefault(WallpaperStyle.FILL)
    }

    /** Public actions deliberately return immediately; errors are surfaced in the status strip. */
    fun perform(action: () -> String?) {
        scope.launch {
            gate.withLock {
                try {
                    val message = action()
                    if (message != null) notice = message
                } catch (e: Exception) {
                    notice = e.message ?: e.javaClass.simpleName
                } finally {
                    refresh()
                }
            }
        }
    }

    fun importFiles(paths: List<Path>) = perform {
        val count = MediaFiles.importPaths(store, paths)
        "Imported or updated " + count + " media files"
    }
    fun toggleFavorite(item: Media) = perform {
        store.favorite(item.id, !item.favorite)
        null
    }
    fun removeFromLibrary(item: Media) = perform {
        store.removeMedia(item.id)
        "Removed from library (source file kept)"
    }
    fun setAppearance(isDark: Boolean) = perform {
        store.setSetting("appearance", if (isDark) "dark" else "light")
        null
    }
    fun selectStyle(newStyle: WallpaperStyle) = perform {
        store.setSetting("style", newStyle.name)
        null
    }

    private fun apply(item: Media, stopPlaylist: Boolean) {
        require(item.kind == MediaKind.IMAGE) {
            "Live video wallpaper is not implemented in this Kotlin port yet"
        }
        if (stopPlaylist) store.setSetting("active_playlist", "")
        desktop.set(item.path, WallpaperStyle.valueOf(store.setting("style", "FILL")))
    }

    fun applyMedia(item: Media) = perform {
        apply(item, stopPlaylist = true)
        "Desktop wallpaper: " + item.name
    }
    fun restoreOriginal() = perform {
        store.setSetting("active_playlist", "")
        desktop.restore()
        "Restored the original Windows desktop wallpaper"
    }
    fun random() = perform {
        val candidates = store.listMedia().filter { it.kind == MediaKind.IMAGE && it.present }
        require(candidates.isNotEmpty()) { "Import some images first" }
        val next = candidates.random()
        apply(next, stopPlaylist = true)
        "Random wallpaper: " + next.name
    }
    fun next() = perform {
        if (store.setting("active_playlist").isNotBlank()) {
            advancePlaylist()
        } else {
            val images = store.listMedia().filter { it.kind == MediaKind.IMAGE && it.present }
            require(images.isNotEmpty()) { "Import some images first" }
            val index = images.indexOfFirst { it.path.toString() == store.setting("current_path") }
            val next = images[(index + 1) % images.size]
            apply(next, stopPlaylist = true)
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
        if (store.setting("active_playlist").toLongOrNull() == id) stopPlaylist()
        store.deletePlaylist(id)
        "Playlist deleted"
    }
    fun startPlaylist(id: Long) = perform {
        require(store.playlist(id) != null) { "Playlist not found" }
        store.setSetting("active_playlist", id.toString())
        store.setSetting("playlist_index", "-1")
        store.setSetting("playlist_bag", "")
        store.setSetting("playlist_current", "")
        store.setSetting("playlist_due", "0")
        advancePlaylist()
        "Playlist started"
    }
    fun stop() = perform {
        stopPlaylist()
        "Playlist stopped"
    }

    private fun stopPlaylist() {
        store.setSetting("active_playlist", "")
        store.setSetting("playlist_due", "")
    }

    private fun advancePlaylist() {
        val id = store.setting("active_playlist").toLongOrNull() ?: return
        val playlist = store.playlist(id) ?: run { stopPlaylist(); return }
        val available = store.playlistItems(id).filter { it.present && it.kind == MediaKind.IMAGE }
        if (available.isEmpty()) {
            stopPlaylist()
            error("Playlist has no available images")
        }
        val selection = PlaylistOrder.next(
            available.map { it.id }, playlist.mode,
            store.setting("playlist_index", "-1").toIntOrNull() ?: -1,
            store.setting("playlist_current").toLongOrNull(),
            store.setting("playlist_bag").split(',').mapNotNull { it.toLongOrNull() }
        )
        val item = available.first { it.id == selection.id }
        apply(item, stopPlaylist = false)
        store.setSetting("playlist_index", selection.index.toString())
        store.setSetting("playlist_current", item.id.toString())
        store.setSetting("playlist_bag", selection.remaining.joinToString(","))
        store.setSetting("playlist_due", (System.currentTimeMillis() +
            playlist.intervalSeconds.coerceAtLeast(60) * 1000L).toString())
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
    fun setScheduleEnabled(enabled: Boolean) = perform {
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
            store.setSetting("active_playlist", rule.playlistId.toString())
            store.setSetting("playlist_index", "-1")
            store.setSetting("playlist_current", "")
            store.setSetting("playlist_bag", "")
            advancePlaylist()
        } else {
            val item = store.mediaById(requireNotNull(rule.mediaId)) ?: error("Scheduled file missing")
            apply(item, stopPlaylist = true)
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
            if (System.currentTimeMillis() >= due) advancePlaylist()
        }
    }

    override fun close() {
        scope.cancel()
        // The app is exiting; SQLite will also be closed by the process if an I/O action is still unwinding.
        runCatching { store.close() }
    }
}
