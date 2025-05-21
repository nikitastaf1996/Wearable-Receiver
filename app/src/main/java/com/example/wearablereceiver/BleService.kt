package com.example.wearablereceiver

import android.Manifest
import java.io.OutputStreamWriter
import android.content.ContentValues // <--- ADD THIS
import android.provider.MediaStore    // <--- ADD THIS
import android.os.Environment       // <--- ADD THIS (for Environment.DIRECTORY_DOWNLOADS)
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.util.UUID

class BleService : Service() {

    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var batteryLevelCharacteristic: BluetoothGattCharacteristic? = null
    private var heartRateReceiverCharacteristic: BluetoothGattCharacteristic? = null
    private var workoutCharacteristic: BluetoothGattCharacteristic? = null
    private var fileOutputStream: FileOutputStream? = null
    private var writer: BufferedWriter? = null
    private var currentFileUri: Uri? = null // To store the URI of the file being written

    private var fileWriterHandlerThread: HandlerThread? = null
    private var fileWriterHandler: Handler? = null

    // TODO: REPLACE WITH YOUR WEARABLE'S MAC ADDRESS (Can be passed via Intent if dynamic)
    private var targetDeviceAddress: String = "78:02:B7:78:38:39" // Default, can be overridden

    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val SCAN_PERIOD: Long = 15000 // Increased scan period for service

    private var mIsConnected: Boolean = false
    private var isSubscribedToNotifications: Boolean = false

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    override fun onBind(intent: Intent?): IBinder = binder // Or return null if you don't need binding

