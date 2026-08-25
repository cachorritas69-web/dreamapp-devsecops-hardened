package team.dreamapp.com.infrastructure.service.auth

import kotliquery.queryOf
import kotliquery.sessionOf
import org.slf4j.LoggerFactory
import team.dreamapp.com.domain.entity.auth.Role
import team.dreamapp.com.domain.entity.auth.UserInfo
import team.dreamapp.com.infrastructure.datasouce.authdatabase.AuthDataSource
import team.dreamapp.com.infrastructure.di.RepositoryProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Persistent opaque session tokens. Only the SHA-256 hash of the token is
 * stored (PostgreSQL table user_session), so a database leak never exposes
 * usable bearer tokens. Sessions survive backend restarts and deployments and
 * can be revoked server side.
 */
object AuthTokenService {
    private val logger = LoggerFactory.getLogger(AuthTokenService::class.java)
    private val random = SecureRandom()
    private const val lifetimeSeconds = 12 * 60 * 60L

    fun issue(user: UserInfo): String {
        cleanup()
        val raw = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        sessionOf(AuthDataSource.get()).use { session ->
            session.run(queryOf("""
                INSERT INTO user_session(token_hash, user_id, role, expires_at)
                VALUES (?, CAST(? AS UUID), ?, CURRENT_TIMESTAMP + (CAST(? AS BIGINT) * INTERVAL '1 second'))
                ON CONFLICT (token_hash) DO NOTHING
            """.trimIndent(), hash(token), user.id, user.role.name, lifetimeSeconds).asUpdate)
        }
        return token
    }

    fun resolve(token: String?): UserInfo? {
        if (token.isNullOrBlank()) return null
        val record = try {
            sessionOf(AuthDataSource.get()).use { session ->
                session.run(queryOf("""
                    SELECT CAST(user_id AS VARCHAR) AS user_id, role,
                           EXTRACT(EPOCH FROM expires_at)::BIGINT AS expires_epoch
                    FROM user_session WHERE token_hash = ?
                """.trimIndent(), hash(token)).map { row ->
                    Triple(row.string("user_id"), row.string("role"), row.long("expires_epoch"))
                }.asSingle)
            }
        } catch (ex: Exception) {
            logger.error("Session lookup failed: {}", ex.javaClass.simpleName)
            return null
        } ?: return null
        if (record.third <= Instant.now().epochSecond) {
            revoke(token)
            return null
        }
        val user = runCatching {
            RepositoryProvider.userAccountRepository.userInfoBy("ID", record.first)?.apply {
                role = try {
                    Role.valueOf(record.second)
                } catch (ex: IllegalArgumentException) {
                    Role.UNAUTHENTICATED
                }
                password = "**************"
            }
        }.getOrNull()
        // The account backing this session disappeared; drop the orphan session.
        if (user == null) revoke(token)
        return user
    }

    fun revoke(token: String?) {
        if (token.isNullOrBlank()) return
        try {
            sessionOf(AuthDataSource.get()).use { session ->
                session.run(queryOf("DELETE FROM user_session WHERE token_hash = ?", hash(token)).asUpdate)
            }
        } catch (ex: Exception) {
            logger.warn("Could not revoke session: {}", ex.javaClass.simpleName)
        }
    }

    private fun cleanup() {
        try {
            sessionOf(AuthDataSource.get()).use { session ->
                session.run(queryOf("DELETE FROM user_session WHERE expires_at < CURRENT_TIMESTAMP").asUpdate)
            }
        } catch (ex: Exception) {
            logger.warn("Could not clean expired sessions: {}", ex.javaClass.simpleName)
        }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
