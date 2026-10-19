package nodomain.freeyourgadget.gadgetbridge.sleepwake.sync

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.BuildConfig
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Json
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.RawJson
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Sample
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.SyncPlan
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SleepWakeStore
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwEvent
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwSession
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwSettings
import nodomain.freeyourgadget.gadgetbridge.sleepwake.service.DeviceAccess
import nodomain.freeyourgadget.gadgetbridge.sleepwake.service.SampleReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads sessions, events and samples to the user's own server (see webapp/README.md).
 * The only networking code of the module: it talks to the configured URL only, no analytics, no other host.
 * Never called from the night loop, only from WorkManager or the "Sync now" button.
 */
class ServerSync(private val ctx: Context) {
    private val settings = SwSettings(ctx)
    private val store = SleepWakeStore.get(ctx)

    data class Result(val ok: Boolean, val message: String)

    class SyncException(message: String, val retryable: Boolean) : Exception(message)

    fun ping(): Result = try {
        val body = request("GET", "/api/v1/ping", null)
        Result(true, "OK $body".take(120))
    } catch (e: SyncException) {
        Result(false, e.message ?: "error")
    }

    /**
     * Downloads the newest detector config (tuned by the analysis from the nights). A failure is harmless:
     * the last downloaded config, or the built-in numbers, stay in use. Short timeouts, safe to call right before a night.
     */
    fun fetchConfig(quick: Boolean = false): Result = try {
        check()
        val body = request("GET", "/api/v1/config", null, if (quick) 6_000 else 20_000, if (quick) 8_000 else 30_000)
        @Suppress("UNCHECKED_CAST")
        val m = Json.parse(body) as Map<String, Any?>
        val version = (m["version"] as? Number)?.toInt() ?: 0
        val cfg = m["config"]
        if (cfg is Map<*, *> && version != settings.detectorConfigVersion) {
            settings.detectorConfigJson = Json.encode(cfg)
            settings.detectorConfigVersion = version
            Result(true, "config v$version downloaded")
        } else {
            Result(true, "config v${settings.detectorConfigVersion} is current")
        }
    } catch (e: SyncException) {
        Result(false, e.message ?: "error")
    } catch (e: Exception) {
        Result(false, "config: ${e.javaClass.simpleName}")
    }

    fun run(): Result {
        val started = System.currentTimeMillis() / 1000
        return try {
            check()
            fetchConfig()
            val counts = intArrayOf(0, 0, 0)
            uploadSessionsAndEvents(counts)
            uploadSamples(counts)
            val msg = "OK: ${counts[0]} sessions, ${counts[1]} events, ${counts[2]} samples"
            settings.lastSyncTs = started
            settings.lastSyncStatus = msg
            Result(true, msg)
        } catch (e: SyncException) {
            settings.lastSyncStatus = "Error: ${e.message}"
            Result(false, e.message ?: "error")
        } catch (e: Exception) {
            settings.lastSyncStatus = "Error: ${e.javaClass.simpleName}: ${e.message}"
            Result(false, settings.lastSyncStatus)
        }
    }

    private fun check() {
        if (!BuildConfig.INTERNET_ACCESS) throw SyncException("this build has no network access", false)
        if (!settings.syncEnabled) throw SyncException("sync is disabled", false)
        if (settings.apiToken.isBlank()) throw SyncException("no API token", false)
        if (!SyncPlan.isUrlAllowed(settings.baseUrl, BuildConfig.DEBUG)) throw SyncException("URL must start with https://", false)
    }

    private fun deviceBlock(address: String): Map<String, Any?> {
        val d = if (address.isNotEmpty()) DeviceAccess.byAddress(address) else DeviceAccess.device(ctx)
        val addr = address.ifEmpty { d?.address ?: "unknown" }
        return mapOf("external_id" to DeviceAccess.externalId(addr), "name" to d?.name, "model" to d?.model, "firmware" to d?.firmwareVersion)
    }