    companion object {
        private const val TAG = "BleService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "BleServiceChannel"

        private var currentGeneralStatus: String = "Status Idle" // Or some initial default
        @JvmField
        var isServiceRunning:Boolean = false
        // Actions for Intents
        const val ACTION_START_SCAN = "com.example.wearablereceiver.ACTION_START_SCAN"
        const val ACTION_CONNECT_DEVICE = "com.example.wearablereceiver.ACTION_CONNECT_DEVICE" // If address is known
        const val ACTION_DISCONNECT = "com.example.wearablereceiver.ACTION_DISCONNECT"
        const val ACTION_REQUEST_BATTERY = "com.example.wearablereceiver.ACTION_REQUEST_BATTERY"
        const val ACTION_TOGGLE_NOTIFY = "com.example.wearablereceiver.ACTION_TOGGLE_NOTIFY"
        const val ACTION_STOP_SERVICE = "com.example.wearablereceiver.ACTION_STOP_SERVICE"

        const val ACTION_REQUEST_CURRENT_STATE = "com.example.wearablereceiver.ACTION_REQUEST_CURRENT_STATE"

        // Extra keys for Intents
        const val EXTRA_DEVICE_ADDRESS = "com.example.wearablereceiver.EXTRA_DEVICE_ADDRESS"

        // Broadcast Actions from Service to Activity
        const val ACTION_STATUS_UPDATE = "com.example.wearablereceiver.ACTION_STATUS_UPDATE"
        const val ACTION_BATTERY_LEVEL_UPDATE = "com.example.wearablereceiver.ACTION_BATTERY_LEVEL_UPDATE"
        const val ACTION_NOTIFICATION_DATA_UPDATE = "com.example.wearablereceiver.ACTION_NOTIFICATION_DATA_UPDATE"
        const val ACTION_SERVICES_DISCOVERED_STATUS = "com.example.wearablereceiver.ACTION_SERVICES_DISCOVERED_STATUS" // To enable buttons
        const val ACTION_GATT_CONNECTED = "com.example.wearablereceiver.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "com.example.wearablereceiver.ACTION_GATT_DISCONNECTED"
        const val ACTION_SCAN_FAILED = "com.example.wearablereceiver.ACTION_SCAN_FAILED"
        const val ACTION_DEVICE_FOUND = "com.example.wearablereceiver.ACTION_DEVICE_FOUND" // Could be useful
        const val ACTION_NOTIFICATION_SUBSCRIPTION_CHANGED = "com.example.wearablereceiver.ACTION_NOTIFICATION_SUBSCRIPTION_CHANGED"


        // Extra keys for Broadcasts
        const val EXTRA_STATUS_MESSAGE = "com.example.wearablereceiver.EXTRA_STATUS_MESSAGE"
        const val EXTRA_BATTERY_LEVEL = "com.example.wearablereceiver.EXTRA_BATTERY_LEVEL"
        const val EXTRA_NOTIFICATION_DATA = "com.example.wearablereceiver.EXTRA_NOTIFICATION_DATA"
        const val EXTRA_IS_BATTERY_READY = "com.example.wearablereceiver.EXTRA_IS_BATTERY_READY"
        const val EXTRA_IS_NOTIFY_READY = "com.example.wearablereceiver.EXTRA_IS_NOTIFY_READY"
        const val EXTRA_IS_SUBSCRIBED = "com.example.wearablereceiver.EXTRA_IS_SUBSCRIBED"
        const val EXTRA_SCAN_ERROR_CODE = "com.example.wearablereceiver.EXTRA_SCAN_ERROR_CODE"


        // UUIDs (same as before)
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("000055ff-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_READER_CHARACTERISTIC_UUID: UUID = UUID.fromString("000033f2-0000-1000-8000-00805f9b34fb")
        val WORKOUT_CHARACTERISTIC_UUID: UUID = UUID.fromString("000033f1-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        createNotificationChannel()
        currentGeneralStatus = "Service Initializing"
        val notification = createNotification("BLE Service Running")
        startForeground(NOTIFICATION_ID, notification)
        fileWriterHandlerThread = HandlerThread("FileWriterThread").apply { start() }
        fileWriterHandler = Handler(fileWriterHandlerThread!!.looper)
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
        val pendingIntentFlags =
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action: ${intent?.action}")
        intent?.action?.let { action ->
            // Update target address if provided
            intent.getStringExtra(EXTRA_DEVICE_ADDRESS)?.let {
                targetDeviceAddress = it
                Log.d(TAG, "Target device address updated to: $targetDeviceAddress")
            }

            when (action) {
                ACTION_REQUEST_CURRENT_STATE -> {
                    broadcastCurrentStateSnapshot()
                }
                ACTION_START_SCAN -> startScan()
                ACTION_CONNECT_DEVICE -> { // Assuming device address is already set or passed
                    // This would typically be called after device is found by scan
                    // For now, we assume targetDeviceAddress is set
                    val device = bluetoothAdapter?.getRemoteDevice(targetDeviceAddress)
                    device?.let { connectToDevice(it) } ?: Log.e(TAG, "Cannot connect, device address invalid or BT adapter null")
                }
                ACTION_DISCONNECT -> disconnectDevice()
                ACTION_REQUEST_BATTERY -> requestBatteryLevel()
                ACTION_TOGGLE_NOTIFY -> toggleNotificationSubscription()
                ACTION_STOP_SERVICE -> {
                    isServiceRunning = false
                    stopSelf() // This will trigger onDestroy
                    return START_NOT_STICKY // Don't restart
                }

                else -> {}
            }
        }
        return START_NOT_STICKY // If service is killed, restart it with the last intent
    }


    private fun broadcastCurrentStateSnapshot() {
        Log.d(TAG, "Broadcasting current state snapshot")

        // 1. Connection Status
        if (mIsConnected) {
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_GATT_CONNECTED))

            // 2. Services Discovered Status (only if connected)
            val batteryReady = batteryLevelCharacteristic != null
            val notifyReady = heartRateReceiverCharacteristic != null &&
                    (heartRateReceiverCharacteristic!!.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0
            var detail = "Sync: "
            if (batteryReady) detail += "Batt OK. " else detail += "Batt N/A. "
            if (notifyReady) detail += "Notify OK." else detail += "Notify N/A."
            broadcastServiceDiscoveredStatus(batteryReady, notifyReady, detail)

            // 3. Notification Subscription Status (only if connected and characteristic is valid)
            if (heartRateReceiverCharacteristic != null) {
                broadcastSubscriptionChanged(isSubscribedToNotifications)
            }

            // 4. Last known battery level (if you cache it, otherwise this might not be available without a read)
            // For simplicity, we'll assume the UI shows "Battery: Ready" or "Battery: N/A" from services discovered
            // If you have a cached `lastBatteryLevel`, send it:
            // intent = Intent(ACTION_BATTERY_LEVEL_UPDATE)
            // intent.putExtra(EXTRA_BATTERY_LEVEL, lastCachedBatteryLevel)
            // LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        } else {
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_GATT_DISCONNECTED))
            // If not connected, other states are implicitly reset or irrelevant for the UI enabling buttons
        }

