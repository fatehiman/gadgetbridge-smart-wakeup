package nodomain.freeyourgadget.gadgetbridge.sleepwake.core

import java.time.Instant

/** In monitor-only mode the single wake fires at the target time or at the deadline (setting). */
enum class MonitorWakeAt { TARGET, DEADLINE }

/**
 * Pure window + deadline logic. No Android, no clock of its own: the caller passes `now`.
 *
 * - Before window start: never wake.
 * - Inside the window (smart mode): ask the detector.
 * - At or after the deadline: always wake, whatever the detector or the connection says.
 */
class WakeEngine(
    private val mode: Mode,
    private val window: WakeWindow,
    private val sessionStart: Instant,
    private val detector: WakeDetector,
    private val monitorWakeAt: MonitorWakeAt = MonitorWakeAt.TARGET,
) {
    fun evaluate(now: Instant, samples: List<Sample>): EngineResult {
        return when (mode) {
            Mode.MONITOR_ONLY -> {
                val at = if (monitorWakeAt == MonitorWakeAt.TARGET) window.target else window.deadline
                if (!now.isBefore(at)) {
                    EngineResult.Wake(WakeReason.MONITOR_TARGET, null, mapOf("mode" to mode.id, "wake_at" to at.epochSecond))
                } else {
                    EngineResult.Wait(mapOf("mode" to mode.id))
                }
            }

            Mode.SMART_WAKE -> {
                if (now.isBefore(window.windowStart)) {
                    return EngineResult.Wait(mapOf("mode" to mode.id, "phase" to "before_window"))
                }
                val ctx = DetectionContext(now, window.windowStart, window.deadline, sessionStart, samples)
                if (!now.isBefore(window.deadline)) {
                    return EngineResult.Wake(WakeReason.DEADLINE, null, mapOf("mode" to mode.id, "phase" to "deadline"))
                }
                val inputs = detector.describe(ctx) + mapOf("detector" to detector.id)
                when (val d = detector.evaluate(ctx)) {
                    is Decision.WakeNow -> EngineResult.Wake(WakeReason.LIGHT_SLEEP, d.reason, inputs + ("confidence" to d.confidence))
                    Decision.Wait -> EngineResult.Wait(inputs)
                }
            }
        }
    }

    /** The instant at which the engine must fire, whatever else happens (used for the hard alarm). */
    fun hardDeadline(): Instant =
        if (mode == Mode.MONITOR_ONLY && monitorWakeAt == MonitorWakeAt.TARGET) window.target else window.deadline
}