    private fun sessionJson(s: SwSession): Map<String, Any?> = mapOf(
        "uuid" to s.uuid, "started_at" to s.startedAt, "ended_at" to s.endedAt, "mode" to s.mode,
        "target_time" to s.targetTime, "tolerance_min" to s.toleranceMin, "window_start" to s.windowStart,
        "deadline" to s.deadline, "detector_id" to s.detectorId, "detector_params" to RawJson(s.detectorParamsJson),
        "timezone" to s.timezone, "wake_reason" to s.wakeReason,
    )

    private fun eventJson(e: SwEvent): Map<String, Any?> = mapOf(
        "session_uuid" to e.sessionUuid, "ts" to e.ts, "type" to e.type, "payload" to e.payloadJson?.let { RawJson(it) },
    )

    private fun encode(v: Any?): String = Json.encode(v)

    private fun uploadSessionsAndEvents(counts: IntArray) {
        while (true) {
            val events = store.pendingEvents(SyncPlan.BATCH_SIZE)
            val pendingSessions = store.sessionsToUpload()
            if (events.isEmpty() && pendingSessions.isEmpty()) return
            val byUuid = linkedMapOf<String, SwSession>()
            pendingSessions.forEach { byUuid[it.uuid] = it }
            events.map { it.sessionUuid }.distinct().forEach { u -> if (u !in byUuid) store.session(u)?.let { byUuid[u] = it } }
            val address = byUuid.values.firstOrNull()?.deviceAddress ?: ""
            val payload = mapOf(
                "device" to deviceBlock(address), "sessions" to byUuid.values.map { sessionJson(it) },
                "events" to events.map { eventJson(it) },
            )
            post(payload)
            store.markSessionsUploaded(pendingSessions.map { it.uuid })
            store.markEventsUploaded(events.map { it.id })
            counts[0] += byUuid.size
            counts[1] += events.size
            if (events.size < SyncPlan.BATCH_SIZE) return
        }
    }

    private fun uploadSamples(counts: IntArray) {
        val device = DeviceAccess.device(ctx) ?: return
        val nowS = System.currentTimeMillis() / 1000
        val from = SyncPlan.sampleFrom(settings.uploadedSampleTs, nowS, SwSettings.SAMPLE_LOOKBACK_SECONDS)
        val samples: List<Sample> = SampleReader.read(device, from, nowS)
        if (samples.isEmpty()) return
        var maxTs = settings.uploadedSampleTs
        for (batch in SyncPlan.batches(samples)) {
            post(mapOf("device" to deviceBlock(device.address), "samples" to batch.map { sampleJson(it) }))
            counts[2] += batch.size
            maxTs = maxOf(maxTs, batch.maxOf { it.ts })
            settings.uploadedSampleTs = maxTs // resumable: progress is kept after every batch
        }
    }

    private fun sampleJson(s: Sample) = mapOf(
        "ts" to s.ts, "heart_rate" to s.heartRate, "steps" to s.steps, "intensity" to s.intensity,
        "movement" to s.movement, "spo2" to s.spo2, "sleep_stage" to s.sleepStage,
    )

    private fun post(payload: Map<String, Any?>) {
        request("POST", "/api/v1/ingest", encode(payload))
    }

    private fun request(method: String, path: String, body: String?, connectMs: Int = 20_000, readMs: Int = 60_000): String {
        val url = URL(settings.baseUrl.trimEnd('/') + path)
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.setRequestProperty("Authorization", "Bearer " + settings.apiToken) // never logged
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) return text
            val retryable = code == 429 || code >= 500
            throw SyncException("HTTP $code" + if (code == 401) " (bad token)" else if (code == 422) " (rejected: ${text.take(160)})" else "", retryable)
        } catch (e: java.io.IOException) {
            throw SyncException("network: ${e.javaClass.simpleName}", true)
        } finally {
            c.disconnect()
        }
    }
}
