package com.example.appmobile.data.remote

import com.example.appmobile.BuildConfig
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.POST

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

interface DreamAppAuthApi {
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
