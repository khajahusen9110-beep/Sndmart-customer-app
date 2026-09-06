package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.example.data.model.City
import com.example.data.remote.SupabaseClient
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.ErrorCard
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityPickerSheet(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onDismiss: () -> Unit,
    onCitySelected: (City) -> Unit,
    onTriggerLocationDetect: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var cities by remember { mutableStateOf<List<City>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val currentCity by sessionManager.selectedCity.collectAsState()
    val userId by sessionManager.userId.collectAsState()

    var pendingCityChange by remember { mutableStateOf<City?>(null) }
    var showSupabaseSettings by remember { mutableStateOf(false) }

    fun loadCities() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val res = repository.getActiveCities()
            if (res.isSuccess) {
                // Rule: cities table, status='active' strictly from backend
                val list = res.getOrNull()?.filter { it.status == "active" } ?: emptyList()
                cities = list
            } else {
                errorMessage = res.exceptionOrNull()?.message ?: "Unable to load cities from backend"
                cities = emptyList()
            }
            isLoading = false
        }
    }

    if (showSupabaseSettings) {
        SupabaseSettingsDialog(
            sessionManager = sessionManager,
            onDismiss = {
                showSupabaseSettings = false
                loadCities()
            }
        )
    }

    LaunchedEffect(Unit) {
        loadCities()
    }

    fun applyCitySelection(city: City) {
        coroutineScope.launch {
            // Update local selection
            sessionManager.setSelectedCity(city)
            // Clear cart on city change
            repository.clearAllCarts()
            // If logged in, update profiles.city_id
            if (!userId.isNullOrBlank()) {
                try {
                    SupabaseClient.api.updateProfile("eq.$userId", mapOf("city_id" to city.id))
                } catch (e: Exception) {
                    // Ignore profile update error or let user proceed
                }
            }
            onCitySelected(city)
            onDismiss()
        }
    }

    // Confirmation dialog when switching cities if cart has items
    if (pendingCityChange != null) {
        val nextCity = pendingCityChange!!
        val cartCount = repository.getCartCount()
        if (cartCount > 0) {
            AlertDialog(
                onDismissRequest = { pendingCityChange = null },
                title = { Text("Change Delivery City?", fontWeight = FontWeight.Bold) },
                text = {
                    Text("Changing your city to ${nextCity.name} will clear your current cart items, as prices and stock are city-specific. Do you wish to continue?")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val c = nextCity
                            pendingCityChange = null
                            applyCitySelection(c)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Change City & Clear Cart")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCityChange = null }) {
                        Text("Cancel")
                    }
                }
            )
        } else {
            // No cart items, apply immediately
            LaunchedEffect(nextCity) {
                applyCitySelection(nextCity)
                pendingCityChange = null
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Select Delivery City",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Active delivery cities from backend",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Auto-detect GPS button
            if (onTriggerLocationDetect != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { onTriggerLocationDetect() }
                        .testTag("auto_detect_city_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = PastelSage)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(NaturalPrimaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = null,
                                tint = NaturalPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Update my location",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = NaturalOnPrimaryContainer
                            )
                            Text(
                                text = "Auto-detect delivery city via GPS",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = NaturalPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (errorMessage != null && cities.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = PastelCoral.copy(alpha = 0.7f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudOff,
                            contentDescription = null,
                            tint = NaturalBadgeRed,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Backend Notice",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = NaturalBadgeRed
                            )
                            Text(
                                text = errorMessage ?: "Unable to fetch live cities from server.",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        FilledTonalButton(
                            onClick = { showSupabaseSettings = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("API Key", fontSize = 11.sp)
                        }
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = NaturalPrimary)
                }
            } else if (cities.isEmpty()) {
                ErrorCard(
                    message = errorMessage ?: "No active cities found.",
                    onRetry = { loadCities() },
                    onConfigureKey = { showSupabaseSettings = true },
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(cities, key = { it.id }) { city ->
                        val isSelected = currentCity?.id == city.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    if (currentCity?.id != city.id) {
                                        pendingCityChange = city
                                    } else {
                                        onDismiss()
                                    }
                                }
                                .testTag("city_item_${city.id}"),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) PastelSage
                                else MaterialTheme.colorScheme.surface
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) NaturalPrimary.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.LocationOn,
                                        contentDescription = null,
                                        tint = if (isSelected) NaturalPrimary else TextSecondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text(
                                            text = city.name,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                            fontSize = 16.sp,
                                            color = if (isSelected) DarkGreenText else TextPrimary
                                        )
                                        if (!city.state.isNullOrBlank()) {
                                            Text(
                                                text = city.state!!,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = NaturalPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
