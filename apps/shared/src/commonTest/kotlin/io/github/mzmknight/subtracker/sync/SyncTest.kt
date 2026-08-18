package io.github.mzmknight.subtracker.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class Note(
    override val id: String,
    override val updatedAt: String,
    override val deleted: Boolean = false,
    val text: String = "",
) : Syncable

class HlcTest {

    @Test
    fun encodingSortsInCausalOrder() {
        val a = Hlc(1000, 0, "aaaaaaaaaaaaaaaa")
        val b = Hlc(1000, 1, "aaaaaaaaaaaaaaaa")
        val c = Hlc(1001, 0, "aaaaaaaaaaaaaaaa")

        assertTrue(a < b)
        assertTrue(b < c)
        // The point of fixed-width encoding: string order matches value order,
        // so SQL and plain comparisons agree without parsing.
        assertTrue(a.encode() < b.encode())
        assertTrue(b.encode() < c.encode())
    }

    @Test
    fun encodingWidthIsStableAcrossMagnitudes() {
        val small = Hlc(1, 0, "aaaaaaaaaaaaaaaa").encode()
        val large = Hlc(1_786_000_000_000, 99, "aaaaaaaaaaaaaaaa").encode()
        assertEquals(small.length, large.length, "widths must match or sorting breaks")
        assertTrue(small < large)
    }

    @Test
    fun roundTripsThroughParse() {
        val original = Hlc(1_786_000_000_000, 42, "a1b2c3d4e5f60718")
        assertEquals(original, Hlc.parse(original.encode()))
        assertNull(Hlc.parse("nonsense"))
        assertNull(Hlc.parse("123-456"))
    }

    @Test
    fun counterAdvancesWhenTheClockStandsStill() {
        var fixed = 1000L
        val clock = HlcClock("aaaaaaaaaaaaaaaa") { fixed }

        val first = clock.tick()
        val second = clock.tick()
        val third = clock.tick()

        assertTrue(first < second && second < third, "ticks must be strictly increasing")
        assertEquals(0, first.counter)
        assertEquals(2, third.counter)

        fixed = 2000
        val afterAdvance = clock.tick()
        assertEquals(2000, afterAdvance.millis)
        assertEquals(0, afterAdvance.counter, "counter resets once real time moves")
    }

    @Test
    fun timestampsKeepRisingEvenIfTheClockJumpsBackwards() {
        // The scenario this whole class exists for: NTP correction, or a laptop
        // waking with a bad clock. A plain wall-clock timestamp would regress
        // here and the device would start losing its own writes.
        var wall = 5000L
        val clock = HlcClock("aaaaaaaaaaaaaaaa") { wall }
        val before = clock.tick()

        wall = 1000
        val after = clock.tick()

        assertTrue(after > before, "must not regress when the clock goes backwards")
        assertEquals(5000, after.millis)
    }

    @Test
    fun observingARemoteTimestampPullsTheLocalClockForward() {
        // A device with a slow clock must not lose every merge forever.
        val slow = HlcClock("aaaaaaaaaaaaaaaa") { 1000L }
        val remote = Hlc(9_000_000, 3, "bbbbbbbbbbbbbbbb")

        val observed = slow.observe(remote)
        assertTrue(observed > remote, "local writes must now sort after what we learned")

        val next = slow.tick()
        assertTrue(next > remote)
    }

    @Test
    fun clockStateSurvivesARestart() {
        var wall = 9000L
        val original = HlcClock("aaaaaaaaaaaaaaaa") { wall }
        val last = original.tick()

        // Restart with a clock that has since gone backwards.
        wall = 100
        val restarted = HlcClock("aaaaaaaaaaaaaaaa") { wall }
        restarted.restore(last)

        assertTrue(restarted.tick() > last, "a restart must not rewind our own history")
    }
}

class MergeTest {

    private fun stamp(millis: Long, device: String) = Hlc(millis, 0, device.padEnd(16, 'x')).encode()

    @Test
    fun laterWriteWins() {
        val local = listOf(Note("n1", stamp(100, "a"), text = "old"))
        val remote = listOf(Note("n1", stamp(200, "b"), text = "new"))

        val result = Merge.merge(local, remote)
        assertEquals(1, result.merged.size)
        assertEquals("new", result.merged.first().text)
        assertEquals(1, result.incoming.size)
    }

    @Test
    fun earlierWriteLoses() {
        val local = listOf(Note("n1", stamp(300, "a"), text = "mine"))
        val remote = listOf(Note("n1", stamp(200, "b"), text = "theirs"))

        val result = Merge.merge(local, remote)
        assertEquals("mine", result.merged.first().text)
        assertTrue(result.incoming.isEmpty())
        assertEquals(1, result.outgoing.size, "the peer needs our newer copy")
    }

