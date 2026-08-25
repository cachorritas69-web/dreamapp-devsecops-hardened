package com.example.appmobile.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.appmobile.data.remote.CloudSleepSession
import com.example.appmobile.data.remote.SleepApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CloudSleepHistoryViewModel : ViewModel() {
    private val _sessions = MutableStateFlow<List<CloudSleepSession>>(emptyList())
    val sessions: StateFlow<List<CloudSleepSession>> = _sessions.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val response = SleepApiClient.apiService.getSleepHistory()
                if (response.isSuccessful && response.body()?.success == true) {
                    _sessions.value = response.body()?.data.orEmpty()
                } else {
                    _error.value = when (response.code()) {
                        401 -> "Tu sesión venció. Inicia sesión nuevamente."
                        else -> "No se pudo cargar el historial (${response.code()})."
                    }
                }
            } catch (_: Exception) {
                _error.value = "No se pudo conectar con DreamApp. Intenta nuevamente."
            } finally {
                _loading.value = false
            }
        }
    }
}
