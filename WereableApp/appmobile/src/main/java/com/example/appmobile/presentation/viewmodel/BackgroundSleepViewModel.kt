package com.example.appmobile.presentation.viewmodel

import android.app.Application
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.appmobile.data.remote.SleepApiClient
import com.example.appmobile.data.remote.SleepStateUpdateRequest
import com.example.appmobile.presentation.websocket.SleepStateEnum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Sincroniza por HTTPS porque los WebSockets están desactivados en Render. */
class BackgroundSleepViewModel(application: Application) : AndroidViewModel(application) {
    private val api = SleepApiClient.apiService
    private val deviceId = Settings.Secure.getString(
        application.contentResolver,
        Settings.Secure.ANDROID_ID
    ) ?: "android-mobile"

    private val _isSyncEnabled = MutableStateFlow(false)
    val isSyncEnabled: StateFlow<Boolean> = _isSyncEnabled.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _currentSleepState = MutableStateFlow<SleepStateEnum?>(null)
    val currentSleepState: StateFlow<SleepStateEnum?> = _currentSleepState.asStateFlow()

    fun startBackgroundSync(userId: String, userName: String) {
        _isSyncEnabled.value = true
        _isConnected.value = false
        postState(userId, userName, _currentSleepState.value ?: SleepStateEnum.AWAKE)
    }

    fun stopBackgroundSync() {
        _isSyncEnabled.value = false
        _isConnected.value = false
        _currentSleepState.value = null
    }

    fun sendSleepState(userId: String, userName: String, sleepState: SleepStateEnum) {
        if (_isSyncEnabled.value) postState(userId, userName, sleepState)
    }

    fun toggleSync(userId: String, userName: String) {
        if (_isSyncEnabled.value) stopBackgroundSync() else startBackgroundSync(userId, userName)
    }

    private fun postState(userId: String, userName: String, sleepState: SleepStateEnum) {
        viewModelScope.launch {
            try {
                val response = api.updateSleepState(
                    SleepStateUpdateRequest(
                        userId = userId,
                        userName = userName,
                        sleepState = sleepState.name,
                        deviceId = "android_$deviceId"
                    )
                )
                val connected = response.isSuccessful && response.body()?.status == "success"
                _isConnected.value = connected
                if (connected) {
                    _currentSleepState.value = sleepState
                } else {
                    Log.e("BackgroundSleepVM", "REST sync failed: ${response.code()} ${response.errorBody()?.string()}")
                }
            } catch (error: Exception) {
                _isConnected.value = false
                Log.e("BackgroundSleepVM", "REST sync error", error)
            }
        }
    }
}
