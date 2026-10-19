package nodomain.freeyourgadget.gadgetbridge.sleepwake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAndJsonTest {
    @Test fun batchesAreAtMost1000AndKeepEverything() {
        val items = (1..2500).toList()
        val b = SyncPlan.batches(items)
        assertEquals(listOf(1000, 1000, 500), b.map { it.size })
        assertEquals(items, b.flatten())
        assertTrue(SyncPlan.batches(emptyList<Int>()).isEmpty())
    }

    @Test fun uploadStartsBeforeTheLastUploadedTimestampSoLateSamplesAreCaught() {
        val now = 1_760_000_000L
        assertEquals(now - 7 * 86400, SyncPlan.sampleFrom(0, now, 6 * 3600)) // first run
        assertEquals(1_759_900_000L - 6 * 3600, SyncPlan.sampleFrom(1_759_900_000L, now, 6 * 3600))
        assertEquals(0L, SyncPlan.sampleFrom(100, now, 6 * 3600)) // never negative
    }

    @Test fun resendingTheSameBatchIsIdenticalSoServerDedupeIsSafe() {
        val s = listOf(Sample(ts = 100, heartRate = 55, movement = 0), Sample(ts = 160, heartRate = 56))
        val a = Json.encode(s.map { mapOf("ts" to it.ts, "heart_rate" to it.heartRate) })
        val b = Json.encode(s.map { mapOf("ts" to it.ts, "heart_rate" to it.heartRate) })
        assertEquals(a, b)
    }

    @Test fun onlyHttpsIsAcceptedExceptInDebug() {
        assertTrue(SyncPlan.isUrlAllowed("https://gadget.peppasoft.com", false))
        assertFalse(SyncPlan.isUrlAllowed("http://gadget.peppasoft.com", false))
        assertTrue(SyncPlan.isUrlAllowed("http://192.168.1.10:8000", true))
        assertFalse(SyncPlan.isUrlAllowed("ftp://x", true))
        assertFalse(SyncPlan.isUrlAllowed("", true))
    }

    @Test fun jsonEncodesEventPayloads() {
        val m = mapOf(
            "decision" to "wait", "hr_recent" to 55.0, "mov_recent" to 0, "x" to null, "ok" to true,
            "list" to listOf(1, "a\"b"), "nested" to mapOf("k" to 1L), "nan" to Double.NaN, "half" to 55.5,
        )
        assertEquals(
            """{"decision":"wait","hr_recent":55,"mov_recent":0,"x":null,"ok":true,"list":[1,"a\"b"],"nested":{"k":1},"nan":null,"half":55.5}""",
            Json.encode(m),
        )
    }

    @Test fun jsonEscapesControlCharactersAndEmbedsRawJson() {
        assertEquals("\"a\\nb\\u0001\"", Json.encode("a\nb\u0001"))
        assertEquals("""{"p":{"a":1},"q":null}""", Json.encode(mapOf("p" to RawJson("""{"a":1}"""), "q" to RawJson(""))))
    }

    @Test fun modeIdsRoundTrip() {
        Mode.entries.forEach { assertEquals(it, Mode.fromId(it.id)) }
        assertEquals(Mode.MONITOR_ONLY, Mode.fromId("garbage"))
    }
}
