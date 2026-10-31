package dev.juliansaratsola.micoachfitness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Adds a screenshot to the pending Coach captures without opening the app. */
class AddCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "content") return
        val prefs = MonitorPreferences(context)
        prefs.addPendingCapture(uri.toString())
        val count = prefs.pendingCaptures().size
        Toast.makeText(context, context.getString(R.string.capture_added, count), Toast.LENGTH_SHORT).show()
    }
}
