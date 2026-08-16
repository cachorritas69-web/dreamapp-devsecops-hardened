package team.dreamapp.com.presentation.controller.sleep

import io.javalin.http.Context
import org.slf4j.LoggerFactory
import team.dreamapp.com.domain.usecase.sleep.GetSleepStatsUseCase
import team.dreamapp.com.infrastructure.di.RepositoryProvider

object SleepStatsController {

    private val logger = LoggerFactory.getLogger("SleepStatsController")
    private val getSleepStatsUseCase = GetSleepStatsUseCase(RepositoryProvider.sleepRepository)

    fun getSleepStats(ctx: Context) {
        val uidUser = ctx.queryParam("uid")
        if (uidUser.isNullOrBlank()) {
            ctx.status(400).json(mapOf("success" to false, "error" to "Missing uid parameter"))
            return
        }
        val response = getSleepStatsUseCase.execute(uidUser)
        ctx.json(mapOf("success" to true, "data" to response))
    }
}
