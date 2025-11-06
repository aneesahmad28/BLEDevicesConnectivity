package com.rnd.ble.utility

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class WeightData(
    val weight: Double,
    val isStable: Boolean,
    val unit: String
)

class BLEManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner: BluetoothLeScanner? by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    // Coroutine scope for periodic operations
    private val bleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _receivedData = MutableStateFlow<String>("")
    val receivedData: StateFlow<String> = _receivedData.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice.asStateFlow()

    private val _weightData = MutableStateFlow<WeightData?>(null)
    val weightData: StateFlow<WeightData?> = _weightData.asStateFlow()

    private val scanResults = mutableMapOf<String, BleDevice>()
    private val dataBuffer = StringBuilder()

    private val MANUFACTURER_ID = 256
    private val WEIGHT_OFFSET = 10
    private val STABLE_OFFSET = 14

    // Scan Callback
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = getDeviceName(result)
            val device = BleDevice(
                name = deviceName,
                address = result.device.address,
                rssi = result.rssi,
                device = result.device
            )

            scanResults[device.address] = device
            _scannedDevices.value = scanResults.values
                .filter { it.name != "Unknown Device" }
                .sortedByDescending { it.rssi }
                .toList()

            // --- New code for weight scale ---
            result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)?.let { bytes ->
                Log.d("BLEManager", "Found manufacturer data for ID $MANUFACTURER_ID with length ${bytes.size}")
                if (bytes.size >= STABLE_OFFSET + 1) { // Ensure data is long enough
                    val weightRaw = ((bytes[WEIGHT_OFFSET].toInt() and 0xFF) shl 8) or (bytes[WEIGHT_OFFSET + 1].toInt() and 0xFF)
                    val stable = (bytes[STABLE_OFFSET].toInt() and 0xF0) shr 4
                    val unitRaw = bytes[STABLE_OFFSET].toInt() and 0x0F

                    Log.d("BLEManager", "Weight Raw: $weightRaw, Stable: $stable, Unit: $unitRaw")

                    // As per ScaleWatcher.java, weight is 0.01kg
                    val weight = weightRaw / 100.0
                    val isStable = stable != 0
                    val unit = when (unitRaw) {
                        0 -> "lb"
                        1 -> "kg"
                        else -> "Unknown"
                    }

                    if(!isStable) {
                        val newWeightData = WeightData(weight, isStable, unit)
                        _receivedData.value = "${newWeightData.weight} $unit"
                        Log.d("BLEManager", "Parsed Weight Data: $newWeightData")
                    }

                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            Log.e("BLEManager", "Scan failed with error: $errorCode")
        }
    }

    // GATT Callback
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("BLEManager", "Connected to GATT server")
                    _connectionState.value = ConnectionState.CONNECTED
                    _connectedDevice.value = gatt.device
                    // Discover services after a delay
                    bleScope.launch {
                        delay(600)
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BLEManager", "Disconnected from GATT server, status: $status")
                    Log.d("BLEManager", "Disconnected from GATT new state: $newState")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _connectedDevice.value = null
                    stopPolling()
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    readCharacteristic = null
                    writeCharacteristic = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLEManager", "Services discovered successfully")

                // Log all services and characteristics
                gatt.services.forEach { service ->
                    Log.d("BLEManager", "Service: ${service.uuid}")
                    service.characteristics.forEach { characteristic ->
                        val properties = mutableListOf<String>()
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) properties.add("READ")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) properties.add("WRITE")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) properties.add("WRITE_NO_RESPONSE")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) properties.add("NOTIFY")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) properties.add("INDICATE")
                        Log.d("BLEManager", "  Characteristic: ${characteristic.uuid} [${properties.joinToString()}]")
                    }
                }

                // Find the notify/indicate characteristic (for receiving data)
                val notifyCharacteristic = gatt.services.asSequence()
                    .flatMap { it.characteristics.asSequence() }
                    .firstOrNull { (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 }
                    ?: gatt.services.asSequence()
                        .flatMap { it.characteristics.asSequence() }
                        .firstOrNull { (it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }

                // Find the write characteristic (for sending commands)
                writeCharacteristic = gatt.services.asSequence()
                    .flatMap { it.characteristics.asSequence() }
                    .firstOrNull {
                        (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    }

                // Find readable characteristic (for polling if notifications don't work)
                readCharacteristic = gatt.services.asSequence()
                    .flatMap { it.characteristics.asSequence() }
                    .firstOrNull { (it.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0 }

                notifyCharacteristic?.let {
                    // Enable notifications
                    val success = gatt.setCharacteristicNotification(it, true)
                    Log.d("BLEManager", "setCharacteristicNotification: $success for ${it.uuid}")

                    // Enable descriptor for notifications
                    val descriptor = it.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    descriptor?.let { desc ->
                        val value = if ((it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        } else {
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val writeSuccess = gatt.writeDescriptor(desc, value)
                            Log.d("BLEManager", "Descriptor write initiated (API 33+): $writeSuccess")
                        } else {
                            @Suppress("DEPRECATION")
                            desc.value = value
                            @Suppress("DEPRECATION")
                            val writeSuccess = gatt.writeDescriptor(desc)
                            Log.d("BLEManager", "Descriptor write initiated: $writeSuccess")
                        }
                    }
                    Log.d("BLEManager", "Subscribed to characteristic: ${it.uuid}")
                } ?: run {
                    Log.w("BLEManager", "No NOTIFY/INDICATE characteristic found, will try polling")
                    // If no notification support, start polling
                    startPolling()
                }
            } else {
                Log.e("BLEManager", "Service discovery failed with status: $status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLEManager", "Descriptor write successful for ${descriptor.characteristic.uuid}")

                // Try different commands to enable continuous transmission
                writeCharacteristic?.let { char ->
                    // Try multiple command formats that weight scales typically use
                    val commands = listOf(
                        "rnP 2\r",           // Your original command
                        "rnP 2\n",           // Try with newline
                        "rnP 2\r\n",         // Try with both
                        "START\r\n",         // Generic start command
                        "STREAM ON\r\n",     // Another common format
                    )

                    // Send first command
                    bleScope.launch {
                        commands.forEach { command ->
                            delay(500) // Small delay between commands
                            sendCommand(char, command)
                        }

                        // After trying commands, start polling as backup
                        delay(2000)
                        startPolling()
                    }
                }
            } else {
                Log.e("BLEManager", "Descriptor write failed, status: $status")
                // Start polling as fallback
                startPolling()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleReceivedData(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val value = characteristic.value
                handleReceivedData(value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLEManager", "Command write successful")
            } else {
                Log.e("BLEManager", "Command write failed, status: $status")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleReceivedData(value)
            } else {
                Log.e("BLEManager", "Characteristic read failed, status: $status")
            }
        }
    }

    private fun handleReceivedData(value: ByteArray) {
        val receivedString = String(value, Charsets.UTF_8)
        Log.d("BLEManager", "Raw data received: ${receivedString.replace("\r", "\\r").replace("\n", "\\n")}")

        dataBuffer.append(receivedString)

        // Process complete messages (separated by newline)
        var newlineIndex: Int
        while (dataBuffer.indexOf('\n').also { newlineIndex = it } != -1) {
            val completeMessage = dataBuffer.substring(0, newlineIndex + 1).trim()
            if (completeMessage.isNotBlank()) {
                Log.d("BLEManager", "Complete message: $completeMessage")
                _receivedData.value = completeMessage
            }
            dataBuffer.delete(0, newlineIndex + 1)
        }

        // Also check for carriage return
        var crIndex: Int
        while (dataBuffer.indexOf('\r').also { crIndex = it } != -1) {
            val completeMessage = dataBuffer.substring(0, crIndex + 1).trim()
            if (completeMessage.isNotBlank()) {
                Log.d("BLEManager", "Complete message (CR): $completeMessage")
                _receivedData.value = completeMessage
            }
            dataBuffer.delete(0, crIndex + 1)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(characteristic: BluetoothGattCharacteristic, command: String) {
        val data = command.toByteArray(Charsets.US_ASCII)
        Log.d("BLEManager", "Sending command: ${command.replace("\r", "\\r").replace("\n", "\\n")}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            bluetoothGatt?.writeCharacteristic(characteristic)
        }
    }

    // Polling mechanism as fallback
    @SuppressLint("MissingPermission")
    private fun startPolling() {
        if (pollingJob?.isActive == true) {
            Log.d("BLEManager", "Polling already active")
            return
        }

        Log.d("BLEManager", "Starting polling mechanism")
        pollingJob = bleScope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                readCharacteristic?.let { char ->
                    Log.d("BLEManager", "Polling data from ${char.uuid}")
                    bluetoothGatt?.readCharacteristic(char)
                }

                // Also try writing a read request command if write characteristic exists
                writeCharacteristic?.let { char ->
                    // Common weight scale read commands
                    val readCommands = listOf("R\r\n", "READ\r\n", "?\r\n", "GET\r\n")
                    readCommands.forEach { cmd ->
                        sendCommand(char, cmd)
                        delay(500)
                    }
                }

                delay(1000) // Poll every second
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        Log.d("BLEManager", "Polling stopped")
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions(context)) {
            Log.e("BLEManager", "Missing required BLE permissions")
            return
        }

        if (_isScanning.value) {
            return
        }

        scanResults.clear()
        _scannedDevices.value = emptyList()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val scanFilters = listOf<ScanFilter>()
        Log.d("BLEManager", "Starting BLE scan")
        bleScanner?.startScan(scanFilters, scanSettings, scanCallback)
        _isScanning.value = true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) {
            return
        }

        bleScanner?.stopScan(scanCallback)
        _isScanning.value = false
        Log.d("BLEManager", "BLE scan stopped")
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        stopScan()

        Log.d("BLEManager", "Connecting to device: ${device.address}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d("BLEManager", "Connecting with PHY_LE_1M_MASK")
            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK
            )
        } else {
            Log.d("BLEManager", "Connecting without explicit PHY")
            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            _connectionState.value = ConnectionState.DISCONNECTING
            stopPolling()
            bluetoothGatt?.disconnect()
        }
    }

    // Manually send a command to the device
    @SuppressLint("MissingPermission")
    fun sendCustomCommand(command: String): Boolean {
        writeCharacteristic?.let { char ->
            sendCommand(char, command)
            return true
        }
        Log.e("BLEManager", "No write characteristic available")
        return false
    }

    // Manually trigger a read
    @SuppressLint("MissingPermission")
    fun requestData(): Boolean {
        readCharacteristic?.let { char ->
            return bluetoothGatt?.readCharacteristic(char) ?: false
        }
        Log.e("BLEManager", "No read characteristic available")
        return false
    }

    fun filterUnknownDevices(showUnknown: Boolean) {
        if (showUnknown) {
            _scannedDevices.value = scanResults.values.sortedByDescending { it.rssi }.toList()
        } else {
            _scannedDevices.value = scanResults.values
                .filter { it.name != "Unknown Device" }
                .sortedByDescending { it.rssi }
                .toList()
        }
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceName(result: ScanResult): String {
        result.scanRecord?.deviceName?.let { name ->
            if (name.isNotBlank()) return name
        }

        result.device.name?.let { name ->
            if (name.isNotBlank()) return name
        }

        result.scanRecord?.bytes?.let { bytes ->
            val name = parseName(bytes)
            if (name != null && name.isNotBlank()) return name
        }

        return "Unknown Device"
    }

    private fun parseName(scanRecord: ByteArray): String? {
        var currentPos = 0

        while (currentPos < scanRecord.size) {
            val length = scanRecord[currentPos++].toInt() and 0xFF
            if (length == 0) break

            val type = scanRecord[currentPos].toInt() and 0xFF

            if (type == 0x08 || type == 0x09) {
                val nameBytes = scanRecord.copyOfRange(currentPos + 1, currentPos + length)
                return String(nameBytes, Charsets.UTF_8)
            }

            currentPos += length
        }

        return null
    }

    private fun hasPermissions(context: Context): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun cleanup() {
        stopScan()
        stopPolling()
        disconnect()
        bleScope.cancel()
        _weightData.value = null
    }

    data class BleDevice(
        val name: String?,
        val address: String,
        val rssi: Int,
        val device: BluetoothDevice
    )

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }
}
