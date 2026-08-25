package presentation.auth

import team.dreamapp.com.presentation.auth.AccessTokenShape
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessTokenShapeTest {
    private fun opaqueToken(): String {
        val raw = ByteArray(32)
        java.security.SecureRandom().nextBytes(raw)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    @Test
    fun `opaque dreamapp tokens are not mistaken for firebase id tokens`() {
        assertFalse(AccessTokenShape.looksLikeFirebaseIdToken(opaqueToken()))
    }

    @Test
    fun `three segment tokens are recognized as jwt shaped`() {
        assertTrue(AccessTokenShape.looksLikeFirebaseIdToken("header.payload.signature"))
    }

    @Test
    fun `null blank or two segment tokens are rejected`() {
        assertFalse(AccessTokenShape.looksLikeFirebaseIdToken(null))
        assertFalse(AccessTokenShape.looksLikeFirebaseIdToken(""))
        assertFalse(AccessTokenShape.looksLikeFirebaseIdToken("   "))
        assertFalse(AccessTokenShape.looksLikeFirebaseIdToken("only.one"))
    }
}