    @Test
    fun aDeleteIsNotResurrectedByAStaleCopy() {
        // Without tombstones this is exactly how a deleted subscription comes back.
        val phoneDeleted = listOf(Note("n1", stamp(300, "a"), deleted = true))
        val pcStale = listOf(Note("n1", stamp(100, "b"), text = "still here"))

        val result = Merge.merge(pcStale, phoneDeleted)
        assertTrue(result.merged.single().deleted)
        assertTrue(result.live.isEmpty())
    }

    @Test
    fun anEditAfterADeleteWins() {
        val deleted = listOf(Note("n1", stamp(100, "a"), deleted = true))
        val revived = listOf(Note("n1", stamp(200, "b"), text = "back"))

        assertEquals("back", Merge.merge(deleted, revived).merged.single().text)
        assertTrue(Merge.merge(deleted, revived).live.isNotEmpty())
    }

    @Test
    fun mergeIsCommutative() {
        val a = listOf(
            Note("n1", stamp(100, "a"), text = "a1"),
            Note("n2", stamp(400, "a"), text = "a2"),
            Note("n4", stamp(500, "a"), deleted = true),
        )
        val b = listOf(
            Note("n1", stamp(200, "b"), text = "b1"),
            Note("n3", stamp(300, "b"), text = "b3"),
            Note("n4", stamp(100, "b"), text = "b4"),
        )

        val forwards = Merge.merge(a, b).merged.sortedBy { it.id }
        val backwards = Merge.merge(b, a).merged.sortedBy { it.id }
        assertEquals(forwards, backwards, "sync order must not change the outcome")
    }

    @Test
    fun mergeIsIdempotent() {
        val a = listOf(Note("n1", stamp(100, "a"), text = "a1"), Note("n2", stamp(400, "a")))
        val b = listOf(Note("n1", stamp(200, "b"), text = "b1"), Note("n3", stamp(300, "b")))

        val once = Merge.merge(a, b).merged.sortedBy { it.id }
        val twice = Merge.merge(once, b).merged.sortedBy { it.id }
        val thrice = Merge.merge(twice, b).merged.sortedBy { it.id }

        assertEquals(once, twice, "re-syncing must be a no-op")
        assertEquals(once, thrice)
    }

    @Test
    fun threeDevicesConvergeWhateverOrderTheySync() {
        val phone = listOf(Note("s1", stamp(100, "p"), text = "phone"))
        val laptop = listOf(Note("s1", stamp(300, "l"), text = "laptop"), Note("s2", stamp(150, "l")))
        val server = listOf(Note("s1", stamp(200, "s"), text = "server"), Note("s3", stamp(250, "s")))

        // phone -> laptop -> server
        val routeA = Merge.merge(Merge.merge(phone, laptop).merged, server).merged.sortedBy { it.id }
        // server -> phone -> laptop
        val routeB = Merge.merge(Merge.merge(server, phone).merged, laptop).merged.sortedBy { it.id }
        // laptop -> server -> phone
        val routeC = Merge.merge(Merge.merge(laptop, server).merged, phone).merged.sortedBy { it.id }

        assertEquals(routeA, routeB)
        assertEquals(routeB, routeC)
        assertEquals("laptop", routeA.first { it.id == "s1" }.text, "highest timestamp wins everywhere")
        assertEquals(3, routeA.size)
    }

    @Test
    fun sameTimestampIsBrokenByDeviceIdNotByOrder() {
        // Two devices writing in the same millisecond must still agree on a winner.
        val a = listOf(Note("n1", stamp(100, "a"), text = "a"))
        val b = listOf(Note("n1", stamp(100, "b"), text = "b"))

        assertEquals("b", Merge.merge(a, b).merged.single().text)
        assertEquals("b", Merge.merge(b, a).merged.single().text)
    }

    @Test
    fun highestTimestampIsFoundForAdvancingTheClock() {
        val records = listOf(
            Note("n1", stamp(100, "a")),
            Note("n2", stamp(900, "b")),
            Note("n3", stamp(400, "c")),
        )
        assertEquals(900, Merge.highestTimestamp(records)?.millis)
        assertNull(Merge.highestTimestamp(emptyList()))
    }
}

class RecordIdTest {

    @Test
    fun idsMatchThePocketBaseFormat() {
        val random = Random(42)
        repeat(200) {
            val id = RecordId.generate(random)
            assertEquals(15, id.length)
            assertTrue(RecordId.isValid(id), "generated id must be acceptable to PocketBase: $id")
        }
    }

    @Test
    fun idsAreDistinct() {
        val ids = (1..5000).map { RecordId.generate() }.toSet()
        assertEquals(5000, ids.size, "collisions at this scale would indicate broken randomness")
    }

    @Test
    fun malformedIdsAreRejected() {
        assertTrue(!RecordId.isValid(""))
        assertTrue(!RecordId.isValid("tooshort"))
        assertTrue(!RecordId.isValid("UPPERCASEISBAD"))
        assertTrue(!RecordId.isValid("has-a-dash-abc"))
        assertNotEquals(RecordId.generate(), RecordId.generate())
    }
}
