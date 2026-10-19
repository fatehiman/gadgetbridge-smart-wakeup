package nodomain.freeyourgadget.gadgetbridge.sleepwake.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Numbers of [ScoredDetector]. They arrive from the server as a "detector config" (JSON), so the algorithm can be
 * tuned from the data without a new app build. Keys of the JSON `params` object = the property names below in snake case.
 */
data class ScoredParams(
    val recentMin: Int = 5,
    val slopeWinMin: Int = 10,
    val basePercentile: Int = 20,
    val onsetQuietMin: Int = 10,
    val maxStaleMin: Int = 20,
    val stageLookbackMin: Int = 10,
    val cycleLenMin: Int = 90,
    val bias: Double = -1.0,
    val threshold: Double = 0.0,
    val weights: Map<String, Double> = DEFAULT_WEIGHTS,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "recent_min" to recentMin, "slope_win_min" to slopeWinMin, "base_percentile" to basePercentile,
        "onset_quiet_min" to onsetQuietMin, "max_stale_min" to maxStaleMin, "stage_lookback_min" to stageLookbackMin,
        "cycle_len_min" to cycleLenMin, "bias" to bias, "threshold" to threshold, "weights" to weights,
    )

    companion object {
        /** Starting weights (a prior from sleep science, to be replaced by weights fitted on the owner's own nights). */
        val DEFAULT_WEIGHTS: Map<String, Double> = mapOf(
            "mov" to 1.5, "hr_delta" to 0.12, "hr_slope" to 0.15, "hr_sd" to 0.1,
            "stage_light" to 1.0, "stage_rem" to 0.3, "stage_deep" to -1.5,
            "t_frac" to 1.0, "cycle_end" to 0.5,
        )

        fun fromMap(m: Map<String, Any?>): ScoredParams {
            val d = ScoredParams()
            fun i(k: String, def: Int) = (m[k] as? Number)?.toInt() ?: def
            fun f(k: String, def: Double) = (m[k] as? Number)?.toDouble() ?: def

            @Suppress("UNCHECKED_CAST")
            val w = (m["weights"] as? Map<String, Any?>)?.mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toDouble() } }?.toMap()
            return ScoredParams(
                recentMin = i("recent_min", d.recentMin).coerceAtLeast(1), slopeWinMin = i("slope_win_min", d.slopeWinMin).coerceAtLeast(2),
                basePercentile = i("base_percentile", d.basePercentile).coerceIn(1, 99), onsetQuietMin = i("onset_quiet_min", d.onsetQuietMin).coerceAtLeast(1),
                maxStaleMin = i("max_stale_min", d.maxStaleMin).coerceAtLeast(1), stageLookbackMin = i("stage_lookback_min", d.stageLookbackMin).coerceAtLeast(1),
                cycleLenMin = i("cycle_len_min", d.cycleLenMin).coerceIn(30, 180), bias = f("bias", d.bias), threshold = f("threshold", d.threshold),
                weights = DEFAULT_WEIGHTS + (w ?: emptyMap()),
            )
        }
    }
}

/**
 * Weighted sum of features of the last minutes: wake when `bias + sum(weight * feature) >= threshold`.
 * Features (all computed from samples with ts <= now):
 * - mov: samples with movement in the last `recent_min` minutes, capped at 3, divided by 3 (0..1)
 * - hr_delta: median HR (last recent_min) minus the base HR (percentile of HR since estimated sleep onset), clipped -10..30
 * - hr_slope: median HR of the last `slope_win_min` minutes minus the median of the `slope_win_min` before, clipped -10..20
 * - hr_sd: standard deviation of HR in the last `slope_win_min` minutes, clipped 0..10 (needs 3 samples)
 * - stage_light / stage_rem / stage_deep: the newest watch-reported stage within `stage_lookback_min` (light and awake count as light)
 * - t_frac: position in the wake window, 0 at window start, 1 at the deadline
 * - cycle_end: 1 at a ~90 minute sleep-cycle boundary counted from the estimated sleep onset, 0 far from one
 * No fresh data (newest sample older than `max_stale_min`) means Wait; the hard deadline still fires.
 * The same feature code exists in tools/analysis/detector.py (for fitting). Test vectors keep both in step.
 */
class ScoredDetector(private val p: ScoredParams = ScoredParams()) : WakeDetector {
    override val id = "scored_v1"
    override fun params() = p.toMap()

    data class Result(val features: Map<String, Double>, val score: Double, val stale: Boolean, val lastSampleAgeMin: Long?)

