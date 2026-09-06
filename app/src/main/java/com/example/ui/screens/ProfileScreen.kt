package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.City
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onNavigateToCityPicker: () -> Unit,
    onNavigateToWallet: () -> Unit,
    onRequireLogin: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogoutSuccess: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isLoggedIn by sessionManager.isLoggedIn.collectAsState()
    val userEmail by sessionManager.userEmail.collectAsState()
    val userName by sessionManager.userName.collectAsState()
    val userPhone by sessionManager.userPhone.collectAsState()
    val currentCity by sessionManager.selectedCity.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Account", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // User Header Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = PastelSage,
                            modifier = Modifier.size(60.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = NaturalPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            if (isLoggedIn) {
                                Text(
                                    text = userName?.takeIf { it.isNotBlank() } ?: "Sndmart Customer",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                                if (!userEmail.isNullOrBlank()) {
                                    Text(
                                        text = userEmail!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                                if (!userPhone.isNullOrBlank()) {
                                    Text(
                                        text = userPhone!!,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                            } else {
                                Text(
                                    text = "Welcome to Sndmart",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = "Log in or sign up to manage your orders",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        if (!isLoggedIn) {
                            Button(
                                onClick = onRequireLogin,
                                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.testTag("profile_login_button")
                            ) {
                                Text("Login")
                            }
                        }
                    }
                }
            }

            // Quick Menu Items
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column {
                        ProfileMenuItem(
                            icon = Icons.Outlined.LocationCity,
                            title = "Delivery City",
                            subtitle = currentCity?.let { "${it.name}, ${it.state ?: ""}" } ?: "Not selected",
                            onClick = onNavigateToCityPicker,
                            testTag = "menu_item_city"
                        )
                        HorizontalDivider()
                        ProfileMenuItem(
                            icon = Icons.Outlined.AccountBalanceWallet,
                            title = "Sndmart Wallet",
                            subtitle = "View balance & automatic order refunds",
                            onClick = {
                                if (isLoggedIn) onNavigateToWallet()
                                else onRequireLogin()
                            },
                            testTag = "menu_item_wallet"
                        )
                        HorizontalDivider()
                        ProfileMenuItem(
                            icon = Icons.Outlined.Tune,
                            title = "Supabase Connection Settings",
                            subtitle = "View API endpoint and configure anon key",
                            onClick = onOpenSettings,
                            testTag = "menu_item_settings"
                        )
                    }
                }
            }

            if (isLoggedIn) {
                item {
                    Button(
                        onClick = { showLogoutDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("logout_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = PastelCoral),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(Icons.Default.ExitToApp, contentDescription = null, tint = NaturalBadgeRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Log Out", color = NaturalBadgeRed, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log Out", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to log out of your Sndmart account?") },
            confirmButton = {
                Button(
                    onClick = {
                        sessionManager.logout()
                        showLogoutDialog = false
                        onLogoutSuccess()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalBadgeRed),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Log Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ProfileMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NaturalPrimary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp)
        )
    }
}
