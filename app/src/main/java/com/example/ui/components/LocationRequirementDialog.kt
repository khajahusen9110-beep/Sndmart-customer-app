package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*

enum class LocationDetectionState {
    PERMISSION_REQUIRED,
    DETECTING,
    SUCCESS,
    UNSUPPORTED_AREA,
    PERMISSION_DENIED
}

@Composable
fun LocationRequirementDialog(
    state: LocationDetectionState,
    detectedCityName: String?,
    assignedCityName: String?,
    activeCitiesSummary: String,
    onRequestPermission: () -> Unit,
    onManualSelect: () -> Unit,
    onDismiss: () -> Unit,
    onNotifyMe: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = {
            // Only allow dismiss if success or if manual select allowed
            if (state == LocationDetectionState.SUCCESS || state == LocationDetectionState.PERMISSION_DENIED || state == LocationDetectionState.UNSUPPORTED_AREA) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = (state == LocationDetectionState.SUCCESS || state == LocationDetectionState.PERMISSION_DENIED),
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("location_requirement_dialog"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterVertically
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            when (state) {
                                LocationDetectionState.SUCCESS -> PastelSage
                                LocationDetectionState.DETECTING -> PastelOcean
                                LocationDetectionState.UNSUPPORTED_AREA, LocationDetectionState.PERMISSION_DENIED -> PastelCoral
                                else -> NaturalPrimaryContainer
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when (state) {
                        LocationDetectionState.DETECTING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = NaturalPrimary,
                                strokeWidth = 3.dp
                            )
                        }
                        LocationDetectionState.SUCCESS -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = NaturalPrimary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        LocationDetectionState.PERMISSION_DENIED -> {
                            Icon(
                                imageVector = Icons.Default.LocationOff,
                                contentDescription = null,
                                tint = NaturalBadgeRed,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = null,
                                tint = NaturalPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Title & Subtitle based on state
                when (state) {
                    LocationDetectionState.PERMISSION_REQUIRED -> {
                        Text(
                            text = "Location Access Required",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Sndmart needs your device location to automatically detect your delivery city and show available local fresh groceries and food.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                    LocationDetectionState.DETECTING -> {
                        Text(
                            text = "Detecting Your Location...",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Finding your current delivery zone and connecting to live stores...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                    LocationDetectionState.SUCCESS -> {
                        Text(
                            text = "Delivery City Assigned!",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = NaturalPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Assigned automatically to $assignedCityName based on your live location.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )
                    }
                    LocationDetectionState.UNSUPPORTED_AREA -> {
                        Text(
                            text = "We're Not Available in Your Area Yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = NaturalBadgeRed,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Sndmart hasn't launched in your current location yet. We're expanding fast — stay tuned!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                    LocationDetectionState.PERMISSION_DENIED -> {
                        Text(
                            text = "Location Access Denied",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Please allow location permission to auto-detect your delivery city, or pick your active city from the list.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                when (state) {
                    LocationDetectionState.PERMISSION_REQUIRED -> {
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("allow_location_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Allow Location Access", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                    LocationDetectionState.DETECTING -> {
                        // Progress is animating in header
                    }
                    LocationDetectionState.SUCCESS -> {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("start_shopping_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Start Shopping", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                    LocationDetectionState.UNSUPPORTED_AREA -> {
                        if (onNotifyMe != null) {
                            Button(
                                onClick = onNotifyMe,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("notify_me_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Notify Me When We Launch", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        OutlinedButton(
                            onClick = onManualSelect,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("choose_active_city_button"),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Browse Other Cities", fontWeight = FontWeight.Bold)
                        }
                    }
                    LocationDetectionState.PERMISSION_DENIED -> {
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("retry_location_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Grant Permission", fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onManualSelect,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("manual_city_select_button"),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Choose City Manually")
                        }
                    }
                }
            }
        }
    }
}
