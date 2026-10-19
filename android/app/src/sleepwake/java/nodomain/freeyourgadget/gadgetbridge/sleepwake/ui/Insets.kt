package nodomain.freeyourgadget.gadgetbridge.sleepwake.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Since Android 15 a list placed straight into the activity content (a preference screen) is drawn behind the
 * action bar and the navigation bar. Pads it by the real size of the action bar and of the navigation bar,
 * so the first and the last item are not hidden.
 */
fun View.padBelowActionBar(activity: Activity) {
    val bar = activity.findViewById<View>(androidx.appcompat.R.id.action_bar_container)

    var scrolledToTop = false

    fun update() {
        val nav = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val top = bar?.bottom ?: 0
        updatePadding(top = top, bottom = nav)
        // the list keeps its scroll position when the padding grows: show the first item once
        if (!scrolledToTop && top > 0) {
            scrolledToTop = true
            (this as? androidx.recyclerview.widget.RecyclerView)?.let { rv -> rv.post { rv.scrollToPosition(0) } }
        }
    }
    bar?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        update()
        insets
    }
    post { update() }
    ViewCompat.requestApplyInsets(this)
}
