package infrastructure.service.auth

import io.javalin.http.Context
import team.dreamapp.com.domain.entity.account.UserAccount
import team.dreamapp.com.domain.entity.auth.UserInfo
import team.dreamapp.com.domain.repository.account.UserAccountRepository
import team.dreamapp.com.infrastructure.service.auth.AccountInactiveException
import team.dreamapp.com.infrastructure.service.auth.AuthServiceImpl
import team.dreamapp.com.infrastructure.service.auth.InvalidCredentialsException
import team.dreamapp.com.infrastructure.service.auth.RoleNotAllowedException
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthServiceImplTest {

    private class FakeUserAccountRepository(private val user: UserInfo?) : UserAccountRepository {
        override fun insert(userAccount: UserAccount): String = "unused"
        override fun update(userAccount: UserAccount): String = "unused"
        override fun delete(uuid: String): String = "unused"
        override fun getAll(where: String): List<UserAccount> = emptyList()
        override fun getByUUID(uuid: String): UserAccount? = null
        override fun userInfoBy(type: String, param: String): UserInfo? = user
    }

    private fun storedUser(
        password: String = "Correcta123",
        active: Boolean = true,
        roles: List<String> = listOf("Cliente")
    ): UserInfo = UserInfo(
        id = "11111111-1111-1111-1111-111111111111",
        userName = "nuevousuario",
        password = BCrypt.hashpw(password, BCrypt.gensalt(12)),
        fullname = "Nuevo Usuario",
        roles = roles,
        active = active,
        currentDate = "2026-08-25"
    )

    @Test
    fun `unknown user fails with typed invalid credentials`() {
        val service = AuthServiceImpl(FakeUserAccountRepository(null))
        assertFailsWith<InvalidCredentialsException> { service.login("ghost", "x", "Cliente") }
    }

    @Test
    fun `wrong password fails with typed invalid credentials`() {
        val service = AuthServiceImpl(FakeUserAccountRepository(storedUser()))
        assertFailsWith<InvalidCredentialsException> { service.login("nuevousuario", "Incorrecta123", "Cliente") }
    }

    @Test
    fun `corrupted hash is treated as invalid credentials and not as a crash`() {
        val broken = storedUser().copy(password = "not-a-bcrypt-hash")
        val service = AuthServiceImpl(FakeUserAccountRepository(broken))
        assertFailsWith<InvalidCredentialsException> { service.login("nuevousuario", "Correcta123", "Cliente") }
    }

    @Test
    fun `inactive account is rejected even with the right password`() {
        val service = AuthServiceImpl(FakeUserAccountRepository(storedUser(active = false)))
        assertFailsWith<AccountInactiveException> { service.login("nuevousuario", "Correcta123", "Cliente") }
    }

    @Test
    fun `account without the requested role is rejected`() {
        val service = AuthServiceImpl(
            FakeUserAccountRepository(storedUser(roles = listOf("SysAdmin")))
        )
        assertFailsWith<RoleNotAllowedException> { service.login("nuevousuario", "Correcta123", "Cliente") }
    }

    @Test
    fun `existing user keeps logging in and never leaks the hash`() {
        val service = AuthServiceImpl(FakeUserAccountRepository(storedUser()))
        val user = service.login("nuevousuario", "Correcta123", "Cliente")
        assertEquals("**************", user.password)
    }
}
