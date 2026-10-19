package nodomain.freeyourgadget.gadgetbridge.sleepwake.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Injectable clock, so all time logic can be tested. */
fun interface Clock {
    fun now(): Instant

    companion object {
        val SYSTEM = Clock { Instant.now() }
    }
}

/** `window_start = target - tol`, `deadline = target + tol`. */
data class WakeWindow(val target: Instant, val toleranceMin: Int) {
    val windowStart: Instant get() = target.minus(Duration.ofMinutes(toleranceMin.toLong()))
    val deadline: Instant get() = target.plus(Duration.ofMinutes(toleranceMin.toLong()))

    fun isInside(now: Instant) = !now.isBefore(windowStart) && now.isBefore(deadline)

    companion object {
        /**
         * The next occurrence of the local wall-clock [time] strictly after [now] in [zone].
         * Uses zoned arithmetic, so it is right across midnight and DST changes.
         * If the local time does not exist (DST gap), java.time moves it forward.
         */
        fun nextTarget(now: Instant, time: LocalTime, zone: ZoneId): Instant {
            val local = now.atZone(zone)
            var candidate = ZonedDateTime.of(local.toLocalDate(), time, zone)
            if (!candidate.toInstant().isAfter(now)) {
                candidate = ZonedDateTime.of(local.toLocalDate().plusDays(1), time, zone)
            }
            return candidate.toInstant()
        }

        fun forNextTarget(now: Instant, time: LocalTime, zone: ZoneId, toleranceMin: Int) =
            WakeWindow(nextTarget(now, time, zone), toleranceMin)
    }
}
