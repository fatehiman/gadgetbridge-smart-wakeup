package nodomain.freeyourgadget.gadgetbridge.sleepwake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WakeLogicTest {
    private val helsinki = ZoneId.of("Europe/Helsinki")
    private fun at(s: String) = ZonedDateTime.parse(s).toInstant()

    // ---------- window ----------

    @Test fun windowIsTargetPlusMinusTolerance() {
        val w = WakeWindow(at("2026-10-20T08:00:00+03:00"), 15)
        assertEquals(at("2026-10-20T07:45:00+03:00"), w.windowStart)
        assertEquals(at("2026-10-20T08:15:00+03:00"), w.deadline)
        assertFalse(w.isInside(at("2026-10-20T07:44:59+03:00")))
        assertTrue(w.isInside(at("2026-10-20T07:45:00+03:00")))
        assertFalse(w.isInside(at("2026-10-20T08:15:00+03:00")))
    }

    @Test fun nextTargetCrossesMidnight() {
        val now = at("2026-10-19T23:30:00+02:00") // evening
        val t = WakeWindow.nextTarget(now, LocalTime.of(8, 0), helsinki)
        assertEquals(ZonedDateTime.of(2026, 10, 20, 8, 0, 0, 0, helsinki).toInstant(), t)
    }

    @Test fun nextTargetSameDayWhenStillAhead() {
        val now = at("2026-10-19T01:00:00+03:00")
        val t = WakeWindow.nextTarget(now, LocalTime.of(8, 0), helsinki)
        assertEquals(ZonedDateTime.of(2026, 10, 19, 8, 0, 0, 0, helsinki).toInstant(), t)
    }

    @Test fun nextTargetAcrossDstEnd() {
        // Helsinki clocks go back on 2026-10-25 (04:00 -> 03:00): that night is 9 real hours from 23:00 to 08:00
        val now = ZonedDateTime.of(2026, 10, 24, 23, 0, 0, 0, helsinki).toInstant()
        val t = WakeWindow.nextTarget(now, LocalTime.of(8, 0), helsinki)
        assertEquals(ZonedDateTime.of(2026, 10, 25, 8, 0, 0, 0, helsinki).toInstant(), t)
        assertEquals(Duration.ofHours(10), Duration.between(now, t)) // 23:00 -> 08:00 plus the repeated hour
    }

    @Test fun nextTargetAcrossDstStart() {
        // clocks go forward on 2026-03-29 (03:00 -> 04:00): 23:00 -> 08:00 is only 8 real hours
        val now = ZonedDateTime.of(2026, 3, 28, 23, 0, 0, 0, helsinki).toInstant()
        val t = WakeWindow.nextTarget(now, LocalTime.of(8, 0), helsinki)
        assertEquals(Duration.ofHours(8), Duration.between(now, t))
    }

    @Test fun nextTargetInDstGapMovesForward() {
        val now = ZonedDateTime.of(2026, 3, 28, 23, 0, 0, 0, helsinki).toInstant()
        val t = WakeWindow.nextTarget(now, LocalTime.of(3, 30), helsinki) // 03:30 does not exist that night
        assertEquals(ZonedDateTime.of(2026, 3, 29, 4, 30, 0, 0, helsinki).toInstant(), t)
    }

    // ---------- engine ----------

    private val target = at("2026-10-20T08:00:00+03:00")
    private val window = WakeWindow(target, 15)
    private val start = at("2026-10-19T23:00:00+03:00")

    private class FixedDetector(private val d: Decision) : WakeDetector {
        override val id = "fixed"
        var calls = 0
        override fun evaluate(ctx: DetectionContext): Decision { calls++; return d }
    }

    @Test fun smartNeverWakesBeforeWindowEvenIfDetectorSaysWake() {
        val det = FixedDetector(Decision.WakeNow("x", 1.0))
        val e = WakeEngine(Mode.SMART_WAKE, window, start, det)
        val r = e.evaluate(at("2026-10-20T07:44:59+03:00"), emptyList())
        assertTrue(r is EngineResult.Wait)
        assertEquals(0, det.calls)
    }

    @Test fun smartWakesInsideWindowWhenDetectorSaysSo() {
        val e = WakeEngine(Mode.SMART_WAKE, window, start, FixedDetector(Decision.WakeNow("light", 0.9)))
        val r = e.evaluate(at("2026-10-20T07:50:00+03:00"), emptyList()) as EngineResult.Wake
        assertEquals(WakeReason.LIGHT_SLEEP, r.reason)
    }

    @Test fun smartWaitsInsideWindowWhenDetectorWaits() {
        val e = WakeEngine(Mode.SMART_WAKE, window, start, FixedDetector(Decision.Wait))
        assertTrue(e.evaluate(at("2026-10-20T08:10:00+03:00"), emptyList()) is EngineResult.Wait)
    }

    @Test fun smartAlwaysWakesAtDeadlineEvenWithNoData() {
        val e = WakeEngine(Mode.SMART_WAKE, window, start, FixedDetector(Decision.Wait))
        val r = e.evaluate(at("2026-10-20T08:15:00+03:00"), emptyList()) as EngineResult.Wake
        assertEquals(WakeReason.DEADLINE, r.reason)
        val late = e.evaluate(at("2026-10-20T09:00:00+03:00"), emptyList()) as EngineResult.Wake
        assertEquals(WakeReason.DEADLINE, late.reason)
    }

    @Test fun deadlineDoesNotConsultTheDetector() {
        val det = FixedDetector(Decision.Wait)
        WakeEngine(Mode.SMART_WAKE, window, start, det).evaluate(at("2026-10-20T08:15:00+03:00"), emptyList())
        assertEquals(0, det.calls)
    }

    @Test fun monitorOnlyIgnoresDetectorAndWakesAtTarget() {
        val det = FixedDetector(Decision.WakeNow("x", 1.0))
        val e = WakeEngine(Mode.MONITOR_ONLY, window, start, det)
        assertTrue(e.evaluate(at("2026-10-20T07:59:59+03:00"), emptyList()) is EngineResult.Wait)
        val r = e.evaluate(at("2026-10-20T08:00:00+03:00"), emptyList()) as EngineResult.Wake
        assertEquals(WakeReason.MONITOR_TARGET, r.reason)
        assertEquals(0, det.calls)
        assertEquals(target, e.hardDeadline())
    }

    @Test fun monitorOnlyCanWakeAtDeadlineInstead() {
        val e = WakeEngine(Mode.MONITOR_ONLY, window, start, NoopDetector, MonitorWakeAt.DEADLINE)
        assertTrue(e.evaluate(at("2026-10-20T08:00:00+03:00"), emptyList()) is EngineResult.Wait)
        assertTrue(e.evaluate(at("2026-10-20T08:15:00+03:00"), emptyList()) is EngineResult.Wake)
        assertEquals(window.deadline, e.hardDeadline())
    }

    @Test fun zeroToleranceMeansTargetIsTheDeadline() {
        val w = WakeWindow(target, 0)
        val e = WakeEngine(Mode.SMART_WAKE, w, start, FixedDetector(Decision.Wait))
        assertTrue(e.evaluate(target.minusSeconds(1), emptyList()) is EngineResult.Wait)
        assertTrue(e.evaluate(target, emptyList()) is EngineResult.Wake)
    }

    // ---------- heuristic detector ----------

    private fun ctx(now: Instant, samples: List<Sample>) = DetectionContext(now, window.windowStart, window.deadline, start, samples)

    @Test fun heuristicWaitsOnQuietStableSleep() {
        val s = Simulation.syntheticNight(start, 9)
        val d = HeuristicDetector().evaluate(ctx(at("2026-10-20T07:50:00+03:00"), s))
        assertEquals(Decision.Wait, d)
    }

    @Test fun heuristicWakesOnMovement() {
        val s = Simulation.syntheticNight(start, 9, wakeMovementAt = at("2026-10-20T07:48:00+03:00"))
        val d = HeuristicDetector().evaluate(ctx(at("2026-10-20T07:50:00+03:00"), s))
        assertTrue(d is Decision.WakeNow)
    }

    @Test fun heuristicWakesOnHeartRateRiseWithoutMovement() {
        val base = Simulation.syntheticNight(start, 9)
        val from = at("2026-10-20T07:40:00+03:00").epochSecond
        val s = base.map { if (it.ts >= from) it.copy(heartRate = 75) else it }
        val d = HeuristicDetector().evaluate(ctx(at("2026-10-20T07:50:00+03:00"), s))
        assertTrue(d is Decision.WakeNow)
    }

    @Test fun heuristicWaitsOnStaleData() {
        val s = Simulation.syntheticNight(start, 6) // data ends at 05:00
        val d = HeuristicDetector().evaluate(ctx(at("2026-10-20T07:50:00+03:00"), s))
        assertEquals(Decision.Wait, d)
    }

    @Test fun heuristicWaitsWithNoData() {
        assertEquals(Decision.Wait, HeuristicDetector().evaluate(ctx(at("2026-10-20T07:50:00+03:00"), emptyList())))
    }

    @Test fun heuristicWorksWithHrOnly() {
        // no movement field at all: only HR is used
        val base = Simulation.syntheticNight(start, 9).map { it.copy(movement = null, steps = null) }
        assertEquals(Decision.Wait, HeuristicDetector().evaluate(ctx(at("2026-10-20T07:50:00+03:00"), base)))
        val from = at("2026-10-20T07:40:00+03:00").epochSecond
        val up = base.map { if (it.ts >= from) it.copy(heartRate = 80) else it }
        assertTrue(HeuristicDetector().evaluate(ctx(at("2026-10-20T07:50:00+03:00"), up)) is Decision.WakeNow)
    }

    @Test fun medianAndPercentile() {
        assertEquals(3.0, HeuristicDetector.median(listOf(5, 1, 3))!!, 0.0)
        assertEquals(2.5, HeuristicDetector.median(listOf(1, 2, 3, 4))!!, 0.0)
        assertEquals(null, HeuristicDetector.median(emptyList()))
        assertEquals(1.0, HeuristicDetector.percentile(listOf(1, 2, 3, 4, 5), 20)!!, 0.0)
        assertEquals(5.0, HeuristicDetector.percentile(listOf(1, 2, 3, 4, 5), 100)!!, 0.0)
    }

    @Test fun describeExposesInputsForTheLog() {
        val s = Simulation.syntheticNight(start, 9)
        val m = HeuristicDetector().describe(ctx(at("2026-10-20T07:50:00+03:00"), s))
        assertTrue(m.containsKey("hr_recent") && m.containsKey("hr_base") && m.containsKey("mov_recent"))
    }

    // ---------- simulation harness ----------

    @Test fun simulationWakesEarlyOnRestlessnessAndAtDeadlineOtherwise() {
        val quiet = Simulation.syntheticNight(start, 10)
        val steps = Simulation.run(Mode.SMART_WAKE, window, start, HeuristicDetector(), quiet)
        val last = steps.last().result as EngineResult.Wake
        assertEquals(WakeReason.DEADLINE, last.reason)

        val restless = Simulation.syntheticNight(start, 10, wakeMovementAt = at("2026-10-20T07:52:00+03:00"))
        val steps2 = Simulation.run(Mode.SMART_WAKE, window, start, HeuristicDetector(), restless)
        val last2 = steps2.last()
        assertEquals(WakeReason.LIGHT_SLEEP, (last2.result as EngineResult.Wake).reason)
        assertTrue(last2.now.isBefore(window.deadline) && !last2.now.isBefore(window.windowStart))
        steps2.takeLast(4).forEach { println(it) }
    }

    @Test fun deadlineHoldsEvenWhenTheWatchDeliversNothing() {
        val steps = Simulation.run(Mode.SMART_WAKE, window, start, HeuristicDetector(), emptyList())
        assertEquals(WakeReason.DEADLINE, (steps.last().result as EngineResult.Wake).reason)
        assertTrue(!steps.last().now.isBefore(window.deadline))
    }
}
