package wallpaper

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WallpaperStyleTest {
    @Test fun invalidSavedStyleFallsBackToFill() {
        assertEquals(WallpaperStyle.FILL, WallpaperStyle.fromSetting("unrecognized"))
        assertEquals(WallpaperStyle.FILL, WallpaperStyle.fromSetting(null))
    }

    @Test fun validSavedStyleIsPreserved() {
        assertEquals(WallpaperStyle.TILE, WallpaperStyle.fromSetting("TILE"))
    }
}
