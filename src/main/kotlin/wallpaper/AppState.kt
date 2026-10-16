package wallpaper

/** A complete UI snapshot, published only after the controller finishes an operation. */
internal data class AppState(
    val media: List<Media> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val rules: List<DailyRule> = emptyList(),
    val members: Map<Long, List<Media>> = emptyMap(),
    val availableMediaIds: Set<Long> = emptySet(),
    val currentPath: String = "",
    val runningPlaylist: Long? = null,
    val dark: Boolean = true,
    val scheduleEnabled: Boolean = true,
    val closeToTray: Boolean = true,
    val style: WallpaperStyle = WallpaperStyle.FILL,
    val canRestore: Boolean = false,
    val notice: String = "Import images or folders to get started."
) {
    val currentMedia: Media? get() = media.firstOrNull { it.path.toString() == currentPath }
}
