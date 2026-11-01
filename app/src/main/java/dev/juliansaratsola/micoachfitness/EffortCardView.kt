package dev.juliansaratsola.micoachfitness

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.util.Locale

/**
 * Main card of a workout. The effort points are the giant number and pace is only a small detail line. A trench day
 * gets an orange background and border plus a flame badge, so resilience is visible at a glance.
 */
class EffortCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_effort_card, this, true)
        isScreenReaderFocusable = true
    }

    private val trenchBadge: View = findViewById(R.id.trenchBadge)
    private val trenchBadgeText: TextView = findViewById(R.id.trenchBadgeText)
    private val meta: TextView = findViewById(R.id.effortMeta)
    private val points: TextView = findViewById(R.id.effortPoints)
    private val detail: TextView = findViewById(R.id.effortDetail)
    private val note: TextView = findViewById(R.id.effortNote)
    private val feelings: TextView = findViewById(R.id.effortFeelings)
    private val inset = (CARD_PADDING_DP * resources.displayMetrics.density).toInt()

    fun bind(record: WorkoutRecord) {
        val trench = record.esDiaTrinchera
        setBackgroundResource(if (trench) R.drawable.bg_effort_card_trench else R.drawable.bg_effort_card)
        setPadding(inset, inset, inset, inset)

        show(meta, record.headerText())
        points.text = NumberFormat.getIntegerInstance().format(record.puntosEsfuerzo)
        points.setTextColor(context.getColor(if (trench) R.color.trench_dark else R.color.seed))
        trenchBadge.visibility = if (trench) VISIBLE else GONE
        trenchBadgeText.text = context.getString(
            R.string.effort_trench_badge,
            String.format(Locale.getDefault(), "%.1f", EffortCalculator.TRENCH_MULTIPLIER)
        )
        show(detail, record.detailParts().joinToString(" · "))
        show(note, basisNote(record))
        show(feelings, record.textoSensaciones.trim().takeIf { it.isNotEmpty() }
            ?.let { context.getString(R.string.effort_feelings_quote, it) })
    }

    /** Warns when a standard value stood in for missing data, so the points are not read as measured. */
    private fun basisNote(record: WorkoutRecord): String? = when (record.effortBasis()) {
        EffortBasis.MEASURED -> null
        EffortBasis.NO_HEART_RATE -> context.getString(R.string.effort_note_no_hr)
        EffortBasis.NO_DURATION ->
            context.getString(R.string.effort_note_no_duration, EffortCalculator.STANDARD_DURATION_MIN.toInt())
        EffortBasis.NO_DATA -> context.getString(R.string.effort_note_no_data)
    }

    private fun show(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    private companion object {
        const val CARD_PADDING_DP = 20
    }
}
