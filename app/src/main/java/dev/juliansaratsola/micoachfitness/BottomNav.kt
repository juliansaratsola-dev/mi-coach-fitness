package dev.juliansaratsola.micoachfitness

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Bottom tab bar shared by every top-level screen: Inicio, Coach, [WIP] and Config. */
object BottomNav {
    enum class Tab(val iconRes: Int, val labelRes: Int, val target: Class<out Activity>) {
        HOME(R.drawable.ic_nav_home, R.string.tab_home, MainActivity::class.java),
        COACH(R.drawable.ic_nav_coach, R.string.tab_coach, CoachActivity::class.java),
        WIP(R.drawable.ic_nav_wip, R.string.tab_wip, WipActivity::class.java),
        SETTINGS(R.drawable.ic_nav_settings, R.string.tab_settings, SettingsActivity::class.java)
    }

    /** Wraps [content] with the tab bar and sets it as the activity content. */
    fun attach(activity: Activity, content: View, selected: Tab) {
        val density = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(View(activity).apply { setBackgroundColor(Color.parseColor("#1F000000")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }
        val active = activity.getColor(R.color.seed)
        val inactive = Color.parseColor("#757575")
        for (tab in Tab.values()) {
            val color = if (tab == selected) active else inactive
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                contentDescription = activity.getString(tab.labelRes)
                setOnClickListener { open(activity, tab, selected) }
            }
            item.addView(ImageView(activity).apply {
                setImageResource(tab.iconRes)
                setColorFilter(color)
            }, LinearLayout.LayoutParams((26 * density).toInt(), (26 * density).toInt()))
            item.addView(TextView(activity).apply {
                text = activity.getString(tab.labelRes)
                textSize = 12f
                setTextColor(color)
                gravity = Gravity.CENTER
            })
            bar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            content.setPadding(0, bars.top, 0, 0)
            bar.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        activity.setContentView(root)
    }

    private fun open(activity: Activity, tab: Tab, current: Tab) {
        if (tab == current) return
        activity.startActivity(
            Intent(activity, tab.target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
        if (current != Tab.HOME) activity.finish()
        activity.overridePendingTransition(0, 0)
    }
}
