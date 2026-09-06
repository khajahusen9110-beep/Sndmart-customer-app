package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DeliveryPartnerReview
import com.example.data.model.VendorReview
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.ErrorCard
import com.example.ui.theme.*
import kotlinx.coroutines.launch

// A single review row, unified across vendor and delivery-partner reviews.
private data class ReviewRow(
    val typeLabel: String,
    val rating: Int,
    val comment: String?,
    val orderId: String?,
    val createdAt: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyReviewsScreen(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val userId = sessionManager.userId.collectAsState().value

    var reviews by remember { mutableStateOf<List<ReviewRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadReviews() {
        if (userId.isNullOrBlank()) {
            isLoading = false
            return
        }
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val rows = mutableListOf<ReviewRow>()
            val vRes = repository.getMyVendorReviews(userId)
            vRes.getOrNull()?.forEach {
                rows.add(ReviewRow("Hotel / Vendor", it.rating, it.comment, it.orderId, it.createdAt))
            }
            val dRes = repository.getMyDeliveryPartnerReviews(userId)
            dRes.getOrNull()?.forEach {
                rows.add(ReviewRow("Delivery Partner", it.rating, it.comment, it.orderId, it.createdAt))
            }
            // Newest first (createdAt desc by string sort).
            reviews = rows.sortedByDescending { it.createdAt ?: "" }
            errorMessage = vRes.exceptionOrNull()?.message ?: dRes.exceptionOrNull()?.message
            isLoading = false
        }
    }

    LaunchedEffect(userId) { loadReviews() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Reviews", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadReviews() }, modifier = Modifier.testTag("refresh_reviews_button")) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = NaturalPrimary)
            } else if (errorMessage != null && reviews.isEmpty()) {
                ErrorCard(message = errorMessage!!, onRetry = { loadReviews() }, modifier = Modifier.align(Alignment.Center))
            } else if (reviews.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.StarBorder, contentDescription = null, tint = TextMuted, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No reviews yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Reviews you submit after an order will appear here.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(reviews, key = { "${it.typeLabel}-${it.orderId}-${it.createdAt}" }) { review ->
                        ReviewCard(review)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(review: ReviewRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(review.typeLabel, fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            if (review.typeLabel == "Hotel / Vendor") Icons.Default.Store else Icons.Default.DeliveryDining,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(containerColor = PastelSand)
                )
                Row {
                    (1..5).forEach { star ->
                        Icon(
                            imageVector = if (star <= review.rating) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = null,
                            tint = AmberAccent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (!review.comment.isNullOrBlank()) {
                Text(review.comment, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            } else {
                Text("No written comment.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!review.createdAt.isNullOrBlank()) {
                    Text(review.createdAt.take(16).replace("T", " "), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                if (!review.orderId.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("• Order #${review.orderId.take(8)}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
        }
    }
}
