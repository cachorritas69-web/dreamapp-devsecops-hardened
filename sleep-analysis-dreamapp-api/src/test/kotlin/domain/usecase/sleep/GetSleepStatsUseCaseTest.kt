package domain.usecase.sleep

import kotlin.test.Test
import kotlin.test.assertEquals
import team.dreamapp.com.domain.model.sleep.Quality
import team.dreamapp.com.domain.model.sleep.SleepSessionInput
import team.dreamapp.com.domain.model.sleep.SleepSummary
import team.dreamapp.com.domain.repository.sleep.SleepRepository
import team.dreamapp.com.domain.usecase.sleep.GetSleepStatsUseCase
import java.time.LocalDate

class GetSleepStatsUseCaseTest {
    @Test
    fun `statistics are calculated only from the authenticated user repository result`() {
        val today = LocalDate.now().toString()
        val repository = object : SleepRepository {
            override fun getAllSleepSummaryByUser(uidUser: String): List<SleepSummary> {
                assertEquals("account-123", uidUser)
                return listOf(SleepSummary(today, Quality.GOOD, 90.0, 420, 210, 90, 90, 30, 60, 40, 2))
            }

            override fun upsertSleepSession(uidUser: String, input: SleepSessionInput) = "unused"
        }

        val stats = GetSleepStatsUseCase(repository).execute("account-123")

        assertEquals(90, stats.lastDayStats.sleepEfficiency)
        assertEquals(420, stats.lastDayStats.sleepDuration)
        assertEquals(1, stats.efficiencyChart.last7Days.size)
    }
}
