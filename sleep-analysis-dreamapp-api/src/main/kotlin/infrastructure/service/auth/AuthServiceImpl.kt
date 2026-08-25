package team.dreamapp.com.infrastructure.service.auth

import io.javalin.http.Context
import org.mindrot.jbcrypt.BCrypt
import team.dreamapp.com.domain.entity.auth.UserInfo
import team.dreamapp.com.domain.services.auth.AuthService
import team.dreamapp.com.infrastructure.di.RepositoryProvider
import team.dreamapp.com.domain.repository.account.UserAccountRepository

class AuthServiceImpl(
    private val repository: UserAccountRepository = RepositoryProvider.userAccountRepository
) : AuthService {
    override fun login(userName: String, password: String, role: String): UserInfo {
        val user = repository.userInfoBy("username", userName) ?: throw InvalidCredentialsException()
        val passwordMatches = try {
            BCrypt.checkpw(password, user.password)
        } catch (ex: IllegalArgumentException) {
            false
        }
        if (!passwordMatches) throw InvalidCredentialsException()
        if (!user.active) throw AccountInactiveException()
        try {
            user.mapRole(role)
        } catch (e: Exception) {
            throw RoleNotAllowedException()
        }
        user.password = "**************"
        return user
    }

    override fun logout(ctx: Context): Boolean {
        ctx.sessionAttribute("USER_INFO", null)
        return true
    }
}
