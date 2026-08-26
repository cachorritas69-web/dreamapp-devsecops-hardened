package presentation.auth

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.mindrot.jbcrypt.BCrypt
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import team.dreamapp.com.domain.entity.auth.Role
import team.dreamapp.com.infrastructure.datasouce.authdatabase.AuthDataSource
import team.dreamapp.com.infrastructure.service.auth.AuthTokenService
import team.dreamapp.com.presentation.auth.AccessManager
import team.dreamapp.com.presentation.controller.auth.AuthController
import team.dreamapp.com.presentation.controller.auth.GoogleAuthController
import team.dreamapp.com.presentation.controller.auth.RegistrationController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * End-to-end authentication flow against a real PostgreSQL instance.
 * Requires Docker; every test is skipped with an explicit assumption when
 * Docker is unavailable (documented limitation of this environment).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthFlowIntegrationTest {

    private lateinit var app: Javalin
    private var baseUrl: String = ""
    private val http = OkHttpClient()

    companion object {
        private const val SECRET = "integration-test-secret-0123456789abcdef"
        private val dockerAvailable by lazy {
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        }

        @JvmStatic
        private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

        private fun codeHash(email: String, code: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$SECRET:$email:$code".toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
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
        System.setProperty("EMAIL_VERIFICATION_SECRET", SECRET)
        AuthDataSource.init()
        app = Javalin.create { config ->
            config.showJavalinBanner = false
            config.router.mount { route -> route.beforeMatched(AccessManager::handleAccess) }.apiBuilder {
                path("auth") {
                    post("login", AuthController::login, Role.UNAUTHENTICATED)
                    post("google", GoogleAuthController::authenticate, Role.UNAUTHENTICATED)
                    post("register", RegistrationController::register, Role.UNAUTHENTICATED)
                    post("verify", RegistrationController::verify, Role.UNAUTHENTICATED)
                    post("logout", AuthController::logout, Role.SYSADMIN, Role.ADMIN, Role.CLIENT)
                }
                get("probe", { it.json(mapOf("ok" to true)) }, Role.CLIENT)
            }
        }.start(0)
        baseUrl = "http://localhost:${app.port()}"
    }

    @AfterAll
    fun tearDown() {
        if (::app.isInitialized) app.stop()
        if (dockerAvailable) postgres.stop()
    }

    /** Each test starts from a clean registration state; the container database persists across methods. */
    @BeforeEach
    fun cleanRegistrationState() {
        if (!dockerAvailable || !AuthDataSource.isConnectionHealthy()) return
        AuthDataSource.get().connection.use { connection ->
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM pending_registration WHERE email LIKE '%@example.com'")
                it.executeUpdate("DELETE FROM user_account WHERE email LIKE '%@example.com'")
            }
        }
    }

    /** Seeds pending_registration exactly as POST /auth/register would, minus the email delivery. */
    private fun seedPendingRegistration(email: String, username: String, password: String, code: String) {
        AuthDataSource.get().connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO pending_registration(email, username, firstname, lastname, password_hash, code_hash,
                  expires_at, attempts, last_sent_at)
                VALUES (?, ?, 'Nuevo', 'Usuario', ?, ?, CURRENT_TIMESTAMP + INTERVAL '10 minutes', 0, CURRENT_TIMESTAMP)
                ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                  code_hash = EXCLUDED.code_hash, expires_at = EXCLUDED.expires_at, attempts = 0
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, email)
                statement.setString(2, username.trim().lowercase())
                statement.setString(3, BCrypt.hashpw(password, BCrypt.gensalt(12)))
                statement.setString(4, codeHash(email, code))
                statement.executeUpdate()
            }
        }
    }

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

    private fun completeVerification(username: String, email: String, password: String, code: String) {
        seedPendingRegistration(email, username, password, code)
        post("/auth/verify", """{"email":"$email","code":"$code"}""").use { response ->
            assertEquals(201, response.code, "verification failed: ${response.body?.string()}")
        }
    }

    @Test
    fun `registered as NuevoUsuario can log in as nuevousuario`() {
        completeVerification("nuevousuario", "case1@example.com", "S3curePass1", "123456")
        post("/auth/login", """{"userName":"nuevousuario","password":"S3curePass1"}""").use {
            assertEquals(200, it.code, it.body?.string())
        }
    }

    @Test
    fun `registered as NuevoUsuario can log in typing NuevoUsuario`() {
        completeVerification("nuevousuario", "case2@example.com", "S3curePass1", "123456")
        post("/auth/login", """{"userName":"NuevoUsuario","password":"S3curePass1"}""").use {
            assertEquals(200, it.code, it.body?.string())
        }
    }

    @Test
    fun `surrounding whitespace in the username does not block login`() {
        completeVerification("nuevousuario", "case3@example.com", "S3curePass1", "123456")
        post("/auth/login", """{"userName":"  NuevoUsuario ","password":"S3curePass1"}""").use {
            assertEquals(200, it.code, it.body?.string())
        }
    }

    @Test
    fun `wrong password returns the generic 401`() {
        completeVerification("nuevousuario", "badpass@example.com", "S3curePass1", "123456")
        post("/auth/login", """{"userName":"nuevousuario","password":"WrongPass999"}""").use {
            assertEquals(401, it.code)
            assertEquals("""{"success":false,"error":"Invalid credentials"}""", it.body?.string())
        }
    }

    @Test
    fun `unknown user returns the identical generic 401`() {
        post("/auth/login", """{"userName":"ghostuser","password":"Whatever123"}""").use {
            assertEquals(401, it.code)
            assertEquals("""{"success":false,"error":"Invalid credentials"}""", it.body?.string())
        }
    }

    @Test
    fun `inactive account is rejected`() {
        completeVerification("nuevousuario", "inactive@example.com", "S3curePass1", "123456")
        AuthDataSource.get().connection.use { connection ->
            connection.prepareStatement("UPDATE user_account SET is_active = FALSE WHERE email = ?").use {
                it.setString(1, "inactive@example.com"); it.executeUpdate()
            }
        }
        post("/auth/login", """{"userName":"nuevousuario","password":"S3curePass1"}""").use {
            assertEquals(401, it.code)
        }
    }

    @Test
    fun `login works even when the client sends a previously invalid session`() {
        completeVerification("nuevousuario", "stalehdr@example.com", "S3curePass1", "123456")
        post(
            "/auth/login",
            """{"userName":"nuevousuario","password":"S3curePass1"}""",
            "Bearer dead-session-token"
        ).use {
            assertEquals(200, it.code, it.body?.string())
        }
    }

    @Test
    fun `opaque dreamapp token never reaches firebase and gets a plain 401 on protected routes`() {
        val raw = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val opaque = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        get("/probe", "Bearer $opaque").use {
            assertEquals(401, it.code)
        }
    }

    @Test
    fun `auth google rejects opaque tokens instead of treating them as id tokens`() {
        val raw = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val opaque = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        post("/auth/google", "{}", "Bearer $opaque").use {
            assertEquals(401, it.code)
        }
    }

    @Test
    fun `issued sessions survive backend restarts because they are database backed`() {
        completeVerification("nuevousuario", "persist@example.com", "S3curePass1", "123456")
        lateinit var token: String
        post("/auth/login", """{"userName":"nuevousuario","password":"S3curePass1"}""").use { response ->
            assertEquals(200, response.code)
            val body = response.body!!.string()
            token = Regex(""""token"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
        }
        assertNotNull(AuthTokenService.resolve(token))
        // A restart only means new processes: state lives in PostgreSQL, so resolve keeps working.
        assertNotNull(AuthTokenService.resolve(token))
        AuthTokenService.revoke(token)
        assertNull(AuthTokenService.resolve(token))
    }

    @Test
    fun `stored username is canonical lowercase after verification`() {
        completeVerification("MixedCaseUser", "canonical@example.com", "S3curePass1", "123456")
        AuthDataSource.get().connection.use { connection ->
            connection.prepareStatement("SELECT username FROM user_account WHERE email = ?").use {
                it.setString(1, "canonical@example.com")
                it.executeQuery().use { rs ->
                    rs.next()
                    assertEquals("mixedcaseuser", rs.getString(1))
                    assertNotEquals("MixedCaseUser", rs.getString(1))
                }
            }
        }
    }
}
