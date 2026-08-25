package com.example.appmobile.data.remote

import com.example.appmobile.BuildConfig
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.POST
import retrofit2.http.Body

data class DreamAppUserInfo(
    val id: String,
    val userName: String,
    val fullname: String
)

data class DreamAppGoogleAuthResponse(
    val success: Boolean,
    val data: DreamAppUserInfo?,
    val token: String?,
    val error: String?
)

data class DreamAppPasswordLoginRequest(
    val userName: String,
    val password: String
)

interface DreamAppAuthApi {
    @POST("auth/login")
    suspend fun login(
        @Body request: DreamAppPasswordLoginRequest
    ): Response<DreamAppGoogleAuthResponse>

    @POST("auth/google")
    suspend fun authenticateGoogle(): Response<DreamAppGoogleAuthResponse>
}

object DreamAppAuthClient {
    val api: DreamAppAuthApi = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(okhttp3.OkHttpClient.Builder().addInterceptor(FirebaseAuthInterceptor()).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(DreamAppAuthApi::class.java)
}
