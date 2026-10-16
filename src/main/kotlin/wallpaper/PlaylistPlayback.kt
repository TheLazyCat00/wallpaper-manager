package wallpaper

/** Coordinates persisted playlist playback with wallpaper application.
 * Any failure stops playback, preventing the scheduler from retrying a broken
 * wallpaper every five seconds.
 */
internal class PlaylistPlayback(
    private val store: Store,
    private val apply: (Media) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    fun start(id: Long) {
        require(store.playlist(id) != null) { "Playlist not found" }
        store.setSetting("active_playlist", id.toString())
        store.setSetting("playlist_index", "-1")
        store.setSetting("playlist_current", "")
        store.setSetting("playlist_bag", "")
        store.setSetting("playlist_due", "0")
        advance()
    }

    fun stop() {
        store.setSetting("active_playlist", "")
        store.setSetting("playlist_due", "")
    }

    fun advance() {
        val id = store.setting("active_playlist").toLongOrNull() ?: return
        try {
            val playlist = store.playlist(id) ?: error("Playlist no longer exists")
            val images = store.playlistItems(id).filter { it.present && it.kind == MediaKind.IMAGE }
            require(images.isNotEmpty()) { "Playlist has no available images" }
            val selection = PlaylistOrder.next(
                images.map { it.id }, playlist.mode,
                store.setting("playlist_index", "-1").toIntOrNull() ?: -1,
                store.setting("playlist_current").toLongOrNull(),
                store.setting("playlist_bag").split(',').mapNotNull { it.toLongOrNull() }
            )
            val item = images.first { it.id == selection.id }
            apply(item)
            store.setSetting("playlist_index", selection.index.toString())
            store.setSetting("playlist_current", item.id.toString())
            store.setSetting("playlist_bag", selection.remaining.joinToString(","))
            store.setSetting("playlist_due", (
                nowMillis() + playlist.intervalSeconds.coerceAtLeast(60) * 1000L
            ).toString())
        } catch (failure: Throwable) {
            // Keep the original error visible even if cleanup itself fails.
            runCatching { stop() }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }
}
