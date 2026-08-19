package team.dreamapp.com.presentation.controller.auth

import io.javalin.http.Context
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import team.dreamapp.com.domain.entity.auth.Role
import team.dreamapp.com.domain.entity.auth.UserInfo
import team.dreamapp.com.infrastructure.datasouce.authdatabase.AuthDataSource
import team.dreamapp.com.infrastructure.service.auth.AuthTokenService
import team.dreamapp.com.infrastructure.service.auth.FirebaseIdentityVerifier
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.util.Base64

object GoogleAuthController {
    private val logger = LoggerFactory.getLogger(GoogleAuthController::class.java)
    private val random = SecureRandom()

    fun authenticate(ctx: Context) {
        val bearer = ctx.header("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')?.trim()
        val identity = FirebaseIdentityVerifier.verify(bearer)
        if (identity == null) {
            ctx.status(401).json(mapOf("success" to false, "error" to "Token de Google inválido."))
            return
        }

        try {
            val user = AuthDataSource.get().connection.use { connection ->
                connection.autoCommit = false
                try {
                    val byUid = findUser(connection, "firebase_uid = ?", identity.uid)
                    val byEmail = findUser(connection, "LOWER(email) = LOWER(?)", identity.email)
                    if (byUid != null && byEmail != null && byUid.id != byEmail.id) {
                        connection.rollback()
                        ctx.status(409).json(mapOf("success" to false, "error" to "La cuenta de Google ya está vinculada a otro usuario."))
                        return
                    }
                    val account = when {
                        byUid != null -> byUid
                        byEmail != null -> {
                            connection.prepareStatement("UPDATE user_account SET firebase_uid=?, email_verified=TRUE WHERE id=CAST(? AS UUID) AND firebase_uid IS NULL").use {
                                it.setString(1, identity.uid); it.setString(2, byEmail.id)
                                if (it.executeUpdate() != 1) error("Account could not be linked")
                            }
                            byEmail
                        }
                        else -> createUser(connection, identity.uid, identity.email, identity.displayName)
                    }
                    connection.commit()
                    account
                } catch (ex: Exception) {
                    connection.rollback()
                    throw ex
                } finally {
                    connection.autoCommit = true
                }
            }
            if (!user.active) {
                ctx.status(403).json(mapOf("success" to false, "error" to "La cuenta está desactivada."))
                return
            }
            user.mapRole("Cliente")
            val token = AuthTokenService.issue(user)
            ctx.json(mapOf("success" to true, "data" to user, "token" to token, "expiresIn" to 43200))
        } catch (ex: Exception) {
            logger.error("Could not link Google identity", ex)
            ctx.status(503).json(mapOf("success" to false, "error" to "No se pudo vincular la cuenta de Google."))
        }
    }

    fun resolveFirebaseUser(idToken: String?): UserInfo? {
        val identity = FirebaseIdentityVerifier.verify(idToken) ?: return null
        return runCatching {
            AuthDataSource.get().connection.use { connection ->
                findUser(connection, "firebase_uid = ?", identity.uid)?.also { it.mapRole("Cliente") }
            }
        }.getOrNull()
    }

    private fun findUser(connection: Connection, where: String, value: String): UserInfo? =
        connection.prepareStatement("""
            SELECT CAST(id AS VARCHAR) id, username, firstname, lastname, user_password, user_roles,
                   is_active, TO_CHAR(CURRENT_DATE, 'YYYY-MM-DD') current_date
            FROM user_account WHERE $where
        """.trimIndent()).use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { result -> if (result.next()) result.toUserInfo() else null }
        }

    private fun createUser(connection: Connection, firebaseUid: String, email: String, displayName: String): UserInfo {
        val parts = displayName.split(Regex("\\s+")).filter(String::isNotBlank)
        val firstName = parts.firstOrNull()?.take(100) ?: "Usuario"
        val lastName = parts.drop(1).joinToString(" ").take(100).ifBlank { "DreamApp" }
        val base = email.substringBefore('@').lowercase().replace(Regex("[^a-z0-9._-]"), "").take(30).ifBlank { "usuario" }
        var username = base
        var suffix = 0
        while (connection.prepareStatement("SELECT 1 FROM user_account WHERE LOWER(username)=LOWER(?)").use {
                it.setString(1, username); it.executeQuery().next()
            }) {
            suffix++
            username = "${base.take(30)}-$suffix"
        }
        val unusablePassword = ByteArray(32).also(random::nextBytes).let {
            BCrypt.hashpw(Base64.getUrlEncoder().withoutPadding().encodeToString(it), BCrypt.gensalt(12))
        }
        return connection.prepareStatement("""
            INSERT INTO user_account(id, username, firstname, lastname, user_password, user_roles,
              mobile_phone, phone_office, phone_ext, email, is_active, email_verified, firebase_uid)
            VALUES (gen_random_uuid(), ?, ?, ?, ?, 'Cliente', '', '', '', ?, TRUE, TRUE, ?)
            RETURNING CAST(id AS VARCHAR) id, username, firstname, lastname, user_password, user_roles,
              is_active, TO_CHAR(CURRENT_DATE, 'YYYY-MM-DD') current_date
        """.trimIndent()).use { statement ->
            statement.setString(1, username); statement.setString(2, firstName); statement.setString(3, lastName)
            statement.setString(4, unusablePassword); statement.setString(5, email); statement.setString(6, firebaseUid)
            statement.executeQuery().use { result -> result.next(); result.toUserInfo() }
        }
    }

    private fun ResultSet.toUserInfo() = UserInfo(
        id = getString("id"), userName = getString("username"), password = getString("user_password"),
        fullname = "${getString("firstname")} ${getString("lastname")}",
        roles = getString("user_roles").split(','), active = getBoolean("is_active"),
        currentDate = getString("current_date"), role = Role.UNAUTHENTICATED
    )
}
