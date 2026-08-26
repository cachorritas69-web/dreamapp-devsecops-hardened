package team.dreamapp.com.domain.model.sleep

/** Recent wearable measurement returned to the client (no batch identifier). */
data class SleepMeasurement(
    val id: String,
    val deviceId: String,
    val clientMeasurementId: String,
    val measuredAt: String,
    val heartRateBpm: Int,
    val sleepPhase: String,
    val hrvRmssd: Double?,
    val hrvSdnn: Double?,
    val movement: Double?,
    val receivedAt: String
)
