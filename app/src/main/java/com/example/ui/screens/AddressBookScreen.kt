package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomerAddress
import com.example.data.repository.SndmartRepository
import com.example.data.session.UserSessionManager
import com.example.ui.components.AddressPickerDialog
import com.example.ui.components.ErrorCard
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressBookScreen(
    repository: SndmartRepository,
    sessionManager: UserSessionManager,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val userId = sessionManager.userId.collectAsState().value
    val snackbarHostState = remember { SnackbarHostState() }

    var addresses by remember { mutableStateOf<List<CustomerAddress>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CustomerAddress?>(null) }
    var showAddEdit by remember { mutableStateOf(false) }

    fun loadAddresses() {
        if (userId.isNullOrBlank()) {
            isLoading = false
            return
        }
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            val res = repository.getAddresses(userId)
            if (res.isSuccess) addresses = res.getOrNull() ?: emptyList()
            else errorMessage = res.exceptionOrNull()?.message
            isLoading = false
        }
    }

    LaunchedEffect(userId) { loadAddresses() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Addresses", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { editing = null; showAddEdit = true }, modifier = Modifier.testTag("add_address_button")) {
                        Icon(Icons.Default.Add, contentDescription = "Add Address")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = NaturalPrimary)
            } else if (errorMessage != null) {
                ErrorCard(message = errorMessage!!, onRetry = { loadAddresses() }, modifier = Modifier.align(Alignment.Center))
            } else if (addresses.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = TextMuted, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No saved addresses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Detect your location via GPS to set exact doorstep delivery.", style = MaterialTheme.typography.bodySmall, color = TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { editing = null; showAddEdit = true },
                            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth(0.85f).height(48.dp)
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Detect My Location", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Swiggy-Style Quick Detect Location Top Card
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editing = null; showAddEdit = true }
                                .testTag("detect_my_location_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = PastelSage),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NaturalPrimary.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = NaturalPrimary,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MyLocation,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.padding(9.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Detect My Current Location",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = NaturalOnPrimaryContainer
                                    )
                                    Text(
                                        "Using high-accuracy device GPS for doorstep pin",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = NaturalPrimary
                                )
                            }
                        }
                    }

                    items(addresses, key = { it.id ?: it.addressLine }) { addr ->
                        AddressCard(
                            address = addr,
                            onSetDefault = {
                                coroutineScope.launch {
                                    val res = repository.setDefaultAddress(userId ?: "", addr.id ?: "")
                                    if (res.isSuccess) {
                                        snackbarHostState.showSnackbar("Default address updated")
                                        loadAddresses()
                                    } else {
                                        snackbarHostState.showSnackbar("Failed: ${res.exceptionOrNull()?.message}")
                                    }
                                }
                            },
                            onEdit = { editing = addr; showAddEdit = true },
                            onDelete = {
                                coroutineScope.launch {
                                    val res = repository.deleteAddress(addr.id ?: "")
                                    if (res.isSuccess) {
                                        snackbarHostState.showSnackbar("Address deleted")
                                        loadAddresses()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddEdit) {
        val userPhone = sessionManager.userPhone.collectAsState().value ?: ""
        AddressPickerDialog(
            repository = repository,
            userId = userId ?: "",
            existing = editing,
            defaultPhone = userPhone,
            onDismiss = { showAddEdit = false; editing = null },
            onSaved = {
                showAddEdit = false
                editing = null
                loadAddresses()
            }
        )
    }
}

@Composable
private fun AddressCard(
    address: CustomerAddress,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (address.isDefault) NaturalPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(address.label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                if (address.isDefault) {
                    Surface(shape = RoundedCornerShape(8.dp), color = PastelSage) {
                        Text(
                            "Default",
                            color = DarkGreenText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(address.recipientName.ifBlank { "Recipient" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (address.phone.isNotBlank()) {
                Text(address.phone, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Text(address.addressLine, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            if (!address.landmark.isNullOrBlank()) {
                Text("Landmark: ${address.landmark}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            if (address.lat != null && address.lng != null && address.lat != 0.0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PinDrop, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Map Pin: %.4f, %.4f".format(address.lat, address.lng),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!address.isDefault) {
                    OutlinedButton(onClick = onSetDefault, shape = RoundedCornerShape(16.dp), modifier = Modifier.testTag("set_default_button")) {
                        Text("Set Default", fontSize = 12.sp)
                    }
                }
                OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(16.dp), modifier = Modifier.testTag("edit_address_button")) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit on Map", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onDelete, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = NaturalBadgeRed), modifier = Modifier.testTag("delete_address_button")) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", fontSize = 12.sp)
                }
            }
        }
    }
}
