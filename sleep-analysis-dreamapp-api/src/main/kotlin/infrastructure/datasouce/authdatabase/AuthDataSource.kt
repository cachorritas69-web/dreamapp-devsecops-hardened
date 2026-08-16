package team.dreamapp.com.infrastructure.datasouce.authdatabase

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import team.dreamapp.com.infrastructure.config.Config
import java.sql.Connection
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
        val config = HikariConfig().apply {
            jdbcUrl = Config.SVR_AUTH_CONF.dbURL
            username = Config.SVR_AUTH_CONF.dbUser
            password = Config.SVR_AUTH_CONF.dbPwd
            maximumPoolSize = 10
            isAutoCommit = true
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }

        dataSource = HikariDataSource(config)
        logger.info("[OK] AuthDataSource initialized with URL: ${Config.SVR_AUTH_CONF.dbURL}")
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