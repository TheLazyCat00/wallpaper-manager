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
    private val playback = PlaylistPlayback(store) { item -> apply(item, stopPlaylist = false) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()

    var media by mutableStateOf<List<Media>>(emptyList())
        private set
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    var rules by mutableStateOf<List<DailyRule>>(emptyList())
        private set
    var members by mutableStateOf<Map<Long, List<Media>>>(emptyMap())
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
    var closeToTray by mutableStateOf(true)
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
        members = playlists.associate { it.id to store.playlistItems(it.id) }
        rules = store.listRules()
        currentPath = store.setting("current_path")
        runningPlaylist = store.setting("active_playlist").toLongOrNull()
        dark = store.setting("appearance", "dark") == "dark"
        scheduleEnabled = store.setting("schedule_enabled", "true") == "true"
        closeToTray = store.setting("close_to_tray", "true") == "true"
        style = WallpaperStyle.fromSetting(store.setting("style", WallpaperStyle.FILL.name))
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
                } catch (e: LinkageError) {
                    // Native/JNA linkage errors do not inherit from Exception.
                    notice = e.message ?: e.javaClass.simpleName
                } finally {
                    refresh()
                }
            }
        }
    }

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
        val deletedRules = store.listRules().count { it.mediaId == item.id }
        store.removeMedia(item.id)
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

    private fun apply(item: Media, stopPlaylist: Boolean) {
        require(item.kind == MediaKind.IMAGE) {
            "Live video wallpaper is not implemented in this Kotlin port yet"
        }
        if (stopPlaylist) store.setSetting("active_playlist", "")
        val selectedStyle = WallpaperStyle.fromSetting(store.setting("style", WallpaperStyle.FILL.name))
        desktop.set(item.path, selectedStyle)
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
        val deletedRules = store.listRules().count { it.playlistId == id }
        if (store.setting("active_playlist").toLongOrNull() == id) stopPlaylist()
        store.deletePlaylist(id)
        "Playlist deleted ($deletedRules matching daily schedule rules removed)"
    }
    fun startPlaylist(id: Long) = perform {
        playback.start(id)
        "Playlist started"
    }
    fun stop() = perform {
        stopPlaylist()
        "Playlist stopped"
    }

    private fun stopPlaylist() = playback.stop()
    private fun advancePlaylist() = playback.advance()

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
