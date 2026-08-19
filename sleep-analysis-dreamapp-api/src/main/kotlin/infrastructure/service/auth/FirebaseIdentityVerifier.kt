package team.dreamapp.com.infrastructure.service.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

data class VerifiedGoogleIdentity(val uid: String, val email: String, val displayName: String)

/** Verifies Firebase tokens with Google's public keys; no service-account secret is needed. */
object FirebaseIdentityVerifier {
    private const val CERTIFICATES_URL = "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"
    private val logger = LoggerFactory.getLogger(FirebaseIdentityVerifier::class.java)
    private val json = jacksonObjectMapper()
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val decoder = Base64.getUrlDecoder()
    @Volatile private var certificates: Map<String, X509Certificate> = emptyMap()
    @Volatile private var certificatesExpireAt = Instant.EPOCH

    fun verify(idToken: String?): VerifiedGoogleIdentity? {
        if (idToken.isNullOrBlank() || idToken.length > 8_192) return null
        val projectId = System.getenv("FIREBASE_PROJECT_ID")?.trim().orEmpty()
        if (projectId.isBlank()) {
            logger.error("FIREBASE_PROJECT_ID is required for Google authentication")
            return null
        }
        return try {
            val pieces = idToken.split('.')
            require(pieces.size == 3) { "Malformed token" }
            val header = decodeJson(pieces[0])
            val claims = decodeJson(pieces[1])
            require(header.path("alg").asText() == "RS256") { "Unexpected algorithm" }
            val kid = header.path("kid").asText()
            require(kid.isNotBlank()) { "Missing key id" }
            val certificate = currentCertificates()[kid] ?: refreshCertificates(force = true)[kid]
                ?: error("Unknown signing key")
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(certificate.publicKey)
            verifier.update("${pieces[0]}.${pieces[1]}".toByteArray(Charsets.US_ASCII))
            require(verifier.verify(decoder.decode(pieces[2]))) { "Invalid signature" }

            val now = Instant.now().epochSecond
            require(claims.path("aud").asText() == projectId) { "Invalid audience" }
            require(claims.path("iss").asText() == "https://securetoken.google.com/$projectId") { "Invalid issuer" }
            require(claims.path("exp").asLong(0) > now) { "Expired token" }
            require(claims.path("iat").asLong(Long.MAX_VALUE) <= now + 60) { "Invalid issue time" }
            val uid = claims.path("sub").asText()
            val email = claims.path("email").asText().trim().lowercase()
            require(uid.isNotBlank() && uid.length <= 128) { "Invalid subject" }
            require(email.isNotBlank() && claims.path("email_verified").asBoolean(false)) { "Email is not verified" }
            VerifiedGoogleIdentity(uid, email, claims.path("name").asText().trim())
        } catch (ex: Exception) {
            logger.warn("Firebase ID token verification failed: {}", ex.message ?: ex.javaClass.simpleName)
            null
        }
    }

    private fun decodeJson(value: String): JsonNode = json.readTree(decoder.decode(value))

    private fun currentCertificates(): Map<String, X509Certificate> =
        if (Instant.now().isBefore(certificatesExpireAt) && certificates.isNotEmpty()) certificates
        else refreshCertificates(force = false)

    @Synchronized
    private fun refreshCertificates(force: Boolean): Map<String, X509Certificate> {
        if (!force && Instant.now().isBefore(certificatesExpireAt) && certificates.isNotEmpty()) return certificates
        http.newCall(Request.Builder().url(CERTIFICATES_URL).get().build()).execute().use { response ->
            require(response.isSuccessful) { "Could not obtain Google signing certificates" }
            val fields = json.readTree(response.body?.string() ?: error("Empty certificate response")).fields()
            val values = fields.asSequence().associate { (kid, pem) ->
                kid to (CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(pem.asText().toByteArray())) as X509Certificate)
            }
            require(values.isNotEmpty()) { "No signing certificates returned" }
            val maxAge = response.header("Cache-Control")?.split(',')?.map(String::trim)
                ?.firstOrNull { it.startsWith("max-age=") }?.substringAfter('=')?.toLongOrNull() ?: 3600
            certificates = values
            certificatesExpireAt = Instant.now().plusSeconds(maxAge.coerceIn(300, 86_400))
            return values
        }
    }
}
