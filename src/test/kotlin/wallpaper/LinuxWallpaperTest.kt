package wallpaper

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxWallpaperTest {
    private val image = Path.of("wallpapers", "morning light.png")
    private val listKeys = listOf("gsettings", "list-keys", "org.gnome.desktop.background")

    private fun successfulCommand(commands: MutableList<List<String>>, command: List<String>): String {
        commands.add(command)
        return if (command == listKeys) "picture-uri\npicture-uri-dark\npicture-options\n" else ""
    }

    @Test fun kdeUsesPlasmaEvenWhenGsettingsWouldSucceed() {
        val commands = mutableListOf<List<String>>()
        LinuxWallpaper(mapOf("XDG_CURRENT_DESKTOP" to "KDE")) { successfulCommand(commands, it) }.set(image)
        assertEquals(listOf(listOf("plasma-apply-wallpaperimage", image.toAbsolutePath().toString())), commands)
    }

    @Test fun gnomeSetsBothLightAndDarkUris() {
        val commands = mutableListOf<List<String>>()
        LinuxWallpaper(mapOf("XDG_CURRENT_DESKTOP" to "ubuntu:GNOME")) { successfulCommand(commands, it) }.set(image)
        val prefix = listOf("gsettings", "set", "org.gnome.desktop.background")
        val uri = image.toAbsolutePath().toUri().toString()
        assertEquals(listOf(listKeys, prefix + listOf("picture-uri", uri), prefix + listOf("picture-uri-dark", uri)), commands)
    }

    @Test fun desktopMarkersAreCaseInsensitiveAndColonSeparated() {
        val commands = mutableListOf<List<String>>()
        LinuxWallpaper(mapOf("XDG_CURRENT_DESKTOP" to " vendor : kde ")) { successfulCommand(commands, it) }.set(image)
        assertEquals("plasma-apply-wallpaperimage", commands.single().first())
    }

    @Test fun sessionNamesAreUsedWhenCurrentDesktopIsMissingOrBlank() {
        for (environment in listOf(
            mapOf("XDG_CURRENT_DESKTOP" to " ", "XDG_SESSION_DESKTOP" to "plasma"),
            mapOf("DESKTOP_SESSION" to "plasmawayland"),
            mapOf("DESKTOP_SESSION" to "plasma-x11")
        )) {
            val commands = mutableListOf<List<String>>()
            LinuxWallpaper(environment) { successfulCommand(commands, it) }.set(image)
            assertEquals("plasma-apply-wallpaperimage", commands.single().first())
        }
    }

    @Test fun currentDesktopTakesPrecedenceOverSessionFallbacks() {
        val commands = mutableListOf<List<String>>()
        LinuxWallpaper(mapOf("XDG_CURRENT_DESKTOP" to "KDE", "DESKTOP_SESSION" to "gnome")) {
            successfulCommand(commands, it)
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
            "picture-uri\npicture-options\n"
        }.set(image)
        val uri = image.toAbsolutePath().toUri().toString()
        assertEquals(listOf(listKeys, listOf("gsettings", "set", "org.gnome.desktop.background", "picture-uri", uri)), commands)
    }

    @Test fun gnomeDarkWriteFailureIsPropagated() {
        val commands = mutableListOf<List<String>>()
        val failure = assertFailsWith<IllegalStateException> {
            LinuxWallpaper(mapOf("XDG_CURRENT_DESKTOP" to "GNOME")) {
                val output = successfulCommand(commands, it)
                if ("picture-uri-dark" in it) error("Dark wallpaper key is locked")
                output
            }.set(image)
        }
        assertEquals("Dark wallpaper key is locked", failure.message)
        assertEquals(3, commands.size)
    }

    @Test fun gnomeLightWriteFailureDoesNotAttemptTheDarkWrite() {
        val commands = mutableListOf<List<String>>()
        assertFailsWith<IllegalStateException> {
            LinuxWallpaper(mapOf("XDG_CURRENT_DESKTOP" to "GNOME")) {
                val output = successfulCommand(commands, it)
                if ("picture-uri" in it) error("Light wallpaper key is locked")
                output
            }.set(image)
        }
        assertEquals(2, commands.size)
        assertTrue(commands.none { "picture-uri-dark" in it })
    }

    @Test fun unsupportedOrMissingSessionFailsWithoutWritingSettings() {
        for (environment in listOf(
            emptyMap(),
            mapOf("XDG_CURRENT_DESKTOP" to "XFCE", "DESKTOP_SESSION" to "gnome")
        )) {
            val commands = mutableListOf<List<String>>()
            assertFailsWith<IllegalStateException> {
                LinuxWallpaper(environment) { successfulCommand(commands, it) }.set(image)
            }
            assertTrue(commands.isEmpty())
        }
    }
}
