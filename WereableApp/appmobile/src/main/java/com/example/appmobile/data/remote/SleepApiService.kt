package com.example.appmobile.data.remote

import com.example.appmobile.domain.model.SleepDataUpload
import com.example.appmobile.domain.model.SleepUploadResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class SleepStateUpdateRequest(
    val userId: String,
    val userName: String,
    val sleepState: String,
    val deviceId: String
)

data class SleepStateUpdateResponse(
    val status: String? = null,
    val message: String? = null,
    val error: String? = null
)

data class CloudSleepHistoryResponse(
    val success: Boolean,
    val data: List<CloudSleepSession> = emptyList()
)

data class CloudSleepSession(
    val date: String,
    val quality: String,
    val sleepEfficiency: Double,
    val sleepDuration: Int,
    val light: Int,
    val deep: Int,
    val rem: Int,
    val awake: Int,
    val avgHR: Int,
    val avgHRV: Int,
    val awakenings: Int
)

interface SleepApiService {
    @GET("sleep/sessions")
    suspend fun getSleepHistory(): Response<CloudSleepHistoryResponse>

    
    @POST("sleep/sessions")
    suspend fun uploadSleepData(
        @Body sleepData: SleepDataUpload
    ): Response<SleepUploadResponse>

    @POST("sleep/states")
    suspend fun updateSleepState(
        @Body request: SleepStateUpdateRequest
    ): Response<SleepStateUpdateResponse>
}

object SleepApiClient {
    
    private val retrofit = retrofit2.Retrofit.Builder()
        .baseUrl(com.example.appmobile.BuildConfig.API_BASE_URL)
        .client(okhttp3.OkHttpClient.Builder().addInterceptor(FirebaseAuthInterceptor()).build())
        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
        .build()
    
    val apiService: SleepApiService = retrofit.create(SleepApiService::class.java)
    
    // Método para obtener la URL actual (útil para debugging)
    fun getCurrentBaseUrl(): String = com.example.appmobile.BuildConfig.API_BASE_URL
}
