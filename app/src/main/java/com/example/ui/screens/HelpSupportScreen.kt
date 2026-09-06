package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

// NOTE: These are PLACEHOLDER support contact details. Swap for real support contact
// info (phone, WhatsApp number, email) before launch.
private const val SUPPORT_PHONE = "+910000000000"
private const val SUPPORT_WHATSAPP = "910000000000"
private const val SUPPORT_EMAIL = "support@sndmart.in"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSupportScreen(
    orderNumber: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help & Support", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "How can we help you?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (orderNumber != null) {
                Text(
                    "We've pre-filled your order number ($orderNumber) for faster support.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            // Call Support
            SupportOptionCard(
                icon = Icons.Default.Call,
                iconTint = NaturalPrimary,
                iconBg = PastelSky,
                title = "Call Support",
                subtitle = SUPPORT_PHONE,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$SUPPORT_PHONE")))
                },
                testTag = "help_call_button"
            )

            // WhatsApp Support
            SupportOptionCard(
                icon = Icons.Default.Chat,
                iconTint = SuccessGreen,
                iconBg = PastelSage,
                title = "WhatsApp Support",
                subtitle = "Chat with us instantly",
                onClick = {
                    val text = if (orderNumber != null)
                        "I need help with order $orderNumber"
                    else
                        "Hi Sndmart Support, I need help"
                    val encoded = java.net.URLEncoder.encode(text, "UTF-8")
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$SUPPORT_WHATSAPP?text=$encoded")))
                },
                testTag = "help_whatsapp_button"
            )

            // Email Support
            SupportOptionCard(
                icon = Icons.Default.Email,
                iconTint = NaturalPrimary,
                iconBg = PastelSand,
                title = "Email Support",
                subtitle = SUPPORT_EMAIL,
                onClick = {
                    val subject = if (orderNumber != null) "Help with Order $orderNumber" else "Sndmart Support"
                    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL?subject=${Uri.encode(subject)}")))
                },
                testTag = "help_email_button"
            )

            Spacer(modifier = Modifier.weight(1f))
            Text(
                "Our support team is available 9 AM – 9 PM, all days.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun SupportOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = iconBg, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconTint)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}
