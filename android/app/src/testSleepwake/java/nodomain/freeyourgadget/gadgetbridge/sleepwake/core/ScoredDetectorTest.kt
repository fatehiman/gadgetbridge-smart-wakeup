package nodomain.freeyourgadget.gadgetbridge.sleepwake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class ScoredDetectorTest {
    /** The Python port (tools/analysis/detector.py) wrote these vectors. Kotlin must give the same numbers. */
    @Test fun matchesThePythonPortOnSharedVectors() {
        val f = listOf("../../tools/analysis/testvectors.json", "../tools/analysis/testvectors.json", "tools/analysis/testvectors.json")
            .map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("testvectors.json not found (run tools/analysis/make_testvectors.py)", f)
        @Suppress("UNCHECKED_CAST")
        val vectors = Json.parse(f!!.readText()) as List<Map<String, Any?>>
        assertTrue(vectors.size > 20)
        for ((n, v) in vectors.withIndex()) {
            @Suppress("UNCHECKED_CAST")
            val params = (v["params"] as? Map<String, Any?>)?.let { ScoredParams.fromMap(it) } ?: ScoredParams()
            @Suppress("UNCHECKED_CAST")
            val samples = (v["samples"] as List<List<Any?>>).map { r ->
                Sample(ts = (r[0] as Number).toLong(), heartRate = (r[1] as? Number)?.toInt(), movement = (r[2] as? Number)?.toInt(), sleepStage = r[3] as? String)
            }
            val ctx = DetectionContext(
                Instant.ofEpochSecond((v["now"] as Number).toLong()), Instant.ofEpochSecond((v["window_start"] as Number).toLong()),
                Instant.ofEpochSecond((v["deadline"] as Number).toLong()), Instant.ofEpochSecond((v["session_start"] as Number).toLong()), samples,
            )
            val r = ScoredDetector(params).compute(ctx)
            @Suppress("UNCHECKED_CAST")
            val exp = v["expected"] as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val ef = exp["features"] as Map<String, Number>
            for ((k, x) in ef) assertEquals("vector $n feature $k", x.toDouble(), r.features[k]!!, 1e-6)
            assertEquals("vector $n score", (exp["score"] as Number).toDouble(), r.score, 1e-6)
            assertEquals("vector $n stale", exp["stale"], r.stale)
        }
    }

    private fun ctx(now: Instant, samples: List<Sample>, start: Instant) =
        DetectionContext(now, start.plusSeconds(420 * 60), start.plusSeconds(450 * 60), start, samples)

    @Test fun waitsOnQuietNightAndWakesWhenRestless() {
        val start = Instant.ofEpochSecond(1_760_000_000)
        val quiet = Simulation.syntheticNight(start, 8)
        val d = ScoredDetector(ScoredParams(threshold = 1.0))
        val early = ctx(start.plusSeconds(422 * 60), quiet, start)
        assertEquals(Decision.Wait, d.evaluate(early))
        val restless = Simulation.syntheticNight(start, 8, wakeMovementAt = start.plusSeconds(430 * 60))
        assertTrue(d.evaluate(ctx(start.plusSeconds(440 * 60), restless, start)) is Decision.WakeNow)
    }

    @Test fun staleDataAlwaysWaits() {
        val start = Instant.ofEpochSecond(1_760_000_000)
        val old = Simulation.syntheticNight(start, 5)
        val d = ScoredDetector(ScoredParams(bias = 100.0)) // would wake on any data
        assertEquals(Decision.Wait, d.evaluate(ctx(start.plusSeconds(440 * 60), old, start)))
    }

    @Test fun deepStageHoldsBackTheWake() {
        val start = Instant.ofEpochSecond(1_760_000_000)
        val base = Simulation.syntheticNight(start, 8)
        val lastTs = base.last().ts
        val deep = base.map { if (it.ts > lastTs - 600) it.copy(sleepStage = "deep") else it }
        val light = base.map { if (it.ts > lastTs - 600) it.copy(sleepStage = "light") else it }
        val now = Instant.ofEpochSecond(lastTs)
        val d = ScoredDetector()
        assertTrue(d.compute(ctx(now, light, start)).score > d.compute(ctx(now, deep, start)).score)
    }

    // ---------- config ----------

    @Test fun factoryBuildsTheDetectorFromConfig() {
        val scored = DetectorFactory.create("""{"detector":"scored_v1","params":{"threshold":2.5,"weights":{"mov":3}}}""", HeuristicParams())
        assertEquals("scored_v1", scored.id)
        assertEquals(2.5, scored.params()["threshold"] as Double, 0.0)
        @Suppress("UNCHECKED_CAST")
        assertEquals(3.0, (scored.params()["weights"] as Map<String, Double>)["mov"]!!, 0.0)
        @Suppress("UNCHECKED_CAST")
        assertEquals(ScoredParams.DEFAULT_WEIGHTS["hr_delta"], (scored.params()["weights"] as Map<String, Double>)["hr_delta"])

        val h = DetectorFactory.create("""{"detector":"heuristic_v1","params":{"hr_delta":11}}""", HeuristicParams())
        assertEquals("heuristic_v1", h.id)
        assertEquals(11, h.params()["hr_delta"])
    }

    @Test fun factoryFallsBackOnMissingOrBadConfig() {
        listOf(null, "", "not json", """{"detector":"unknown"}""", "[1,2]").forEach {
            assertEquals("config: $it", "heuristic_v1", DetectorFactory.create(it, HeuristicParams()).id)
        }
    }

    @Test fun jsonParseRoundTrip() {
        val text = """{"a":1,"b":[true,false,null,"x\ny\"zA"],"c":{"d":-2.5e1},"e":""}"""
        val v = Json.parse(text) as Map<*, *>
        assertEquals(1L, v["a"])
        assertEquals(listOf(true, false, null, "x\ny\"zA"), v["b"])
        assertEquals(-25.0, (v["c"] as Map<*, *>)["d"])
        assertEquals("", v["e"])
        assertEquals(Json.encode(v), Json.encode(Json.parse(Json.encode(v)))) // numbers may change Long/Double, the text must not
    }

    @Test(expected = IllegalArgumentException::class) fun jsonParseRejectsGarbage() {
        Json.parse("{\"a\":1} x")
    }
}
