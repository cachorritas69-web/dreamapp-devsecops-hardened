package team.dreamapp.com.infrastructure.repository.sleep

import team.dreamapp.com.domain.model.sleep.Quality
import team.dreamapp.com.domain.model.sleep.SleepSessionInput
import team.dreamapp.com.domain.model.sleep.SleepSummary
import team.dreamapp.com.domain.repository.sleep.SleepRepository
import team.dreamapp.com.infrastructure.datasouce.authdatabase.AuthDataSource
import java.sql.Types

class SleepRepositoryImpl : SleepRepository {
    override fun getAllSleepSummaryByUser(uidUser: String): List<SleepSummary> =
        AuthDataSource.get().connection.use { connection ->
            connection.prepareStatement("""
                SELECT sleep_date, quality, sleep_efficiency, sleep_duration, light_minutes, deep_minutes,
                       rem_minutes, awake_minutes, avg_heart_rate, avg_hrv, awakenings
                FROM sleep_session WHERE user_id=CAST(? AS UUID) ORDER BY sleep_date
            """.trimIndent()).use { statement ->
                statement.setString(1, uidUser)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(SleepSummary(
                            date = result.getDate("sleep_date").toLocalDate().toString(),
                            quality = Quality.fromString(result.getString("quality")),
                            sleepEfficiency = result.getDouble("sleep_efficiency"),
                            sleepDuration = result.getInt("sleep_duration"),
                            light = result.getInt("light_minutes"), deep = result.getInt("deep_minutes"),
                            rem = result.getInt("rem_minutes"), awake = result.getInt("awake_minutes"),
                            avgHR = result.getInt("avg_heart_rate"), avgHRV = result.getDouble("avg_hrv").toInt(),
                            awakenings = result.getInt("awakenings")
                        ))
                    }
                }
            }
        }

    override fun upsertSleepSession(uidUser: String, input: SleepSessionInput): String =
        AuthDataSource.get().connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO sleep_session(user_id, device_id, sleep_date, start_time, end_time, timezone,
                  total_duration, sleep_duration, light_minutes, deep_minutes, rem_minutes, awake_minutes,
                  sleep_efficiency, awakenings, quality, avg_heart_rate, min_heart_rate, max_heart_rate, avg_hrv)
                VALUES (CAST(? AS UUID), ?, CAST(? AS DATE), CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ), ?,
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, sleep_date) DO UPDATE SET
                  device_id=EXCLUDED.device_id, start_time=EXCLUDED.start_time, end_time=EXCLUDED.end_time,
                  timezone=EXCLUDED.timezone, total_duration=EXCLUDED.total_duration,
                  sleep_duration=EXCLUDED.sleep_duration, light_minutes=EXCLUDED.light_minutes,
                  deep_minutes=EXCLUDED.deep_minutes, rem_minutes=EXCLUDED.rem_minutes,
                  awake_minutes=EXCLUDED.awake_minutes, sleep_efficiency=EXCLUDED.sleep_efficiency,
                  awakenings=EXCLUDED.awakenings, quality=EXCLUDED.quality,
                  avg_heart_rate=EXCLUDED.avg_heart_rate, min_heart_rate=EXCLUDED.min_heart_rate,
                  max_heart_rate=EXCLUDED.max_heart_rate, avg_hrv=EXCLUDED.avg_hrv,
                  updated_at=CURRENT_TIMESTAMP
                RETURNING CAST(id AS VARCHAR)
            """.trimIndent()).use { statement ->
                statement.setString(1, uidUser); statement.setString(2, input.deviceId.take(160)); statement.setString(3, input.date)
                setNullable(statement, 4, input.startTime); setNullable(statement, 5, input.endTime)
                statement.setString(6, input.timezone.take(80)); statement.setInt(7, input.totalDuration)
                statement.setInt(8, input.sleepDuration); statement.setInt(9, input.lightSleepMinutes)
                statement.setInt(10, input.deepSleepMinutes); statement.setInt(11, input.remSleepMinutes)
                statement.setInt(12, input.awakeDuration); statement.setDouble(13, input.sleepEfficiency)
                statement.setInt(14, input.awakeningsCount); statement.setString(15, input.quality.uppercase().take(20))
                statement.setInt(16, input.avgHeartRate); statement.setInt(17, input.minHeartRate)
                statement.setInt(18, input.maxHeartRate); statement.setDouble(19, input.avgRmssd)
                statement.executeQuery().use { result -> result.next(); result.getString(1) }
            }
        }

    private fun setNullable(statement: java.sql.PreparedStatement, index: Int, value: String?) {
        if (value.isNullOrBlank()) statement.setNull(index, Types.VARCHAR) else statement.setString(index, value)
    }
}
