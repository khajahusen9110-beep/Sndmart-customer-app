package com.example.data.remote

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object SupabaseClient {
    private const val TAG = "SupabaseClient"

    @Volatile
    var customAnonKey: String? = null

    @Volatile
    var userAccessToken: String? = null

    fun isKeyConfigured(): Boolean {
        val key = getEffectiveAnonKey()
        return key.isNotBlank() &&
                !key.endsWith(".placeholder", ignoreCase = true) &&
                !key.contains("placeholder", ignoreCase = true) &&
                key != "DEFAULT_ANON_KEY"
    }

    fun getEffectiveAnonKey(): String {
        val custom = customAnonKey?.takeIf { it.isNotBlank() }
        if (custom != null) return custom

        // Check if injected via BuildConfig (Secrets Gradle plugin from AI Studio secrets panel)
        val buildConfigKey = try {
            val field = com.example.BuildConfig::class.java.getField("SUPABASE_ANON_KEY")
            (field.get(null) as? String)?.takeIf {
                it.isNotBlank() &&
                        !it.contains("placeholder", ignoreCase = true) &&
                        it != "DEFAULT_ANON_KEY"
            }
        } catch (e: Exception) {
            null
        }
        if (buildConfigKey != null) return buildConfigKey

        return SupabaseConfig.DEFAULT_ANON_KEY
    }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val anonKey = getEffectiveAnonKey()
        val token = userAccessToken?.takeIf { it.isNotBlank() } ?: anonKey

        val requestBuilder = original.newBuilder()
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .method(original.method, original.body)

        val request = requestBuilder.build()
        val response = chain.proceed(request)

        if (!response.isSuccessful) {
            Log.w(TAG, "Request to ${request.url} returned HTTP ${response.code}: ${response.message}")
        }
        response
    }

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d(TAG, message)
    }.apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    val api: SupabaseApi by lazy {
        Retrofit.Builder()
            .baseUrl("${SupabaseConfig.BASE_URL}/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SupabaseApi::class.java)
    }

    fun parseErrorMessage(response: retrofit2.Response<*>): String {
        return try {
            val errorBody = response.errorBody()?.string()
            if (!errorBody.isNullOrBlank()) {
                try {
                    val json = JSONObject(errorBody)
                    val msg = json.optString("message", json.optString("msg", json.optString("error_description", "")))
                    val hint = json.optString("hint", "")
                    val details = json.optString("details", "")
                    buildString {
                        if (msg.isNotBlank()) append(msg) else append(errorBody)
                        if (hint.isNotBlank()) append(" (Hint: $hint)")
                        if (details.isNotBlank()) append(" [$details]")
                    }
                } catch (e: Exception) {
                    errorBody
                }
            } else {
                "HTTP ${response.code()}: ${response.message()}"
            }
        } catch (e: Exception) {
            "Error HTTP ${response.code()}: ${response.message()}"
        }
    }
}
