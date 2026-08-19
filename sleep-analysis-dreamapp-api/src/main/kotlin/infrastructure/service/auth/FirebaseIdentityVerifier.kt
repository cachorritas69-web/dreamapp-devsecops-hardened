package team.dreamapp.com.infrastructure.service.auth

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import org.slf4j.LoggerFactory

data class VerifiedGoogleIdentity(
    val uid: String,
    val email: String,
    val displayName: String
)

object FirebaseIdentityVerifier {
    private val logger = LoggerFactory.getLogger(FirebaseIdentityVerifier::class.java)

    private val auth: FirebaseAuth? by lazy {
        val projectId = System.getenv("FIREBASE_PROJECT_ID")?.trim().orEmpty()
        if (projectId.isBlank()) {
            logger.error("FIREBASE_PROJECT_ID is required for Google authentication")
            null
        } else {
            val app = FirebaseApp.getApps().firstOrNull() ?: FirebaseApp.initializeApp(
                FirebaseOptions.builder().setProjectId(projectId).build()
            )
            FirebaseAuth.getInstance(app)
        }
    }

    fun verify(idToken: String?): VerifiedGoogleIdentity? {
        if (idToken.isNullOrBlank() || idToken.length > 8_192) return null
        return try {
            val token = auth?.verifyIdToken(idToken) ?: return null
            val email = token.email?.trim()?.lowercase().orEmpty()
            if (!token.isEmailVerified || email.isBlank()) return null
            VerifiedGoogleIdentity(token.uid, email, token.name?.trim().orEmpty())
        } catch (ex: Exception) {
            logger.warn("Firebase ID token verification failed: {}", ex.javaClass.simpleName)
            null
        }
    }
}
