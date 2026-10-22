package dev.gourav.espwidget.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dev.gourav.espwidget.data.WifiNetwork
import dev.gourav.espwidget.protocol.Frame
import dev.gourav.espwidget.protocol.FragmentAssembler
import dev.gourav.espwidget.protocol.HelloAckPayload
import dev.gourav.espwidget.protocol.HelloPayload
import dev.gourav.espwidget.protocol.Msg
import dev.gourav.espwidget.protocol.StatusPayload
import dev.gourav.espwidget.protocol.WifiScanResult
import dev.gourav.espwidget.protocol.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class BleManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        val SERVICE: UUID = UUID.fromString("8f1d0001-6a4d-4c1e-9a1e-73e2f4b1c001")
        val RX: UUID = UUID.fromString("8f1d0002-6a4d-4c1e-9a1e-73e2f4b1c001")
        val TX: UUID = UUID.fromString("8f1d0003-6a4d-4c1e-9a1e-73e2f4b1c001")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val PREFIX = "ESP-WIDGET-"
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _connection = MutableStateFlow(BleState())
    val connection: StateFlow<BleState> = _connection
    val frames = MutableSharedFlow<Frame>(extraBufferCapacity = 64)
    val deviceName = MutableStateFlow<String?>(null)
    val deviceStatus = MutableStateFlow<StatusPayload?>(null)
    val wifiNetworks = MutableStateFlow<List<WifiNetwork>>(emptyList())
    val lastError = MutableStateFlow<String?>(null)

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    private var currentAddress: String? = null
    private var writeMtu = 20
    private var sequence = 0
    private var frameId = 0
    private val assembler = FragmentAssembler()
    private val outboxLock = Any()
    private val outbox = ArrayDeque<ByteArray>()
    private var inFlight: ByteArray? = null
    private var inFlightRetries = 0
    @Volatile private var writing = false
    @Volatile private var negotiated = false

    // Reconnect bookkeeping: the user tapping "disconnect" is the only thing
    // that stops us from coming back automatically.
    @Volatile private var userDisconnected = false
    private var reconnectAttempts = 0
    private var reconnectJob: kotlinx.coroutines.Job? = null

    val isBluetoothReady: Boolean
        get() = adapter?.isEnabled == true

    fun hasPermissions(): Boolean {
        if (adapter == null) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions() || adapter?.isEnabled != true) {
            lastError.value = "Bluetooth permission or adapter unavailable"
            return
        }
        stopScan()
        val scanner = adapter?.bluetoothLeScanner ?: return
        _connection.value = _connection.value.copy(scanning = true, error = null)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = record?.deviceName ?: result.device.name ?: ""
                val hasService = record?.serviceUuids?.any { it.uuid == SERVICE } == true
                if (name.startsWith(PREFIX) || hasService) {
                    val displayName = if (name.isNotBlank()) name else "$PREFIX${result.device.address.takeLast(5).replace(":", "")}"
                    connect(result.device.address, displayName)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                lastError.value = "Scan failed ($errorCode)"
                _connection.value = _connection.value.copy(scanning = false)
            }
        }
        scanCallback = callback
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE))
            .build()
        scanner.startScan(listOf(filter), settings, callback)
        appScope.launch {
            delay(20_000)
            if (_connection.value.scanning) stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (adapter?.isEnabled == true && hasPermissions()) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
        scanCallback = null
        _connection.value = _connection.value.copy(scanning = false)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, label: String? = null) {
        if (!hasPermissions()) {
            lastError.value = "Missing Bluetooth permission"
            return
        }
        stopScan()
        if (gatt != null && currentAddress == address &&
            (_connection.value.connected || _connection.value.connecting)
        ) return
        closeGatt()
        userDisconnected = false
        reconnectJob?.cancel()
        currentAddress = address
        deviceName.value = label
        negotiated = false
        assembler.reset()
        _connection.value = BleState(connecting = true, deviceName = label, address = address)
        gatt = adapter?.getRemoteDevice(address)?.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun connectFirstPaired() {
        if (!hasPermissions()) return
        val device = adapter?.bondedDevices?.firstOrNull {
            it.name?.startsWith(PREFIX) == true || it.name?.contains("WIDGET", ignoreCase = true) == true
        } ?: return
        connect(device.address, device.name ?: "ESP-Widget")
    }

    /** User-initiated disconnect: stays disconnected until connect() is called again. */
    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        closeGatt()
        _connection.value = _connection.value.copy(connected = false, connecting = false, scanning = false)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        negotiated = false
        rx = null
        tx = null
        clearOutbox()
        val old = gatt
        gatt = null
        // Always close(): a BluetoothGatt that is only disconnect()ed keeps a
        // client slot. Android has very few, and once they run out every
        // connect fails with status 133 until Bluetooth is toggled.
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
    }

    private fun clearOutbox() {
        synchronized(outboxLock) {
            outbox.clear()
            inFlight = null
            inFlightRetries = 0
            writing = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleReconnect() {
        val address = currentAddress ?: return
        if (userDisconnected || !hasPermissions() || adapter?.isEnabled != true) return
        reconnectJob?.cancel()
        val wait = (1_500L shl reconnectAttempts.coerceAtMost(4)).coerceAtMost(30_000L)
        reconnectAttempts++
        reconnectJob = appScope.launch {
            delay(wait)
            if (userDisconnected || _connection.value.connected) return@launch
            closeGatt()
            _connection.value = _connection.value.copy(connecting = true)
            // autoConnect = true lets Android wait for the widget to come back
            // into range (or wake from sleep) without us polling.
            gatt = adapter?.getRemoteDevice(address)
                ?.connectGatt(context, reconnectAttempts > 2, callback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    /**
     * Called periodically: if we should be connected but nothing is in
     * progress (e.g. Bluetooth was switched off and on), start reconnecting.
     */
    fun reconnectIfIdle() {
        if (userDisconnected || currentAddress == null) return
        val state = _connection.value
        if (state.connected || state.connecting || state.scanning) return
        if (reconnectJob?.isActive == true) return
        scheduleReconnect()
    }

    /** Largest payload that still fits the 32-fragment transport at the current MTU. */
    fun maxPhotoChunkBytes(): Int {
        val chunk = (writeMtu - 3 - Wire.FRAG_HEADER).coerceAtLeast(8)
        return (chunk * Wire.MAX_FRAG_PARTS - Wire.HEADER_SIZE - Wire.CRC_SIZE - 4).coerceIn(32, 512)
    }

    fun send(type: Int, payload: ByteArray = ByteArray(0)): Boolean {
        val target = gatt ?: return false
        if (!_connection.value.connected) return false
        val seq = (++sequence) and 0xFFFF
        val frame = Wire.encode(type, seq, payload)
        val chunkPayload = (writeMtu - 3 - Wire.FRAG_HEADER).coerceIn(8, 239)
        val chunks = Wire.fragment(frame, chunkPayload, ++frameId and 0xFF)
        if (chunks.isEmpty()) return false
        synchronized(outboxLock) { outbox.addAll(chunks) }
        pump(target)
        return true
    }

    /**
     * Sends the next chunk. Exactly one write is in flight at a time; a chunk
     * is only dropped from the queue once the stack confirms it, so a busy
     * stack or a write that fails while bonding is retried instead of
     * silently corrupting the frame it belongs to.
     */
    @SuppressLint("MissingPermission")
    private fun pump(target: BluetoothGatt) {
        val characteristic = rx ?: return
        val chunk = synchronized(outboxLock) {
            if (writing) return
            val next = inFlight ?: outbox.removeFirstOrNull() ?: return
            inFlight = next
            writing = true
            next
        }
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            target.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = chunk
            @Suppress("DEPRECATION")
            target.writeCharacteristic(characteristic)
        }
        if (!accepted) {
            // Stack busy (e.g. mid-bonding). Keep the chunk and try again shortly.
            synchronized(outboxLock) { writing = false }
            retryLater(target, 120)
        }
    }

    private fun retryLater(target: BluetoothGatt, delayMs: Long) {
        appScope.launch {
            delay(delayMs)
            if (gatt === target) pump(target)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(target: BluetoothGatt) {
        val characteristic = tx ?: return
        target.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            target.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            target.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    fun requestMtu(mtu: Int = 247) {
        gatt?.requestMtu(mtu)
    }

    @SuppressLint("MissingPermission")
    fun clearBonds() {
        adapter?.bondedDevices?.filter { it.name?.startsWith(PREFIX) == true }?.forEach {
            runCatching { it.javaClass.getMethod("removeBond").invoke(it) }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                this@BleManager.gatt = gatt
                reconnectAttempts = 0
                _connection.value = _connection.value.copy(connecting = true, error = null)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                if (this@BleManager.gatt !== gatt) {
                    // A stale handle from an earlier attempt; just release it.
                    runCatching { gatt.close() }
                    return
                }
                closeGatt()
                _connection.value = _connection.value.copy(connected = false, connecting = false)
                lastError.value = if (status != BluetoothGatt.GATT_SUCCESS && !userDisconnected) {
                    "Widget out of reach, reconnecting..."
                } else {
                    null
                }
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                lastError.value = "Service discovery failed ($status)"
                return
            }
            val service = gatt.getService(SERVICE) ?: run {
                lastError.value = "Widget service not found"
                return
            }
            rx = service.getCharacteristic(RX)
            tx = service.getCharacteristic(TX)
            if (rx == null || tx == null) {
                lastError.value = "Widget characteristics missing"
                return
            }
            if (!gatt.requestMtu(247)) {
                enableNotifications(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            writeMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu.coerceAtLeast(23) else 23
            enableNotifications(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiated = true
                _connection.value = _connection.value.copy(connected = true, connecting = false, error = null)
                send(Msg.HELLO_ACK, HelloAckPayload().encode())
            } else {
                lastError.value = "Notification subscription failed ($status)"
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                synchronized(outboxLock) {
                    inFlight = null
                    inFlightRetries = 0
                    writing = false
                }
                pump(gatt)
                return
            }
            // Usually "insufficient authentication/encryption" while Android
            // runs the pairing dialog. Retry the same chunk once bonding is done.
            val giveUp = synchronized(outboxLock) {
                writing = false
                inFlightRetries++
                inFlightRetries > 8
            }
            if (giveUp) {
                lastError.value = "Widget did not accept data ($status). Try re-pairing."
                clearOutbox()
            } else {
                retryLater(gatt, 700)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handleChunk(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleChunk(value)
        }
    }

    private fun handleChunk(chunk: ByteArray) {
        val frameBytes = assembler.feed(chunk) ?: return
        val frame = Wire.decode(frameBytes)
        if (frame == null) {
            lastError.value = "Bad frame from widget"
            return
        }
        when (frame.type) {
            Msg.HELLO -> HelloPayload.decode(frame.payload)?.let {
                _connection.value = _connection.value.copy(
                    deviceName = it.name,
                    firmware = it.firmware,
                )
                deviceName.value = it.name
            }
            Msg.STATUS -> StatusPayload.decode(frame.payload)?.let { deviceStatus.value = it }
            Msg.WIFI_SCAN_RESULT -> WifiScanResult.decode(frame.payload)?.let { result ->
                wifiNetworks.value = result.aps.map {
                    dev.gourav.espwidget.data.WifiNetwork(it.ssid, it.rssi, it.secure)
                }
            }
            Msg.NACK -> lastError.value = "Widget rejected a message"
        }
        frames.tryEmit(frame)
    }

    fun setError(message: String?) {
        lastError.value = message
    }
}

data class BleState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val scanning: Boolean = false,
    val deviceName: String? = null,
    val address: String? = null,
    val firmware: String? = null,
    val error: String? = null,
)
