package com.example.appmobile.domain.usecase

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.example.appmobile.R
import com.example.appmobile.domain.model.SignInResult
import com.example.appmobile.domain.model.UserData
import com.example.appmobile.data.database.DatabaseCleaner
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.BeginSignInRequest.GoogleIdTokenRequestOptions
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import android.util.Log
import com.google.android.gms.common.api.ApiException

class GoogleAuthUiClient(
    private val context: Context,
    private val oneTapClient: SignInClient
) {
    private val auth = Firebase.auth
    private val databaseCleaner = DatabaseCleaner(context)
    var lastSignInError: String? = null
        private set

    suspend fun signIn(): IntentSender? {
        lastSignInError = null
        val result = try {
            oneTapClient.beginSignIn(
                buildSignInRequest()
            ).await()
        } catch(e: Exception) {
            if(e is CancellationException) throw e
            val statusCode = (e as? ApiException)?.statusCode
            lastSignInError = when (statusCode) {
                10 -> "Google rechazó la configuración de esta app (código 10). Falta registrar la firma SHA en Firebase."
                16 -> "No hay una cuenta de Google disponible o se canceló la selección (código 16)."
                else -> "Google Sign-In falló${statusCode?.let { " (código $it)" } ?: ""}: ${e.localizedMessage ?: "error desconocido"}"
            }
            Log.e("GoogleAuthUiClient", lastSignInError, e)
            null
        }
        return result?.pendingIntent?.intentSender
    }

    suspend fun signInWithIntent(intent: Intent): SignInResult {
        return try {
            val credential = oneTapClient.getSignInCredentialFromIntent(intent)
            val googleIdToken = credential.googleIdToken
                ?: return SignInResult(data = null, errorMessage = "Google no devolvió un token de identidad.")
            val googleCredentials = GoogleAuthProvider.getCredential(googleIdToken, null)
            val user = auth.signInWithCredential(googleCredentials).await().user
            SignInResult(
                data = user?.run {
                    UserData(
                        userId = uid,
                        username = displayName,
                        profilePictureUrl = photoUrl?.toString()
                    )
                },
                errorMessage = null
            )
        } catch(e: Exception) {
            if(e is CancellationException) throw e
            val statusCode = (e as? ApiException)?.statusCode
            Log.e("GoogleAuthUiClient", "No se pudo completar Google Sign-In", e)
            SignInResult(
                data = null,
                errorMessage = statusCode?.let { "No se pudo completar Google Sign-In (código $it)." }
                    ?: (e.localizedMessage ?: "No se pudo autenticar con Firebase.")
            )
        }
    }

    suspend fun signOut() {
        try {
            // Limpiar bases de datos locales antes del sign out
            databaseCleaner.clearAllDatabases()
            
            // Hacer sign out de Google
            oneTapClient.signOut().await()
            auth.signOut()
        } catch(e: Exception) {
            e.printStackTrace()
            if(e is CancellationException) throw e
        }
    }

    fun getSignedInUser(): UserData? = auth.currentUser?.run {
        UserData(
            userId = uid,
            username = displayName,
            profilePictureUrl = photoUrl?.toString()
        )
    }

    private fun buildSignInRequest(): BeginSignInRequest {
        return BeginSignInRequest.Builder()
            .setGoogleIdTokenRequestOptions(
                GoogleIdTokenRequestOptions.builder()
                    .setSupported(true)
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(context.getString(R.string.default_web_client_id))
                    .build()
            )
            .setAutoSelectEnabled(true)
            .build()
    }
}
