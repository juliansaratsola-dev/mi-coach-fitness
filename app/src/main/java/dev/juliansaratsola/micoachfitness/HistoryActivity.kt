package dev.juliansaratsola.micoachfitness

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONException
import java.io.IOException
import java.text.NumberFormat

/** Browsable local history of analyzed workouts. */
class HistoryActivity : Activity() {
    private lateinit var store: WorkoutStore
    private lateinit var workoutList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WorkoutStore(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        content.addView(text(getString(R.string.history_title), 26f, bold = true, color = getColor(R.color.seed)))
        content.addView(text(getString(R.string.history_intro), 14f, color = getColor(R.color.ink_muted)).apply {
            setPadding(0, dp(6), 0, dp(16))
        })
        workoutList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(workoutList)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        BottomNav.attach(this, scroll, BottomNav.Tab.HISTORY)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::workoutList.isInitialized) refresh()
    }

    private fun refresh() {
        workoutList.removeAllViews()
        try {
            val history = store.all()
            if (history.isEmpty()) {
                workoutList.addView(text(getString(R.string.history_empty), 15f, color = getColor(R.color.ink_muted)).apply {
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    background = getDrawable(R.drawable.bg_card)
                })
                return
            }
            history.indices.sortedWith(
                compareByDescending<Int> { history[it].localDate() }.thenByDescending { it }
            ).forEach { index ->
                val record = history[index]
                val points = NumberFormat.getIntegerInstance().format(record.puntosEsfuerzo)
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = getDrawable(R.drawable.bg_card)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showDetails(index, record) }
                }
                item.addView(text(record.headerText(), 17f, bold = true, color = getColor(R.color.seed)))
                item.addView(text(
                    listOf(record.detailParts().joinToString(" · "), getString(R.string.history_points, points))
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    14f,
                    color = getColor(R.color.ink_muted)
                ).apply { setPadding(0, dp(5), 0, 0) })
                workoutList.addView(item, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) })
            }
        } catch (error: IOException) {
            showHistoryUnavailable(error)
        } catch (error: JSONException) {
            showHistoryUnavailable(error)
        }
    }

    private fun showDetails(index: Int, record: WorkoutRecord) {
        val details = buildString {
            append(record.detailParts().joinToString(" · "))
            append("\n").append(getString(R.string.effort_points_label)).append(": ").append(record.puntosEsfuerzo)
            if (record.esDiaTrinchera) append(" · ").append(getString(R.string.legend_trench))
            record.metricGroups().forEach { (title, items) ->
                append("\n\n").append(title).append('\n')
                items.forEach { append(it.first).append(": ").append(it.second).append('\n') }
            }
            if (record.hasLegacyReview()) {
                if (record.headline.isNotBlank()) append("\n\n").append(record.headline)
                record.sections.forEach {
                    append("\n\n").append(it.title).append(" ").append(it.score).append("/10\n").append(it.review)
                }
                listOf(
                    "Técnica" to record.tecnica,
                    "Cardio" to record.cardio,
                    "Potencia y rendimiento" to record.potenciaRendimiento,
                    "Comparativa" to record.comparativaPoblacional,
                    "Comparativa personal" to record.comparison
                ).filter { it.second.isNotBlank() }.forEach { (title, body) ->
                    append("\n\n").append(title).append('\n').append(body)
                }
                record.suggestions.forEach { append("\n\nSugerencia\n").append(it) }
                record.followUpQuestions.forEach { append("\n\nSeguimiento\n").append(it) }
            } else {
                val review = record.verdadPura
                listOf(
                    getString(R.string.review_verdict) to review.veredictoTrinchera,
                    getString(R.string.review_cardio) to review.cardioYEsfuerzo,
                    getString(R.string.review_comparison) to review.comparativaMeritocratica,
                    getString(R.string.review_consistency) to review.potenciaYConsistencia,
                    getString(R.string.review_technique) to (if (record.technicalMetricsWarning) {
                        getString(R.string.coach_technical_read_warning)
                    } else {
                        review.tecnica
                    })
                ).filter { it.second.isNotBlank() }.forEach { (title, body) ->
                    append("\n\n").append(title).append('\n').append(body)
                }
            }
            record.feelingsForPrompt()?.let {
                append("\n\n").append(getString(R.string.coach_notes_hint)).append(": ").append(it)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(record.headerText())
            .setMessage(details.trim())
            .setPositiveButton(R.string.coach_close, null)
            .setNegativeButton(R.string.coach_history_delete) { _, _ -> confirmDelete(index) }
            .show()
    }

    private fun confirmDelete(index: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.coach_history_delete_confirm_title)
            .setMessage(R.string.coach_history_delete_confirm)
            .setNegativeButton(R.string.coach_close, null)
            .setPositiveButton(R.string.coach_history_delete) { _, _ ->
                try {
                    store.deleteAt(index)
                    refresh()
                } catch (error: IOException) {
                    showHistoryUnavailable(error)
                } catch (error: JSONException) {
                    showHistoryUnavailable(error)
                }
            }
            .show()
    }

    private fun showHistoryUnavailable(error: Exception) {
        android.util.Log.e(TAG, "Unable to load or update workout history", error)
        Toast.makeText(this, R.string.coach_history_error, Toast.LENGTH_LONG).show()
    }

    private fun text(value: String, size: Float, bold: Boolean = false, color: Int = getColor(R.color.ink)) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "HistoryActivity"
    }
}
