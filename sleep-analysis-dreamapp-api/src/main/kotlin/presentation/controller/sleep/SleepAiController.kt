package team.dreamapp.com.presentation.controller.sleep

import io.javalin.http.Context
import org.slf4j.LoggerFactory
import team.dreamapp.com.domain.usecase.sleep.GetSleepRecommendationUseCase
import team.dreamapp.com.infrastructure.di.RepositoryProvider
import team.dreamapp.com.infrastructure.datasouce.ollama.AiDataSource

object SleepAiController {
    private val logger = LoggerFactory.getLogger("SleepAiController")

    private val aiDataSource: AiDataSource by lazy {
        val ds = AiDataSource()
        ds.init()
        ds
    }
    private val getSleepRecommendationUseCase by lazy {
        GetSleepRecommendationUseCase(RepositoryProvider.sleepRepository, aiDataSource)
    }
    private val predictionsUseCase by lazy {
        team.dreamapp.com.domain.usecase.sleep.GeneratePredictionsEfficiencyNextMonth(
            RepositoryProvider.sleepRepository,
            aiDataSource
        )
    }

    // Endpoint to generate a recommendation with history stats user
    fun getRecommendation(ctx: Context) {
        val uidUser = ctx.queryParam("uid")
        if (uidUser.isNullOrBlank()) {
            ctx.status(400).json(mapOf("success" to false, "error" to "Missing uid parameter"))
            return
        }
        val response = getSleepRecommendationUseCase.execute(uidUser)
        ctx.json(response)
    }
    
    // Endpoint to generate predictions next month efficiency
    fun predictEfficiencyNextMonth(ctx: Context) {
        val uidUser = ctx.queryParam("uid")
        if (uidUser == null) {
            ctx.status(400).json(mapOf("error" to "uid is required"))
            return
        }
        val predictions = predictionsUseCase.execute(uidUser)
        logger.info("[CONTROLLER RESPONSE] Generated predictions values: ${predictions.joinToString(", ") { "${it.date}: ${it.sleepEfficiency}" }}")
        ctx.json(mapOf("success" to true, "nextMonthPredictions" to predictions))
    }
}
