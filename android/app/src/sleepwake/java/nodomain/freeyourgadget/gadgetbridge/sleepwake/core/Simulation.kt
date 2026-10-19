package nodomain.freeyourgadget.gadgetbridge.sleepwake.core

import java.time.Duration
import java.time.Instant

/**
 * Simulation harness: feeds a recorded or synthetic sample stream and a fake clock to the engine and
 * returns the decision of every evaluation. This is how detectors are tuned offline
 * (the same inputs are logged by the app in the `detector_evaluated` events).
 */
object Simulation {
    data class Step(val now: Instant, val samplesSeen: Int, val result: EngineResult) {
        override fun toString(): String {
            val r = when (result) {
                is EngineResult.Wait -> "wait"
                is EngineResult.Wake -> "WAKE ${result.reason} (${result.detail ?: "-"})"
            }
            return "$now  samples=$samplesSeen  $r  ${result.inputs.filterKeys { it != "params" }}"
        }
    }

    /**
     * Evaluates every [tickMinutes] from the session start until the engine wakes (or [maxHours] pass).
     * Only samples with ts <= now are visible at each tick, like in a real night.
     * [fetchDelayMinutes] simulates the watch delivering data late.
     */
    fun run(
        mode: Mode,
        window: WakeWindow,
        sessionStart: Instant,
        detector: WakeDetector,
        samples: List<Sample>,
        tickMinutes: Long = 5,
        fetchDelayMinutes: Long = 0,
        monitorWakeAt: MonitorWakeAt = MonitorWakeAt.TARGET,
        maxHours: Long = 16,
    ): List<Step> {
        val engine = WakeEngine(mode, window, sessionStart, detector, monitorWakeAt)
        val steps = mutableListOf<Step>()
        var now = sessionStart
        val end = sessionStart.plus(Duration.ofHours(maxHours))
        while (now.isBefore(end)) {
            val visible = samples.filter { it.ts <= now.epochSecond - fetchDelayMinutes * 60 }
            val r = engine.evaluate(now, visible)
            steps += Step(now, visible.size, r)
            if (r is EngineResult.Wake) break
            now = now.plus(Duration.ofMinutes(tickMinutes))
        }
        return steps
    }

    /** A believable night for tests: HR drifts with sleep cycles, quiet until [wakeMovementAt], then restless. */
    fun syntheticNight(start: Instant, hours: Int, wakeMovementAt: Instant? = null, baseHr: Int = 55): List<Sample> {
        val out = mutableListOf<Sample>()
        var t = start.epochSecond
        val endTs = start.epochSecond + hours * 3600L
        while (t <= endTs) {
            val cycle = Math.sin((t - start.epochSecond) / 5400.0 * 2 * Math.PI) // ~90 min cycles
            val restless = wakeMovementAt != null && t >= wakeMovementAt.epochSecond
            val hr = baseHr + (3 * cycle).toInt() + if (restless) 14 else 0
            out += Sample(ts = t, heartRate = hr, steps = 0, movement = if (restless) 3 else 0)
            t += 60
        }
        return out
    }
}
