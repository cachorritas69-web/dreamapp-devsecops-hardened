package team.dreamapp.com.domain.repository.sleep

import team.dreamapp.com.domain.model.sleep.SleepMeasurement
import team.dreamapp.com.domain.model.sleep.SleepMeasurementBatchInput
import team.dreamapp.com.domain.model.sleep.SleepMeasurementBatchResult

interface SleepMeasurementRepository {
    /**
     * Persists a batch of wearable measurements atomically.
     * Duplicates (same user, device and client id) are skipped via ON CONFLICT DO NOTHING.
     * On any database error the whole batch is rolled back and an exception is thrown.
     */
    fun insertBatch(uidUser: String, input: SleepMeasurementBatchInput): SleepMeasurementBatchResult

    /** Returns the authenticated user's most recent measurements, ordered by measured_at DESC. */
    fun findRecentByUser(uidUser: String, limit: Int): List<SleepMeasurement>
}
