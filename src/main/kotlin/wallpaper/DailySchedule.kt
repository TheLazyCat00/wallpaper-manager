package wallpaper

import java.time.LocalDateTime
import java.time.LocalTime

data class ScheduleOccurrence(val rule: DailyRule, val timestamp: LocalDateTime) {
    val key get() = rule.id.toString() + "@" + timestamp.toString()
}

object DailySchedule {
    fun parse(time: String): LocalTime? = runCatching { LocalTime.parse(time) }.getOrNull()
    /** Wrap to yesterday's last rule until today's first scheduled wallpaper. */
    fun active(rules: List<DailyRule>, now: LocalDateTime): ScheduleOccurrence? {
        val valid = rules.filter { it.enabled }.mapNotNull { rule ->
            parse(rule.time)?.let { rule to it }
        }
        return valid.flatMap { (rule, time) ->
            listOf(-1L, 0L).map { delta ->
                ScheduleOccurrence(rule, now.toLocalDate().plusDays(delta).atTime(time))
            }
        }.filter { !it.timestamp.isAfter(now) }
            .maxWithOrNull(compareBy<ScheduleOccurrence> { it.timestamp }.thenBy { it.rule.id })
    }
}

object PlaylistOrder {
    data class Selection(val id: Long, val index: Int, val remaining: List<Long>)
    /** A shuffled bag plays everything once before repeating, without immediate duplicates. */
    fun next(
        ids: List<Long>, mode: OrderMode, previousIndex: Int, previousId: Long?,
        previousBag: List<Long>, shuffle: (MutableList<Long>) -> Unit = { it.shuffle() }
    ): Selection {
        require(ids.isNotEmpty())
        if (mode == OrderMode.SEQUENTIAL) {
            val index = (previousIndex + 1).mod(ids.size)
            return Selection(ids[index], index, emptyList())
        }
        val bag = previousBag.filter { it in ids }.distinct().toMutableList()
        if (bag.isEmpty()) {
            bag.addAll(ids.distinct())
            shuffle(bag)
            if (bag.size > 1 && bag.first() == previousId) bag.add(bag.removeAt(0))
        }
        return Selection(bag.removeAt(0), previousIndex, bag)
    }
}
