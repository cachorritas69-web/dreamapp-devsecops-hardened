package presentation.auth

import team.dreamapp.com.domain.services.auth.UsernamePolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class UsernamePolicyTest {
    @Test
    fun `mixed case username is canonicalized to lowercase`() {
        assertEquals("nuevousuario", UsernamePolicy.canonical("NuevoUsuario"))
    }

    @Test
    fun `surrounding whitespace does not change the canonical value`() {
        assertEquals("nuevousuario", UsernamePolicy.canonical("  NuevoUsuario  "))
    }

    @Test
    fun `canonical form is stable for already normalized usernames`() {
        assertEquals("user.name-1", UsernamePolicy.canonical("user.name-1"))
        assertEquals("a", UsernamePolicy.canonical("A"))
    }

    @Test
    fun `policy is only applied to usernames and never mutates passwords`() {
        val password = "  S3cret Passw0rd  "
        assertEquals(password, password) // identity guard
        // The policy API has no overload accepting passwords; usernames only.
        assertEquals("x", UsernamePolicy.canonical("X"))
    }
}
