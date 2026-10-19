package nodomain.freeyourgadget.gadgetbridge.sleepwake.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import nodomain.freeyourgadget.gadgetbridge.sleepwake.core.Json

data class SwSession(
    val uuid: String,
    val startedAt: Long,
    val endedAt: Long?,
    val mode: String,
    val targetTime: Long,
    val toleranceMin: Int,
    val windowStart: Long,
    val deadline: Long,
    /** when the hard alarm fires: target (monitor-only, default) or deadline */
    val fireAt: Long,
    val detectorId: String,
    val detectorParamsJson: String,
    val timezone: String,
    val deviceAddress: String,
    val wakeReason: String?,
    val wakeTs: Long?,
    val ackedAt: Long?,
    val uploaded: Boolean,
)

data class SwEvent(val id: Long, val sessionUuid: String, val ts: Long, val type: String, val payloadJson: String?)

/**
 * Own SQLite file (not Gadgetbridge's greenDAO DB), so upstream schema migrations never touch it.
 * All session state lives here, so every component can restore it after the process was killed.
 */
class SleepWakeStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "sleepwake.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sw_session (
                uuid TEXT PRIMARY KEY, started_at INTEGER NOT NULL, ended_at INTEGER, mode TEXT NOT NULL,
                target_time INTEGER NOT NULL, tolerance_min INTEGER NOT NULL, window_start INTEGER NOT NULL,
                deadline INTEGER NOT NULL, fire_at INTEGER NOT NULL, detector_id TEXT NOT NULL,
                detector_params_json TEXT NOT NULL, timezone TEXT NOT NULL, device_address TEXT NOT NULL,
                wake_reason TEXT, wake_ts INTEGER, acked_at INTEGER, uploaded INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE sw_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT, session_uuid TEXT NOT NULL, ts INTEGER NOT NULL,
                type TEXT NOT NULL, payload_json TEXT, uploaded INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX sw_event_session ON sw_event(session_uuid, ts)")
        db.execSQL("CREATE INDEX sw_event_uploaded ON sw_event(uploaded)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---- sessions ----

    @Synchronized
    fun insertSession(s: SwSession) {
        writableDatabase.insertOrThrow("sw_session", null, ContentValues().apply {
            put("uuid", s.uuid); put("started_at", s.startedAt); put("mode", s.mode); put("target_time", s.targetTime)
            put("tolerance_min", s.toleranceMin); put("window_start", s.windowStart); put("deadline", s.deadline)
            put("fire_at", s.fireAt); put("detector_id", s.detectorId); put("detector_params_json", s.detectorParamsJson)
            put("timezone", s.timezone); put("device_address", s.deviceAddress); put("uploaded", 0)
        })
    }

    private fun readSession(c: android.database.Cursor) = SwSession(
        uuid = c.getString(c.getColumnIndexOrThrow("uuid")),
        startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
        endedAt = c.longOrNull("ended_at"),
        mode = c.getString(c.getColumnIndexOrThrow("mode")),
        targetTime = c.getLong(c.getColumnIndexOrThrow("target_time")),
        toleranceMin = c.getInt(c.getColumnIndexOrThrow("tolerance_min")),
        windowStart = c.getLong(c.getColumnIndexOrThrow("window_start")),
        deadline = c.getLong(c.getColumnIndexOrThrow("deadline")),
        fireAt = c.getLong(c.getColumnIndexOrThrow("fire_at")),
        detectorId = c.getString(c.getColumnIndexOrThrow("detector_id")),
        detectorParamsJson = c.getString(c.getColumnIndexOrThrow("detector_params_json")),
        timezone = c.getString(c.getColumnIndexOrThrow("timezone")),
        deviceAddress = c.getString(c.getColumnIndexOrThrow("device_address")),
        wakeReason = c.getString(c.getColumnIndexOrThrow("wake_reason")),
        wakeTs = c.longOrNull("wake_ts"),
        ackedAt = c.longOrNull("acked_at"),
        uploaded = c.getInt(c.getColumnIndexOrThrow("uploaded")) != 0,
    )

    private fun android.database.Cursor.longOrNull(col: String): Long? {
        val i = getColumnIndexOrThrow(col)
        return if (isNull(i)) null else getLong(i)
    }

    /** The one running session (ended_at is NULL), or null. */
    @Synchronized
    fun activeSession(): SwSession? = readableDatabase.query("sw_session", null, "ended_at IS NULL", null, null, null, "started_at DESC", "1")
        .use { if (it.moveToFirst()) readSession(it) else null }

    @Synchronized
    fun session(uuid: String): SwSession? = readableDatabase.query("sw_session", null, "uuid=?", arrayOf(uuid), null, null, null)
        .use { if (it.moveToFirst()) readSession(it) else null }

    @Synchronized
    fun recentSessions(limit: Int): List<SwSession> = readableDatabase.query("sw_session", null, null, null, null, null, "started_at DESC", limit.toString())
        .use { c -> buildList { while (c.moveToNext()) add(readSession(c)) } }

    @Synchronized
    fun sessionsToUpload(): List<SwSession> = readableDatabase.query("sw_session", null, "uploaded=0", null, null, null, "started_at")
        .use { c -> buildList { while (c.moveToNext()) add(readSession(c)) } }

    @Synchronized
    fun markSessionsUploaded(uuids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            uuids.forEach { db.execSQL("UPDATE sw_session SET uploaded=1 WHERE uuid=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Any change to a session makes it upload again (the server upserts). */
    @Synchronized
    fun updateSession(uuid: String, values: Map<String, Any?>) {
        val cv = ContentValues()
        for ((k, v) in values) when (v) {
            null -> cv.putNull(k)
            is Long -> cv.put(k, v)
            is Int -> cv.put(k, v)
            else -> cv.put(k, v.toString())
        }
        cv.put("uploaded", 0)
        writableDatabase.update("sw_session", cv, "uuid=?", arrayOf(uuid))
    }

    // ---- events ----

    @Synchronized
    fun addEvent(sessionUuid: String, type: String, payload: Map<String, Any?>? = null, tsMs: Long = System.currentTimeMillis()): Long {
        // unique (session, ts, type) on the server: nudge the ts if the same type is logged within one ms
        var ts = tsMs
        while (readableDatabase.rawQuery("SELECT 1 FROM sw_event WHERE session_uuid=? AND ts=? AND type=?", arrayOf(sessionUuid, ts.toString(), type)).use { it.moveToFirst() }) ts++
        return writableDatabase.insertOrThrow("sw_event", null, ContentValues().apply {
            put("session_uuid", sessionUuid); put("ts", ts); put("type", type)
            if (payload != null) put("payload_json", Json.encode(payload)) else putNull("payload_json")
            put("uploaded", 0)
        })
    }

    @Synchronized
    fun events(sessionUuid: String, type: String? = null): List<SwEvent> {
        val sel = if (type == null) "session_uuid=?" else "session_uuid=? AND type=?"
        val args = if (type == null) arrayOf(sessionUuid) else arrayOf(sessionUuid, type)
        return readableDatabase.query("sw_event", null, sel, args, null, null, "ts").use { readEvents(it) }
    }

    @Synchronized
    fun pendingEvents(limit: Int): List<SwEvent> = readableDatabase.query("sw_event", null, "uploaded=0", null, null, null, "id", limit.toString()).use { readEvents(it) }

    @Synchronized
    fun markEventsUploaded(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.execSQL("UPDATE sw_event SET uploaded=1 WHERE id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun lastEventOfType(sessionUuid: String, type: String): SwEvent? = events(sessionUuid, type).lastOrNull()

    private fun readEvents(c: android.database.Cursor) = buildList {
        while (c.moveToNext()) {
            add(
                SwEvent(
                    c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("session_uuid")),
                    c.getLong(c.getColumnIndexOrThrow("ts")), c.getString(c.getColumnIndexOrThrow("type")),
                    c.getString(c.getColumnIndexOrThrow("payload_json")),
                )
            )
        }
    }

    companion object {
        @Volatile private var instance: SleepWakeStore? = null

        fun get(context: Context): SleepWakeStore =
            instance ?: synchronized(this) { instance ?: SleepWakeStore(context).also { instance = it } }
    }
}
