package com.example.wearablereceiver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.BufferedWriter
// import java.io.FileOutputStream // No longer directly used by BleService
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * Service for managing BLE connection, communication, and data logging.
 *
 * This service handles scanning for BLE devices, connecting to a specified device,
 * discovering services and characteristics, reading data (like battery level),
 * subscribing to notifications (like heart rate), and logging received data to a file.
 * It also provides a binding interface for `MainActivity` to interact with it.
 */
class BleService : Service() {

    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var batteryLevelCharacteristic: BluetoothGattCharacteristic? = null
    private var heartRateReceiverCharacteristic: BluetoothGattCharacteristic? = null
    private var workoutCharacteristic: BluetoothGattCharacteristic? = null

    private var fileWriterHandlerThread: HandlerThread? = null
    private var fileWriterHandler: Handler? = null
    private lateinit var heartRateFileLogger: HeartRateFileLogger

    // TODO: REPLACE WITH YOUR WEARABLE'S MAC ADDRESS (Can be passed via Intent if dynamic)
    private var targetDeviceAddress: String = "78:02:B7:78:38:39" // Default, can be overridden

    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD: Long = 15000

    private var mIsConnected: Boolean = false
    private var isSubscribedToNotifications: Boolean = false
    private var currentGeneralStatus: String = "Status Idle" // Instance variable

    /**
     * Binder for clients (e.g., `MainActivity`) to interact with this service.
     */
    inner class LocalBinder : Binder() {
        /** Returns this instance of BleService so clients can call public methods. */
        fun getService(): BleService = this@BleService
    }

    /**
     * Called when a client binds to the service.
     * @param intent The Intent that was used to bind to this service.
     * @return Return an IBinder through which clients can call on to the service.
     */
    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "BleService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "BleServiceChannel"

        // currentGeneralStatus has been moved to an instance variable
        @JvmField
        var isServiceRunning:Boolean = false
        // Actions for Intents
        const val ACTION_START_SCAN = "com.example.wearablereceiver.ACTION_START_SCAN"
        const val ACTION_CONNECT_DEVICE = "com.example.wearablereceiver.ACTION_CONNECT_DEVICE"
        const val ACTION_DISCONNECT = "com.example.wearablereceiver.ACTION_DISCONNECT"
        const val ACTION_REQUEST_BATTERY = "com.example.wearablereceiver.ACTION_REQUEST_BATTERY"
        const val ACTION_TOGGLE_NOTIFY = "com.example.wearablereceiver.ACTION_TOGGLE_NOTIFY"
        const val ACTION_STOP_SERVICE = "com.example.wearablereceiver.ACTION_STOP_SERVICE"

        const val ACTION_REQUEST_CURRENT_STATE = "com.example.wearablereceiver.ACTION_REQUEST_CURRENT_STATE"
        // TODO: Add KDocs for public constants if desired, e.g., for actions and extras.
        // For brevity in this exercise, focusing on classes and methods.

        // Extra keys for Intents
        const val EXTRA_DEVICE_ADDRESS = "com.example.wearablereceiver.EXTRA_DEVICE_ADDRESS"

        // Broadcast Actions from Service to Activity
        const val ACTION_STATUS_UPDATE = "com.example.wearablereceiver.ACTION_STATUS_UPDATE"
        const val ACTION_BATTERY_LEVEL_UPDATE = "com.example.wearablereceiver.ACTION_BATTERY_LEVEL_UPDATE"
        const val ACTION_NOTIFICATION_DATA_UPDATE = "com.example.wearablereceiver.ACTION_NOTIFICATION_DATA_UPDATE"
        const val ACTION_SERVICES_DISCOVERED_STATUS = "com.example.wearablereceiver.ACTION_SERVICES_DISCOVERED_STATUS"
        const val ACTION_GATT_CONNECTED = "com.example.wearablereceiver.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "com.example.wearablereceiver.ACTION_GATT_DISCONNECTED"
        const val ACTION_SCAN_FAILED = "com.example.wearablereceiver.ACTION_SCAN_FAILED"
        const val ACTION_DEVICE_FOUND = "com.example.wearablereceiver.ACTION_DEVICE_FOUND"
        const val ACTION_NOTIFICATION_SUBSCRIPTION_CHANGED = "com.example.wearablereceiver.ACTION_NOTIFICATION_SUBSCRIPTION_CHANGED"


