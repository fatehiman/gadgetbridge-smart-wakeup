package nodomain.freeyourgadget.gadgetbridge.sleepwake.data

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.BuildConfig
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Json
import nodomain.freeyourgadget.gadgetbridge.sleepwake.sync.SyncScheduler
import java.io.File

/**
 * Debug builds only: lets the developer put the sync settings in through adb instead of typing a 51 character token.
 * `adb shell run-as <pkg> sh -c 'cat > files/provision.json'` with {"base_url":"...","token":"..."}.
 * The file is read once and deleted. Release builds ignore it. The token still ends up in the encrypted storage.
 */
object Provision {
    fun applyIfPresent(ctx: Context) {
        if (!BuildConfig.DEBUG) return
        val f = File(ctx.filesDir, "provision.json")
        if (!f.exists()) return
        // read and delete first: applying the settings creates SwSettings objects again, which must not re-enter this code
        val text = try { f.readText() } finally { f.delete() }
        try {
            @Suppress("UNCHECKED_CAST")
            val m = Json.parse(text) as Map<String, Any?>
            val s = SwSettings(ctx, skipProvision = true)
            (m["base_url"] as? String)?.let { s.baseUrl = it }
            (m["token"] as? String)?.let { s.apiToken = it }
            if (m["enable_sync"] != false) s.syncEnabled = true
            SyncScheduler.apply(ctx)
            SyncScheduler.enqueueNow(ctx, 3)
        } catch (e: Exception) {
            android.util.Log.w("SleepWake", "provision failed: ${e.javaClass.simpleName}")
        }
    }
}
