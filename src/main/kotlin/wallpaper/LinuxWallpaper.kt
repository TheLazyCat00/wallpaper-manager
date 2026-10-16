package wallpaper

import java.nio.file.Path

/** Select the active desktop, since a settings write can succeed for an inactive desktop. */
internal class LinuxWallpaper(
    private val environment: Map<String, String>,
    private val runCommand: (List<String>) -> Unit
) {
    private enum class Desktop { GNOME, KDE }

    private fun desktop(): Desktop {
        // Prefer the current desktop; session names are fallbacks for older launchers.
        for (key in listOf("XDG_CURRENT_DESKTOP", "XDG_SESSION_DESKTOP", "DESKTOP_SESSION")) {
            val value = environment[key]?.trim().orEmpty()
            if (value.isEmpty()) continue
            for (name in value.split(':').map { it.trim().lowercase() }) {
                when {
                    name == "kde" || name == "plasma" || name.startsWith("plasma-") ||
                        name == "plasmawayland" || name == "plasmax11" -> return Desktop.KDE
                    name == "gnome" || name.startsWith("gnome-") ||
                        name == "ubuntu" || name.startsWith("ubuntu-") -> return Desktop.GNOME
                }
            }
            // A declared but unsupported desktop must not inherit a stale session fallback.
            error("Unsupported Linux desktop: $value. Wallpaper changes support GNOME and KDE Plasma.")
        }
        error("Cannot detect the Linux desktop. Set XDG_CURRENT_DESKTOP to GNOME or KDE for the active session.")
    }

    fun set(path: Path) {
        val absolute = path.toAbsolutePath()
        when (desktop()) {
            Desktop.KDE -> runCommand(listOf("plasma-apply-wallpaperimage", absolute.toString()))
            Desktop.GNOME -> {
                val command = listOf("gsettings", "set", "org.gnome.desktop.background")
                val uri = absolute.toUri().toString()
                runCommand(command + listOf("picture-uri", uri))
                // Older GNOME schemas may not have the dark-mode key.
                runCatching { runCommand(command + listOf("picture-uri-dark", uri)) }
            }
        }
    }
}
