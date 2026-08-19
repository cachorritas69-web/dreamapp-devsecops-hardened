package team.dreamapp.com.infrastructure.datasouce.authdatabase

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import team.dreamapp.com.infrastructure.config.Config
import java.sql.Connection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.sql.DataSource

/**
 * Singleton object that provides access to the authentication database via HikariCP connection pooling.
 * This component is part of the DATA layer in the Clean Architecture structure.
 */
object AuthDataSource {

    private val logger = LoggerFactory.getLogger(AuthDataSource::class.java)

    // Internal reference to the configured HikariDataSource
    private lateinit var dataSource: HikariDataSource

    /**
     * Initializes the HikariCP data source with database credentials and configuration.
     * Must be called before using `get()` or `isConnectionHealthy()`.
     */
    fun init() {
        val connection = resolveConnection()
        val config = HikariConfig().apply {
            jdbcUrl = connection.jdbcUrl
            username = connection.username
            password = connection.password
            maximumPoolSize = 5
            minimumIdle = 1
            connectionTimeout = 10_000
            validationTimeout = 3_000
            isAutoCommit = true
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        }

        dataSource = HikariDataSource(config)
        migrate()
        logger.info("[OK] PostgreSQL authentication database initialized")
    }

    private data class ConnectionSettings(val jdbcUrl: String, val username: String, val password: String)

    private fun resolveConnection(): ConnectionSettings {
        val databaseUrl = System.getenv("DATABASE_URL")?.trim().orEmpty()
        if (databaseUrl.isBlank()) {
            return ConnectionSettings(Config.SVR_AUTH_CONF.dbURL, Config.SVR_AUTH_CONF.dbUser, Config.SVR_AUTH_CONF.dbPwd)
        }
        val normalized = databaseUrl.replaceFirst("postgres://", "postgresql://")
        val uri = URI(normalized)
        require(uri.scheme == "postgresql" && !uri.host.isNullOrBlank()) { "DATABASE_URL must be a PostgreSQL URL" }
        val credentials = uri.rawUserInfo?.split(':', limit = 2).orEmpty()
        require(credentials.size == 2) { "DATABASE_URL must include database credentials" }
        val user = URLDecoder.decode(credentials[0], StandardCharsets.UTF_8)
        val password = URLDecoder.decode(credentials[1], StandardCharsets.UTF_8)
        val port = if (uri.port > 0) uri.port else 5432
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return ConnectionSettings("jdbc:postgresql://${uri.host}:$port${uri.rawPath}$query", user, password)
    }

    private fun migrate() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS user_account (
                        id UUID PRIMARY KEY,
                        username VARCHAR(80) NOT NULL UNIQUE,
                        firstname VARCHAR(100) NOT NULL,
                        lastname VARCHAR(100) NOT NULL,
                        user_password VARCHAR(100) NOT NULL,
                        user_roles VARCHAR(200) NOT NULL,
                        mobile_phone VARCHAR(30) NOT NULL DEFAULT '',
                        phone_office VARCHAR(30) NOT NULL DEFAULT '',
                        phone_ext VARCHAR(15) NOT NULL DEFAULT '',
                        email VARCHAR(254) NOT NULL DEFAULT '',
                        is_active BOOLEAN NOT NULL DEFAULT TRUE,
                        subscription_plan VARCHAR(20) NOT NULL DEFAULT 'FREE',
                        email_verified BOOLEAN NOT NULL DEFAULT FALSE,
                        firebase_uid VARCHAR(128),
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                """.trimIndent())
                statement.executeUpdate("ALTER TABLE user_account ADD COLUMN IF NOT EXISTS subscription_plan VARCHAR(20) NOT NULL DEFAULT 'FREE'")
                statement.executeUpdate("ALTER TABLE user_account ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE")
                statement.executeUpdate("ALTER TABLE user_account ADD COLUMN IF NOT EXISTS firebase_uid VARCHAR(128)")
                statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS user_account_email_unique ON user_account (LOWER(email)) WHERE email <> ''")
                statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS user_account_firebase_uid_unique ON user_account (firebase_uid) WHERE firebase_uid IS NOT NULL")
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sleep_session (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
                        device_id VARCHAR(160) NOT NULL DEFAULT '',
                        sleep_date DATE NOT NULL,
                        start_time TIMESTAMPTZ,
                        end_time TIMESTAMPTZ,
                        timezone VARCHAR(80) NOT NULL DEFAULT 'UTC',
                        total_duration INTEGER NOT NULL DEFAULT 0 CHECK (total_duration >= 0),
                        sleep_duration INTEGER NOT NULL DEFAULT 0 CHECK (sleep_duration >= 0),
                        light_minutes INTEGER NOT NULL DEFAULT 0 CHECK (light_minutes >= 0),
                        deep_minutes INTEGER NOT NULL DEFAULT 0 CHECK (deep_minutes >= 0),
                        rem_minutes INTEGER NOT NULL DEFAULT 0 CHECK (rem_minutes >= 0),
                        awake_minutes INTEGER NOT NULL DEFAULT 0 CHECK (awake_minutes >= 0),
                        sleep_efficiency DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (sleep_efficiency BETWEEN 0 AND 100),
                        awakenings INTEGER NOT NULL DEFAULT 0 CHECK (awakenings >= 0),
                        quality VARCHAR(20) NOT NULL DEFAULT 'POOR',
                        avg_heart_rate INTEGER NOT NULL DEFAULT 0 CHECK (avg_heart_rate >= 0),
                        min_heart_rate INTEGER NOT NULL DEFAULT 0 CHECK (min_heart_rate >= 0),
                        max_heart_rate INTEGER NOT NULL DEFAULT 0 CHECK (max_heart_rate >= 0),
                        avg_hrv DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (avg_hrv >= 0),
                        source VARCHAR(30) NOT NULL DEFAULT 'MOBILE',
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (user_id, sleep_date)
                    )
                """.trimIndent())
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pending_registration (
                        email VARCHAR(254) PRIMARY KEY,
                        username VARCHAR(80) NOT NULL,
                        firstname VARCHAR(100) NOT NULL,
                        lastname VARCHAR(100) NOT NULL,
                        password_hash VARCHAR(100) NOT NULL,
                        code_hash VARCHAR(64) NOT NULL,
                        expires_at TIMESTAMPTZ NOT NULL,
                        attempts SMALLINT NOT NULL DEFAULT 0,
                        last_sent_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                """.trimIndent())
                statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS pending_registration_username_unique ON pending_registration (LOWER(username))")
                // Remove only the legacy bootstrap account. New accounts are always self-service clients.
                statement.executeUpdate("DELETE FROM user_account WHERE LOWER(username) = 'admin' AND user_roles LIKE '%SysAdmin%'")
            }
        }
    }

    /**
     * Provides access to the configured DataSource.
     * @return the active Hikari DataSource
     * @throws UninitializedPropertyAccessException if `init()` has not been called
     */
    fun get(): DataSource = dataSource

    /**
     * Checks whether the current database connection is valid.
     * Attempts to acquire a connection and validate it with a 3-second timeout.
     * @return true if the connection is healthy; false otherwise
     */
    fun isConnectionHealthy(): Boolean {
        return try {
            dataSource.connection.use { conn: Connection ->
                conn.isValid(3)
            }
            logger.info("[OK] Connection to AuthDataSource is healthy")
            true
        } catch (e: Exception) {
            logger.error("[X] Connection to AuthDataSource failed", e)
            false
        }
    }

    /**
     * Returns a basic description of the database environment.
     * This can be extended to reflect development, testing, or production modes.
     */
    fun getEnvironmentInfo(): String = "Database for production"
}
