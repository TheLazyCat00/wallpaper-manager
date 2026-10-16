package wallpaper

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import java.util.concurrent.TimeUnit

/** Native desktop wallpaper setting is separated from UI and playlist logic. */
class DesktopWallpaper(private val store: Store) {
    private interface User32 : StdCallLibrary {
        fun SystemParametersInfoW(action: Int, param: Int, buffer: Pointer, flags: Int): Boolean
    }

    private val os = System.getProperty("os.name").lowercase()
    private val windows get() = os.contains("win")

    fun set(path: Path, style: WallpaperStyle) {
        require(Files.isRegularFile(path)) { "File no longer exists: " + path }
        val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
        require(ext in setOf("jpg", "jpeg", "png", "bmp", "webp", "gif")) { "Not an image" }
        if (windows) {
            backupOriginalOnce()
            val supported = ext in setOf("jpg", "jpeg", "png", "bmp")
            val file = if (supported) path else convertToPng(path)
            setWindows(file, style)
        } else if (os.contains("mac")) {
            runCommand("osascript", "-e",
                "tell application \"System Events\" to set picture of every desktop to " +
                    "\"" + path.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        } else {
            // GNOME uses picture-uri on current desktop. KDE users may install plasma-apply-wallpaperimage.
            val uri = path.toAbsolutePath().toUri().toString()
            runCatching { runCommand("gsettings", "set", "org.gnome.desktop.background", "picture-uri", uri) }
                .getOrElse { runCommand("plasma-apply-wallpaperimage", path.toAbsolutePath().toString()) }
            runCatching { runCommand("gsettings", "set", "org.gnome.desktop.background", "picture-uri-dark", uri) }
        }
        store.setSetting("current_path", path.toAbsolutePath().toString())
        store.setSetting("style", style.name)
    }

    private fun convertToPng(path: Path): Path {
        val image: BufferedImage = ImageIO.read(path.toFile())
            ?: error("Cannot decode image: " + path.fileName)
        val directory = Files.createDirectories(AppFolders.home.resolve("cache"))
        val output = directory.resolve("wallpaper.png")
        check(ImageIO.write(image, "png", output.toFile())) { "PNG encoder unavailable" }
        image.flush()
        return output
    }

    private fun api() = Native.load("user32", User32::class.java)

    private fun originalWindowsPath(): Path? {
        val buffer = Memory(32768)
        if (!api().SystemParametersInfoW(0x0073, 32768 / 2, buffer, 0)) return null
        return buffer.getWideString(0).takeIf { it.isNotBlank() }?.let(Path::of)
    }

    private fun backupOriginalOnce() {
        if (store.setting("original_backup").isNotBlank()) return
        val source = originalWindowsPath() ?: return
        if (!Files.isRegularFile(source)) return
        val backup = Files.createDirectories(AppFolders.home.resolve("backup"))
            .resolve("original." + source.fileName.toString().substringAfterLast('.', "bmp"))
        Files.copy(source, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        // Read positioning before the first wallpaper change, not during restore.
        store.setSetting("original_style", registryValue("WallpaperStyle") ?: "10")
        store.setSetting("original_tile", registryValue("TileWallpaper") ?: "0")
        store.setSetting("original_backup", backup.toString())
    }

    fun canRestore() = windows && store.setting("original_backup").isNotBlank()

    fun restore() {
        check(windows) { "Restore original is currently implemented for Windows only" }
        val file = Path.of(store.setting("original_backup"))
        require(Files.isRegularFile(file)) { "Original wallpaper backup is missing" }
        setWindows(file, store.setting("original_style", "10"), store.setting("original_tile", "0"))
        store.setSetting("current_path", "")
    }

    private fun setWindows(file: Path, style: WallpaperStyle) = setWindows(file, style.registry, style.tile)

    private fun registryValue(name: String): String? = runCatching {
        val text = runCommand("reg", "query", "HKCU\\\\Control Panel\\\\Desktop", "/v", name)
        Regex("""(?m)^\\s*${Regex.escape(name)}\\s+REG_SZ\\s+(\\S+)""").find(text)?.groupValues?.get(1)
    }.getOrNull()

    private fun setWindows(file: Path, registryStyle: String, tile: String) {
        val key = "HKCU\\\\Control Panel\\\\Desktop"
        runCommand("reg", "add", key, "/v", "WallpaperStyle", "/t", "REG_SZ", "/d", registryStyle, "/f")
        runCommand("reg", "add", key, "/v", "TileWallpaper", "/t", "REG_SZ", "/d", tile, "/f")
        val full = file.toAbsolutePath().toString()
        val buffer = Memory((full.length + 1L) * Native.WCHAR_SIZE)
        buffer.setWideString(0, full)
        check(api().SystemParametersInfoW(0x0014, 0, buffer, 0x03)) {
            "Windows rejected the wallpaper change"
        }
    }

    /** Enforce a deadline even when a child blocks on output or an OS permission prompt. */
    private fun runCommand(vararg parts: String): String {
        val output = Files.createTempFile("wallpaper-command-", ".log")
        var process: Process? = null
        try {
            process = ProcessBuilder(*parts).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                error("Command timed out: " + parts.first())
            }
            val text = Files.readString(output)
            check(process.exitValue() == 0) {
                "Command failed: " + parts.first() + " — " + text.take(300)
            }
            return text
        } finally {
            if (process?.isAlive == true) process.destroyForcibly()
            Files.deleteIfExists(output)
        }
    }
}
