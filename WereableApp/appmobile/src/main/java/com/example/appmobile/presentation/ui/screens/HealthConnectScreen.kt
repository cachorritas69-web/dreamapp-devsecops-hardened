package com.example.appmobile.presentation.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.example.appmobile.data.health.HealthConnectSleepRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthConnectScreen(userId: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val available = remember { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }
    val repository = remember(available) { if (available) HealthConnectSleepRepository(context) else null }
    var hasPermissions by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Comprobando permisos…") }
    var success by remember { mutableStateOf(false) }

    fun synchronize() {
        val repo = repository ?: return
        scope.launch {
            working = true
            success = false
            message = "Leyendo la última noche desde Health Connect…"
            runCatching { repo.synchronizeLatest(userId) }
                .onSuccess {
                    success = true
                    message = "Sincronizado: ${it.date}, ${it.sleepMinutes} min de sueño, " +
                        "pulso promedio ${if (it.averageHeartRate > 0) "${it.averageHeartRate} bpm" else "sin datos"}. " +
                        "Origen: ${it.source}."
                }
                .onFailure { message = it.message ?: "No fue posible sincronizar." }
            working = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted: Set<String> ->
        hasPermissions = granted.containsAll(repository?.permissions.orEmpty())
        if (hasPermissions) synchronize() else message = "DreamApp necesita permiso para leer sueño y pulso."
    }

    LaunchedEffect(repository) {
        if (!available || repository == null) {
            message = "Health Connect no está disponible en este teléfono."
        } else {
            hasPermissions = repository.hasPermissions()
            message = if (hasPermissions) "Listo para leer datos de Samsung Health." else
                "Autoriza a DreamApp para leer sueño y frecuencia cardiaca."
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Galaxy Watch") },
            navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Volver") } }
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Sincronización oficial", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("El reloj envía las mediciones a Samsung Health; Health Connect las comparte con DreamApp con tu autorización.")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (success) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(message, modifier = Modifier.padding(18.dp))
            }
            if (working) CircularProgressIndicator()
            if (!hasPermissions) {
                Button(
                    enabled = available && !working,
                    onClick = { repository?.let { permissionLauncher.launch(it.permissions) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Autorizar y sincronizar") }
            } else {
                Button(
                    enabled = !working,
                    onClick = { synchronize() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sincronizar última noche") }
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent("android.health.connect.action.HEALTH_CONNECT_SETTINGS"))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Abrir Health Connect") }
            Spacer(Modifier.height(8.dp))
            Text(
                "Antes de sincronizar: abre Samsung Health → Ajustes → Health Connect y permite compartir Sueño y Frecuencia cardiaca.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
