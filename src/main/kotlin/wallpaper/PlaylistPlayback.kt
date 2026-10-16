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
        store.setSettings(
            "active_playlist" to id.toString(),
            "playlist_index" to "-1",
            "playlist_current" to "",
            "playlist_bag" to "",
            "playlist_due" to "0"
        )
        advance()
    }

    fun stop() {
        store.setSettings("active_playlist" to "", "playlist_due" to "")
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
            store.setSettings(
                "playlist_index" to selection.index.toString(),
                "playlist_current" to item.id.toString(),
                "playlist_bag" to selection.remaining.joinToString(","),
                "playlist_due" to (nowMillis() + playlist.intervalSeconds.coerceAtLeast(60) * 1000L).toString()
            )
        } catch (failure: Throwable) {
            // Keep the original error visible even if cleanup itself fails.
            runCatching { stop() }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }
}
