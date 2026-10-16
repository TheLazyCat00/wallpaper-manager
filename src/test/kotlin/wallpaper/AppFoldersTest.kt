package wallpaper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppFoldersTest {
    @TempDir lateinit var home: Path

    @Test fun unsetBlankOrRelativeXdgDataHomeUsesTheUserDataDirectory() {
        for (value in listOf(null, "", "   ", "relative-data")) {
            val environment = value?.let { mapOf("XDG_DATA_HOME" to it) }.orEmpty()
            val resolved = AppFolders.resolveHome("Linux", environment, home)
            assertEquals(home.resolve(".local/share/wallpaper-manager-kotlin"), resolved)
            assertTrue(resolved.isAbsolute)
        }
    }

    @Test fun absoluteXdgDataHomeIsRespected() {
        val data = home.resolve("custom-data")
        assertEquals(data.resolve("wallpaper-manager-kotlin"),
            AppFolders.resolveHome("Linux", mapOf("XDG_DATA_HOME" to data.toString()), home))
    }

    @Test fun explicitOverrideTakesPrecedenceAndBlankOverrideIsIgnored() {
        val custom = home.resolve("test-data")
        assertEquals(custom, AppFolders.resolveHome("Linux",
            mapOf("WPM_KOTLIN_DATA_DIR" to custom.toString(), "XDG_DATA_HOME" to ""), home))
        assertEquals(home.resolve(".local/share/wallpaper-manager-kotlin"),
            AppFolders.resolveHome("Linux", mapOf("WPM_KOTLIN_DATA_DIR" to " "), home))
    }

    @Test fun blankWindowsDataHomeUsesTheUserHome() {
        assertEquals(home.resolve("WallpaperManagerKotlin"),
            AppFolders.resolveHome("Windows 11", mapOf("LOCALAPPDATA" to ""), home))
    }
}
