package dev.juliansaratsola.micoachfitness

import android.content.Context
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import java.text.NumberFormat
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * What the Inicio tab shows about the user's training: the Form Score as an RPG level, the 30-day consistency chart
 * and the effort card of the last workout. Pace and speed play no part in any of them.
 */
class HomeDashboard(private val context: Context, root: View) {
    private val formCard: View = root.findViewById(R.id.formCard)
    private val levelBadge: View = root.findViewById(R.id.levelBadge)
    private val levelNumber: TextView = root.findViewById(R.id.levelNumber)
    private val levelTitle: TextView = root.findViewById(R.id.levelTitle)
    private val formScoreValue: TextView = root.findViewById(R.id.formScoreValue)
    private val levelProgress: ProgressBar = root.findViewById(R.id.levelProgress)
    private val levelHint: TextView = root.findViewById(R.id.levelHint)
    private val gravityNote: TextView = root.findViewById(R.id.gravityNote)
    private val chartCard: View = root.findViewById(R.id.chartCard)
    private val chartSummary: TextView = root.findViewById(R.id.chartSummary)
    private val chart: ConsistencyChart = root.findViewById(R.id.consistencyChart)
    private val lastWorkoutCard: EffortCardView = root.findViewById(R.id.lastWorkoutCard)
    private val lastWorkoutEmpty: TextView = root.findViewById(R.id.lastWorkoutEmpty)

    fun bind(history: List<WorkoutRecord>, today: LocalDate = LocalDate.now()) {
        formCard.visibility = View.VISIBLE
        chartCard.visibility = View.VISIBLE
        bindLevel(FormScore.snapshot(history, today))
        bindChart(FormScore.dailyEffort(history, today))
        bindLastWorkout(history.lastOrNull())
    }

    /** The history could not be read: show that instead of an empty level that would pass as real data. */
    fun showUnavailable() {
        formCard.visibility = View.GONE
        chartCard.visibility = View.GONE
        lastWorkoutCard.visibility = View.GONE
        lastWorkoutEmpty.setText(R.string.coach_history_error)
        lastWorkoutEmpty.visibility = View.VISIBLE
    }

    private fun bindLevel(snapshot: FormSnapshot) {
        val level = snapshot.level
        val numbers = NumberFormat.getIntegerInstance()
        levelNumber.text = level.number.toString()
        levelBadge.contentDescription = context.getString(R.string.form_level_description, level.number)
        levelTitle.text = level.title
        formScoreValue.text = context.getString(R.string.form_score_value, numbers.format(snapshot.scoreWhole))
        levelProgress.progress = (level.progress * levelProgress.max).roundToInt()
        levelHint.text = context.getString(
            R.string.form_next_level, numbers.format(snapshot.pointsToNextLevel), level.number + 1
        )
        gravityNote.text = when (val days = snapshot.daysSinceLast) {
            null -> context.getString(R.string.form_gravity_none)
            0 -> context.getString(R.string.form_gravity_today)
            1 -> context.getString(R.string.form_gravity_yesterday)
            else -> context.getString(R.string.form_gravity_days, days, days + 1)
        }
    }

    private fun bindChart(days: List<DayEffort>) {
        val summary = ConsistencyChartLayout.summarize(days)
        chart.setDays(days)
        chartSummary.text = if (summary.trainedDays == 0) {
            context.getString(R.string.chart_empty)
        } else {
            listOfNotNull(
                context.resources.getQuantityString(
                    R.plurals.chart_trained_days, summary.trainedDays, summary.trainedDays
                ),
                context.getString(R.string.chart_summary_points, NumberFormat.getIntegerInstance().format(summary.points)),
                if (summary.trenchDays > 0) context.getString(R.string.chart_summary_trench, summary.trenchDays) else null
            ).joinToString(" · ")
        }
    }

    private fun bindLastWorkout(record: WorkoutRecord?) {
        if (record == null) {
            lastWorkoutCard.visibility = View.GONE
            lastWorkoutEmpty.setText(R.string.home_last_workout_empty)
            lastWorkoutEmpty.visibility = View.VISIBLE
            return
        }
        lastWorkoutCard.bind(record)
        lastWorkoutCard.visibility = View.VISIBLE
        lastWorkoutEmpty.visibility = View.GONE
    }
}