    fun compute(ctx: DetectionContext): Result {
        val nowS = ctx.now.epochSecond
        val s = ctx.samples.filter { it.ts <= nowS }
        val age = s.lastOrNull()?.let { (nowS - it.ts) / 60 }
        val stale = age == null || age > p.maxStaleMin

        fun window(fromMin: Int, toMin: Int) = s.filter { it.ts > nowS - fromMin * 60L && it.ts <= nowS - toMin * 60L }
        fun hr(list: List<Sample>) = list.mapNotNull { it.heartRate }.filter { it > 0 }

        val recent = window(p.recentMin, 0)
        val mov = min(recent.count { (it.movement ?: 0) > 0 }, 3) / 3.0
        val onset = HeuristicDetector.estimateOnset(s, ctx.sessionStart.epochSecond, p.onsetQuietMin)
        val base = HeuristicDetector.percentile(hr(s.filter { it.ts >= onset }), p.basePercentile)
        val hrRecent = HeuristicDetector.median(hr(recent))
        val hrDelta = if (hrRecent != null && base != null) (hrRecent - base).coerceIn(-10.0, 30.0) else 0.0
        val cur = HeuristicDetector.median(hr(window(p.slopeWinMin, 0)))
        val prev = HeuristicDetector.median(hr(window(2 * p.slopeWinMin, p.slopeWinMin)))
        val slope = if (cur != null && prev != null) (cur - prev).coerceIn(-10.0, 20.0) else 0.0
        val hrWin = hr(window(p.slopeWinMin, 0))
        val sd = if (hrWin.size >= 3) {
            val mean = hrWin.average()
            sqrt(hrWin.sumOf { (it - mean) * (it - mean) } / hrWin.size).coerceIn(0.0, 10.0)
        } else 0.0
        val stage = s.lastOrNull { it.sleepStage != null && it.ts > nowS - p.stageLookbackMin * 60L }?.sleepStage
        val windowLen = (ctx.deadline.epochSecond - ctx.windowStart.epochSecond).toDouble()
        val tFrac = if (windowLen <= 0) 1.0 else ((nowS - ctx.windowStart.epochSecond) / windowLen).coerceIn(0.0, 1.0)
        val sinceOnsetMin = (nowS - onset) / 60.0
        val pos = ((sinceOnsetMin % p.cycleLenMin) + p.cycleLenMin) % p.cycleLenMin / p.cycleLenMin
        val cycleEnd = max(0.0, 1.0 - min(pos, 1 - pos) * 4)

        val f = linkedMapOf(
            "mov" to mov, "hr_delta" to hrDelta, "hr_slope" to slope, "hr_sd" to sd,
            "stage_light" to if (stage == "light" || stage == "awake") 1.0 else 0.0,
            "stage_rem" to if (stage == "rem") 1.0 else 0.0,
            "stage_deep" to if (stage == "deep") 1.0 else 0.0,
            "t_frac" to tFrac, "cycle_end" to cycleEnd,
        )
        val score = p.bias + f.entries.sumOf { (p.weights[it.key] ?: 0.0) * it.value }
        return Result(f, score, stale, age)
    }

    override fun evaluate(ctx: DetectionContext): Decision {
        val r = compute(ctx)
        if (r.stale) return Decision.Wait
        return if (r.score >= p.threshold) Decision.WakeNow("score %.2f >= %.2f".format(r.score, p.threshold), (r.score - p.threshold).coerceIn(0.0, 1.0)) else Decision.Wait
    }

    override fun describe(ctx: DetectionContext): Map<String, Any?> {
        val r = compute(ctx)
        return mapOf(
            "features" to r.features.mapValues { Math.round(it.value * 1000) / 1000.0 }, "score" to Math.round(r.score * 1000) / 1000.0,
            "threshold" to p.threshold, "stale" to r.stale, "last_sample_age_min" to r.lastSampleAgeMin,
        )
    }
}

/** Builds the detector from the downloaded config JSON, else from the local heuristic numbers. */
object DetectorFactory {
    const val DEFAULT_ID = "heuristic_v1"

    fun create(configJson: String?, fallback: HeuristicParams): WakeDetector {
        val cfg = try {
            @Suppress("UNCHECKED_CAST")
            configJson?.takeIf { it.isNotBlank() }?.let { Json.parse(it) as? Map<String, Any?> }
        } catch (e: Exception) {
            null
        } ?: return HeuristicDetector(fallback)
        @Suppress("UNCHECKED_CAST")
        val params = (cfg["params"] as? Map<String, Any?>) ?: emptyMap()
        return when (cfg["detector"]) {
            "scored_v1" -> ScoredDetector(ScoredParams.fromMap(params))
            "heuristic_v1" -> {
                fun i(k: String, def: Int) = (params[k] as? Number)?.toInt() ?: def
                HeuristicDetector(
                    HeuristicParams(
                        recentMinutes = i("recent_min", fallback.recentMinutes).coerceAtLeast(1), hrDeltaBpm = i("hr_delta", fallback.hrDeltaBpm),
                        basePercentile = i("base_percentile", fallback.basePercentile).coerceIn(1, 99),
                        onsetQuietMinutes = i("onset_quiet_min", fallback.onsetQuietMinutes).coerceAtLeast(1),
                        maxStaleMinutes = i("max_stale_min", fallback.maxStaleMinutes).coerceAtLeast(1),
                    )
                )
            }
            else -> HeuristicDetector(fallback)
        }
    }
}
