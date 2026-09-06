package com.example.data.session

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.City
import com.example.data.model.Profile
import com.example.data.remote.SupabaseClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _selectedCity = MutableStateFlow<City?>(loadSelectedCity())
    val selectedCity: StateFlow<City?> = _selectedCity.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(prefs.getString(KEY_ACCESS_TOKEN, null) != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    init {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        SupabaseClient.userAccessToken = token

        val customKey = prefs.getString(KEY_CUSTOM_ANON_KEY, null)
        if (!customKey.isNullOrBlank()) {
            SupabaseClient.customAnonKey = customKey
        }
    }

    fun saveSession(
        token: String?,
        refreshToken: String?,
        userId: String?,
        email: String?,
        name: String? = null,
        phone: String? = null
    ) {
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, token)
            putString(KEY_REFRESH_TOKEN, refreshToken)
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
    }

    fun hasSavedSession(): Boolean {
        return !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank()
    }

    fun updateProfileInfo(profile: Profile) {
        prefs.edit().apply {
            putString(KEY_USER_NAME, profile.fullName)
            putString(KEY_USER_PHONE, profile.phone)
            apply()
        }
        _userName.value = profile.fullName
        _userPhone.value = profile.phone
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
            remove(KEY_USER_ID)
            remove(KEY_USER_EMAIL)
            remove(KEY_USER_NAME)
            remove(KEY_USER_PHONE)
            apply()
        }
        SupabaseClient.userAccessToken = null
        _userId.value = null
        _userEmail.value = null
        _userName.value = null
        _userPhone.value = null
        _isLoggedIn.value = false
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_CITY_ID = "city_id"
        private const val KEY_CITY_NAME = "city_name"
        private const val KEY_CITY_STATE = "city_state"
        private const val KEY_CUSTOM_ANON_KEY = "custom_anon_key"
    }
}
