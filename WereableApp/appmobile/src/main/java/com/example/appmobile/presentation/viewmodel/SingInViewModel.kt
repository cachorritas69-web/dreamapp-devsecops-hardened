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
                val body = response.body()
                val backendUser = body?.data
                if (!response.isSuccessful || body?.success != true || backendUser == null) {
                    _state.update { it.copy(
                        isLoading = false,
                        isSignInSuccessful = false,
                        signInError = body?.error ?: "DreamApp no pudo vincular la cuenta de Google."
                    ) }
                    return@launch
                }
                _state.update { it.copy(
                    uid = backendUser.id,
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

    fun resetState() {
        _state.update { SignInState() }
    }

    fun clearSuccessMessage() {
        _state.update { it.copy(signInSuccessMessage = null) }
    }
}
