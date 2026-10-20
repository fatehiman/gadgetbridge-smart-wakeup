package nodomain.freeyourgadget.gadgetbridge.sleepwake.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SleepWakeStore
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Phone movement during the night, from the phone's accelerometer (the usual trick of sleep apps: put the phone on the
 * mattress next to you and it feels you turn). The CMF watch gives no live movement, so this is the extra signal.
 *
 * Per minute it stores: the sum of acceleration changes, the biggest change, and how many readings moved. If the phone
 * lies on a table the numbers stay near zero: the analysis checks, against the watch's sleep stages that arrive the next
 * morning, whether the signal is useful at all. Samples are batched (about 30 s) so the CPU is not kept awake.
 */
class PhoneMotion(private val ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val store = SleepWakeStore.get(ctx)
    private var last: FloatArray? = null
    private var minute = -1L
    private var sum = 0.0
    private var peak = 0.0
    private var moved = 0
    private var n = 0
    private var running = false

    /** @return false if the phone has no accelerometer */
    fun start(): Boolean {
        if (running) return true
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        running = sm.registerListener(this, sensor, SAMPLE_US, BATCH_US)
        return running
    }

    fun stop() {
        if (!running) return
        sm.unregisterListener(this)
        running = false
        flush()
        last = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        // event time is "elapsed realtime": convert to the wall clock
        val wallMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
        val m = wallMs / 60_000
        if (m != minute) {
            flush()
            minute = m
        }
        val p = last
        last = e.values.copyOf()
        if (p == null) return
        val dx = (e.values[0] - p[0]).toDouble()
        val dy = (e.values[1] - p[1]).toDouble()
        val dz = (e.values[2] - p[2]).toDouble()
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        sum += d
        peak = max(peak, d)
        if (d > MOVE_THRESHOLD) moved++
        n++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun flush() {
        if (minute >= 0 && n > 0) store.putPhoneMotion(minute * 60, sum, peak, moved, n)
        sum = 0.0
        peak = 0.0
        moved = 0
        n = 0
    }

    companion object {
        private const val SAMPLE_US = 200_000 // 5 readings per second
        private const val BATCH_US = 30_000_000 // delivered in batches of about 30 s
        private const val MOVE_THRESHOLD = 0.15 // m/s2 change between two readings that counts as "moved"
    }
}
