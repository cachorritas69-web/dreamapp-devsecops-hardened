package presentation.controller.sleep

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.apibuilder.ApiBuilder.post
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import team.dreamapp.com.domain.entity.auth.Role
import team.dreamapp.com.domain.entity.auth.UserInfo
import team.dreamapp.com.infrastructure.datasouce.authdatabase.AuthDataSource
import team.dreamapp.com.infrastructure.service.auth.AuthTokenService
import team.dreamapp.com.presentation.auth.AccessManager
import team.dreamapp.com.presentation.controller.sleep.SleepMeasurementController
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end wearable measurement flow against a real PostgreSQL instance.
 * Requires Docker; skipped with an explicit assumption when unavailable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SleepMeasurementFlowIntegrationTest {

    private lateinit var app: Javalin
    private var baseUrl: String = ""
    private val http = OkHttpClient()
    private val logCapture = ListAppender<ILoggingEvent>()

    companion object {
        private val dockerAvailable by lazy {
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        }

        @JvmStatic
        private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

        private const val BATCH_ID = "b405fa7d-ea18-4a70-83d8-109d6cbd1e8f"
        private const val DEVICE = "vitalwatch-001"
    }

    @BeforeAll
    fun setUp() {
        assumeTrue(dockerAvailable, "Docker unavailable: integration tests skipped")
        postgres.start()
        // DATABASE_URL must carry credentials; Testcontainers' jdbcUrl omits them.
        System.setProperty(
            "DATABASE_URL",
            "postgresql://${postgres.username}:${postgres.password}@${postgres.host}:${postgres.getMappedPort(5432)}/${postgres.databaseName}"
        )
        AuthDataSource.init()
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        if (rootLogger is ch.qos.logback.classic.Logger) {
            rootLogger.level = Level.INFO
            rootLogger.addAppender(logCapture)
        }
        app = Javalin.create { config ->
            config.showJavalinBanner = false
            config.router.mount { route -> route.beforeMatched(AccessManager::handleAccess) }.apiBuilder {
                path("sleep") {
                    post("measurements/batch", SleepMeasurementController::uploadBatch, Role.CLIENT)
                    get("measurements/recent", SleepMeasurementController::getRecent, Role.CLIENT)
                }
            }
        }.start(0)
        baseUrl = "http://localhost:${app.port()}"
    }

    @AfterAll
    fun tearDown() {
        if (::app.isInitialized) app.stop()
        System.clearProperty("DATABASE_URL")
        if (dockerAvailable) postgres.stop()
    }

    // ---------- helpers ----------

    private fun seedUser(id: String, username: String) {
        AuthDataSource.get().connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO user_account(id, username, firstname, lastname, user_password, user_roles,
                  email, email_verified, is_active)
                VALUES (CAST(? AS UUID), ?, 'Test', 'Usuario', 'not-a-real-password', 'Cliente', ?, TRUE, TRUE)
                ON CONFLICT (username) DO NOTHING
            """.trimIndent()).use {
                it.setString(1, id); it.setString(2, username); it.setString(3, "$username@example.com")
                it.executeUpdate()
            }
        }
    }

    private fun tokenFor(userId: String): String = AuthTokenService.issue(
        UserInfo(
            id = userId, userName = "user$userId".take(20), password = "**************",
            fullname = "Test Usuario", role = Role.CLIENT, roles = listOf("Cliente"),
            active = true, currentDate = LocalDate.now().toString()
        )
    )

    private fun post(path: String, body: String, authorization: String? = null): okhttp3.Response {
        val builder = Request.Builder().url("$baseUrl$path")
            .post(body.toRequestBody("application/json".toMediaType()))
        authorization?.let { builder.header("Authorization", it) }
        return http.newCall(builder.build()).execute()
    }

    private fun get(path: String, authorization: String? = null): okhttp3.Response {
        val builder = Request.Builder().url("$baseUrl$path").get()
        authorization?.let { builder.header("Authorization", it) }
        return http.newCall(builder.build()).execute()
    }

    private fun batchBody(
        measurementsJson: List<String>,
        batchId: String = BATCH_ID,
        deviceId: String = DEVICE
    ): String = """{"batchId":"$batchId","deviceId":"$deviceId","measurements":[${measurementsJson.joinToString()}]}"""

    private fun measurementJson(
        clientMeasurementId: String = "$DEVICE-182",
        measuredAt: String = "2026-08-25T23:45:10.123Z",
        heartRateBpm: Int = 62,
        sleepPhase: String = "DEEP",
        extras: String = """"hrvRmssd":45.2,"hrvSdnn":52.1,"movement":0.18"""
    ): String {
        val suffix = if (extras.isEmpty()) "" else ",$extras"
        return """{"clientMeasurementId":"$clientMeasurementId","measuredAt":"$measuredAt","heartRateBpm":$heartRateBpm,"sleepPhase":"$sleepPhase"$suffix}"""
    }

    private fun rowCount(userId: String): Int =
        AuthDataSource.get().connection.use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM sleep_measurement WHERE user_id = CAST(? AS UUID)"
            ).use {
                it.setString(1, userId); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }

    // ---------- tests ----------

    @Test
    @Order(1)
    fun `request without authentication returns 401`() {
        post("/sleep/measurements/batch", batchBody(listOf(measurementJson()))).use {
            assertEquals(401, it.code)
        }
        get("/sleep/measurements/recent").use {
            assertEquals(401, it.code)
        }
    }

    @Test
    @Order(2)
    fun `a valid batch is accepted and attributed to the authenticated user`() {
        seedUser("11111111-1111-1111-1111-111111111111", "alice")
        val token = tokenFor("11111111-1111-1111-1111-111111111111")
        val body = batchBody((1..3).map { measurementJson(clientMeasurementId = "$DEVICE-$it") })
        post("/sleep/measurements/batch", body, "Bearer $token").use { response ->
            assertEquals(200, response.code)
            val payload = response.body!!.string()
            assertTrue(payload.contains("\"received\":3"))
            assertTrue(payload.contains("\"inserted\":3"))
            assertTrue(payload.contains("\"duplicates\":0"))
        }
        assertEquals(3, rowCount("11111111-1111-1111-1111-111111111111"))
    }

    @Test
    @Order(3)
    fun `resending the same batch reports duplicates without creating new rows`() {
        val userId = "11111111-1111-1111-1111-111111111111"
        val token = tokenFor(userId)
        val before = rowCount(userId)
        val body = batchBody((1..3).map { measurementJson(clientMeasurementId = "$DEVICE-$it") })
        post("/sleep/measurements/batch", body, "Bearer $token").use { response ->
            assertEquals(200, response.code)
            val payload = response.body!!.string()
            assertTrue(payload.contains("\"inserted\":0"))
            assertTrue(payload.contains("\"duplicates\":3"))
        }
        assertEquals(before, rowCount(userId))
    }

    @Test
    @Order(4)
    fun `an empty batch is rejected`() {
        val token = tokenFor("11111111-1111-1111-1111-111111111111")
        post("/sleep/measurements/batch", """{"batchId":"$BATCH_ID","deviceId":"$DEVICE","measurements":[]}""", "Bearer $token").use {
            assertEquals(400, it.code)
        }
    }

    @Test
    @Order(5)
    fun `more than five hundred measurements are rejected`() {
        val token = tokenFor("11111111-1111-1111-1111-111111111111")
        val many = (1..501).map { measurementJson(clientMeasurementId = "m-$it") }
        post("/sleep/measurements/batch", batchBody(many), "Bearer $token").use {
            assertEquals(400, it.code)
        }
    }

    @Test
    @Order(6)
    fun `an invalid batch inserts no rows at all`() {
        val userId = "11111111-1111-1111-1111-111111111111"
        val token = tokenFor(userId)
        val before = rowCount(userId)
        listOf(
            // BPM out of range
            batchBody(listOf(measurementJson(heartRateBpm = 300))),
            // Unknown phase
            batchBody(listOf(measurementJson(sleepPhase = "NAPPING"))),
            // Invalid timestamp
            batchBody(listOf(measurementJson(measuredAt = "yesterday"))),
            // NaN in a double field
            batchBody(listOf("""{"clientMeasurementId":"nan-1","measuredAt":"2026-08-25T23:45:10Z","heartRateBpm":62,"sleepPhase":"DEEP","movement":NaN}""")),
            // One bad measurement poisons an otherwise valid batch
            batchBody(listOf(measurementJson(clientMeasurementId = "good-1"), measurementJson(heartRateBpm = 10))),
            // Client-supplied user identifier
            """{"batchId":"$BATCH_ID","deviceId":"$DEVICE","userId":"22222222-2222-2222-2222-222222222222","measurements":[${measurementJson()}]}"""
        ).forEach { body ->
            post("/sleep/measurements/batch", body, "Bearer $token").use { assertEquals(400, it.code, body.take(80)) }
        }
        assertEquals(before, rowCount(userId))
    }

    @Test
    @Order(7)
    fun `two users can use the same client measurement id without collision`() {
        seedUser("33333333-3333-3333-3333-333333333333", "bob")
        val aliceToken = tokenFor("11111111-1111-1111-1111-111111111111")
        val bobToken = tokenFor("33333333-3333-3333-3333-333333333333")
        val sharedBatch = "44444444-4444-4444-4444-444444444444"
        val body = batchBody(listOf(measurementJson(clientMeasurementId = "shared-1")), batchId = sharedBatch)
        post("/sleep/measurements/batch", body, "Bearer $aliceToken").use { assertEquals(200, it.code) }
        post("/sleep/measurements/batch", body, "Bearer $bobToken").use { response ->
            val payload = response.body!!.string()
            assertEquals(200, response.code, payload)
            assertTrue(payload.contains("\"inserted\":1"))
        }
    }

    @Test
    @Order(8)
    fun `recent measurements never leak another user's data`() {
        val aliceToken = tokenFor("11111111-1111-1111-1111-111111111111")
        val bobToken = tokenFor("33333333-3333-3333-3333-333333333333")
        get("/sleep/measurements/recent?limit=100", "Bearer $bobToken").use { response ->
            val payload = response.body!!.string()
            assertEquals(200, response.code, payload)
            assertFalse(payload.contains("shared-bob-alice-leak"))
            assertTrue(payload.contains("\"success\":true"))
        }
        get("/sleep/measurements/recent?limit=100&userId=11111111-1111-1111-1111-111111111111", "Bearer $bobToken").use {
            assertEquals(200, it.code)
            assertFalse(it.body!!.string().contains("vitalwatch-001-1\""))
        }
        get("/sleep/measurements/recent?limit=100", "Bearer $aliceToken").use { response ->
            val payload = response.body!!.string()
            assertTrue(payload.contains("\"clientMeasurementId\":\"shared-1\""))
        }
    }

    @Test
    @Order(9)
    fun `recent limit outside one to five hundred is rejected`() {
        val token = tokenFor("11111111-1111-1111-1111-111111111111")
        get("/sleep/measurements/recent?limit=0", "Bearer $token").use { assertEquals(400, it.code) }
        get("/sleep/measurements/recent?limit=501", "Bearer $token").use { assertEquals(400, it.code) }
        get("/sleep/measurements/recent?limit=nope", "Bearer $token").use { assertEquals(400, it.code) }
    }

    @Test
    @Order(10)
    fun `a database error rolls back the whole batch`() {
        val userId = "11111111-1111-1111-1111-111111111111"
        val token = tokenFor(userId)
        val before = rowCount(userId)
        // Simulate a schema failure on the throwaway container only.
        AuthDataSource.get().connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("ALTER TABLE sleep_measurement DROP COLUMN movement") }
        }
        try {
            post("/sleep/measurements/batch", batchBody(listOf(measurementJson(clientMeasurementId = "rollback-1"))), "Bearer $token").use {
                assertEquals(503, it.code)
            }
            assertEquals(before, rowCount(userId))
        } finally {
            AuthDataSource.get().connection.use { connection ->
                connection.createStatement().use {
                    it.executeUpdate("ALTER TABLE sleep_measurement ADD COLUMN movement DOUBLE PRECISION")
                }
            }
        }
    }

    @Test
    @Order(11)
    fun `logs never contain tokens or request bodies`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) is ch.qos.logback.classic.Logger,
            "Logback is not the active SLF4J backend; log capture skipped"
        )
        logCapture.list.clear()
        val secretMarker = "SECRET-BODY-MARKER-x7f3"
        val token = tokenFor("11111111-1111-1111-1111-111111111111")
        post("/sleep/measurements/batch", batchBody(listOf(measurementJson(clientMeasurementId = secretMarker))), "Bearer $token").use {
            assertEquals(200, it.code)
        }
        post("/sleep/measurements/batch", batchBody(listOf()), "Bearer $token").use { assertEquals(400, it.code) }
        val captured = logCapture.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
        assertFalse(captured.contains(token), "logs must never contain session tokens")
        assertFalse(captured.contains(secretMarker), "logs must never contain request bodies")
        assertFalse(captured.contains(BATCH_ID), "logs must not echo batch identifiers with user data")
    }
}
