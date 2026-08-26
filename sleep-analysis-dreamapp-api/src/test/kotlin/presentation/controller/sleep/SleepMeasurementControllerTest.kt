package presentation.controller.sleep

import team.dreamapp.com.domain.model.sleep.SleepMeasurementBatchInput
import team.dreamapp.com.domain.model.sleep.SleepMeasurementInput
import team.dreamapp.com.presentation.controller.sleep.SleepMeasurementController.parseBody
import team.dreamapp.com.presentation.controller.sleep.SleepMeasurementController.valid
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepMeasurementControllerTest {

    private val batchId = "b405fa7d-ea18-4a70-83d8-109d6cbd1e8f"

    private fun measurement(
        clientMeasurementId: String = "watch-001-1",
        measuredAt: String = "2026-08-25T23:45:10.123Z",
        heartRateBpm: Int = 62,
        sleepPhase: String = "DEEP",
        hrvRmssd: Double? = 45.2,
        hrvSdnn: Double? = 52.1,
        movement: Double? = 0.18
    ) = SleepMeasurementInput(clientMeasurementId, measuredAt, heartRateBpm, sleepPhase, hrvRmssd, hrvSdnn, movement)

    private fun batch(measurements: List<SleepMeasurementInput> = listOf(measurement())) =
        SleepMeasurementBatchInput(batchId, "vitalwatch-001", measurements)

    @Test
    fun `a valid batch is accepted`() {
        assertTrue(valid(batch()))
    }

    @Test
    fun `an empty batch is rejected`() {
        assertFalse(valid(batch(emptyList())))
    }

    @Test
    fun `more than five hundred measurements are rejected`() {
        val oversized = (1..501).map { measurement(clientMeasurementId = "m-$it") }
        assertFalse(valid(batch(oversized)))
        val exactLimit = (1..500).map { measurement(clientMeasurementId = "m-$it") }
        assertTrue(valid(batch(exactLimit)))
    }

    @Test
    fun `heart rate outside the allowed range is rejected`() {
        assertFalse(valid(batch(listOf(measurement(heartRateBpm = 19)))))
        assertFalse(valid(batch(listOf(measurement(heartRateBpm = 251)))))
        assertFalse(valid(batch(listOf(measurement(heartRateBpm = -5)))))
    }

    @Test
    fun `unknown sleep phases are rejected and known ones are accepted in any case`() {
        PHASES_INVALID.forEach { phase ->
            assertFalse(valid(batch(listOf(measurement(sleepPhase = phase)))), "phase=$phase")
        }
        listOf("deep", "Deep", "rem").forEach { phase ->
            assertTrue(valid(batch(listOf(measurement(sleepPhase = phase)))), "phase=$phase")
        }
    }

    @Test
    fun `invalid timestamps are rejected`() {
        INVALID_TIMESTAMPS.forEach { timestamp ->
            assertFalse(valid(batch(listOf(measurement(measuredAt = timestamp)))), "timestamp=$timestamp")
        }
    }

    @Test
    fun `timestamps without timezone are rejected because iso-8601 with zone is required`() {
        assertFalse(valid(batch(listOf(measurement(measuredAt = "2026-08-25T23:45:10")))))
        assertFalse(valid(batch(listOf(measurement(measuredAt = "2026-08-25")))))
    }

    @Test
    fun `nan and infinity are rejected in double fields`() {
        assertFalse(valid(batch(listOf(measurement(hrvRmssd = Double.NaN)))))
        assertFalse(valid(batch(listOf(measurement(hrvRmssd = Double.POSITIVE_INFINITY)))))
        assertFalse(valid(batch(listOf(measurement(hrvSdnn = Double.NEGATIVE_INFINITY)))))
        assertFalse(valid(batch(listOf(measurement(movement = Double.NaN)))))
        // Out-of-range finite values are also rejected.
        assertFalse(valid(batch(listOf(measurement(hrvRmssd = 1000.01)))))
        assertFalse(valid(batch(listOf(measurement(movement = -0.01)))))
    }

    @Test
    fun `an invalid batch id is rejected`() {
        assertFalse(valid(SleepMeasurementBatchInput("not-a-uuid", "vitalwatch-001", listOf(measurement()))))
        assertFalse(valid(SleepMeasurementBatchInput("", "vitalwatch-001", listOf(measurement()))))
    }

    @Test
    fun `device id length boundaries are enforced`() {
        assertTrue(valid(SleepMeasurementBatchInput(batchId, "x".repeat(160), listOf(measurement()))))
        assertFalse(valid(SleepMeasurementBatchInput(batchId, "   ", listOf(measurement()))))
        assertFalse(valid(SleepMeasurementBatchInput(batchId, "x".repeat(161), listOf(measurement()))))
    }

    @Test
    fun `client measurement id length boundaries are enforced`() {
        assertTrue(valid(batch(listOf(measurement(clientMeasurementId = "y".repeat(100))))))
        assertFalse(valid(batch(listOf(measurement(clientMeasurementId = "")))))
        assertFalse(valid(batch(listOf(measurement(clientMeasurementId = "y".repeat(101))))))
    }

    @Test
    fun `body maps containing user identifiers are refused`() {
        mapOf<String, Any>(
            "batchId" to batchId, "deviceId" to "vitalwatch-001",
            "userId" to "00000000-0000-0000-0000-000000000001",
            "measurements" to listOf(mapOf("clientMeasurementId" to "m-1"))
        ).let { assertNull(parseBody(it)) }
        assertNull(parseBody(mapOf("uidUser" to "attacker")))
        assertNull(parseBody(mapOf("idUser" to "attacker")))
    }

    @Test
    fun `identity keys are refused case-insensitively and in any nested object`() {
        listOf("username", "UserName", "EMAIL", "correo", "USER_ID", "UidUser").forEach { key ->
            assertNull(
                parseBody(mapOf("batchId" to batchId, key to "attacker", "measurements" to emptyList<Any>())),
                "top-level key=$key"
            )
            assertNull(
                parseBody(
                    mapOf(
                        "batchId" to batchId, "deviceId" to "vitalwatch-001",
                        "measurements" to listOf(mapOf("clientMeasurementId" to "m-1", key to "attacker"))
                    )
                ),
                "nested key=$key"
            )
        }
    }

    @Test
    fun `a valid body map converts into the input contract`() {
        val parsed = parseBody(
            mapOf(
                "batchId" to batchId,
                "deviceId" to "vitalwatch-001",
                "measurements" to listOf(
                    mapOf(
                        "clientMeasurementId" to "watch-001-182",
                        "measuredAt" to "2026-08-25T23:45:10.123Z",
                        "heartRateBpm" to 62,
                        "sleepPhase" to "DEEP",
                        "hrvRmssd" to 45.2,
                        "hrvSdnn" to 52.1,
                        "movement" to 0.18
                    )
                )
            )
        )
        assertNotNull(parsed)
        assertEquals(UUID.fromString(batchId), UUID.fromString(parsed.batchId.trim()))
        assertEquals(1, parsed.measurements.size)
        assertEquals(62, parsed.measurements.first().heartRateBpm)
        assertEquals(null, SleepMeasurementInput().hrvRmssd)
    }

    companion object {
        private val PHASES_INVALID = listOf("", "UNKNOWN", "DEEP_SLEEP", "sleeping")
        private val INVALID_TIMESTAMPS = listOf(
            "", "not-a-date", "25/08/2026", "2026-13-45T99:00:00Z", "2026-08-25T23:45:10+99:00"
        )
    }
}
