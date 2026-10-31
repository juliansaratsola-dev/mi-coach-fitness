package dev.juliansaratsola.micoachfitness

import android.content.Context
import android.health.connect.AggregateRecordsRequest
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.TimeRangeFilter
import android.health.connect.datatypes.ActiveCaloriesBurnedRecord
import android.health.connect.datatypes.DataOrigin
import android.health.connect.datatypes.DistanceRecord
import android.health.connect.datatypes.ElevationGainedRecord
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.PowerRecord
import android.health.connect.datatypes.SpeedRecord
import android.health.connect.datatypes.StepsCadenceRecord
import android.health.connect.datatypes.TotalCaloriesBurnedRecord
import android.os.Build
import android.os.OutcomeReceiver
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reads finished workouts from the Android 14+ Health Connect framework API (no extra library needed). */
class HealthConnectReader(private val context: Context) {
    private val manager: HealthConnectManager? =
        if (supported()) context.getSystemService(HealthConnectManager::class.java) else null
    private val executor = Executors.newSingleThreadExecutor()

    fun hasPermissions(): Boolean = supported() && REQUIRED_PERMISSIONS.all {
        context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Best candidate among recent sessions: the latest real workout, not a tiny auto-detected one. */
    fun latestSession(afterMillis: Long = 0L): HealthSession? =
        HealthSession.pickBest(recentSessions(afterMillis, 6))

    /** Recent exercise sessions that ended after [afterMillis], newest first, with their metrics. */
    fun recentSessions(afterMillis: Long = 0L, limit: Int = 6): List<HealthSession> {
        val hc = manager ?: return emptyList()
        val filter = TimeInstantRangeFilter.Builder()
            .setStartTime(Instant.ofEpochMilli(maxOf(afterMillis, System.currentTimeMillis() - LOOKBACK_MS)))
            .setEndTime(Instant.now().plusSeconds(60))
            .build()
        val request = ReadRecordsRequestUsingFilters.Builder(ExerciseSessionRecord::class.java)
            .setTimeRangeFilter(filter)
            .setPageSize(50)
            .build()
        val response = await<ReadRecordsResponse<ExerciseSessionRecord>> { receiver ->
            hc.readRecords(request, executor, receiver)
        } ?: return emptyList()
        return response.records
            .filter { it.endTime.toEpochMilli() > afterMillis }
            .sortedByDescending { it.endTime }
            .take(limit)
            .map { details(it) }
    }

    private fun details(session: ExerciseSessionRecord): HealthSession {
        val range = TimeInstantRangeFilter.Builder()
            .setStartTime(session.startTime)
            .setEndTime(session.endTime)
            .build()
        // Only count data written by the same app as the session, so phone sensors or other apps do not mix in.
        val origin = session.metadata.dataOrigin
        return HealthSession(
            startMillis = session.startTime.toEpochMilli(),
            endMillis = session.endTime.toEpochMilli(),
            exerciseType = session.exerciseType,
            title = session.title?.toString(),
            distanceM = aggregate(range, origin, DistanceRecord.DISTANCE_TOTAL) { it.inMeters },
            avgHr = aggregate(range, origin, HeartRateRecord.BPM_AVG) { it },
            maxHr = aggregate(range, origin, HeartRateRecord.BPM_MAX) { it },
            avgPowerW = aggregate(range, origin, PowerRecord.POWER_AVG) { it.inWatts },
            elevationGainM = aggregate(range, origin, ElevationGainedRecord.ELEVATION_GAINED_TOTAL) { it.inMeters },
            cadenceSpm = aggregate(range, origin, StepsCadenceRecord.STEPS_CADENCE_RATE_AVG) { it },
            origin = origin.packageName,
            minHr = aggregate(range, origin, HeartRateRecord.BPM_MIN) { it },
            maxPowerW = aggregate(range, origin, PowerRecord.POWER_MAX) { it.inWatts },
            maxCadenceSpm = aggregate(range, origin, StepsCadenceRecord.STEPS_CADENCE_RATE_MAX) { it },
            maxSpeedKmh = aggregate(range, origin, SpeedRecord.SPEED_MAX) { it.inMetersPerSecond * 3.6 },
            activeKcal = aggregate(range, origin, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) { it.inCalories / 1000.0 },
            totalKcal = aggregate(range, origin, TotalCaloriesBurnedRecord.ENERGY_TOTAL) { it.inCalories / 1000.0 }
        )
    }

    // Each metric is aggregated alone so one missing permission or record type does not hide the rest.
    private fun <T, R> aggregate(
        range: TimeRangeFilter,
        origin: DataOrigin,
        type: android.health.connect.datatypes.AggregationType<T>,
        convert: (T) -> R
    ): R? {
        val hc = manager ?: return null
        val request = AggregateRecordsRequest.Builder<T>(range).addAggregationType(type).addDataOriginsFilter(origin).build()
        val response = await<android.health.connect.AggregateRecordsResponse<T>> { receiver ->
            hc.aggregate(request, executor, receiver)
        } ?: return null
        return response.get(type)?.let(convert)
    }

    private fun <R : Any> await(call: (OutcomeReceiver<R, HealthConnectException>) -> Unit): R? {
        val latch = CountDownLatch(1)
        var result: R? = null
        call(object : OutcomeReceiver<R, HealthConnectException> {
            override fun onResult(value: R) {
                result = value
                latch.countDown()
            }

            override fun onError(error: HealthConnectException) {
                latch.countDown()
            }
        })
        latch.await(TIMEOUT_S, TimeUnit.SECONDS)
        return result
    }

    companion object {
        private const val LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000
        private const val TIMEOUT_S = 20L

        val REQUIRED_PERMISSIONS = arrayOf(
            "android.permission.health.READ_EXERCISE",
            "android.permission.health.READ_DISTANCE",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_POWER",
            "android.permission.health.READ_ELEVATION_GAINED",
            "android.permission.health.READ_STEPS"
        )
        /** Extra metrics; the review still works if the user does not grant them. */
        val OPTIONAL_PERMISSIONS = arrayOf(
            "android.permission.health.READ_ACTIVE_CALORIES_BURNED",
            "android.permission.health.READ_TOTAL_CALORIES_BURNED",
            "android.permission.health.READ_SPEED"
        )
        const val BACKGROUND_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

        fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}
