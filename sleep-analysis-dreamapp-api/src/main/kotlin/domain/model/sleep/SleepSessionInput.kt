package team.dreamapp.com.domain.model.sleep

data class SleepSessionInput(
    val deviceId: String = "",
    val date: String = "",
    val startTime: String? = null,
    val endTime: String? = null,
    val timezone: String = "UTC",
    val totalDuration: Int = 0,
    val sleepDuration: Int = 0,
    val lightSleepMinutes: Int = 0,
    val deepSleepMinutes: Int = 0,
    val remSleepMinutes: Int = 0,
    val awakeDuration: Int = 0,
    val sleepEfficiency: Double = 0.0,
    val awakeningsCount: Int = 0,
    val quality: String = "POOR",
    val avgHeartRate: Int = 0,
    val minHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val avgRmssd: Double = 0.0
)
