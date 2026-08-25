package team.dreamapp.com.domain.services.auth

import java.util.Locale

/**
 * Canonical username representation shared by registration and login so that
 * "NuevoUsuario" and "nuevousuario" refer to the same account.
 * Never apply this policy to passwords.
 */
object UsernamePolicy {
    fun canonical(raw: String): String = raw.trim().lowercase(Locale.ROOT)
}
