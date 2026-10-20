package nodomain.freeyourgadget.gadgetbridge.sleepwake.service

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.devices.CmfSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.CmfSpo2SampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.CmfStressSampleProvider
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Sample
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SleepWakeStore
import java.util.TreeMap

/**
 * Reads samples from Gadgetbridge's own DB (read-only) and merges everything the watch gave for the minute:
 * heart rate, steps, SpO2, stress, the watch's own sleep stage (expanded to minutes) and the raw activity bytes.
 * The module never copies Gadgetbridge's samples into its own DB.
 */
object SampleReader {
    /** All samples with [fromSec] <= ts <= [toSec] (epoch seconds), time-ordered. */
    fun read(ctx: Context, device: GBDevice, fromSec: Long, toSec: Long): List<Sample> {
        val from = fromSec.coerceAtLeast(0).toInt()
        val to = toSec.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val byTs = TreeMap<Long, Sample>()

        fun merge(ts: Long, f: (Sample) -> Sample) {
            if (ts < fromSec || ts > toSec) return
            byTs[ts] = f(byTs[ts] ?: Sample(ts = ts))
        }

        GBApplication.acquireDbReadOnly().use { db ->
            val provider = device.deviceCoordinator.getSampleProvider(device, db.daoSession)
            @Suppress("UNCHECKED_CAST")
            val raw = (provider?.getAllActivitySamples(from, to) ?: emptyList<ActivitySample>()) as List<ActivitySample>
            for (s in raw) {
                val steps = s.steps.takeIf { it >= 0 }
                val intensity = s.rawIntensity.takeIf { it > 0 }
                merge(s.timestamp.toLong()) {
                    it.copy(
                        heartRate = s.heartRate.takeIf { h -> h > 0 }, steps = steps, intensity = intensity,
                        // movement: steps in this sample, else the raw intensity. HR-only samples give 0.
                        movement = maxOf(steps ?: 0, intensity ?: 0),
                        sleepStage = stageName(s.kind) ?: it.sleepStage,
                    )
                }
            }

            if (device.deviceCoordinator.javaClass.simpleName.startsWith("Cmf")) {
                val session = db.daoSession
                runCatching {
                    // SpO2 and stress (the watch derives stress from heart-rate variability)
                    for (x in CmfSpo2SampleProvider(device, session).getAllSamples(fromSec * 1000, toSec * 1000)) {
                        merge(x.timestamp / 1000 / 60 * 60) { it.copy(spo2 = x.spo2) }
                    }
                    for (x in CmfStressSampleProvider(device, session).getAllSamples(fromSec * 1000, toSec * 1000)) {
                        merge(x.timestamp / 1000 / 60 * 60) { it.copy(extra = (it.extra ?: emptyMap()) + ("stress" to x.stress)) }
                    }
                    // the watch's own sleep stages: arrive hours after the night, so they are expanded here to one value per minute
                    val ms = (fromSec - 12 * 3600) * 1000
                    for (st in CmfSleepStageSampleProvider(device, session).getAllSamples(ms, toSec * 1000)) {
                        val name = when (st.stage) { 1 -> "deep"; 2 -> "light"; 3 -> "rem"; else -> "awake" }
                        var t = st.timestamp / 1000 / 60 * 60
                        val end = st.timestamp / 1000 + st.duration
                        while (t < end) {
                            merge(t) { it.copy(sleepStage = name) }
                            t += 60
                        }
                    }
                }
            }
        }

        // the raw 16 unknown bytes of each activity record (kept in the module's own DB)
        for (r in SleepWakeStore.get(ctx).rawActivity(fromSec, toSec)) {
            merge(r.ts) { it.copy(extra = (it.extra ?: emptyMap()) + mapOf("raw" to r.hex, "dist" to r.dist, "cal" to r.cal)) }
        }
        // phone movement per minute (accelerometer on the mattress): pm = sum of acceleration changes, pmx = peak, pmn = readings that moved
        for (m in SleepWakeStore.get(ctx).phoneMotion(fromSec, toSec)) {
            merge(m.ts) { it.copy(extra = (it.extra ?: emptyMap()) + mapOf("pm" to Math.round(m.sum * 100) / 100.0, "pmx" to Math.round(m.peak * 100) / 100.0, "pmn" to m.moved)) }
        }
        return byTs.values.toList()
    }

    private fun stageName(kind: ActivityKind): String? = when (kind) {
        ActivityKind.LIGHT_SLEEP -> "light"
        ActivityKind.DEEP_SLEEP -> "deep"
        ActivityKind.REM_SLEEP -> "rem"
        ActivityKind.AWAKE_SLEEP -> "awake"
        else -> null
    }

    fun maxTimestamp(samples: List<Sample>): Long? = samples.maxOfOrNull { it.ts }
}
