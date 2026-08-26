package team.dreamapp.com.presentation.controller.sleep

import com.fasterxml.jackson.databind.ObjectMapper
import io.javalin.http.Context
import org.slf4j.LoggerFactory
import team.dreamapp.com.domain.model.sleep.SleepMeasurementBatchInput
import team.dreamapp.com.infrastructure.di.RepositoryProvider
import team.dreamapp.com.presentation.auth.AccessManager.userInfo
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

object SleepMeasurementController {

    private val logger = LoggerFactory.getLogger("SleepMeasurementController")
    private val mapper = ObjectMapper()

    private const val MIN_BPM = 20
    private const val MAX_BPM = 250
    private const val MAX_MEASUREMENTS = 500
    private const val DEFAULT_RECENT_LIMIT = 100
    private const val MAX_RECENT_LIMIT = 500

    fun uploadBatch(ctx: Context) {
        val uidUser = ctx.userInfo?.id
        if (uidUser == null) {
            ctx.status(401).json(mapOf("success" to false, "error" to "No autenticado."))
            return
        }
        val input = runCatching { ctx.bodyAsClass(Map::class.java) }
            .mapCatching { parseBody(it) }.getOrNull()
        if (input == null || !valid(input)) {
            ctx.status(400).json(mapOf("success" to false, "error" to "Lote de mediciones inválido."))
            return
        }
        try {
            val result = RepositoryProvider.sleepMeasurementRepository.insertBatch(uidUser, input)
            ctx.json(mapOf(
                "success" to true,
                "message" to "Mediciones sincronizadas.",
                "data" to mapOf(
                    "batchId" to result.batchId,
                    "received" to result.received,
                    "inserted" to result.inserted,
                    "duplicates" to result.duplicates
                )
            ))
        } catch (ex: Exception) {
            logger.error("Could not store measurement batch")
            ctx.status(503).json(mapOf("success" to false, "error" to "No se pudieron guardar las mediciones."))
        }
    }

    fun getRecent(ctx: Context) {
        val uidUser = ctx.userInfo?.id
        if (uidUser == null) {
            ctx.status(401).json(mapOf("success" to false, "error" to "No autenticado."))
            return
        }
        val rawLimit = ctx.queryParam("limit")?.trim()?.takeIf(String::isNotEmpty)
        if (rawLimit != null && rawLimit.toIntOrNull() == null) {
            ctx.status(400).json(mapOf("success" to false, "error" to "El límite debe estar entre 1 y 500."))
            return
        }
        val limit = rawLimit?.toIntOrNull() ?: DEFAULT_RECENT_LIMIT
        if (limit < 1 || limit > MAX_RECENT_LIMIT) {
            ctx.status(400).json(mapOf("success" to false, "error" to "El límite debe estar entre 1 y 500."))
            return
        }
        try {
            val measurements = RepositoryProvider.sleepMeasurementRepository.findRecentByUser(uidUser, limit)
            ctx.json(mapOf("success" to true, "data" to measurements))
        } catch (ex: Exception) {
            logger.error("Could not fetch recent measurements")
            ctx.status(503).json(mapOf("success" to false, "error" to "No se pudieron consultar las mediciones."))
        }
    }

    /** Parses the raw body map while refusing any client-supplied user identifier, at any nesting level. */
    internal fun parseBody(raw: Map<*, *>): SleepMeasurementBatchInput? {
        if (containsForbiddenKey(raw)) return null
        return runCatching { mapper.convertValue(raw, SleepMeasurementBatchInput::class.java) }.getOrNull()
    }

    private fun containsForbiddenKey(node: Any?): Boolean = when (node) {
        is Map<*, *> -> node.keys.any { it.toString().trim().lowercase(Locale.ROOT) in FORBIDDEN_KEYS } ||
            node.values.any { containsForbiddenKey(it) }
        is List<*> -> node.any { containsForbiddenKey(it) }
        else -> false
    }

    /** All-or-nothing validation: one bad measurement rejects the entire batch. */
    internal fun valid(input: SleepMeasurementBatchInput): Boolean = runCatching {
        UUID.fromString(input.batchId.trim())
        val deviceId = input.deviceId.trim()
        require(deviceId.isNotEmpty() && deviceId.length <= 160)
        require(input.measurements.isNotEmpty() && input.measurements.size <= MAX_MEASUREMENTS)
        input.measurements.forEach { measurement ->
            val clientMeasurementId = measurement.clientMeasurementId.trim()
            require(clientMeasurementId.isNotEmpty() && clientMeasurementId.length <= 100)
            OffsetDateTime.parse(measurement.measuredAt.trim())
            require(measurement.heartRateBpm in MIN_BPM..MAX_BPM)
            require(measurement.sleepPhase.trim().uppercase(Locale.ROOT) in PHASES)
            measurement.hrvRmssd?.let { require(it.isFinite() && it in 0.0..1_000.0) }
            measurement.hrvSdnn?.let { require(it.isFinite() && it in 0.0..1_000.0) }
            measurement.movement?.let { require(it.isFinite() && it >= 0.0) }
        }
        true
    }.getOrDefault(false)

    private val PHASES = setOf("AWAKE", "LIGHT", "DEEP", "REM")

    /** Identity fields that must never appear in the payload; the user comes only from the session token. */
    private val FORBIDDEN_KEYS = setOf(
        "userid", "iduser", "uiduser", "user_id", "user-id",
        "username", "user-name", "email", "correo"
    )
}
