package com.example.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

interface ModrinthApiService {
    @GET("project/{idOrSlug}")
    suspend fun getProject(
        @Path("idOrSlug") idOrSlug: String
    ): ProjectResponse

    @GET("project/{idOrSlug}/version")
    suspend fun getProjectVersions(
        @Path("idOrSlug") idOrSlug: String
    ): List<VersionResponse>

    companion object {
        private const val BASE_URL = "https://api.modrinth.com/v2/"

        fun create(): ModrinthApiService {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            // Custom OkHttpClient with User-Agent header
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val original = chain.request()
                    val request = original.newBuilder()
                        .header("User-Agent", "simonuwu-modrinth-notifier/1.0.0 (desousasimon023@gmail.com)")
                        .method(original.method, original.body)
                        .build()
                    chain.proceed(request)
                }
                .addInterceptor(loggingInterceptor)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
                .create(ModrinthApiService::class.java)
        }
    }
}
