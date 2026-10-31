package dev.juliansaratsola.micoachfitness

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Placeholder for the fourth tab, reserved for a future feature. */
class WipActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setText(R.string.wip_text)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        BottomNav.attach(this, text, BottomNav.Tab.WIP)
    }
}
