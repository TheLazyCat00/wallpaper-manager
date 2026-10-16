package wallpaper

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DailyScheduleTest {
    private val morning = DailyRule(1, "07:00", 42, null, true, "morning.jpg")
    private val evening = DailyRule(2, "18:00", 43, null, true, "evening.jpg")

    @Test fun resolvesToday() {
        val actual = DailySchedule.active(listOf(morning, evening), LocalDateTime.of(2026, 10, 16, 13, 0))
        assertEquals(1L, actual?.rule?.id)
        assertEquals(16, actual?.timestamp?.dayOfMonth)
    }
    @Test fun rollsOverToYesterday() {
        val actual = DailySchedule.active(listOf(morning, evening), LocalDateTime.of(2026, 10, 16, 1, 0))
        assertEquals(2L, actual?.rule?.id)
        assertEquals(15, actual?.timestamp?.dayOfMonth)
    }
    @Test fun ignoresDisabled() {
        assertNull(DailySchedule.active(listOf(morning.copy(enabled = false)), LocalDateTime.now()))
    }
    @Test fun rejectsInvalidTime() {
        assertNull(DailySchedule.parse("25:00"))
        assertNull(DailySchedule.parse("not a time"))
    }
}
