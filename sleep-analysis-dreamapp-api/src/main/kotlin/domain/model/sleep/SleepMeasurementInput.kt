package team.dreamapp.com.domain.model.sleep

data class SleepMeasurementBatchInput(
    val batchId: String = "",
    val deviceId: String = "",
    val measurements: List<SleepMeasurementInput> = emptyList()
)

data class SleepMeasurementInput(
    val clientMeasurementId: String = "",
    val measuredAt: String = "",
    val heartRateBpm: Int = 0,
    val sleepPhase: String = "",
    val hrvRmssd: Double? = null,
    val hrvSdnn: Double? = null,
    val movement: Double? = null
)

/** Result of persisting a measurement batch, with idempotent duplicate counting. */
data class SleepMeasurementBatchResult(
    val batchId: String,
    val received: Int,
    val inserted: Int,
    val duplicates: Int
)
