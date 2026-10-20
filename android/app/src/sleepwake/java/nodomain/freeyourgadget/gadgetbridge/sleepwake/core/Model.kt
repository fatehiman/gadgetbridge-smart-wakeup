/*  SleepWake module (fork of Gadgetbridge), AGPL-3.0-or-later, same license as Gadgetbridge.  */
package nodomain.freeyourgadget.gadgetbridge.sleepwake.core

import java.time.Instant

/** One minute-ish sample of the night. [ts] is epoch seconds. [movement] > 0 means the wrist moved. */
data class Sample(
    val ts: Long,
    val heartRate: Int? = null,
    val steps: Int? = null,
    val intensity: Int? = null,
    val movement: Int? = null,
    val spo2: Int? = null,
    val sleepStage: String? = null,
    /** anything else the watch gave: stress, raw bytes of the activity record, ... (uploaded as the server's `extra`) */
    val extra: Map<String, Any?>? = null,
)

enum class Mode(val id: String) {
    MONITOR_ONLY("monitor_only"),
    SMART_WAKE("smart_wake");

    companion object {
        fun fromId(id: String?): Mode = entries.firstOrNull { it.id == id } ?: MONITOR_ONLY
    }
}

enum class WakeReason { LIGHT_SLEEP, DEADLINE, MONITOR_TARGET, MANUAL, FALLBACK }

data class DetectionContext(
    val now: Instant,
    val windowStart: Instant,
    val deadline: Instant,
    val sessionStart: Instant,
    /** all session samples so far, time-ordered */
    val samples: List<Sample>,
)

sealed interface Decision {
    object Wait : Decision {
        override fun toString() = "Wait"
    }

    data class WakeNow(val reason: String, val confidence: Double?) : Decision
}

/** Pluggable detector. Later replaced by an algorithm derived from real data. */
interface WakeDetector {
    val id: String

    fun evaluate(ctx: DetectionContext): Decision

    /** The numbers behind the decision, for the event log (so the decision can be replayed offline). */
    fun describe(ctx: DetectionContext): Map<String, Any?> = emptyMap()

    /** Parameters for the session row. */
    fun params(): Map<String, Any?> = emptyMap()
}

/** What the engine tells the caller to do. */
sealed interface EngineResult {
    val inputs: Map<String, Any?>

    data class Wait(override val inputs: Map<String, Any?> = emptyMap()) : EngineResult
    data class Wake(val reason: WakeReason, val detail: String?, override val inputs: Map<String, Any?> = emptyMap()) : EngineResult
}
