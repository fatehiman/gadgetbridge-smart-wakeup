package nodomain.freeyourgadget.gadgetbridge.sleepwake.service

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SwSettings
import java.security.MessageDigest

/** The only place where the module talks to Gadgetbridge's device layer. */
object DeviceAccess {
    /** Paired devices that can deliver activity data. */
    fun candidates(): List<GBDevice> =
        GBApplication.app().deviceManager.devices.filter { d ->
            try {
                d.deviceCoordinator.supportsDataFetching(d)
            } catch (e: Exception) {
                false
            }
        }

    /** The chosen device (by address), else the first candidate. */
    fun device(ctx: Context): GBDevice? {
        val addr = SwSettings(ctx).deviceAddress
        val all = candidates()
        return all.firstOrNull { it.address == addr } ?: all.firstOrNull()
    }

    fun byAddress(address: String): GBDevice? = GBApplication.app().deviceManager.devices.firstOrNull { it.address == address }

    fun isReady(d: GBDevice?) = d != null && d.isInitialized

    /** Same call the refresh button uses (ActivityChartsActivity). The CMF driver ignores the type mask. */
    fun requestFetch(d: GBDevice) {
        GBApplication.deviceService(d).onFetchRecordedData(
            RecordedDataTypes.TYPE_ACTIVITY or RecordedDataTypes.TYPE_SLEEP or RecordedDataTypes.TYPE_HEART_RATE or
                RecordedDataTypes.TYPE_SPO2 or RecordedDataTypes.TYPE_STRESS
        )
    }

    fun connect(d: GBDevice) {
        GBApplication.deviceService(d).connect()
    }

    /** Find-watch: the CMF driver cannot stop it (onFindDevice(false) is a no-op), so callers repeat it. */
    fun findWatch(d: GBDevice, start: Boolean) {
        GBApplication.deviceService(d).onFindDevice(start)
    }

    /** Stable, non-reversible id for the server (hash of the MAC). */
    fun externalId(address: String): String =
        MessageDigest.getInstance("SHA-256").digest(address.uppercase().toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
