package wallpaper

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxWallpaperTest {
    private val image = Path.of("wallpapers", "morning light.png")

    @Test fun kdeUsesPlasmaEvenWhenGsettingsWouldSucceed() {
        val commands = mutableListOf<List<String>>()
        LinuxWallpaper(mapOf("XDG_CURRENT_DESKTOP" to "KDE")) { commands.add(it) }.set(image)
        assertEquals(listOf(listOf("plasma-apply-wallpaperimage", image.toAbsolutePath().toString())), commands)
    }

    @Test fun gnomeSetsBothLightAndDarkUris() {
        val commands = mutableListOf<List<String>>()
        LinuxWallpaper(mapOf("XDG_CURRENT_DESKTOP" to "ubuntu:GNOME")) { commands.add(it) }.set(image)
        val prefix = listOf("gsettings", "set", "org.gnome.desktop.background")
        val uri = image.toAbsolutePath().toUri().toString()
        assertEquals(listOf(prefix + listOf("picture-uri", uri), prefix + listOf("picture-uri-dark", uri)), commands)
    }

    @Test fun desktopMarkersAreCaseInsensitiveAndColonSeparated() {
        val commands = mutableListOf<List<String>>()
        LinuxWallpaper(mapOf("XDG_CURRENT_DESKTOP" to " vendor : kde ")) { commands.add(it) }.set(image)
        assertEquals("plasma-apply-wallpaperimage", commands.single().first())
    }

    @Test fun sessionNamesAreUsedWhenCurrentDesktopIsMissingOrBlank() {
        for (environment in listOf(
            mapOf("XDG_CURRENT_DESKTOP" to " ", "XDG_SESSION_DESKTOP" to "plasma"),
            mapOf("DESKTOP_SESSION" to "plasmawayland"),
            mapOf("DESKTOP_SESSION" to "plasma-x11")
        )) {
            val commands = mutableListOf<List<String>>()
            LinuxWallpaper(environment) { commands.add(it) }.set(image)
            assertEquals("plasma-apply-wallpaperimage", commands.single().first())
        }
    }

    @Test fun currentDesktopTakesPrecedenceOverSessionFallbacks() {
        val commands = mutableListOf<List<String>>()
        LinuxWallpaper(mapOf("XDG_CURRENT_DESKTOP" to "KDE", "DESKTOP_SESSION" to "gnome")) {
            commands.add(it)
        }.set(image)
        assertEquals("plasma-apply-wallpaperimage", commands.single().first())
    }

    @Test fun backendFailureDoesNotTryAnotherDesktop() {
        for (desktop in listOf("KDE", "GNOME")) {
            val commands = mutableListOf<List<String>>()
            assertFailsWith<IllegalStateException> {
                LinuxWallpaper(mapOf("XDG_CURRENT_DESKTOP" to desktop)) {
                    commands.add(it)
                    error("Desktop command failed")
                }.set(image)
            }
            assertEquals(1, commands.size)
        }
    }

    @Test fun missingGnomeDarkKeyIsOptional() {
        val commands = mutableListOf<List<String>>()
        LinuxWallpaper(mapOf("XDG_CURRENT_DESKTOP" to "GNOME")) {
            commands.add(it)
            if ("picture-uri-dark" in it) error("Schema key unavailable")
        }.set(image)
        assertEquals(2, commands.size)
    }

    @Test fun unsupportedOrMissingSessionFailsWithoutWritingSettings() {
        for (environment in listOf(
            emptyMap(),
            mapOf("XDG_CURRENT_DESKTOP" to "XFCE", "DESKTOP_SESSION" to "gnome")
        )) {
            val commands = mutableListOf<List<String>>()
            assertFailsWith<IllegalStateException> {
                LinuxWallpaper(environment) { commands.add(it) }.set(image)
            }
            assertTrue(commands.isEmpty())
        }
    }
}
