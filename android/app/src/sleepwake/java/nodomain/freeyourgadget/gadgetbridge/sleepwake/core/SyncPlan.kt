package nodomain.freeyourgadget.gadgetbridge.sleepwake.core

/** Pure helpers for incremental, idempotent uploads. The server dedupes, so re-sending is always safe. */
object SyncPlan {
    const val BATCH_SIZE = 1000
    const val FIRST_RUN_DAYS = 7L

    /**
     * Samples may arrive late or be back-filled with older timestamps, so every upload starts a
     * trailing [lookbackSeconds] before the newest timestamp that was already uploaded.
     * [uploadedTs] == 0 means never uploaded: send the last [FIRST_RUN_DAYS] days.
     */
    fun sampleFrom(uploadedTs: Long, nowS: Long, lookbackSeconds: Long): Long =
        if (uploadedTs <= 0) nowS - FIRST_RUN_DAYS * 86400 else (uploadedTs - lookbackSeconds).coerceAtLeast(0)

    fun <T> batches(items: List<T>, size: Int = BATCH_SIZE): List<List<T>> = if (items.isEmpty()) emptyList() else items.chunked(size)

    /** True for https, and for http only in debug builds (cleartext is rejected). */
    fun isUrlAllowed(url: String, debugBuild: Boolean): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("https://") || (debugBuild && u.startsWith("http://"))
    }
}
