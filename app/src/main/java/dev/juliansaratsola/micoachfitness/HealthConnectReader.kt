package dev.juliansaratsola.micoachfitness

import android.content.Context
import android.health.connect.AggregateRecordsRequest
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.TimeRangeFilter
import android.health.connect.datatypes.DistanceRecord
import android.health.connect.datatypes.ElevationGainedRecord
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.PowerRecord
import android.health.connect.datatypes.StepsCadenceRecord
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

    /** Most recent exercise session that ended after [afterMillis], or null. */
    fun latestSession(afterMillis: Long = 0L): HealthSession? {
        val hc = manager ?: return null
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
        } ?: return null
        val session = response.records.maxByOrNull { it.endTime } ?: return null
        if (session.endTime.toEpochMilli() <= afterMillis) return null
        return details(session)
    }

    private fun details(session: ExerciseSessionRecord): HealthSession {
        val range = TimeInstantRangeFilter.Builder()
            .setStartTime(session.startTime)
            .setEndTime(session.endTime)
            .build()
        return HealthSession(
            startMillis = session.startTime.toEpochMilli(),
            endMillis = session.endTime.toEpochMilli(),
            exerciseType = session.exerciseType,
            title = session.title?.toString(),
            distanceM = aggregate(range, DistanceRecord.DISTANCE_TOTAL) { it.inMeters },
            avgHr = aggregate(range, HeartRateRecord.BPM_AVG) { it },
            maxHr = aggregate(range, HeartRateRecord.BPM_MAX) { it },
            avgPowerW = aggregate(range, PowerRecord.POWER_AVG) { it.inWatts },
            elevationGainM = aggregate(range, ElevationGainedRecord.ELEVATION_GAINED_TOTAL) { it.inMeters },
            cadenceSpm = aggregate(range, StepsCadenceRecord.STEPS_CADENCE_RATE_AVG) { it }
        )
    }

    // Each metric is aggregated alone so one missing permission or record type does not hide the rest.
    private fun <T, R> aggregate(
        range: TimeRangeFilter,
        type: android.health.connect.datatypes.AggregationType<T>,
        convert: (T) -> R
    ): R? {
        val hc = manager ?: return null
        val request = AggregateRecordsRequest.Builder<T>(range).addAggregationType(type).build()
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
        private const val LOOKBACK_MS = 24L * 60 * 60 * 1000
        private const val TIMEOUT_S = 20L

        val REQUIRED_PERMISSIONS = arrayOf(
            "android.permission.health.READ_EXERCISE",
            "android.permission.health.READ_DISTANCE",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_POWER",
            "android.permission.health.READ_ELEVATION_GAINED",
            "android.permission.health.READ_STEPS"
        )
        const val BACKGROUND_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

        fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}
