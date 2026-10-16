package wallpaper

import java.nio.file.Files
import java.nio.file.Path

enum class MediaKind { IMAGE, VIDEO }
enum class OrderMode { SEQUENTIAL, RANDOM }
enum class WallpaperStyle(val label: String, val registry: String, val tile: String) {
    FILL("Fill", "10", "0"), FIT("Fit", "6", "0"),
    STRETCH("Stretch", "2", "0"), CENTER("Center", "0", "0"), TILE("Tile", "0", "1");

    companion object {
        fun fromSetting(value: String?): WallpaperStyle =
            entries.firstOrNull { it.name == value } ?: FILL
    }
}

data class Media(
    val id: Long, val path: Path, val kind: MediaKind,
    val width: Int?, val height: Int?, val bytes: Long,
    val favorite: Boolean, val addedAt: Long, val revision: Long = 0
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
        Files.createDirectories(resolveHome(
            System.getProperty("os.name"), System.getenv(), Path.of(System.getProperty("user.home"))
        ))
    }

    internal fun resolveHome(osName: String, environment: Map<String, String>, userHome: Path): Path {
        val custom = environment["WPM_KOTLIN_DATA_DIR"]?.takeIf { it.isNotBlank() }
        val os = osName.lowercase()
        return when {
            custom != null -> Path.of(custom)
            os.contains("win") -> (environment["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }
                ?.let(Path::of) ?: userHome).resolve("WallpaperManagerKotlin")
            os.contains("mac") -> userHome.resolve("Library/Application Support/WallpaperManagerKotlin")
            else -> {
                // XDG locations must be non-empty absolute paths.
                val dataHome = environment["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }
                    ?.let(Path::of)?.takeIf { it.isAbsolute }
                    ?: userHome.resolve(".local/share")
                dataHome.resolve("wallpaper-manager-kotlin")
            }
        }
    }
}
