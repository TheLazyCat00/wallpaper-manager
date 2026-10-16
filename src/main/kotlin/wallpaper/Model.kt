package wallpaper

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

enum class MediaKind { IMAGE, VIDEO }
enum class OrderMode { SEQUENTIAL, RANDOM }
enum class WallpaperStyle(val label: String, val registry: String, val tile: String) {
    FILL("Fill", "10", "0"), FIT("Fit", "6", "0"),
    STRETCH("Stretch", "2", "0"), CENTER("Center", "0", "0"), TILE("Tile", "0", "1")
}

data class Media(
    val id: Long, val path: Path, val kind: MediaKind,
    val width: Int?, val height: Int?, val bytes: Long,
    val favorite: Boolean, val addedAt: Long
) {
    val name get() = path.fileName.toString()
    val present get() = Files.isRegularFile(path)
}
data class Playlist(val id: Long, val name: String, val mode: OrderMode, val intervalSeconds: Int, val count: Int)
data class DailyRule(
    val id: Long, val time: String, val mediaId: Long?,
    val playlistId: Long?, val enabled: Boolean, val target: String
)
object AppFolders {
    val home: Path by lazy {
        val custom = System.getenv("WPM_KOTLIN_DATA_DIR")?.takeIf { it.isNotBlank() }
        val os = System.getProperty("os.name").lowercase()
        val path = when {
            custom != null -> Paths.get(custom)
            os.contains("win") -> Paths.get(System.getenv("LOCALAPPDATA")
                ?: System.getProperty("user.home"), "WallpaperManagerKotlin")
            os.contains("mac") -> Paths.get(System.getProperty("user.home"), "Library",
                "Application Support", "WallpaperManagerKotlin")
            else -> Paths.get(System.getenv("XDG_DATA_HOME")
                ?: Paths.get(System.getProperty("user.home"), ".local", "share").toString(),
                "wallpaper-manager-kotlin")
        }
        Files.createDirectories(path)
    }
}
