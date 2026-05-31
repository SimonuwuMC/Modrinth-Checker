package com.example.data
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
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

    @GET("search")
    suspend fun searchProjects(
        @Query("query") query: String,
        @Query("limit") limit: Int = 20
    ): SearchResponse

    companion object {
        private const val BASE_URL = "https://api.modrinth.com/v2/"

        private fun isNetworkAvailable(context: Context): Boolean {
            return try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val nw = connectivityManager.activeNetwork ?: return false
                val actNw = connectivityManager.getNetworkCapabilities(nw) ?: return false
                when {
                    actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                    actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                    actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                    else -> false
                }
            } catch (e: Exception) {
                // Default to true to allow online request fallback in case of security exceptions or other system errors
                true
            }
        }

        fun create(context: Context): ModrinthApiService {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            // Set up a 15MB Cache directory for HTTP requests
            val cacheSize = 15 * 1024 * 1024
            val cacheDir = File(context.cacheDir, "http_cache")
            val cache = Cache(cacheDir, cacheSize.toLong())

            val okHttpClient = OkHttpClient.Builder()
                .cache(cache)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                // Offline Interceptor
                .addInterceptor { chain ->
                    var request = chain.request()
                    if (!isNetworkAvailable(context)) {
                        request = request.newBuilder()
                            .header("Cache-Control", "public, only-if-cached, max-stale=86400") // 1 day stale fallback
                            .build()
                    }
                    chain.proceed(request)
                }
                // Custom User-Agent Header
                .addInterceptor { chain ->
                    val original = chain.request()
                    val request = original.newBuilder()
                        .header("User-Agent", "simonuwu-modrinth-notifier/1.0.0 (contact@simonuwu.com)")
                        .method(original.method, original.body)
                        .build()
                    chain.proceed(request)
                }
                // Network cache override: caches GET success responses for 10 minutes even when server doesn't instruct
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val response = chain.proceed(request)
                    if (request.method == "GET" && response.isSuccessful) {
                        response.newBuilder()
                            .header("Cache-Control", "public, max-age=600") // 10 minutes cache
                            .removeHeader("Pragma")
                            .build()
                    } else {
                        response
                    }
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
