package com.example.appmobile.presentation.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.appmobile.data.remote.CloudSleepSession
import com.example.appmobile.presentation.viewmodel.CloudSleepHistoryViewModel
import com.example.appmobile.presentation.viewmodel.SleepMonitorViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepHistoryScreen(
    monitorViewModel: SleepMonitorViewModel,
    onNavigateBack: () -> Unit
) {
    val cloudViewModel: CloudSleepHistoryViewModel = viewModel()
    val sessions by cloudViewModel.sessions.collectAsState()
    val loading by cloudViewModel.loading.collectAsState()
    val error by cloudViewModel.error.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Historial de Sueño", fontWeight = FontWeight.Medium) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Regresar")
                }
            },
            actions = {
                IconButton(onClick = cloudViewModel::refresh, enabled = !loading) {
                    Icon(Icons.Default.Refresh, "Actualizar historial")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
    }) { padding ->
        when {
            loading && sessions.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            error != null && sessions.isEmpty() -> EmptyHistory(
                message = error ?: "No se pudo cargar el historial",
                onRetry = cloudViewModel::refresh,
                modifier = Modifier.padding(padding)
            )

            sessions.isEmpty() -> EmptyHistory(
                message = "Aún no hay sesiones sincronizadas con tu cuenta",
                onRetry = cloudViewModel::refresh,
                modifier = Modifier.padding(padding)
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                items(sessions, key = { it.date }) { CloudSleepCard(it) }
            }
        }
    }
}

@Composable
private fun EmptyHistory(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.History, null, Modifier.size(48.dp))
                Text("Sin datos de sueño", style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onRetry) { Text("Actualizar") }
            }
        }
    }
}

@Composable
private fun CloudSleepCard(session: CloudSleepSession) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(3.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(session.date, fontWeight = FontWeight.Bold)
                Text("${session.sleepEfficiency.roundToInt()}% · ${translateQuality(session.quality)}",
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HistoryMetric("Duración", minutes(session.sleepDuration))
                HistoryMetric("Pulso", "${session.avgHR} bpm")
                HistoryMetric("Despertares", session.awakenings.toString())
            }
            Text(
                "Ligero ${minutes(session.light)}  ·  Profundo ${minutes(session.deep)}  ·  REM ${minutes(session.rem)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryMetric(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, fontWeight = FontWeight.Medium) }
}

private fun minutes(value: Int) = "${value / 60}h ${value % 60}m"
private fun translateQuality(value: String) = when (value.uppercase()) {
    "POOR" -> "Pobre"; "FAIR" -> "Regular"; "GOOD" -> "Buena"; "EXCELLENT" -> "Excelente"; else -> value
}
