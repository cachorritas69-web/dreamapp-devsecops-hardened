package com.example.appmobile.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.appmobile.data.remote.SleepApiClient
import com.example.appmobile.domain.model.SleepDataUpload
import com.example.appmobile.domain.model.SleepPhaseData
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

class HealthConnectSleepRepository(context: Context) {
    val client: HealthConnectClient = HealthConnectClient.getOrCreate(context)

    val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )

    suspend fun hasPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun synchronizeLatest(userId: String): SyncSummary {
        check(hasPermissions()) { "Autoriza sueño y frecuencia cardiaca en Health Connect." }
        val now = Instant.now()
        val sleepRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now),
                ascendingOrder = false,
                pageSize = 20
            )
        ).records
        val sleep = sleepRecords.maxByOrNull { it.endTime }
            ?: error("No hay sesiones de sueño en Health Connect. Sincroniza primero Samsung Health.")
        val heartRates = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(sleep.startTime, sleep.endTime),
                ascendingOrder = true,
                pageSize = 5000
            )
        ).records.flatMap { it.samples }.map { it.beatsPerMinute.toInt() }

        val zone = ZoneId.systemDefault()
        val stages = sleep.stages.sortedBy { it.startTime }
        fun minutes(type: Int): Int = stages.filter { it.stage == type }
            .sumOf { Duration.between(it.startTime, it.endTime).toMinutes().toInt() }
        val light = minutes(SleepSessionRecord.STAGE_TYPE_LIGHT)
        val deep = minutes(SleepSessionRecord.STAGE_TYPE_DEEP)
        val rem = minutes(SleepSessionRecord.STAGE_TYPE_REM)
        val awake = minutes(SleepSessionRecord.STAGE_TYPE_AWAKE) +
            minutes(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED) +
            minutes(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
        val total = Duration.between(sleep.startTime, sleep.endTime).toMinutes().toInt().coerceIn(0, 1440)
        val categorizedSleep = light + deep + rem + minutes(SleepSessionRecord.STAGE_TYPE_SLEEPING)
        val sleepMinutes = (if (categorizedSleep > 0) categorizedSleep else total - awake).coerceIn(0, total)
        val efficiency = if (total == 0) 0.0 else sleepMinutes * 100.0 / total
        val quality = when {
            efficiency >= 90 && sleepMinutes >= 420 -> "EXCELLENT"
            efficiency >= 80 && sleepMinutes >= 360 -> "GOOD"
            efficiency >= 65 -> "FAIR"
            else -> "POOR"
        }
        val phases = stages.mapIndexed { index, stage ->
            SleepPhaseData(
                id = index + 1,
                phase = stageName(stage.stage),
                datetime = stage.startTime.atZone(zone).toOffsetDateTime().toString(),
                hrBpm = nearestHeartRate(stage.startTime, heartRates, sleep.startTime, sleep.endTime),
                hrvRmssd = 0.0,
                hrvSdnn = 0.0
            )
        }
        val sourcePackage = sleep.metadata.dataOrigin.packageName.ifBlank { "health-connect" }
        val payload = SleepDataUpload(
            uidUser = userId,
            deviceId = "health-connect:$sourcePackage",
            date = sleep.endTime.atZone(zone).toLocalDate().toString(),
            startTime = sleep.startTime.atZone(zone).toOffsetDateTime().toString(),
            endTime = sleep.endTime.atZone(zone).toOffsetDateTime().toString(),
            timezone = zone.id,
            totalDuration = total,
            sleepDuration = sleepMinutes,
            lightSleepMinutes = light,
            deepSleepMinutes = deep,
            remSleepMinutes = rem,
            awakeDuration = awake,
            sleepEfficiency = efficiency.coerceIn(0.0, 100.0),
            awakeningsCount = stages.count {
                it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE ||
                    it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED
            }.coerceAtMost(100),
            quality = quality,
            avgHeartRate = heartRates.averageOrNull()?.roundToInt() ?: 0,
            minHeartRate = heartRates.minOrNull() ?: 0,
            maxHeartRate = heartRates.maxOrNull() ?: 0,
            avgMovement = 0,
            avgRmssd = 0.0,
            avgSdnn = 0.0,
            sleepPhaseData = phases,
            createdAt = System.currentTimeMillis(),
            dataVersion = "health-connect-1.0"
        )
        val response = SleepApiClient.apiService.uploadSleepData(payload)
        if (!response.isSuccessful || response.body()?.success != true) {
            error(response.errorBody()?.string()?.take(300) ?: "La API rechazó la sesión (${response.code()}).")
        }
        return SyncSummary(
            date = payload.date,
            totalMinutes = total,
            sleepMinutes = sleepMinutes,
            averageHeartRate = payload.avgHeartRate,
            source = if (sourcePackage.contains("shealth", true)) "Samsung Health" else sourcePackage
        )
    }

    private fun stageName(type: Int): String = when (type) {
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "LIGHT"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "DEEP"
        SleepSessionRecord.STAGE_TYPE_REM -> "REM"
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "AWAKE"
        else -> "SLEEPING"
    }

    private fun nearestHeartRate(
        time: Instant,
        values: List<Int>,
        sessionStart: Instant,
        sessionEnd: Instant
    ): Int {
        if (values.isEmpty()) return 0
        val totalSeconds = Duration.between(sessionStart, sessionEnd).seconds.coerceAtLeast(1)
        val elapsed = Duration.between(sessionStart, time).seconds.coerceIn(0, totalSeconds)
        val index = ((elapsed.toDouble() / totalSeconds) * (values.size - 1)).roundToInt()
        return values[index]
    }

    private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()
}

data class SyncSummary(
    val date: String,
    val totalMinutes: Int,
    val sleepMinutes: Int,
    val averageHeartRate: Int,
    val source: String
)
