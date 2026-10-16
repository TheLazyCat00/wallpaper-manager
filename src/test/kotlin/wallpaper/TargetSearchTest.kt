package wallpaper

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TargetSearchTest {
    @Test fun everyTargetRemainsAccessibleBeyondTheFirstHundred() {
        val options = (1L..180L).map { id -> id to "Wallpaper $id" }
        assertEquals(180, matchingTargets(options, "").size)
        assertEquals(listOf(179L to "Wallpaper 179"), matchingTargets(options, "WALLPAPER 179"))
    }

    @Test fun searchIgnoresSurroundingWhitespaceAndCase() {
        val options = listOf(1L to "Morning", 2L to "Evening", 3L to "Sunrise")
        assertEquals(listOf(2L to "Evening"), matchingTargets(options, "  eVENing "))
    }
}
