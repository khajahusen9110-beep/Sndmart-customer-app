package com.example.data.remote

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class SessionExpiredException(message: String = "Your session expired, please log in again") : Exception(message)

class ApiException(val code: Int, message: String) : Exception("API error $code: $message")

interface SessionTokenProvider {
    fun getAccessToken(): String?
    fun getRefreshToken(): String?
    fun onTokensUpdated(accessToken: String, refreshToken: String?, expiresInSeconds: Long?)
    fun onSessionExpired(message: String = "Your session expired, please log in again")
}

object SupabaseClient {
    private const val TAG = "SupabaseClient"

    @Volatile
    var customAnonKey: String? = null

    @Volatile
    var userAccessToken: String? = null

    @Volatile
    var sessionTokenProvider: SessionTokenProvider? = null

    private val refreshLock = Any()

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

    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++
            prior = prior.priorResponse
        }
        return result
    }

    private val refreshHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun executeSyncTokenRefresh(refreshToken: String): Triple<String, String?, Long?>? {
        return try {
            val anonKey = getEffectiveAnonKey()
            val json = JSONObject().apply {
                put("refresh_token", refreshToken)
            }.toString()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toRequestBody(mediaType)

            val refreshRequest = Request.Builder()
                .url("${SupabaseConfig.BASE_URL}/auth/v1/token?grant_type=refresh_token")
                .header("apikey", anonKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            val response = refreshHttpClient.newCall(refreshRequest).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && !responseBody.isNullOrBlank()) {
                val jsonObj = JSONObject(responseBody)
                val newAccessToken = jsonObj.optString("access_token", "")
                val newRefreshToken = jsonObj.optString("refresh_token", "").takeIf { it.isNotBlank() } ?: refreshToken
                val expiresIn = jsonObj.optLong("expires_in", 3600L)
                if (newAccessToken.isNotBlank()) {
                    Triple(newAccessToken, newRefreshToken, expiresIn)
                } else {
                    null
                }
            } else {
                Log.w(TAG, "Sync token refresh rejected HTTP ${response.code}: $responseBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during sync token refresh: ${e.message}", e)
            null
        }
    }

    private val tokenAuthenticator = Authenticator { _: Route?, response: Response ->
        val url = response.request.url.toString()
        // 1. Do not loop if the failed request itself was an auth endpoint
        if (url.contains("grant_type=refresh_token") || url.contains("/auth/v1/")) {
            return@Authenticator null
        }

        // 2. Prevent infinite retry loops (max 2 attempts)
        if (responseCount(response) >= 2) {
            Log.w(TAG, "Request to ${response.request.url} failed 401 twice; aborting retry.")
            sessionTokenProvider?.onSessionExpired("Your session expired, please log in again")
            return@Authenticator null
        }

        val provider = sessionTokenProvider
        val refreshToken = provider?.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "No refresh token stored; cannot refresh session.")
            provider?.onSessionExpired("Your session expired, please log in again")
            return@Authenticator null
        }

        val originalAuth = response.request.header("Authorization")

        synchronized(refreshLock) {
            val currentToken = userAccessToken
            // If another thread already refreshed the token since this request was initiated:
            if (!currentToken.isNullOrBlank() && "Bearer $currentToken" != originalAuth) {
                Log.d(TAG, "Reusing already-refreshed access token for retry.")
                return@Authenticator response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            Log.i(TAG, "HTTP 401 received: Refreshing Supabase auth session...")
            val refreshResult = executeSyncTokenRefresh(refreshToken)
            if (refreshResult != null) {
                val (newAccessToken, newRefreshToken, expiresIn) = refreshResult
                userAccessToken = newAccessToken
                provider.onTokensUpdated(newAccessToken, newRefreshToken, expiresIn)
                Log.i(TAG, "Session refreshed successfully; retrying request.")
                return@Authenticator response.request.newBuilder()
                    .header("Authorization", "Bearer $newAccessToken")
                    .build()
            } else {
                Log.e(TAG, "Session refresh failed or refresh token expired.")
                provider.onSessionExpired("Your session expired, please log in again")
                return@Authenticator null
            }
        }
    }

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d(TAG, message)
    }.apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
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
