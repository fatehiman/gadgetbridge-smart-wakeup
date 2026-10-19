package nodomain.freeyourgadget.gadgetbridge.sleepwake.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.sleepwake.data.SleepWakeStore
import nodomain.freeyourgadget.gadgetbridge.sleepwake.sync.SyncScheduler

/** After the wake is acknowledged: "how do I feel" 1-5 plus a note. Saved as a `morning_rating` event (ground truth for later analysis). */
class MorningRatingActivity : AbstractGBActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "How do you feel?"
        val uuid = intent.getStringExtra(EXTRA_SESSION) ?: run { finish(); return }
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt()) }
        root.addView(TextView(this).apply { text = "Good morning. How do you feel? (1 = terrible, 5 = great)"; textSize = 18f })
        val note = EditText(this).apply { hint = "Optional note (dreams, woke at night, ...)"; minLines = 2 }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, (16 * d).toInt(), 0, (16 * d).toInt()) }
        (1..5).forEach { n ->
            row.addView(Button(this).apply {
                text = n.toString()
                setOnClickListener {
                    SleepWakeStore.get(this@MorningRatingActivity).addEvent(uuid, "morning_rating", mapOf("rating" to n, "note" to note.text.toString().ifBlank { null }))
                    SyncScheduler.enqueueNow(this@MorningRatingActivity, 5)
                    finish()
                }
            }, LinearLayout.LayoutParams(0, (64 * d).toInt(), 1f))
        }
        root.addView(row)
        root.addView(note)
        root.addView(Button(this).apply { text = "Skip"; setOnClickListener { finish() } })
        setContentView(root)
    }

    companion object {
        const val EXTRA_SESSION = "session_uuid"
    }
}
