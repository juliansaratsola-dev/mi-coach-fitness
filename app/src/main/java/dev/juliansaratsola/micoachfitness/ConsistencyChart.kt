package dev.juliansaratsola.micoachfitness

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bar chart of the last 30 days. A trained day is a bar whose height is proportional to its effort points (orange on
 * trench days); a rest day is just its empty track. Pace and distance play no part here.
 */
class ConsistencyChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val trackPaint = fillPaint(R.color.bar_rest)
    private val barPaint = fillPaint(R.color.seed)
    private val trenchPaint = fillPaint(R.color.trench)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_muted)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_SP, resources.displayMetrics)
    }
    private val rect = RectF()
    private val todayLabel = context.getString(R.string.chart_today)
    private var days: List<DayEffort> = emptyList()
    private var bars: List<ChartBar> = emptyList()
    private var scale = FormScore.MIN_CHART_SCALE
    private var startLabel = ""
    private var plotHeight = 0f

    /** [series] is oldest first and ends today, as returned by [FormScore.dailyEffort]. */
    fun setDays(series: List<DayEffort>) {
        days = series
        scale = FormScore.chartScale(series)
        startLabel = series.firstOrNull()
            ?.let { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(it.date) }
            .orEmpty()
        val summary = ConsistencyChartLayout.summarize(series)
        contentDescription = context.getString(
            R.string.chart_description, summary.trainedDays, summary.trenchDays, summary.points
        )
        rebuildBars()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (paddingTop + paddingBottom + PLOT_HEIGHT_DP * density + labelBand()).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBars()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        for (bar in bars) {
            val radius = (bar.right - bar.left) / 2f
            rect.set(left + bar.left, top + bar.trackTop, left + bar.right, top + bar.bottom)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            if (bar.trained) {
                rect.top = top + bar.fillTop
                val corner = minOf(radius, bar.fillHeight / 2f)
                canvas.drawRoundRect(rect, corner, corner, if (bar.isTrenchDay) trenchPaint else barPaint)
            }
        }
        val baseline = top + plotHeight + LABEL_GAP_DP * density - labelPaint.fontMetrics.ascent
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(startLabel, left, baseline, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(todayLabel, width - paddingRight.toFloat(), baseline, labelPaint)
    }

    private fun rebuildBars() {
        val plotWidth = (width - paddingLeft - paddingRight).toFloat()
        plotHeight = height - paddingTop - paddingBottom - labelBand()
        bars = ConsistencyChartLayout.bars(days, scale, plotWidth, plotHeight, MIN_FILL_DP * density)
    }

    private fun labelBand(): Float {
        val metrics = labelPaint.fontMetrics
        return metrics.descent - metrics.ascent + LABEL_GAP_DP * density
    }

    private fun fillPaint(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(colorRes) }

    private companion object {
        const val PLOT_HEIGHT_DP = 104f
        const val MIN_FILL_DP = 4f
        const val LABEL_GAP_DP = 6f
        const val LABEL_SP = 11f
    }
}
