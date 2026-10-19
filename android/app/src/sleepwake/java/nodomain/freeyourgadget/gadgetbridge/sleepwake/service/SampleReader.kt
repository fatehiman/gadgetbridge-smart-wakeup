package nodomain.freeyourgadget.gadgetbridge.sleepwake.service

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Sample

/**
 * Reads samples from Gadgetbridge's own DB through the device's sample provider (read-only).
 * The module never copies samples into its own DB.
 */
object SampleReader {
    /** All samples with [fromSec] <= ts <= [toSec] (epoch seconds), time-ordered. */
    fun read(device: GBDevice, fromSec: Long, toSec: Long): List<Sample> {
        val from = fromSec.coerceAtLeast(0).toInt()
        val to = toSec.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val raw: List<ActivitySample> = GBApplication.acquireDbReadOnly().use { db ->
            val provider = device.deviceCoordinator.getSampleProvider(device, db.daoSession)
            @Suppress("UNCHECKED_CAST")
            (provider?.getAllActivitySamples(from, to) ?: emptyList<ActivitySample>()) as List<ActivitySample>
        }
        return raw.sortedBy { it.timestamp }.map { s ->
            val hr = s.heartRate.takeIf { it > 0 }
            val steps = s.steps.takeIf { it >= 0 }
            val intensity = s.rawIntensity.takeIf { it > 0 }
            // movement: steps in this sample, else the raw intensity. HR-only devices give 0.
            val movement = maxOf(steps ?: 0, intensity ?: 0)
            val kind = s.kind
            val stage = when (kind) {
                ActivityKind.LIGHT_SLEEP -> "light"
                ActivityKind.DEEP_SLEEP -> "deep"
                ActivityKind.REM_SLEEP -> "rem"
                ActivityKind.AWAKE_SLEEP -> "awake"
                else -> null
            }
            Sample(
                ts = s.timestamp.toLong(), heartRate = hr, steps = steps, intensity = intensity,
                movement = movement, sleepStage = stage,
            )
        }
    }

    fun maxTimestamp(samples: List<Sample>): Long? = samples.maxOfOrNull { it.ts }
}
