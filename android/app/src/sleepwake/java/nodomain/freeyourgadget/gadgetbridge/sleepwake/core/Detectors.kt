package nodomain.freeyourgadget.gadgetbridge.sleepwake.core

/** Always waits. Used by monitor-only mode. */
object NoopDetector : WakeDetector {
    override val id = "noop"
    override fun evaluate(ctx: DetectionContext): Decision = Decision.Wait
}

/** Tunable numbers of [HeuristicDetector]. Defaults are the placeholders from the spec. */
data class HeuristicParams(
    val recentMinutes: Int = 5,
    val hrDeltaBpm: Int = 8,
    val basePercentile: Int = 20,
    /** minutes without movement that mark the estimated sleep onset */
    val onsetQuietMinutes: Int = 10,
    /** samples older than this are "stale": the detector waits (the deadline still fires) */
    val maxStaleMinutes: Int = 20,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "recent_min" to recentMinutes, "hr_delta" to hrDeltaBpm, "base_percentile" to basePercentile,
        "onset_quiet_min" to onsetQuietMinutes, "max_stale_min" to maxStaleMinutes,
    )
}

/**
 * v1 placeholder. Light sleep if `mov_recent > 0` or `hr_recent > hr_base + delta`.
 * - mov_recent: movement in the last N minutes (sum of positive movement values)
 * - hr_recent: median HR in the last N minutes
 * - hr_base: percentile of HR since the estimated sleep onset
 * If the data has no movement at all, only HR is used (limitation, see DISCOVERY.md).
 */
class HeuristicDetector(private val p: HeuristicParams = HeuristicParams()) : WakeDetector {
    override val id = "heuristic_v1"
    override fun params() = p.toMap()

    data class Stats(
        val movRecent: Int,
        val hrRecent: Double?,
        val hrBase: Double?,
        val onsetTs: Long,
        val hrCount: Int,
        val lastSampleAgeMin: Long?,
    )

    fun stats(ctx: DetectionContext): Stats {
        val nowS = ctx.now.epochSecond
        val recentFrom = nowS - p.recentMinutes * 60L
        val s = ctx.samples.filter { it.ts <= nowS }
        val recent = s.filter { it.ts > recentFrom }
        val mov = recent.sumOf { (it.movement ?: 0).coerceAtLeast(0) }
        val hrRecent = median(recent.mapNotNull { it.heartRate }.filter { it > 0 })
        val onset = estimateOnset(s, ctx.sessionStart.epochSecond)
        val baseline = s.filter { it.ts >= onset }.mapNotNull { it.heartRate }.filter { it > 0 }
        val base = percentile(baseline, p.basePercentile)
        val age = s.lastOrNull()?.let { (nowS - it.ts) / 60 }
        return Stats(mov, hrRecent, base, onset, baseline.size, age)
    }

    private fun estimateOnset(samples: List<Sample>, sessionStart: Long): Long = estimateOnset(samples, sessionStart, p.onsetQuietMinutes)

    override fun describe(ctx: DetectionContext): Map<String, Any?> {
        val st = stats(ctx)
        return mapOf(
            "mov_recent" to st.movRecent, "hr_recent" to st.hrRecent, "hr_base" to st.hrBase,
            "onset_ts" to st.onsetTs, "hr_samples" to st.hrCount, "last_sample_age_min" to st.lastSampleAgeMin,
            "params" to p.toMap(),
        )
    }

    override fun evaluate(ctx: DetectionContext): Decision {
        val st = stats(ctx)
        val age = st.lastSampleAgeMin
        if (age == null || age > p.maxStaleMinutes) return Decision.Wait // no fresh data: do not guess
        if (st.movRecent > 0) return Decision.WakeNow("movement in last ${p.recentMinutes} min (${st.movRecent})", 0.6)
        val hr = st.hrRecent
        val base = st.hrBase
        if (hr != null && base != null && hr > base + p.hrDeltaBpm) {
            return Decision.WakeNow("hr %.0f > base %.0f + %d".format(hr, base, p.hrDeltaBpm), 0.5)
        }
        return Decision.Wait
    }

    companion object {
        /** First moment followed by [quietMinutes] minutes with no movement; else the session start. */
        fun estimateOnset(samples: List<Sample>, sessionStart: Long, quietMinutes: Int): Long {
            val quiet = quietMinutes * 60L
            var quietSince: Long? = null
            for (x in samples) {
                if ((x.movement ?: 0) > 0) {
                    quietSince = null
                } else {
                    if (quietSince == null) quietSince = x.ts
                    if (x.ts - quietSince >= quiet) return quietSince
                }
            }
            return sessionStart
        }

        fun median(v: List<Int>): Double? {
            if (v.isEmpty()) return null
            val s = v.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m].toDouble() else (s[m - 1] + s[m]) / 2.0
        }

        /** Nearest-rank percentile, 0..100. */
        fun percentile(v: List<Int>, pct: Int): Double? {
            if (v.isEmpty()) return null
            val s = v.sorted()
            val rank = Math.ceil(pct / 100.0 * s.size).toInt().coerceIn(1, s.size)
            return s[rank - 1].toDouble()
        }
    }
}
