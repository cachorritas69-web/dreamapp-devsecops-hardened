package team.dreamapp.com.infrastructure.repository.sleep

import team.dreamapp.com.domain.model.sleep.SleepMeasurement
import team.dreamapp.com.domain.model.sleep.SleepMeasurementBatchInput
import team.dreamapp.com.domain.model.sleep.SleepMeasurementBatchResult
import team.dreamapp.com.domain.repository.sleep.SleepMeasurementRepository
import team.dreamapp.com.infrastructure.datasouce.authdatabase.AuthDataSource
import java.sql.Types
import java.util.Locale

class SleepMeasurementRepositoryImpl : SleepMeasurementRepository {

    override fun insertBatch(uidUser: String, input: SleepMeasurementBatchInput): SleepMeasurementBatchResult {
        val received = input.measurements.size
        AuthDataSource.get().connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            try {
                connection.autoCommit = false
                var inserted = 0
                connection.prepareStatement("""
                    INSERT INTO sleep_measurement(user_id, batch_id, client_measurement_id, device_id,
                      measured_at, heart_rate_bpm, sleep_phase, hrv_rmssd, hrv_sdnn, movement)
                    VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, device_id, client_measurement_id) DO NOTHING
                """.trimIndent()).use { statement ->
                    for (measurement in input.measurements) {
                        statement.setString(1, uidUser)
                        statement.setString(2, input.batchId)
                        statement.setString(3, measurement.clientMeasurementId.take(100))
                        statement.setString(4, input.deviceId.take(160))
                        statement.setString(5, measurement.measuredAt)
                        statement.setInt(6, measurement.heartRateBpm)
                        statement.setString(7, measurement.sleepPhase.uppercase(Locale.ROOT).take(10))
                        setNullableDouble(statement, 8, measurement.hrvRmssd)
                        setNullableDouble(statement, 9, measurement.hrvSdnn)
                        setNullableDouble(statement, 10, measurement.movement)
                        inserted += statement.executeUpdate()
                    }
                }
                connection.commit()
                return SleepMeasurementBatchResult(
                    batchId = input.batchId,
                    received = received,
                    inserted = inserted,
                    duplicates = received - inserted
                )
            } catch (ex: Exception) {
                runCatching { connection.rollback() }
                throw ex
            } finally {
                runCatching { connection.autoCommit = previousAutoCommit }
            }
        }
    }

    override fun findRecentByUser(uidUser: String, limit: Int): List<SleepMeasurement> =
        AuthDataSource.get().connection.use { connection ->
            connection.prepareStatement("""
                SELECT CAST(id AS VARCHAR), device_id, client_measurement_id, measured_at, heart_rate_bpm,
                       sleep_phase, hrv_rmssd, hrv_sdnn, movement, received_at
                FROM sleep_measurement WHERE user_id = CAST(? AS UUID)
                ORDER BY measured_at DESC LIMIT ?
            """.trimIndent()).use { statement ->
                statement.setString(1, uidUser)
                statement.setInt(2, limit.coerceIn(1, MAX_RECENT_LIMIT))
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(SleepMeasurement(
                            id = result.getString("id"),
                            deviceId = result.getString("device_id"),
                            clientMeasurementId = result.getString("client_measurement_id"),
                            measuredAt = result.getObject("measured_at", java.time.OffsetDateTime::class.java).toString(),
                            heartRateBpm = result.getInt("heart_rate_bpm"),
                            sleepPhase = result.getString("sleep_phase"),
                            hrvRmssd = result.getDouble("hrv_rmssd").let { if (result.wasNull()) null else it },
                            hrvSdnn = result.getDouble("hrv_sdnn").let { if (result.wasNull()) null else it },
                            movement = result.getDouble("movement").let { if (result.wasNull()) null else it },
                            receivedAt = result.getObject("received_at", java.time.OffsetDateTime::class.java).toString()
                        ))
                    }
                }
            }
        }

    private fun setNullableDouble(statement: java.sql.PreparedStatement, index: Int, value: Double?) {
        if (value == null || !value.isFinite()) statement.setNull(index, Types.DOUBLE)
        else statement.setDouble(index, value)
    }

    companion object {
        const val MAX_RECENT_LIMIT = 500
    }
}
