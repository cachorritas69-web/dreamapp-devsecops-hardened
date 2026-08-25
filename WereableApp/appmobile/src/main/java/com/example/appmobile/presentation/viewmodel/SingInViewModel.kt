package com.example.appmobile.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.appmobile.domain.model.SignInResult
import com.example.appmobile.domain.model.SignInState
import com.example.appmobile.domain.usecase.GoogleAuthUiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.appmobile.data.remote.DreamAppAuthClient
import com.example.appmobile.data.remote.DreamAppGoogleAuthResponse
import com.example.appmobile.data.remote.DreamAppPasswordLoginRequest
import com.example.appmobile.data.remote.DreamAppSession
import com.google.gson.Gson
import android.util.Log

class SignInViewModel(
    private val googleAuthUiClient: GoogleAuthUiClient,
    @Suppress("UNUSED_PARAMETER") context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(SignInState())
    val state = _state.asStateFlow()
    val isUserSignedIn = googleAuthUiClient.getSignedInUser()

    fun signIn() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, signInError = null) }
            val intentSender = googleAuthUiClient.signIn()
            if (intentSender == null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        signInError = googleAuthUiClient.lastSignInError
                            ?: "No se pudo iniciar sesión con Google."
                    )
                }
                return@launch
            }
            _state.update { it.copy(signInIntentSender = intentSender, isLoading = false) }
        }
    }

    fun onSignInResult(result: SignInResult) {
        val user = result.data
        if (user == null) {
            _state.update {
                it.copy(
                    isLoading = false,
                    isSignInSuccessful = false,
                    signInError = result.errorMessage ?: "No se pudo obtener la cuenta de Google."
                )
            }
            return
        }

        _state.update { it.copy(isLoading = true, signInError = null) }
        viewModelScope.launch {
            try {
                val response = DreamAppAuthClient.api.authenticateGoogle()
                val body = response.body() ?: runCatching {
                    Gson().fromJson(response.errorBody()?.string(), DreamAppGoogleAuthResponse::class.java)
                }.getOrNull()
                val backendUser = body?.data
                if (!response.isSuccessful || body?.success != true || backendUser == null) {
                    Log.e("DreamAppAuth", "Backend rejected Google link with HTTP ${response.code()}")
                    _state.update { it.copy(
                        isLoading = false,
                        isSignInSuccessful = false,
                        signInError = body?.error ?: "DreamApp no pudo vincular la cuenta de Google."
                    ) }
                    return@launch
                }
                body.token?.let(DreamAppSession::start)
                _state.update { it.copy(
                    uid = backendUser.id,
                    username = backendUser.userName,
                    isNewUser = false,
                    isLoading = false,
                    isSignInSuccessful = true,
                    signInError = null,
                    signInIntentSender = null,
                    signInSuccessMessage = "Cuenta de Google vinculada con DreamApp."
                ) }
            } catch (ex: Exception) {
                _state.update { it.copy(
                    isLoading = false,
                    isSignInSuccessful = false,
                    signInError = "No se pudo conectar con DreamApp API: ${ex.localizedMessage ?: "error de red"}"
                ) }
            }
        }
    }

    fun signInWithPassword(userName: String, password: String) {
        val normalizedUserName = userName.trim()
        if (normalizedUserName.isBlank() || password.isBlank()) {
            _state.update { it.copy(signInError = "Escribe tu usuario y contraseña.") }
            return
        }
        _state.update { it.copy(isLoading = true, signInError = null) }
        viewModelScope.launch {
            try {
                val response = DreamAppAuthClient.api.login(
                    DreamAppPasswordLoginRequest(normalizedUserName, password)
                )
                val body = response.body() ?: runCatching {
                    Gson().fromJson(response.errorBody()?.string(), DreamAppGoogleAuthResponse::class.java)
                }.getOrNull()
                val backendUser = body?.data
                val token = body?.token
                if (!response.isSuccessful || body?.success != true || backendUser == null || token.isNullOrBlank()) {
                    _state.update { it.copy(
                        isLoading = false,
                        isSignInSuccessful = false,
                        signInError = if (response.code() == 401) {
                            "Usuario o contraseña incorrectos."
                        } else {
                            body?.error ?: "DreamApp no pudo iniciar la sesión."
                        }
                    ) }
                    return@launch
                }
                DreamAppSession.start(token)
                _state.update { it.copy(
                    uid = backendUser.id,
                    username = backendUser.userName,
                    isNewUser = false,
                    isLoading = false,
                    isSignInSuccessful = true,
                    signInError = null,
                    signInSuccessMessage = "Sesión iniciada correctamente."
                ) }
            } catch (ex: Exception) {
                _state.update { it.copy(
                    isLoading = false,
                    isSignInSuccessful = false,
                    signInError = "No se pudo conectar con DreamApp API: ${ex.localizedMessage ?: "error de red"}"
                ) }
            }
        }
    }

    fun resetState() {
        DreamAppSession.clear()
        _state.update { SignInState() }
    }

    fun clearSuccessMessage() {
        _state.update { it.copy(signInSuccessMessage = null) }
    }
}
