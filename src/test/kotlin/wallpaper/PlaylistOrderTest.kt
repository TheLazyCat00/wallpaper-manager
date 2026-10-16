package wallpaper

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaylistOrderTest {
    @Test fun sequentialWraps() {
        val ids = listOf(10L, 20L, 30L)
        assertEquals(20L, PlaylistOrder.next(ids, OrderMode.SEQUENTIAL, 0, 10, emptyList()).id)
        assertEquals(10L, PlaylistOrder.next(ids, OrderMode.SEQUENTIAL, 2, 30, emptyList()).id)
    }
    @Test fun sequentialUsesCurrentIdAfterEarlierItemRemoved() {
        // B was previously index 1; after A disappears, B becomes index 0.
        val selection = PlaylistOrder.next(listOf(20L, 30L), OrderMode.SEQUENTIAL,
            previousIndex = 1, previousId = 20L, previousBag = emptyList())
        assertEquals(30L, selection.id)
        assertEquals(1, selection.index)
    }
    @Test fun sequentialUsesFormerPositionIfCurrentItemRemoved() {
        val selection = PlaylistOrder.next(listOf(10L, 30L), OrderMode.SEQUENTIAL,
            previousIndex = 1, previousId = 20L, previousBag = emptyList())
        assertEquals(30L, selection.id)
    }
    @Test fun randomVisitsEveryoneOnce() {
        val ids = listOf(10L, 20L, 30L)
        var bag = emptyList<Long>()
        var previous: Long? = null
        val played = mutableListOf<Long>()
        repeat(3) {
            val next = PlaylistOrder.next(ids, OrderMode.RANDOM, -1, previous, bag) {}
            played.add(next.id)
            previous = next.id
            bag = next.remaining
        }
        assertEquals(ids.toSet(), played.toSet())
        assertEquals(0, bag.size)
        val next = PlaylistOrder.next(ids, OrderMode.RANDOM, -1, previous, bag) {}
        assertFalse(next.id == previous)
    }
    @Test fun removesStaleIdsFromBag() {
        val next = PlaylistOrder.next(listOf(7, 8), OrderMode.RANDOM, -1, 7, listOf(99, 8)) {}
        assertEquals(8L, next.id)
    }
}
