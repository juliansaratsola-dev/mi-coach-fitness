package dev.juliansaratsola.micoachfitness

/** One day slot of the consistency chart: an empty track and, when the user trained, a filled bar on top of it. */
data class ChartBar(
    val left: Float,
    val right: Float,
    /** Top of the whole slot, i.e. of the empty track. */
    val trackTop: Float,
    /** Top of the filled part; equal to [bottom] on rest days, which stay empty. */
    val fillTop: Float,
    val bottom: Float,
    val trained: Boolean,
    val isTrenchDay: Boolean,
    val isToday: Boolean
) {
    val fillHeight: Float get() = bottom - fillTop
}

/** Totals of the series drawn by the chart. */
data class ChartSummary(val trainedDays: Int, val trenchDays: Int, val points: Int)

/** Geometry of the 30-day chart, free of Android classes so its proportions can be unit-tested. */
object ConsistencyChartLayout {
    /** Share of each day slot left empty around the bar. */
    const val GAP_FRACTION = 0.34f

    fun summarize(days: List<DayEffort>): ChartSummary = ChartSummary(
        trainedDays = days.count { it.trained },
        trenchDays = days.count { it.trained && it.isTrenchDay },
        points = days.sumOf { it.points }
    )

    /**
     * Lays out [days] (oldest first, the last one is today) in a [width] x [height] area with y growing downwards.
     * A bar's height is its effort over [scale] (the points of a full-height bar); a trained day never gets less
     * than [minFill], so a short session stays visible, and rest days get no fill at all.
     */
    fun bars(days: List<DayEffort>, scale: Int, width: Float, height: Float, minFill: Float = 0f): List<ChartBar> {
        if (days.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        val slot = width / days.size
        val barWidth = slot * (1f - GAP_FRACTION)
        val inset = (slot - barWidth) / 2f
        val perPoint = height / scale.coerceAtLeast(1)
        val floor = minFill.coerceIn(0f, height)
        return days.mapIndexed { index, day ->
            val left = index * slot + inset
            val fill = if (day.trained) (day.points.coerceAtLeast(0) * perPoint).coerceIn(floor, height) else 0f
            ChartBar(
                left = left,
                right = left + barWidth,
                trackTop = 0f,
                fillTop = height - fill,
                bottom = height,
                trained = day.trained,
                isTrenchDay = day.isTrenchDay,
                isToday = index == days.lastIndex
            )
        }
    }
}
