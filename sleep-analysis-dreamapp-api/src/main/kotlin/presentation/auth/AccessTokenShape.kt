package team.dreamapp.com.presentation.auth

/**
 * Shape classification for bearer tokens. DreamApp session tokens are opaque
 * Base64URL strings without dots; Firebase ID tokens are JWTs with exactly two
 * dot separators. Opaque DreamApp tokens must never be sent to the Firebase
 * verifier.
 */
object AccessTokenShape {
    fun looksLikeFirebaseIdToken(token: String?): Boolean =
        !token.isNullOrBlank() && token.count { it == '.' } == 2
}
