package team.dreamapp.com.domain.repository.sleep

import team.dreamapp.com.domain.model.sleep.SleepSummary
import team.dreamapp.com.domain.model.sleep.SleepSessionInput

interface SleepRepository {
    fun getAllSleepSummaryByUser(uidUser: String): List<SleepSummary>
    fun upsertSleepSession(uidUser: String, input: SleepSessionInput): String
}
