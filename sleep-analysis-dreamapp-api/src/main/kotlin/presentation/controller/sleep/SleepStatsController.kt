package team.dreamapp.com.presentation.controller.sleep

import io.javalin.http.Context
import org.slf4j.LoggerFactory
import team.dreamapp.com.domain.usecase.sleep.GetSleepStatsUseCase
import team.dreamapp.com.infrastructure.di.RepositoryProvider
import team.dreamapp.com.presentation.auth.AccessManager.userInfo
import team.dreamapp.com.domain.model.sleep.SleepSessionInput
import java.time.LocalDate
import java.time.OffsetDateTime

object SleepStatsController {

    private val logger = LoggerFactory.getLogger("SleepStatsController")
    private val getSleepStatsUseCase = GetSleepStatsUseCase(RepositoryProvider.sleepRepository)

    fun getSleepStats(ctx: Context) {
        val uidUser = ctx.userInfo!!.id
        val response = getSleepStatsUseCase.execute(uidUser)
        ctx.json(mapOf("success" to true, "data" to response))
    }

    fun upsertSleepSession(ctx: Context) {
        val input = runCatching { ctx.bodyAsClass(SleepSessionInput::class.java) }.getOrNull()
        if (input == null || !valid(input)) {
            ctx.status(400).json(mapOf("success" to false, "error" to "Datos de sueño inválidos."))
            return
        }
        try {
            val id = RepositoryProvider.sleepRepository.upsertSleepSession(ctx.userInfo!!.id, input)
            ctx.json(mapOf(
                "success" to true,
                "message" to "Sesión de sueño sincronizada.",
                "data" to mapOf(
                    "documentId" to id, "date" to input.date, "startTime" to input.startTime,
                    "userId" to ctx.userInfo!!.id, "totalMeasurements" to 0,
                    "sleepDuration" to input.sleepDuration, "sleepEfficiency" to input.sleepEfficiency,
                    "quality" to input.quality.uppercase()
                )
            ))
        } catch (ex: Exception) {
            logger.error("Could not store sleep session", ex)
            ctx.status(503).json(mapOf("success" to false, "error" to "No se pudo guardar la sesión de sueño."))
        }
    }

    private fun valid(input: SleepSessionInput): Boolean = runCatching {
        LocalDate.parse(input.date)
        input.startTime?.takeIf(String::isNotBlank)?.let(OffsetDateTime::parse)
        input.endTime?.takeIf(String::isNotBlank)?.let(OffsetDateTime::parse)
        require(input.totalDuration in 0..1_440 && input.sleepDuration in 0..1_440)
        require(input.lightSleepMinutes in 0..1_440 && input.deepSleepMinutes in 0..1_440)
        require(input.remSleepMinutes in 0..1_440 && input.awakeDuration in 0..1_440)
        require(input.sleepEfficiency in 0.0..100.0 && input.awakeningsCount in 0..100)
        require(input.avgHeartRate in 0..300 && input.minHeartRate in 0..300 && input.maxHeartRate in 0..300)
        require(input.avgRmssd in 0.0..1_000.0)
        require(input.quality.uppercase() in setOf("POOR", "FAIR", "GOOD", "EXCELLENT"))
        true
    }.getOrDefault(false)
}