        // 5. Always send the current general status message for the main status TextView
        // You'll need to maintain a `currentGeneralStatus` field in your service that you update
        // whenever you call `broadcastStatus` or `updateNotification`.
        // For example, update it in your `broadcastStatus` method:
        // this.currentGeneralStatus = message // before sending the broadcast
        // Then here:
        broadcastStatus(currentGeneralStatus) // Or derive it based on mIsConnected, isScanning etc.
        updateNotification(currentGeneralStatus.substringAfter("Status: ").trim()) // Keep notification in sync
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        // This check is still a good safeguard within the service
        val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        if (!checkServicePermissions(scanPermission)) {
            val errorMsg = "Scan permission ($scanPermission) missing for service. Cannot start scan."
            Log.e(TAG, errorMsg)
            broadcastStatus(errorMsg) // Inform UI
            updateNotification("Scan Permission Missing")
            // Optionally stop self if critical permission is missing and service can't function
            // stopSelf()
            return
        }

//        if (!checkServicePermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)) {
//            broadcastStatus("Scan permission missing for service.")
//            return
//        }
        if (isScanning) {
            Log.d(TAG, "Scan already in progress.")
            return
        }
        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner // Try to re-initialize
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
                stopForeground(STOP_FOREGROUND_REMOVE); // Or stopForeground(true) pre-API 33
                stopSelf();

            }
        }, SCAN_PERIOD)
        bluetoothLeScanner?.startScan(leScanCallback)
        Log.d(TAG, "Scan initiated.")
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return
        if (!checkServicePermissions(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.e(TAG, "BLUETOOTH_SCAN permission not granted for stopping scan in service.")
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
                if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
                    Log.w(TAG, "BLUETOOTH_CONNECT missing, cannot get device name reliably.")
                }
                // val deviceName = device.name ?: "Unknown" // Requires BLUETOOTH_CONNECT for S+
                Log.d(TAG, "Device found: ${device.name ?: "Unknown"} - ${device.address}")
                broadcastDeviceFound(device.address, device.name ?: "Unknown")
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
            stopForeground(STOP_FOREGROUND_REMOVE); // Or stopForeground(true) pre-API 33
            stopSelf();
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            broadcastStatus("Connect permission missing for service.")
            return
        }
        stopScan() // Ensure scan is stopped before connecting
        broadcastStatus("Status: Connecting to ${device.name ?: device.address}...")
        updateNotification("Connecting to ${device.name ?: device.address}")
        Log.d(TAG, "Attempting to connect to GATT server on device: ${device.address}")

        bluetoothGatt =
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }


    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceAddress = gatt?.device?.address
            Log.d(TAG, "onConnectionStateChange: Address: $deviceAddress, Status: $status, NewState: $newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Successfully connected to $deviceAddress")
                    mIsConnected = true
                    if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
                        Log.w(TAG, "BLUETOOTH_CONNECT missing, cannot get device name or discover services reliably.")
                        broadcastStatus("Status: Connected (Connect perm missing)")
                        updateNotification("Connected (perm issue)")
                        LocalBroadcastManager.getInstance(this@BleService).sendBroadcast(Intent(ACTION_GATT_CONNECTED))
                        return
                    }
                    val deviceName = gatt?.device?.name ?: deviceAddress
                    broadcastStatus("Status: Connected to $deviceName")
                    updateNotification("Connected to $deviceName")
                    LocalBroadcastManager.getInstance(this@BleService).sendBroadcast(Intent(ACTION_GATT_CONNECTED))

                    Log.d(TAG, "Attempting to discover services...")
                    val discovered = gatt?.discoverServices()
                    Log.d(TAG, "discoverServices() called, result: $discovered")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from $deviceAddress")
                    mIsConnected = false
                    isSubscribedToNotifications = false
                    broadcastStatus("Status: Disconnected")
                    updateNotification("Disconnected")
                    LocalBroadcastManager.getInstance(this@BleService).sendBroadcast(Intent(ACTION_GATT_DISCONNECTED))
                    stopRecordingToFile(false);
                    closeGatt() // Close GATT client
                    isServiceRunning = false
                    stopForeground(STOP_FOREGROUND_REMOVE); // Or stopForeground(true) pre-API 33
                    stopSelf();
                }
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
            if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
                Log.w(TAG, "BLUETOOTH_CONNECT missing, cannot process discovered services.")
                broadcastServiceDiscoveredStatus(false, false, "Perm missing")
                return
            }

            var batteryReady = false
            var notifyReady = false
            var workoutReady = false
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

                workoutCharacteristic =
                    heartRateService?.getCharacteristic(WORKOUT_CHARACTERISTIC_UUID)
                workoutReady = workoutCharacteristic != null &&
                        ((workoutCharacteristic!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) > 0 ||
                                (workoutCharacteristic!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) > 0)
                if (!workoutReady) detailMessage += "Workout Char N/A or no write prop. "

            } else {
                Log.w(TAG, "Service discovery failed with status: $status")
                detailMessage = "Discovery Fail ($status)"
            }
            broadcastServiceDiscoveredStatus(batteryReady, notifyReady, detailMessage.ifEmpty { "Ready" })
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
            if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) return
            handleCharacteristicRead(characteristic, value, status)
        }

        private fun handleCharacteristicRead(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicRead UUID: ${characteristic.uuid}, Status: $status, Value: ${value.toHexString()}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid == BATTERY_LEVEL_CHARACTERISTIC_UUID) {
                    if (value.isNotEmpty()) {
                        val batteryLevel = value[0].toInt() and 0xFF // Ensure unsigned
                        Log.i(TAG, "Battery Level read: $batteryLevel%")
                        broadcastBatteryLevel(batteryLevel)
                    } else {
                        Log.w(TAG, "Battery Level characteristic read but value is empty.")
                        broadcastBatteryLevel(-1) // Indicate error or empty
                    }
                }
            } else {
                Log.w(TAG, "Characteristic read failed for ${characteristic.uuid}, status: $status")
                if (characteristic.uuid == BATTERY_LEVEL_CHARACTERISTIC_UUID) {
                    broadcastBatteryLevel(-2) // Indicate read fail
                }
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
            if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) return
            handleCharacteristicChanged(characteristic, value)
        }

        private var writeCount = 0 // Add this as a class-level variable

        private fun handleCharacteristicChanged(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "onCharacteristicChanged UUID: ${characteristic.uuid}, Value: ${value.toHexString()}")
            if (characteristic.uuid == HEART_RATE_READER_CHARACTERISTIC_UUID) {
                if (value.size >= 4 &&
                    value[0] == 0xE5.toByte() &&
                    value[1] == 0x11.toByte() &&
                    value[2] == 0x00.toByte()) {
                    val heartRateHexByte = value[3]
                    val heartRateDecimal = heartRateHexByte.toInt() and 0xFF
                    Log.i(TAG, "Heart Rate Data: $heartRateDecimal bpm")
                    broadcastNotificationData("HR: $heartRateDecimal bpm")

                    fileWriterHandler?.post {
                        try {
                            val timestamp = System.currentTimeMillis()
                            writer?.appendLine("$timestamp,$heartRateDecimal")

                            // Increment counter and flush every 10 writes
                            writeCount++
                            if (writeCount >= 10) {
                                writer?.flush()
                                writeCount = 0
                                Log.d(TAG, "Flushed writer to file")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing to heart rate file", e)
                        }
                    }
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) return

            if (descriptor?.uuid == CCCD_UUID && descriptor.characteristic.uuid == HEART_RATE_READER_CHARACTERISTIC_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    isSubscribedToNotifications = !isSubscribedToNotifications;
                    broadcastStatus("Notifications ${if(isSubscribedToNotifications) "Enabled" else "Disabled"}")
                    broadcastSubscriptionChanged(isSubscribedToNotifications)
                    if(isSubscribedToNotifications){
                        val startWorkout: ByteArray = byteArrayOf(
                            0xFD.toByte(), // FD
                            0x11.toByte(), // 11
                            0x01.toByte(), // 01
                            0x01.toByte()  // 01
                        )
                        writeCommand(startWorkout);
                        startRecordingToFile();
                    }
                    else{
                        val endWorkout: ByteArray = byteArrayOf(
                            0xFD.toByte(), // FD
                            0x00.toByte(), // 00
                            0x01.toByte(), // 01
                            0x01.toByte()  // 01
                        )
                        writeCommand(endWorkout);
                        stopRecordingToFile();
                    }
                    Log.i(TAG, "CCCD for custom notify written. Notifications ${if (isSubscribedToNotifications) "enabled" else "disabled"}.")
                } else {
                    Log.e(TAG, "CCCD write for custom notify failed: $status")
                    broadcastStatus("Failed to set notifications")
                    // Optionally broadcast failure to revert UI state
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
        bluetoothGatt?.readCharacteristic(batteryLevelCharacteristic)
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

        bluetoothGatt?.setCharacteristicNotification(characteristic, enable)
        //Thread.sleep(100) // Small delay sometimes helps, but try without first

        val cccdDescriptor = characteristic.getDescriptor(CCCD_UUID)
        if (cccdDescriptor != null) {
            val descriptorValue = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            Log.d(TAG, "Writing to CCCD: ${descriptorValue.toHexString()} to enable: $enable")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = bluetoothGatt?.writeDescriptor(cccdDescriptor, descriptorValue)
                Log.d(TAG, "writeDescriptor (T+ API) result: $result")
            } else {
                cccdDescriptor.value = descriptorValue
                val result = bluetoothGatt?.writeDescriptor(cccdDescriptor)
                Log.d(TAG, "writeDescriptor (legacy API) result: $result")
            }
        } else {
            Log.w(TAG, "CCCD not found for custom characteristic. Assuming notifications toggled by setCharacteristicNotification.")
            isSubscribedToNotifications = enable // Update state optimistically
            broadcastStatus("Notifications ${if(enable) "Enabled (No CCCD)" else "Disabled (No CCCD)"}")
            broadcastSubscriptionChanged(enable)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectDevice() {
        if (bluetoothGatt == null) return
        if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            broadcastStatus("Connect permission missing for disconnect.")
            // Force close if permission is missing to avoid leaks, though it's not ideal
            closeGatt()
            mIsConnected = false // Manually update state
            broadcastStatus("Status: Disconnected (Perm Error)")
            updateNotification("Disconnected (Perm Error)")
            LocalBroadcastManager.getInstance(this@BleService).sendBroadcast(Intent(ACTION_GATT_DISCONNECTED))
            return
        }

        // Polite unsubscribe (optional, but good)
        if (isSubscribedToNotifications && heartRateReceiverCharacteristic != null) {
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
                // No immediate callback needed here, disconnect will follow
            }
        }
        bluetoothGatt?.disconnect() // Triggers onConnectionStateChange -> STATE_DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        if (bluetoothGatt == null) {
            Log.d(TAG, "disconnectDevice called, but GATT is already null. Stopping service.");
            // No active GATT connection to disconnect, so stop the service directly
            stopRecordingToFile(false);
            isServiceRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE); // Or stopForeground(true) pre-API 33
            stopSelf();
            return;
        }
        if (!checkServicePermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for closing GATT in service.")
            bluetoothGatt = null // Just nullify to prevent further use if perm missing
            return
        }
        bluetoothGatt?.disconnect()
        bluetoothGatt = null
        batteryLevelCharacteristic = null
        heartRateReceiverCharacteristic = null
        workoutCharacteristic = null
        Log.d(TAG, "GATT closed.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BleService destroyed.")
        stopScan()
        isServiceRunning = false
        disconnectDevice() // This will also call closeGatt() in its flow
        // Ensure GATT is closed if disconnect didn't complete for some reason
        if (bluetoothGatt != null) {
            closeGatt()
        }
        handler.removeCallbacksAndMessages(null) // Remove any pending scan timeouts
        fileWriterHandlerThread?.quitSafely()
        stopRecordingToFile(false);

        stopForeground(STOP_FOREGROUND_REMOVE) // Remove notification
        stopSelf();
        Log.d(TAG, "BleService cleanup complete.")
    }


    // --- Helper methods for broadcasting ---
    private fun broadcastStatus(message: String) {
        currentGeneralStatus = message // Store the latest status
        val intent = Intent(ACTION_STATUS_UPDATE)
        intent.putExtra(EXTRA_STATUS_MESSAGE, message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        if (message.startsWith("Status:")) {
            updateNotification(message.substringAfter("Status: "))
        } else {
            updateNotification(message) // Or a default if message isn't suitable
        }
    }
    private fun broadcastDeviceFound(address: String, name: String) {
        val intent = Intent(ACTION_DEVICE_FOUND)
        intent.putExtra(EXTRA_DEVICE_ADDRESS, address)
        intent.putExtra(EXTRA_STATUS_MESSAGE, "Found: $name ($address)") // Using status message key for simplicity
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
        intent.putExtra(EXTRA_STATUS_MESSAGE, detail) // Re-using for simplicity
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

    // BleService.kt
    @SuppressLint("MissingPermission")
    private fun writeCommand(commandBytes: ByteArray) {
        if (!mIsConnected || bluetoothGatt == null ||  workoutCharacteristic == null) {
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

        // Check write type
        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) > 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // Or WRITE_TYPE_SIGNED if your peripheral needs it
        } else {
            Log.e(TAG, "Command characteristic is not writable.")
            broadcastStatus("Error: Command characteristic not writable.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(char, commandBytes, writeType)
        } else {
            char.value = commandBytes
            char.writeType = writeType
            bluetoothGatt?.writeCharacteristic(char)
        }
        // The result of this write will come in the onCharacteristicWrite callback
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startRecordingToFile() {
        if (writer != null) { // Already recording
            Log.w(TAG, "Already recording to a file.")
            return
        }

        val fileName = "HeartRate_Data_${System.currentTimeMillis()}.csv"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            // For Android Q (API 29) and above, save to Downloads directory
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            // For older versions, you might need to construct a full path if not using MediaStore
            // but for simplicity, this example focuses on API 29+ for Downloads.
        }

        val resolver = applicationContext.contentResolver
        try {
            currentFileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (currentFileUri == null) {
                Log.e(TAG, "Failed to create new MediaStore entry.")
                broadcastStatus("Error: Could not create file for recording.")
                return
            }

            resolver.openOutputStream(currentFileUri!!)?.let { outputStream ->
                fileOutputStream = outputStream as FileOutputStream // Keep if needed for direct FileOutputStream features
                writer = BufferedWriter(OutputStreamWriter(outputStream))
                // Optional: Write a header row
                writer?.appendLine("Timestamp,HeartRate")
                writer?.flush()
                Log.i(TAG, "Started recording heart rate to: $fileName in Downloads")
                broadcastStatus("Recording started: $fileName")
            } ?: run {
                Log.e(TAG, "Failed to open output stream for $currentFileUri")
                broadcastStatus("Error: Could not open file for recording.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting file recording", e)
            broadcastStatus("Error: File recording failed to start.")
            stopRecordingToFile(false) // Clean up
        }
    }
    private fun stopRecordingToFile(notifySuccess: Boolean = true) {
        try {
            writer?.flush()
            writer?.close()
            fileOutputStream?.close() // Though writer.close() should close the underlying stream
            Log.i(TAG, "Stopped recording heart rate. File URI: $currentFileUri")
            if (notifySuccess && currentFileUri != null) {
                val fileName = currentFileUri?.let { uri ->
                    // Try to get the display name from MediaStore
                    applicationContext.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                        } else null
                    }
                } ?: "UnknownFile.csv"
                broadcastStatus("Recording stopped. Saved to Downloads/$fileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping file recording", e)
        } finally {
            writer = null
            fileOutputStream = null
            currentFileUri = null
        }
    }
    // Helper to convert ByteArray to Hex String
    private fun ByteArray.toHexString(): String =
        joinToString(separator = " ") { byte -> "%02X".format(byte) }
}