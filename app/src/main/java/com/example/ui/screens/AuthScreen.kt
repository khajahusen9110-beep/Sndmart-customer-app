package com.example.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.City
import com.example.data.model.Profile
import com.example.data.remote.SupabaseClient
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.ErrorCard
import com.example.ui.theme.*
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

enum class AuthMode {
    LOGIN,
    SIGNUP,
    VERIFY_OTP
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onAuthSuccess: (hasCity: Boolean) -> Unit,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var authMode by remember { mutableStateOf(AuthMode.LOGIN) }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun registerFcm(userId: String) {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful && task.result != null) {
                    val token = task.result
                    coroutineScope.launch {
                        repository.registerDeviceToken(userId, token)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AuthScreen", "FirebaseMessaging token retrieval error", e)
        }
    }

    fun handleLogin() {
        if (email.isBlank() || password.isBlank()) {
            errorMessage = "Please enter your email and password."
            return
        }
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val res = SupabaseClient.api.loginWithPassword(
                    mapOf("email" to email.trim(), "password" to password)
                )
                if (res.isSuccessful && res.body() != null) {
                    val auth = res.body()!!
                    val uid = auth.user?.id ?: ""
                    val token = auth.accessToken

                    sessionManager.saveSession(
                        token = token,
                        refreshToken = auth.refreshToken,
                        userId = uid,
                        email = email.trim(),
                        name = auth.user?.userMetadata?.get("full_name") as? String
                    )

                    // Fetch profile to check city_id
                    var hasCity = false
                    val pRes = SupabaseClient.api.getProfile("eq.$uid")
                    if (pRes.isSuccessful && !pRes.body().isNullOrEmpty()) {
                        val profile = pRes.body()!!.first()
                        sessionManager.updateProfileInfo(profile)
                        if (!profile.cityId.isNullOrBlank()) {
                            hasCity = true
                            // Try to resolve city
                            val cRes = repository.getActiveCities()
                            if (cRes.isSuccess) {
                                val match = cRes.getOrNull()?.find { it.id == profile.cityId }
                                if (match != null) sessionManager.setSelectedCity(match)
                            }
                        }
                    }

                    registerFcm(uid)
                    isLoading = false
                    onAuthSuccess(hasCity)
                } else {
                    isLoading = false
                    errorMessage = SupabaseClient.parseErrorMessage(res)
                }
            } catch (e: Exception) {
                isLoading = false
                errorMessage = e.message ?: "Authentication failed."
            }
        }
    }

    fun handleSignup() {
        if (email.isBlank() || password.isBlank()) {
            errorMessage = "Please enter an email and password."
            return
        }
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val body = mutableMapOf<String, Any>(
                    "email" to email.trim(),
                    "password" to password,
                    "data" to mapOf(
                        "full_name" to fullName.trim(),
                        "phone" to phone.trim()
                    )
                )
                val res = SupabaseClient.api.signup(body)
                if (res.isSuccessful && res.body() != null) {
                    val auth = res.body()!!
                    val uid = auth.user?.id ?: ""
                    val token = auth.accessToken

                    // If session returned directly without OTP requirement
                    if (token != null) {
                        sessionManager.saveSession(
                            token = token,
                            refreshToken = auth.refreshToken,
                            userId = uid,
                            email = email.trim(),
                            name = fullName.trim()
                        )
                        registerFcm(uid)
                        isLoading = false
                        onAuthSuccess(false) // Prompt city picker next
                    } else {
                        // Switch to OTP verification
                        isLoading = false
                        authMode = AuthMode.VERIFY_OTP
                        snackbarHostState.showSnackbar("Verification code sent to $email")
                    }
                } else {
                    isLoading = false
                    errorMessage = SupabaseClient.parseErrorMessage(res)
                }
            } catch (e: Exception) {
                isLoading = false
                errorMessage = e.message ?: "Signup request failed."
            }
        }
    }

    fun handleVerifyOtp() {
        if (otpCode.isBlank()) {
            errorMessage = "Please enter the verification code."
            return
        }
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val res = SupabaseClient.api.verifyOtp(
                    mapOf(
                        "type" to "signup",
                        "token" to otpCode.trim(),
                        "email" to email.trim()
                    )
                )
                if (res.isSuccessful && res.body() != null) {
                    val auth = res.body()!!
                    val uid = auth.user?.id ?: ""
                    val token = auth.accessToken

                    sessionManager.saveSession(
                        token = token,
                        refreshToken = auth.refreshToken,
                        userId = uid,
                        email = email.trim(),
                        name = fullName.trim()
                    )

                    registerFcm(uid)
                    isLoading = false
                    onAuthSuccess(false) // prompt city picker
                } else {
                    isLoading = false
                    errorMessage = SupabaseClient.parseErrorMessage(res)
                }
            } catch (e: Exception) {
                isLoading = false
                errorMessage = e.message ?: "Verification failed."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (authMode == AuthMode.SIGNUP) "Create Account" else if (authMode == AuthMode.VERIFY_OTP) "Verify OTP" else "Welcome Back", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = PastelSage,
                modifier = Modifier.size(76.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.ShoppingBag,
                        contentDescription = null,
                        tint = NaturalPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Sndmart Customer",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = NaturalPrimary
            )
            Text(
                text = "Fresh Groceries, Fruits & Hotel Dining",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (errorMessage != null) {
                ErrorCard(message = errorMessage!!, onRetry = { errorMessage = null })
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (authMode == AuthMode.VERIFY_OTP) {
                OutlinedTextField(
                    value = otpCode,
                    onValueChange = { otpCode = it },
                    label = { Text("Enter OTP Code") },
                    modifier = Modifier.fillMaxWidth().testTag("otp_code_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { handleVerifyOtp() },
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("verify_otp_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    enabled = !isLoading,
                    shape = RoundedCornerShape(24.dp)
                ) {
                    if (isLoading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    else Text("Verify & Continue", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = { authMode = AuthMode.LOGIN }) {
                    Text("Back to Sign In", color = NaturalPrimary)
                }
            } else {
                if (authMode == AuthMode.SIGNUP) {
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        label = { Text("Full Name") },
                        modifier = Modifier.fillMaxWidth().testTag("auth_name_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Mobile Phone") },
                        modifier = Modifier.fillMaxWidth().testTag("auth_phone_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Address") },
                    modifier = Modifier.fillMaxWidth().testTag("auth_email_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("auth_password_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (authMode == AuthMode.LOGIN) handleLogin()
                        else handleSignup()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("auth_submit_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    enabled = !isLoading,
                    shape = RoundedCornerShape(24.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            text = if (authMode == AuthMode.LOGIN) "Sign In" else "Create Account",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Switch between Login and Signup
                TextButton(
                    onClick = {
                        errorMessage = null
                        authMode = if (authMode == AuthMode.LOGIN) AuthMode.SIGNUP else AuthMode.LOGIN
                    },
                    modifier = Modifier.testTag("switch_auth_mode_button")
                ) {
                    Text(
                        text = if (authMode == AuthMode.LOGIN) "Don't have an account? Sign Up"
                        else "Already have an account? Sign In",
                        color = NaturalPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
