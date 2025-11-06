package com.rnd.ble.presentation.screens

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rnd.ble.R
import com.rnd.ble.utility.BLEManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BLEDevicesListScreen(
    onNavigateToWeightScreen: () -> Unit,
    bleManager: BLEManager,
    onBack: () -> Unit
) {

    val scannedDevices by bleManager.scannedDevices.collectAsState()
    val connectionState by bleManager.connectionState.collectAsState()
    val connectedDevice by bleManager.connectedDevice.collectAsState()
    val isScanning by bleManager.isScanning.collectAsState()
    val receivedData by bleManager.receivedData.collectAsState()
    var connectingDeviceAddress by remember { mutableStateOf<String?>(null) }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            bleManager.startScan()
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState == BLEManager.ConnectionState.CONNECTED) {
            onNavigateToWeightScreen.invoke()
        }
        if (connectionState != BLEManager.ConnectionState.CONNECTING) {
            connectingDeviceAddress = null
        }
    }

    // Request permissions
    LaunchedEffect(Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        permissionLauncher.launch(permissions)
    }

    DisposableEffect(Unit) {
        onDispose {
            bleManager.cleanup()
        }
    }

    Scaffold(
        topBar = {
            ModernTopAppBar(title = stringResource(R.string.ble_device_scanner), onBack = onBack)
        }
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
        ) {
            // Status Cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                StatusCard(title = stringResource(R.string.status), connectionState = connectionState, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(16.dp))
                StatusCard(title = stringResource(R.string.devices_found), value = scannedDevices.size.toString(), modifier = Modifier.weight(1f))
            }

            WeightCard(weight = receivedData)

            // Scan Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = {
                        if (isScanning) {
                            bleManager.stopScan()
                        } else {
                            bleManager.startScan()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isScanning) stringResource(R.string.stop_scan) else stringResource(R.string.start_scan))
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { bleManager.disconnect() },
                    enabled = connectionState == BLEManager.ConnectionState.CONNECTED,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.disconnect))
                }
            }

            // Device List
            if (isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(scannedDevices) { device ->
                    val isConnecting = connectingDeviceAddress == device.address
                    DeviceItem(
                        device = device,
                        isConnected = connectedDevice?.address == device.address,
                        isConnecting = isConnecting,
                        onClick = {
                            if (!isConnecting && connectedDevice?.address != device.address) {
                                connectingDeviceAddress = device.address
                                bleManager.connectToDevice(device.device)
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTopAppBar(title: String, onBack: () -> Unit) {
    CenterAlignedTopAppBar(
        title = { Text(text = title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
fun StatusCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun StatusCard(title: String, connectionState: BLEManager.ConnectionState, modifier: Modifier = Modifier) {
    val statusColor = when (connectionState) {
        BLEManager.ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        BLEManager.ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
        BLEManager.ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
        BLEManager.ConnectionState.DISCONNECTING -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = connectionState.name,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = statusColor
            )
        }
    }
}

@Composable
fun WeightCard(weight: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.weight),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = weight.ifEmpty { stringResource(R.string.empty_weight) },
                style = MaterialTheme.typography.displayLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}


@Composable
fun DeviceItem(
    device: BLEManager.BleDevice,
    isConnected: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        isConnected -> MaterialTheme.colorScheme.tertiaryContainer
        isConnecting -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        isConnected -> MaterialTheme.colorScheme.onTertiaryContainer
        isConnecting -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick, enabled = !isConnecting && !isConnected),
        colors = CardDefaults.cardColors(containerColor = backgroundColor, contentColor = contentColor)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: stringResource(R.string.unknown_device),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(R.string.rssi_format, device.rssi),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.width(16.dp))

            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (isConnected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.connected)
                )
            }
        }
    }
}
