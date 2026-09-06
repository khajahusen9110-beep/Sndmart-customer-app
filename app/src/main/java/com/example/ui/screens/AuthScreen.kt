package com.example.ui.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.data.model.Profile
import com.example.data.remote.SupabaseClient
import com.example.data.remote.SupabaseConfig
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.ErrorCard
import com.example.ui.theme.*
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
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
    onAuthSuccess: () -> Unit,
    onBack: () -> Unit,
    canGoBack: Boolean = true
) {
    val context = LocalContext.current
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
            val app = com.google.firebase.FirebaseApp.getInstance()
            val apiKey = app.options.apiKey
            val isFake = apiKey.contains("FakeKey", ignoreCase = true) ||
                         apiKey.contains("placeholder", ignoreCase = true) ||
                         app.options.gcmSenderId == "123456789012"
            if (isFake) {
                Log.d("AuthScreen", "Firebase credentials are placeholders; skipping FCM registration.")
                return
            }
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful && task.result != null) {
                    val token = task.result
                    coroutineScope.launch {
                        repository.registerDeviceToken(userId, token)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("AuthScreen", "FirebaseMessaging token retrieval skipped: ${e.message}")
        }
    }

    // Ensures profiles table row exists for this customer user ID (role = 'customer')
    suspend fun ensureCustomerProfile(
        userId: String,
        fullName: String?,
        phone: String?,
        email: String?
    ) {
        try {
            val checkRes = SupabaseClient.api.getProfile("eq.$userId")
            if (checkRes.isSuccessful && !checkRes.body().isNullOrEmpty()) {
                val existing = checkRes.body()!!.first()
                val updates = mutableMapOf<String, Any>()
                if (existing.fullName.isNullOrBlank() && !fullName.isNullOrBlank()) updates["full_name"] = fullName
                if (existing.phone.isNullOrBlank() && !phone.isNullOrBlank()) updates["phone"] = phone
                if (existing.email.isNullOrBlank() && !email.isNullOrBlank()) updates["email"] = email
                if (updates.isNotEmpty()) {
                    val patchRes = SupabaseClient.api.updateProfile("eq.$userId", updates)
                    if (patchRes.isSuccessful && !patchRes.body().isNullOrEmpty()) {
                        sessionManager.updateProfileInfo(patchRes.body()!!.first())
                    } else {
                        sessionManager.updateProfileInfo(existing)
                    }
                } else {
                    sessionManager.updateProfileInfo(existing)
                }
            } else {
                // Insert new row in profiles: id = user.id, role = 'customer', full_name, phone, email
                val newProfile = Profile(
                    id = userId,
                    role = "customer",
                    fullName = fullName?.ifBlank { null },
                    phone = phone?.ifBlank { null },
                    email = email?.ifBlank { null }
                )
                val createRes = SupabaseClient.api.createProfile(newProfile)
                if (createRes.isSuccessful && !createRes.body().isNullOrEmpty()) {
                    sessionManager.updateProfileInfo(createRes.body()!!.first())
                } else {
                    sessionManager.updateProfileInfo(newProfile)
                }
            }
        } catch (e: Exception) {
            Log.e("AuthScreen", "Exception ensuring profiles row: ${e.message}", e)
            sessionManager.updateProfileInfo(
                Profile(
                    id = userId,
                    role = "customer",
                    fullName = fullName,
                    phone = phone,
                    email = email
                )
            )
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
                    val resolvedName = auth.user?.userMetadata?.get("full_name") as? String
                    val resolvedPhone = auth.user?.userMetadata?.get("phone") as? String

                    // Save session token in memory + persistent storage
                    sessionManager.saveSession(
                        token = token,
                        refreshToken = auth.refreshToken,
                        userId = uid,
                        email = email.trim(),
                        name = resolvedName,
                        phone = resolvedPhone
                    )

                    // Ensure profiles row exists
                    ensureCustomerProfile(
                        userId = uid,
                        fullName = resolvedName,
                        phone = resolvedPhone,
                        email = email.trim()
                    )

                    registerFcm(uid)
                    isLoading = false
                    // Navigate to Home tab
                    onAuthSuccess()
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
        if (fullName.isBlank()) {
            errorMessage = "Please enter your full name."
            return
        }
        if (email.isBlank() || password.isBlank()) {
            errorMessage = "Please enter an email and password."
            return
        }
        if (password.length < 6) {
            errorMessage = "Password should be at least 6 characters."
            return
        }
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val body = mapOf<String, Any>(
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

                    if (token != null) {
                        // Direct session returned without OTP verification required
                        sessionManager.saveSession(
                            token = token,
                            refreshToken = auth.refreshToken,
                            userId = uid,
                            email = email.trim(),
                            name = fullName.trim(),
                            phone = phone.trim()
                        )

                        // Insert profiles row: id = user.id, role = 'customer', full_name, phone, email
                        ensureCustomerProfile(
                            userId = uid,
                            fullName = fullName.trim(),
                            phone = phone.trim(),
                            email = email.trim()
                        )

                        registerFcm(uid)
                        isLoading = false
                        // Navigate to Home tab
                        onAuthSuccess()
                    } else {
                        // Switch to OTP verification input screen
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

                    // Save session
                    sessionManager.saveSession(
                        token = token,
                        refreshToken = auth.refreshToken,
                        userId = uid,
                        email = email.trim(),
                        name = fullName.trim(),
                        phone = phone.trim()
                    )

                    // On success, insert profiles row: id = user.id, role = 'customer', full_name, phone, email
                    ensureCustomerProfile(
                        userId = uid,
                        fullName = fullName.trim(),
                        phone = phone.trim(),
                        email = email.trim()
                    )

                    registerFcm(uid)
                    isLoading = false
                    // Navigate to Home tab
                    onAuthSuccess()
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

    fun handleGoogleSignIn() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val credentialManager = CredentialManager.create(context)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(SupabaseConfig.googleWebClientId)
                    .setAutoSelectEnabled(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(
                    request = request,
                    context = context
                )

                val credential = result.credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken
                    val googleEmail = googleIdTokenCredential.id
                    val displayName = googleIdTokenCredential.displayName

                    // Exchange ID token with Supabase: POST /auth/v1/token?grant_type=id_token with {provider: "google", id_token: googleIdToken}
                    val exchangeRes = SupabaseClient.api.loginWithGoogleIdToken(
                        mapOf("provider" to "google", "id_token" to idToken)
                    )

                    if (exchangeRes.isSuccessful && exchangeRes.body() != null) {
                        val auth = exchangeRes.body()!!
                        val uid = auth.user?.id ?: ""
                        val token = auth.accessToken
                        val resolvedEmail = auth.user?.email ?: googleEmail
                        val resolvedName = (auth.user?.userMetadata?.get("full_name") as? String) ?: displayName ?: "Google User"

                        // Save session
                        sessionManager.saveSession(
                            token = token,
                            refreshToken = auth.refreshToken,
                            userId = uid,
                            email = resolvedEmail,
                            name = resolvedName
                        )

                        // Check if profiles row exists; if not, insert one with role='customer', full_name and email
                        ensureCustomerProfile(
                            userId = uid,
                            fullName = resolvedName,
                            phone = null,
                            email = resolvedEmail
                        )

                        registerFcm(uid)
                        isLoading = false
                        // Navigate to Home tab
                        onAuthSuccess()
                    } else {
                        isLoading = false
                        errorMessage = SupabaseClient.parseErrorMessage(exchangeRes)
                    }
                } else {
                    isLoading = false
                    errorMessage = "Unexpected Google credential received."
                }
            } catch (e: GetCredentialCancellationException) {
                // User dismissed Google sign-in dialog
                isLoading = false
                Log.d("AuthScreen", "Google Sign-In cancelled by user.")
            } catch (e: GetCredentialException) {
                isLoading = false
                Log.w("AuthScreen", "CredentialManager exception: ${e.message}")
                errorMessage = "${e.message ?: "Google Sign-In failed"}. Please use Email and Password or configure Google Play Services."
            } catch (e: Exception) {
                isLoading = false
                Log.e("AuthScreen", "Google Sign-In error: ${e.message}", e)
                errorMessage = e.message ?: "Google Sign-In failed."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (authMode) {
                            AuthMode.SIGNUP -> "Create Account"
                            AuthMode.VERIFY_OTP -> "Verify Email OTP"
                            AuthMode.LOGIN -> "Welcome Back"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
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
                .padding(horizontal = 24.dp, vertical = 16.dp),
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
                Text(
                    text = "A 6-digit confirmation code was sent to $email. Enter it below to complete your registration.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = otpCode,
                    onValueChange = { otpCode = it },
                    label = { Text("Enter OTP Code") },
                    placeholder = { Text("e.g. 123456") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("otp_code_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = { handleVerifyOtp() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("verify_otp_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    enabled = !isLoading,
                    shape = RoundedCornerShape(24.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text("Verify & Continue", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(
                    onClick = {
                        errorMessage = null
                        authMode = AuthMode.LOGIN
                    }
                ) {
                    Text("Back to Sign In", color = NaturalPrimary, fontWeight = FontWeight.SemiBold)
                }
            } else {
                if (authMode == AuthMode.SIGNUP) {
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        label = { Text("Full Name") },
                        placeholder = { Text("John Doe") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_name_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Mobile Phone") },
                        placeholder = { Text("+91 9876543210") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_phone_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Address") },
                    placeholder = { Text("you@example.com") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auth_email_input"),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auth_password_input"),
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

                // OR Divider
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "OR",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Google Sign-In button
                OutlinedButton(
                    onClick = { handleGoogleSignIn() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("google_signin_button"),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    enabled = !isLoading
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "Google",
                            tint = Color(0xFF4285F4),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Continue with Google",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Switch between Login and Signup modes
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
