package com.example.appmobile.presentation.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.UUID

private val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
private val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
private val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
private val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val TAG = "DreamAppBle"

private data class BleDeviceItem(val name: String, val address: String, val device: BluetoothDevice)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun BleDiagnosticsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var devices by remember { mutableStateOf(emptyList<BleDeviceItem>()) }
    var log by remember { mutableStateOf(listOf("Listo para buscar dispositivos BLE cercanos.")) }
    var scanning by remember { mutableStateOf(false) }
    var gatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    var selectedAddress by remember { mutableStateOf<String?>(null) }

    fun addLog(message: String) {
        Log.i(TAG, message)
        mainHandler.post { log = listOf(message) + log }
    }

    fun hasPermissions() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val item = BleDeviceItem(
                    result.scanRecord?.deviceName ?: result.device.name ?: "Dispositivo sin nombre",
                    result.device.address,
                    result.device
                )
                devices = (devices.filterNot { it.address == item.address } + item).sortedBy { it.name }
            }

            override fun onScanFailed(errorCode: Int) {
                mainHandler.post { scanning = false }
                addLog("Falló el escaneo BLE: código $errorCode")
            }
        }
    }

    fun startScan() {
        devices = emptyList()
        manager.adapter?.bluetoothLeScanner?.startScan(scanCallback)
        scanning = true
        addLog("Escaneando. Toca el Huawei Watch cuando aparezca.")
        mainHandler.postDelayed({
            if (scanning && hasPermissions()) {
                manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                scanning = false
                addLog("Búsqueda detenida automáticamente después de 15 segundos.")
            }
        }, 15_000)
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startScan()
        else addLog("Se necesitan permisos de Bluetooth para buscar el reloj.")
    }

    fun subscribe(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(characteristic, true)) {
            addLog("El reloj rechazó la activación local de frecuencia cardiaca.")
            return
        }
        characteristic.getDescriptor(CLIENT_CONFIG)?.let { descriptor ->
            val started = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            addLog(if (started == BluetoothStatusCodes.SUCCESS) "Solicitando mediciones de frecuencia cardiaca…" else "No se pudo iniciar la suscripción de frecuencia cardiaca (código $started).")
        } ?: addLog("El servicio de pulso no incluye descriptor de notificaciones.")
    }

    fun readBattery(g: BluetoothGatt) {
        val battery = g.services.flatMap { it.characteristics }.firstOrNull { it.uuid == BATTERY_LEVEL }
        if (battery != null && !g.readCharacteristic(battery)) {
            addLog("No se pudo iniciar la lectura de batería.")
        }
    }

    val gattCallback = remember {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    addLog("Conectado; descubriendo servicios…")
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val reason = when (status) {
                        BluetoothGatt.GATT_SUCCESS -> "conexión cerrada normalmente"
                        19 -> "el reloj cerró la conexión"
                        133 -> "error temporal de Android Bluetooth"
                        else -> "código GATT $status"
                    }
                    addLog("Reloj desconectado: $reason.")
                    g.close()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    addLog("No se pudieron descubrir los servicios: código $status.")
                    return
                }
                val lines = g.services.flatMap { service ->
                    service.characteristics.map { c ->
                        "${service.uuid} → ${c.uuid} propiedades=0x${c.properties.toString(16)}"
                    }
                }
                addLog("Servicios encontrados: ${g.services.size}")
                lines.forEach { Log.i(TAG, it) }
                val heartRate = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
                if (heartRate != null) subscribe(g, heartRate)
                else {
                    addLog("El reloj no expone el servicio BLE estándar de frecuencia cardiaca en esta conexión.")
                    readBattery(g)
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CLIENT_CONFIG) {
                    addLog(if (status == BluetoothGatt.GATT_SUCCESS) "Frecuencia cardiaca habilitada; esperando datos…" else "El reloj rechazó las notificaciones de pulso: código $status.")
                    readBattery(g)
                }
            }

            @Deprecated("Deprecated in Android")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) addLog(formatValue(c.uuid, c.value))
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                addLog(formatValue(c.uuid, value))
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (hasPermissions()) manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            gatt?.close()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Diagnóstico Bluetooth") },
            navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Volver") } }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Prototipo local: no rompe cifrado ni sustituye la app Huawei Health.")
                Button(onClick = {
                    if (scanning) {
                        manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                        scanning = false
                    } else if (hasPermissions()) startScan()
                    else permissionsLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                }) { Text(if (scanning) "Detener búsqueda" else "Buscar reloj") }
            }
            items(devices, key = { it.address }) { item ->
                ListItem(
                    headlineContent = { Text(item.name) },
                    supportingContent = { Text(item.address) },
                    modifier = Modifier.clickable {
                        manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                        scanning = false
                        gatt?.close()
                        selectedAddress = item.address
                        log = listOf("Conectando con ${item.name}…") + log
                        gatt = item.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (selectedAddress == item.address) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    )
                )
            }
            item { HorizontalDivider(); Text("Registro técnico") }
            items(log.take(150)) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun formatValue(uuid: UUID, value: ByteArray): String {
    if (uuid == BATTERY_LEVEL && value.isNotEmpty()) return "Batería: ${value[0].toInt() and 0xff}%"
    if (uuid == HEART_RATE_MEASUREMENT && value.size >= 2) {
        val sixteenBit = value[0].toInt() and 1 != 0
        val bpm = if (sixteenBit && value.size >= 3) (value[1].toInt() and 0xff) or ((value[2].toInt() and 0xff) shl 8)
        else value[1].toInt() and 0xff
        return "Frecuencia cardiaca BLE: $bpm bpm"
    }
    return "$uuid = ${value.joinToString("") { "%02x".format(it) }}"
}
