package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.sp
import com.example.data.remote.SupabaseClient
import com.example.data.remote.SupabaseConfig
import com.example.data.session.UserSessionManager
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SupabaseSettingsDialog(
    sessionManager: UserSessionManager,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var currentKey by remember { mutableStateOf(sessionManager.getCustomAnonKey() ?: SupabaseClient.getEffectiveAnonKey()) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = NaturalPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Supabase REST API", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Project Ref: ${SupabaseConfig.PROJECT_REF}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Base URL: ${SupabaseConfig.BASE_URL}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = currentKey,
                    onValueChange = { currentKey = it },
                    label = { Text("Supabase Anon Key (Public)") },
                    modifier = Modifier.fillMaxWidth().testTag("supabase_anon_key_input"),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 4
                )

                if (testResult != null) {
                    val isSuccess = testResult!!.startsWith("Success")
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSuccess) PastelMint else PastelCoral,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = testResult!!,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSuccess) DarkGreenText else NaturalBadgeRed
                        )
                    }
                }

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isTesting = true
                            testResult = null
                            sessionManager.saveCustomAnonKey(currentKey.trim())
                            try {
                                val res = SupabaseClient.api.getCities()
                                if (res.isSuccessful) {
                                    val count = res.body()?.size ?: 0
                                    testResult = "Success! Connected to Supabase ($count active cities loaded)."
                                } else {
                                    testResult = "HTTP ${res.code()}: ${SupabaseClient.parseErrorMessage(res)}"
                                }
                            } catch (e: Exception) {
                                testResult = "Connection failed: ${e.message}"
                            }
                            isTesting = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("test_supabase_connection_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                    shape = RoundedCornerShape(20.dp),
                    enabled = !isTesting
                ) {
                    if (isTesting) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                    else Text("Test Live Connection (Ping)")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    sessionManager.saveCustomAnonKey(currentKey.trim())
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
