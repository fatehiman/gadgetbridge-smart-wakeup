package nodomain.freeyourgadget.gadgetbridge.sleepwake.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.CmfRawTap

/**
 * Runs when the app process starts (before Application.onCreate), the usual trick for "initialize without touching
 * the Application class". It connects the raw tap of the CMF driver to the module's own DB, so the 16 unknown
 * bytes of every activity record are kept, whoever started the fetch (this module, the refresh button, auto fetch).
 */
class SwInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return true
        CmfRawTap.setListener { ts, steps, dist, cal, unk ->
            SleepWakeStore.get(ctx).putRawActivity(ts.toLong(), steps, dist, cal, unk)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