        // Extra keys for Broadcasts
        const val EXTRA_STATUS_MESSAGE = "com.example.wearablereceiver.EXTRA_STATUS_MESSAGE"
        const val EXTRA_BATTERY_LEVEL = "com.example.wearablereceiver.EXTRA_BATTERY_LEVEL"
        const val EXTRA_NOTIFICATION_DATA = "com.example.wearablereceiver.EXTRA_NOTIFICATION_DATA"
        const val EXTRA_IS_BATTERY_READY = "com.example.wearablereceiver.EXTRA_IS_BATTERY_READY"
        const val EXTRA_IS_NOTIFY_READY = "com.example.wearablereceiver.EXTRA_IS_NOTIFY_READY"
        const val EXTRA_IS_SUBSCRIBED = "com.example.wearablereceiver.EXTRA_IS_SUBSCRIBED"
        const val EXTRA_SCAN_ERROR_CODE = "com.example.wearablereceiver.EXTRA_SCAN_ERROR_CODE"


        // UUIDs
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("000055ff-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_READER_CHARACTERISTIC_UUID: UUID = UUID.fromString("000033f2-0000-1000-8000-00805f9b34fb")
        val WORKOUT_CHARACTERISTIC_UUID: UUID = UUID.fromString("000033f1-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    /**
     * Called by the system when the service is first created.
     * Initializes Bluetooth adapter, scanner, notification channel, and file logger.
     */
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        createNotificationChannel()
        currentGeneralStatus = "Service Initializing" // Now correctly refers to instance var
        val notification = createNotification("BLE Service Running")
        startForeground(NOTIFICATION_ID, notification)

        fileWriterHandlerThread = HandlerThread("FileWriterThread").apply { start() }
        fileWriterHandler = Handler(fileWriterHandlerThread!!.looper)
        heartRateFileLogger = HeartRateFileLogger(applicationContext, fileWriterHandler!!) { message ->
           broadcastStatus(message)
        }
        Log.d(TAG, "BleService created and started in foreground.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "BLE Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wearable Receiver")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }


    /**
     * Called by the system every time a client starts the service using `startService(Intent)`.
     * Handles incoming intents, which can trigger actions like scanning, connecting, or data requests.
     * @param intent The Intent supplied to `startService(Intent)`.
     * @param flags Additional data about this start request.
     * @param startId A unique integer representing this specific request to start.
     * @return The return value indicates what semantics the system should use for the service's
     *         current started state.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action: ${intent?.action}")
        intent?.action?.let { action ->
            intent.getStringExtra(EXTRA_DEVICE_ADDRESS)?.let {
                targetDeviceAddress = it
                Log.d(TAG, "Target device address updated to: $targetDeviceAddress")
            }

            when (action) {
                ACTION_REQUEST_CURRENT_STATE -> broadcastCurrentStateSnapshot()
                ACTION_START_SCAN -> startScan()
                ACTION_CONNECT_DEVICE -> {
                    val device = bluetoothAdapter?.getRemoteDevice(targetDeviceAddress)
                    device?.let { connectToDevice(it) }
                        ?: Log.e(TAG, "Cannot connect, device address invalid or BT adapter null")
                }
                ACTION_DISCONNECT -> disconnectDevice()
                ACTION_REQUEST_BATTERY -> requestBatteryLevel()
                ACTION_TOGGLE_NOTIFY -> toggleNotificationSubscription()
                ACTION_STOP_SERVICE -> {
                    isServiceRunning = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                else -> Log.w(TAG, "Unknown action received: $action")
            }
        }
        return START_NOT_STICKY
    }

    // --- Public methods for Bound Service Interaction ---

    /**
     * Represents the various states of BLE connection.
     */
    enum class ConnectionState {
        /** No active connection or connection attempt. */
        DISCONNECTED,
        /** Actively scanning for BLE devices. */
        SCANNING,
        /** Attempting to connect to a BLE device. */
        CONNECTING,
        /** Actively connected to a BLE device. */
        CONNECTED
    }

    /**
     * Initiates a BLE scan for the target device.
     * Assumes `targetDeviceAddress` is set, either by default or via `onStartCommand`.
     */
    fun requestStartScan() {
        startScan()
    }

    /**
     * Attempts to connect to a BLE device at the given address.
     * @param address The MAC address of the BLE device to connect to.
     */
    @SuppressLint("MissingPermission")
    fun requestConnectDevice(address: String) {
        if (bluetoothAdapter?.checkBluetoothAddress(address) == true) {
            targetDeviceAddress = address // Update target address for subsequent operations
            val device = bluetoothAdapter!!.getRemoteDevice(address)
            connectToDevice(device)
        } else {
            Log.e(TAG, "Invalid Bluetooth address provided for connect: $address")
            broadcastStatus("Error: Invalid device address.")
        }
    }

    /**
     * Requests disconnection from the currently connected BLE device.
     */
    fun requestDisconnect() {
        disconnectDevice()
    }

    /**
     * Requests a read of the battery level from the connected device.
     * Requires `isBatteryCharacteristicReady()` to be true.
     */
    fun requestBatteryLevelUpdate() {
        requestBatteryLevel()
    }

    /**
     * Toggles the notification subscription for the heart rate characteristic.
     * Requires `isNotifyCharacteristicReady()` to be true.
     */
    fun requestToggleNotify() {
        toggleNotificationSubscription()
    }

    /**
     * @return The current BLE connection state.
     */
    fun getCurrentConnectionState(): ConnectionState {
        return when {
            mIsConnected -> ConnectionState.CONNECTED
            isScanning -> ConnectionState.SCANNING
            bluetoothGatt != null && !mIsConnected -> ConnectionState.CONNECTING // Check gatt object for connecting state
            else -> ConnectionState.DISCONNECTED
        }
    }
    
    /**
     * @return True if the heart rate notification characteristic is discovered and supports notifications.
     */
    fun isNotifyCharacteristicReady(): Boolean {
        return heartRateReceiverCharacteristic != null &&
                (heartRateReceiverCharacteristic!!.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0
    }

    /**
     * @return True if the battery level characteristic is discovered and readable.
     */
    fun isBatteryCharacteristicReady(): Boolean {
        return batteryLevelCharacteristic != null // Assuming it should be readable if not null
    }

    /**
     * @return True if currently subscribed to heart rate notifications.
     */
    fun getSubscriptionState(): Boolean {
        return isSubscribedToNotifications
    }

    // --- End of Public methods ---

    private fun broadcastCurrentStateSnapshot() {
        Log.d(TAG, "Broadcasting current state snapshot")

        if (mIsConnected) {
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_GATT_CONNECTED))

            val batteryReady = batteryLevelCharacteristic != null
            val notifyReady = heartRateReceiverCharacteristic != null &&
                    (heartRateReceiverCharacteristic!!.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0
            var detail = "Sync: "
            detail += if (batteryReady) "Batt OK. " else "Batt N/A. "
            detail += if (notifyReady) "Notify OK." else "Notify N/A."
            broadcastServiceDiscoveredStatus(batteryReady, notifyReady, detail)

            if (heartRateReceiverCharacteristic != null) {
                broadcastSubscriptionChanged(isSubscribedToNotifications)
            }
        } else {
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_GATT_DISCONNECTED))
        }
        broadcastStatus(currentGeneralStatus) // This also updates the notification
        // updateNotification(currentGeneralStatus.substringAfter("Status: ").trim()) // Redundant call removed
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!checkServicePermissions(scanPermission)) {
            val errorMsg = "Scan permission ($scanPermission) missing for service. Cannot start scan."
            Log.e(TAG, errorMsg)
            broadcastStatus(errorMsg)
            updateNotification("Scan Permission Missing")
            return
        }

        if (isScanning) {
            Log.d(TAG, "Scan already in progress.")
            return
        }
        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "Failed to get BluetoothLeScanner.")
                broadcastStatus("Cannot get BLE Scanner.")
                return
            }
        }

        broadcastStatus("Status: Scanning...")
        updateNotification("Scanning for $targetDeviceAddress")
        Log.d(TAG, "Starting BLE scan...")
        isScanning = true
        handler.postDelayed({
            if (isScanning) {
                stopScan()
                Log.d(TAG, "Scan timeout.")
                broadcastStatus("Status: Scan timeout. Device not found.")
                isServiceRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, SCAN_PERIOD)
        bluetoothLeScanner?.startScan(leScanCallback)
        Log.d(TAG, "Scan initiated.")
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return
        val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!checkServicePermissions(scanPermission)) {
            Log.e(TAG, "$scanPermission permission not granted for stopping scan in service.")
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
            } catch (se: SecurityException){
                Log.e(TAG, "SecurityException while stopping scan without permission: ${se.message}")
            }
            isScanning = false
            return
        }
        bluetoothLeScanner?.stopScan(leScanCallback)
        isScanning = false
        Log.d(TAG, "BLE scan stopped.")
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { device ->
                val deviceName = if (checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
                    device.name ?: "Unknown"
                } else {
                    "Unknown (No Connect Perm)"
                }
                Log.d(TAG, "Device found: $deviceName - ${device.address}")
                broadcastDeviceFound(device.address, deviceName)

                if (device.address == targetDeviceAddress) {
                    Log.d(TAG, "Target device found: ${device.address}")
                    stopScan()
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "BLE Scan Failed with error code: $errorCode")
            broadcastStatus("Status: Scan failed ($errorCode)")
            updateNotification("Scan failed")
            val intent = Intent(ACTION_SCAN_FAILED)
            intent.putExtra(EXTRA_SCAN_ERROR_CODE, errorCode)
            LocalBroadcastManager.getInstance(this@BleService).sendBroadcast(intent)
            isScanning = false
            isServiceRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            broadcastStatus("Connect permission missing for service.")
            return
        }
        stopScan()
        val deviceName = device.name ?: device.address
        broadcastStatus("Status: Connecting to $deviceName...")
        updateNotification("Connecting to $deviceName")
        Log.d(TAG, "Attempting to connect to GATT server on device: ${device.address}")

        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceAddress = gatt?.device?.address ?: "Unknown address"
            Log.d(TAG, "onConnectionStateChange: Address: $deviceAddress, Status: $status, NewState: $newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> handleGattConnected(gatt)
                BluetoothProfile.STATE_DISCONNECTED -> handleGattDisconnected(deviceAddress)
                BluetoothProfile.STATE_CONNECTING -> {
                    broadcastStatus("Status: Connecting...")
                    updateNotification("Connecting...")
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    broadcastStatus("Status: Disconnecting...")
                    updateNotification("Disconnecting...")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            processDiscoveredServices(gatt, status)
        }

        @Deprecated("Used for devices prior to Tiramisu")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) return
                handleCharacteristicRead(characteristic, characteristic.value, status)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
                 Log.e(TAG, "BLUETOOTH_CONNECT permission missing for onCharacteristicRead.")
                return
            }
            handleCharacteristicRead(characteristic, value, status)
        }

        private fun handleCharacteristicRead(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicRead UUID: ${characteristic.uuid}, Status: $status, Value: ${value.toHexString()}")
            if (characteristic.uuid == BATTERY_LEVEL_CHARACTERISTIC_UUID) {
                processBatteryLevelRead(value, status)
            } else {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                     Log.w(TAG, "Characteristic read failed for ${characteristic.uuid}, status: $status")
                }
                // Handle other characteristics if any
            }
        }

        @Deprecated("Used for devices prior to Tiramisu")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) return
                handleCharacteristicChanged(characteristic, characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission missing for onCharacteristicChanged.")
                return
            }
            handleCharacteristicChanged(characteristic, value)
        }

        // writeCount is now managed by HeartRateFileLogger

        private fun handleCharacteristicChanged(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "onCharacteristicChanged UUID: ${characteristic.uuid}, Value: ${value.toHexString()}")
            if (characteristic.uuid == HEART_RATE_READER_CHARACTERISTIC_UUID) {
                processHeartRateData(value)
            }
            // Handle other characteristic changes if any
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission missing for onDescriptorWrite.")
                return
            }

            if (descriptor?.uuid == CCCD_UUID && descriptor.characteristic.uuid == HEART_RATE_READER_CHARACTERISTIC_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    isSubscribedToNotifications = !isSubscribedToNotifications
                    handleSuccessfulDescriptorWrite(isSubscribedToNotifications)
                } else {
                    Log.e(TAG, "CCCD write for HR Notify failed: $status")
                    broadcastStatus("Failed to set notifications")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestBatteryLevel() {
        if (!mIsConnected || bluetoothGatt == null || batteryLevelCharacteristic == null) {
            broadcastStatus("Not ready to read battery.")
            return
        }
        if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            broadcastStatus("Connect permission missing to read battery.")
            return
        }
        val success = bluetoothGatt?.readCharacteristic(batteryLevelCharacteristic)
        Log.d(TAG, "Attempting to read battery level, success: $success")
    }

    @SuppressLint("MissingPermission")
    private fun toggleNotificationSubscription() {
        if (!mIsConnected || bluetoothGatt == null || heartRateReceiverCharacteristic == null) {
            broadcastStatus("Not ready for notifications.")
            return
        }
        if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            broadcastStatus("Connect permission missing for notifications.")
            return
        }

        val characteristic = heartRateReceiverCharacteristic!!
        val enable = !isSubscribedToNotifications

        val notificationSet = bluetoothGatt?.setCharacteristicNotification(characteristic, enable)
        Log.d(TAG, "setCharacteristicNotification for ${characteristic.uuid} to $enable: $notificationSet")

        val cccdDescriptor = characteristic.getDescriptor(CCCD_UUID)
        if (cccdDescriptor != null) {
            val descriptorValue = if (enable) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            Log.d(TAG, "Writing to CCCD ${cccdDescriptor.uuid}: ${descriptorValue.toHexString()} to enable: $enable")

            val writeSuccess: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeDescriptor(cccdDescriptor, descriptorValue) == BluetoothStatusCodes.SUCCESS
            } else {
                cccdDescriptor.value = descriptorValue
                bluetoothGatt?.writeDescriptor(cccdDescriptor) ?: false
            }
            Log.d(TAG, "writeDescriptor for CCCD call result: $writeSuccess")
        } else {
            Log.w(TAG, "CCCD not found for characteristic ${characteristic.uuid}. Assuming notifications toggled by setCharacteristicNotification.")
            isSubscribedToNotifications = enable
            broadcastStatus("Notifications ${if (enable) "Enabled (No CCCD)" else "Disabled (No CCCD)"}")
            broadcastSubscriptionChanged(enable)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectDevice() {
        if (bluetoothGatt == null) {
            Log.d(TAG, "disconnectDevice called, but GATT is already null.")
            return
        }
        if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            broadcastStatus("Connect permission missing for disconnect.")
            closeGatt()
            mIsConnected = false
            broadcastStatus("Status: Disconnected (Perm Error)")
            updateNotification("Disconnected (Perm Error)")
            LocalBroadcastManager.getInstance(this@BleService).sendBroadcast(Intent(ACTION_GATT_DISCONNECTED))
            return
        }

        if (isSubscribedToNotifications && heartRateReceiverCharacteristic != null) {
            Log.d(TAG, "Attempting to unsubscribe from notifications before disconnecting.")
            bluetoothGatt?.setCharacteristicNotification(heartRateReceiverCharacteristic!!, false)
            val cccd = heartRateReceiverCharacteristic!!.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                val value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothGatt?.writeDescriptor(cccd, value)
                } else {
                    cccd.value = value
                    bluetoothGatt?.writeDescriptor(cccd)
                }
            }
        }
        bluetoothGatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        if (bluetoothGatt == null) {
            Log.d(TAG, "closeGatt called, but GATT is already null. Service may stop if not running.")
            if (!isServiceRunning) {
                 heartRateFileLogger.stopRecording(false)
                 stopForeground(STOP_FOREGROUND_REMOVE)
                 stopSelf()
            }
            return
        }
        if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for closing GATT in service.")
        } else {
            bluetoothGatt?.disconnect()
        }
        bluetoothGatt?.close()
        bluetoothGatt = null
        batteryLevelCharacteristic = null
        heartRateReceiverCharacteristic = null
        workoutCharacteristic = null
        mIsConnected = false
        Log.d(TAG, "GATT closed.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BleService destroyed.")
        stopScan()
        isServiceRunning = false
        disconnectDevice()
        closeGatt()

        handler.removeCallbacksAndMessages(null)
        heartRateFileLogger.close() // Close the logger
        fileWriterHandlerThread?.quitSafely() // Quit the handler thread for file I/O
        // stopRecordingToFile(false) // This was already handled by heartRateFileLogger.close()

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "BleService cleanup complete.")
    }

    // --- Helper methods for broadcasting ---
    private fun broadcastStatus(message: String) {
        currentGeneralStatus = message // Instance variable is now updated
        val intent = Intent(ACTION_STATUS_UPDATE)
        intent.putExtra(EXTRA_STATUS_MESSAGE, message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        if (message.startsWith("Status:")) {
            updateNotification(message.substringAfter("Status: ").trim())
        } else {
            updateNotification(message)
        }
    }

    private fun broadcastDeviceFound(address: String, name: String) {
        val intent = Intent(ACTION_DEVICE_FOUND)
        intent.putExtra(EXTRA_DEVICE_ADDRESS, address)
        intent.putExtra(EXTRA_STATUS_MESSAGE, "Found: $name ($address)")
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastBatteryLevel(level: Int) {
        val intent = Intent(ACTION_BATTERY_LEVEL_UPDATE)
        intent.putExtra(EXTRA_BATTERY_LEVEL, level)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastNotificationData(data: String) {
        val intent = Intent(ACTION_NOTIFICATION_DATA_UPDATE)
        intent.putExtra(EXTRA_NOTIFICATION_DATA, data)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastServiceDiscoveredStatus(batteryReady: Boolean, notifyReady: Boolean, detail: String) {
        val intent = Intent(ACTION_SERVICES_DISCOVERED_STATUS)
        intent.putExtra(EXTRA_IS_BATTERY_READY, batteryReady)
        intent.putExtra(EXTRA_IS_NOTIFY_READY, notifyReady)
        intent.putExtra(EXTRA_STATUS_MESSAGE, detail)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastSubscriptionChanged(isSubscribed: Boolean) {
        val intent = Intent(ACTION_NOTIFICATION_SUBSCRIPTION_CHANGED)
        intent.putExtra(EXTRA_IS_SUBSCRIBED, isSubscribed)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun checkServicePermissions(vararg permissions: String): Boolean {
        return permissions.all { permission ->
            ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(commandBytes: ByteArray) {
        if (!mIsConnected || bluetoothGatt == null || workoutCharacteristic == null) {
            Log.w(TAG, "Cannot write command: Not connected or command characteristic not available.")
            broadcastStatus("Error: Command characteristic not ready.")
            return
        }
        if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission missing for writeCommand.")
            broadcastStatus("Error: Connect permission missing for command.")
            return
        }

        val char = workoutCharacteristic!!
        Log.d(TAG, "Writing command: ${commandBytes.toHexString()} to ${char.uuid}")

        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) > 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            Log.e(TAG, "Command characteristic ${char.uuid} is not writable.")
            broadcastStatus("Error: Command characteristic not writable.")
            return
        }

        val success: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(char, commandBytes, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            char.value = commandBytes
            char.writeType = writeType
            bluetoothGatt?.writeCharacteristic(char) ?: false
        }
        Log.d(TAG, "writeCharacteristic for command ${commandBytes.toHexString()} to ${char.uuid} with type $writeType, system call result: $success")
    }

    // Old startRecordingToFile and stopRecordingToFile methods are removed as their logic
    // is now handled by HeartRateFileLogger.

    private fun ByteArray.toHexString(): String =
        joinToString(separator = " ") { byte -> "%02X".format(byte) }

    @SuppressLint("MissingPermission")
    private fun handleGattConnected(gatt: BluetoothGatt?) {
        val deviceAddress = gatt?.device?.address ?: "Unknown address"
        Log.i(TAG, "Successfully connected to $deviceAddress")
        mIsConnected = true
        val deviceName = if (checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            gatt?.device?.name ?: deviceAddress
        } else {
            deviceAddress
        }
        broadcastStatus("Status: Connected to $deviceName")
        updateNotification("Connected to $deviceName")
        LocalBroadcastManager.getInstance(this@BleService).sendBroadcast(Intent(ACTION_GATT_CONNECTED))

        Log.d(TAG, "Attempting to discover services...")
        if (checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            val discovered = gatt?.discoverServices()
            Log.d(TAG, "discoverServices() called, result: $discovered")
        } else {
            Log.e(TAG, "Cannot discover services, BLUETOOTH_CONNECT permission missing.")
            broadcastStatus("Status: Connected (Service discovery failed - perm missing)")
        }
    }

    private fun handleGattDisconnected(deviceAddress: String?) {
        Log.i(TAG, "Disconnected from $deviceAddress")
        mIsConnected = false
        isSubscribedToNotifications = false
        broadcastStatus("Status: Disconnected")
        updateNotification("Disconnected")
        LocalBroadcastManager.getInstance(this@BleService).sendBroadcast(Intent(ACTION_GATT_DISCONNECTED))
        heartRateFileLogger.stopRecording(false)
        closeGatt()
        isServiceRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun processDiscoveredServices(gatt: BluetoothGatt?, status: Int) {
        if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w(TAG, "BLUETOOTH_CONNECT missing, cannot process discovered services.")
            broadcastServiceDiscoveredStatus(false, false, "Perm missing for service discovery")
            return
        }

        var batteryReady = false
        var notifyReady = false
        var detailMessage = ""

        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.i(TAG, "Services discovered successfully!")
            val batteryService = gatt?.getService(BATTERY_SERVICE_UUID)
            batteryLevelCharacteristic = batteryService?.getCharacteristic(BATTERY_LEVEL_CHARACTERISTIC_UUID)
            batteryReady = batteryLevelCharacteristic != null
            if (!batteryReady) detailMessage += "Battery Char N/A. "

            val heartRateService = gatt?.getService(HEART_RATE_SERVICE_UUID)
            heartRateReceiverCharacteristic = heartRateService?.getCharacteristic(HEART_RATE_READER_CHARACTERISTIC_UUID)
            notifyReady = heartRateReceiverCharacteristic != null &&
                    (heartRateReceiverCharacteristic!!.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0
            if (!notifyReady) detailMessage += "Notify Char N/A or no notify prop. "

            workoutCharacteristic = heartRateService?.getCharacteristic(WORKOUT_CHARACTERISTIC_UUID)
            val workoutIsWritable = workoutCharacteristic != null &&
                    ((workoutCharacteristic!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) > 0 ||
                            (workoutCharacteristic!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) > 0)
            if (!workoutIsWritable) detailMessage += "Workout Char N/A or no write prop. "

        } else {
            Log.w(TAG, "Service discovery failed with status: $status")
            detailMessage = "Discovery Fail ($status)"
        }
        broadcastServiceDiscoveredStatus(batteryReady, notifyReady, detailMessage.ifEmpty { "Ready" })
    }

    private fun logHeartRateDataToFile(timestamp: Long, heartRate: Int) {
        heartRateFileLogger.logData(timestamp, heartRate)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun handleSuccessfulDescriptorWrite(isNowSubscribed: Boolean) {
        broadcastStatus("Notifications ${if (isNowSubscribed) "Enabled" else "Disabled"}")
        broadcastSubscriptionChanged(isNowSubscribed)
        if (isNowSubscribed) {
            val startWorkout = byteArrayOf(0xFD.toByte(), 0x11.toByte(), 0x01.toByte(), 0x01.toByte())
            writeCommand(startWorkout)
            heartRateFileLogger.startRecording()
        } else {
            val endWorkout = byteArrayOf(0xFD.toByte(), 0x00.toByte(), 0x01.toByte(), 0x01.toByte())
            writeCommand(endWorkout)
            heartRateFileLogger.stopRecording()
        }
        Log.i(TAG, "CCCD for HR Notify processed. Notifications now ${if (isNowSubscribed) "enabled" else "disabled"}.")
    }

    private fun processBatteryLevelRead(value: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (value.isNotEmpty()) {
                val batteryLevel = value[0].toInt() and 0xFF
                Log.i(TAG, "Battery Level read: $batteryLevel%")
                broadcastBatteryLevel(batteryLevel)
            } else {
                Log.w(TAG, "Battery Level characteristic read but value is empty.")
                broadcastBatteryLevel(-1)
            }
        } else {
            Log.w(TAG, "Battery Level read failed with status: $status")
            broadcastBatteryLevel(-2)
        }
    }

    private fun processHeartRateData(value: ByteArray) {
        if (value.size >= 4 &&
            value[0] == 0xE5.toByte() &&
            value[1] == 0x11.toByte() &&
            value[2] == 0x00.toByte()
        ) {
            val heartRateHexByte = value[3]
            val heartRateDecimal = heartRateHexByte.toInt() and 0xFF
            Log.i(TAG, "Heart Rate Data: $heartRateDecimal bpm")
            broadcastNotificationData("HR: $heartRateDecimal bpm")
            logHeartRateDataToFile(System.currentTimeMillis(), heartRateDecimal) // This call remains
        } else {
            Log.w(TAG, "Received unexpected data format for HR characteristic: ${value.toHexString()}")
        }
    }
}