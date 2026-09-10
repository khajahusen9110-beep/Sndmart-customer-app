package com.example.data.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.model.City
import com.example.data.model.Profile
import com.example.data.remote.SessionTokenProvider
import com.example.data.remote.SupabaseClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class UserSessionManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("sndmart_session", Context.MODE_PRIVATE)

    private val _userId = MutableStateFlow<String?>(prefs.getString(KEY_USER_ID, null))
    val userId: StateFlow<String?> = _userId.asStateFlow()

    private val _userEmail = MutableStateFlow<String?>(prefs.getString(KEY_USER_EMAIL, null))
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    private val _userName = MutableStateFlow<String?>(prefs.getString(KEY_USER_NAME, null))
    val userName: StateFlow<String?> = _userName.asStateFlow()

    private val _userPhone = MutableStateFlow<String?>(prefs.getString(KEY_USER_PHONE, null))
    val userPhone: StateFlow<String?> = _userPhone.asStateFlow()

    private val _userRole = MutableStateFlow<String?>(prefs.getString(KEY_USER_ROLE, "customer"))
    val userRole: StateFlow<String?> = _userRole.asStateFlow()

    private val _selectedCity = MutableStateFlow<City?>(loadSelectedCity())
    val selectedCity: StateFlow<City?> = _selectedCity.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(prefs.getString(KEY_ACCESS_TOKEN, null) != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _sessionExpiredEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val sessionExpiredEvent: SharedFlow<String> = _sessionExpiredEvent.asSharedFlow()

    private val _sessionExpiredMessage = MutableStateFlow<String?>(null)
    val sessionExpiredMessage: StateFlow<String?> = _sessionExpiredMessage.asStateFlow()

    init {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        SupabaseClient.userAccessToken = token

        val customKey = prefs.getString(KEY_CUSTOM_ANON_KEY, null)
        if (!customKey.isNullOrBlank()) {
            SupabaseClient.customAnonKey = customKey
        }

        SupabaseClient.sessionTokenProvider = object : SessionTokenProvider {
            override fun getAccessToken(): String? = this@UserSessionManager.getAccessToken()
            override fun getRefreshToken(): String? = this@UserSessionManager.getRefreshToken()
            override fun onTokensUpdated(accessToken: String, refreshToken: String?, expiresInSeconds: Long?) {
                this@UserSessionManager.updateTokens(accessToken, refreshToken, expiresInSeconds)
            }
            override fun onSessionExpired(message: String) {
                this@UserSessionManager.notifySessionExpired(message)
            }
        }
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun getExpiresAtMs(): Long? {
        val stored = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (stored > 0L) return stored
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        val jwtExp = extractJwtExpiration(token)
        if (jwtExp != null) {
            prefs.edit().putLong(KEY_EXPIRES_AT, jwtExp).apply()
            return jwtExp
        }
        return null
    }

    fun isSessionExpiredOrExpiringSoon(bufferMs: Long = 120_000L): Boolean {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        if (token.isNullOrBlank()) return true
        val expiresAt = getExpiresAtMs() ?: return false
        return System.currentTimeMillis() + bufferMs >= expiresAt
    }

    fun updateTokens(token: String, refreshToken: String?, expiresInSeconds: Long? = null) {
        val calculatedExpiresAt = if (expiresInSeconds != null && expiresInSeconds > 0) {
            System.currentTimeMillis() + (expiresInSeconds * 1000L)
        } else {
            extractJwtExpiration(token) ?: (System.currentTimeMillis() + 3600_000L)
        }

        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, token)
            if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_EXPIRES_AT, calculatedExpiresAt)
            apply()
        }
        SupabaseClient.userAccessToken = token
        _isLoggedIn.value = true
        _sessionExpiredMessage.value = null
    }

    fun notifySessionExpired(message: String = "Your session expired, please log in again") {
        Log.w("UserSessionManager", "Session expired: $message")
        prefs.edit().apply {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_EXPIRES_AT)
            apply()
        }
        SupabaseClient.userAccessToken = null
        _isLoggedIn.value = false
        _sessionExpiredMessage.value = message
        _sessionExpiredEvent.tryEmit(message)
    }

    fun clearSessionExpiredMessage() {
        _sessionExpiredMessage.value = null
    }

    private fun extractJwtExpiration(token: String?): Long? {
        if (token.isNullOrBlank()) return null
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = String(
                android.util.Base64.decode(
                    parts[1],
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                )
            )
            val json = JSONObject(payload)
            val exp = json.optLong("exp", 0L)
            if (exp > 0L) exp * 1000L else null
        } catch (e: Exception) {
            null
        }
    }

    fun saveSession(
        token: String?,
        refreshToken: String?,
        userId: String?,
        email: String?,
        name: String? = null,
        phone: String? = null,
        expiresInSeconds: Long? = null
    ) {
        val calculatedExpiresAt = if (expiresInSeconds != null && expiresInSeconds > 0) {
            System.currentTimeMillis() + (expiresInSeconds * 1000L)
        } else {
            extractJwtExpiration(token) ?: (System.currentTimeMillis() + 3600_000L)
        }

        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, token)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_EXPIRES_AT, calculatedExpiresAt)
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email)
            if (name != null) putString(KEY_USER_NAME, name)
            if (phone != null) putString(KEY_USER_PHONE, phone)
            apply()
        }
        SupabaseClient.userAccessToken = token
        _userId.value = userId
        _userEmail.value = email
        if (name != null) _userName.value = name
        if (phone != null) _userPhone.value = phone
        _isLoggedIn.value = !token.isNullOrBlank()
        _sessionExpiredMessage.value = null
    }

    fun hasSavedSession(): Boolean {
        return !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank()
    }

    fun updateProfileInfo(profile: Profile) {
        prefs.edit().apply {
            putString(KEY_USER_NAME, profile.fullName)
            putString(KEY_USER_PHONE, profile.phone)
            putString(KEY_USER_ROLE, profile.role)
            apply()
        }
        _userName.value = profile.fullName
        _userPhone.value = profile.phone
        _userRole.value = profile.role
    }

    fun setSelectedCity(city: City) {
        prefs.edit().apply {
            putString(KEY_CITY_ID, city.id)
            putString(KEY_CITY_NAME, city.name)
            putString(KEY_CITY_STATE, city.state)
            apply()
        }
        _selectedCity.value = city
    }

    private fun loadSelectedCity(): City? {
        val id = prefs.getString(KEY_CITY_ID, null) ?: return null
        val name = prefs.getString(KEY_CITY_NAME, null) ?: return null
        val state = prefs.getString(KEY_CITY_STATE, null)
        return City(id = id, name = name, state = state, status = "active")
    }

    fun saveCustomAnonKey(key: String) {
        prefs.edit().putString(KEY_CUSTOM_ANON_KEY, key).apply()
        SupabaseClient.customAnonKey = key
    }

    fun getCustomAnonKey(): String? {
        return prefs.getString(KEY_CUSTOM_ANON_KEY, null)
    }

    fun logout() {
        prefs.edit().apply {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_EXPIRES_AT)
            remove(KEY_USER_ID)
            remove(KEY_USER_EMAIL)
            remove(KEY_USER_NAME)
            remove(KEY_USER_PHONE)
            remove(KEY_USER_ROLE)
            apply()
        }
        SupabaseClient.userAccessToken = null
        _userId.value = null
        _userEmail.value = null
        _userName.value = null
        _userPhone.value = null
        _userRole.value = "customer"
        _isLoggedIn.value = false
        _sessionExpiredMessage.value = null
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_USER_ROLE = "user_role"
        private const val KEY_CITY_ID = "city_id"
        private const val KEY_CITY_NAME = "city_name"
        private const val KEY_CITY_STATE = "city_state"
        private const val KEY_CUSTOM_ANON_KEY = "custom_anon_key"
    }
}
