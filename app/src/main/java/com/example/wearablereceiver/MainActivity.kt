package com.example.wearablereceiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.bluetooth.BluetoothManager

/**
 * Main activity for the Wearable Receiver application.
 *
 * This activity provides the user interface for interacting with the BLE device via [BleService].
 * It handles:
 * - Displaying connection status, battery level, and received notification data.
 * - User actions to connect/disconnect, request battery level, and toggle notifications.
 * - Requesting necessary Bluetooth and location permissions.
 * - Binding to [BleService] to communicate with it directly.
 * - Receiving updates from [BleService] via [BroadcastReceiver].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var connectButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var getBatteryButton: Button
    private lateinit var batteryTextView: TextView
    private lateinit var subscribeNotifyButton: Button
    private lateinit var notifyDataTextView: TextView

    private var bleService: BleService? = null
    private var isServiceBound: Boolean = false
    // mIsConnectedUi and mIsSubscribedUi will now largely be driven by service state via binder

    // TODO: If you want the Activity to set the target device, you'd pass this to the service
    private val TARGET_DEVICE_ADDRESS = "78:02:B7:78:38:39" // Keep consistent with Service or pass it

    companion object {
        private const val TAG = "MainActivity"
    }

    private val serviceConnection = object : android.content.ServiceConnection {
        /**
         * Called when a connection to the Service has been established, with the
         * [android.os.IBinder] of the communication channel to the Service.
         */
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            isServiceBound = true
            Log.d(TAG, "BleService connected")
            updateUiBasedOnServiceState()
        }

        /**
         * Called when a connection to the Service has been lost.
         * This typically happens when the process hosting the service has crashed or been killed.
         */
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            bleService = null
            isServiceBound = false
            Log.d(TAG, "BleService disconnected")
            // Optionally update UI to reflect service disconnection
            connectButton.text = "Connect to Wearable"
            getBatteryButton.isEnabled = false
            subscribeNotifyButton.isEnabled = false
            statusTextView.text = "Status: Service Unbound"
        }
    }

    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            permissions.entries.forEach {
                if (!it.value) allGranted = false
            }
            if (allGranted) {
                Log.d(TAG, "All permissions granted by user.")
                // Permissions granted, now try to start scan via service if appropriate
                initiateBleConnection()
            } else {
                Log.e(TAG, "One or more permissions denied by user.")
                Toast.makeText(this, "Permissions denied. BLE features may not work.", Toast.LENGTH_LONG).show()
            }
        }

    private val bleUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BleService.ACTION_STATUS_UPDATE -> handleStatusUpdate(intent)
                BleService.ACTION_GATT_CONNECTED -> uiHandleGattConnected() // Renamed to avoid conflict
                BleService.ACTION_GATT_DISCONNECTED -> uiHandleGattDisconnected() // Renamed
                BleService.ACTION_BATTERY_LEVEL_UPDATE -> handleBatteryLevelUpdate(intent)
                BleService.ACTION_NOTIFICATION_DATA_UPDATE -> handleNotificationDataUpdate(intent)
                BleService.ACTION_SERVICES_DISCOVERED_STATUS -> handleServicesDiscovered(intent)
                BleService.ACTION_NOTIFICATION_SUBSCRIPTION_CHANGED -> handleNotificationSubscriptionChanged(intent)
                BleService.ACTION_SCAN_FAILED -> handleScanFailed(intent)
                BleService.ACTION_DEVICE_FOUND -> handleDeviceFound(intent)
            }
        }
    }

    /** Handles status updates broadcasted by [BleService]. */
    private fun handleStatusUpdate(intent: Intent) {
        val message = intent.getStringExtra(BleService.EXTRA_STATUS_MESSAGE) ?: "Status unknown"
        statusTextView.text = message
        Log.d(TAG, "UI Status Update: $message")
        // If status indicates a major state change, might call updateUiBasedOnServiceState()
        if (message.contains("Connected") || message.contains("Disconnected") || message.contains("Scanning")) {
            updateUiBasedOnServiceState()
        }
    }

    /** Handles GATT connected event broadcasted by [BleService]. */
    private fun uiHandleGattConnected() {
        Log.d(TAG, "UI Update: GATT Connected broadcast received.")
        updateUiBasedOnServiceState()
    }

    /** Handles GATT disconnected event broadcasted by [BleService]. */
    private fun uiHandleGattDisconnected() {
        Log.d(TAG, "UI Update: GATT Disconnected broadcast received.")
        updateUiBasedOnServiceState()
    }

    /** Handles battery level updates broadcasted by [BleService]. */
    private fun handleBatteryLevelUpdate(intent: Intent) {
        val level = intent.getIntExtra(BleService.EXTRA_BATTERY_LEVEL, -1)
        batteryTextView.text = when {
            level >= 0  -> "Battery: $level%"
            level == -1 -> "Battery: Empty/Error"
            level == -2 -> "Battery: Read Fail"
            else        -> "Battery: N/A"
        }
    }

    /** Handles new notification data broadcasted by [BleService]. */
    private fun handleNotificationDataUpdate(intent: Intent) {
        val data = intent.getStringExtra(BleService.EXTRA_NOTIFICATION_DATA) ?: ""
        notifyDataTextView.text = data
    }

    /** Handles service discovery status updates broadcasted by [BleService]. */
    private fun handleServicesDiscovered(intent: Intent) {
        val batteryReady = intent.getBooleanExtra(BleService.EXTRA_IS_BATTERY_READY, false)
        val notifyReady = intent.getBooleanExtra(BleService.EXTRA_IS_NOTIFY_READY, false)
        val detail = intent.getStringExtra(BleService.EXTRA_STATUS_MESSAGE) ?: ""

        getBatteryButton.isEnabled = batteryReady
        subscribeNotifyButton.isEnabled = notifyReady

        if (!batteryReady) batteryTextView.text = "Battery: N/A ($detail)"
        else batteryTextView.text = "Battery: Ready"

        if (!notifyReady) notifyDataTextView.text = "Notify: N/A ($detail)"
        else notifyDataTextView.text = "Notification Data: (ready to subscribe)"

        if (batteryReady || notifyReady) {
            statusTextView.text = "Status: Services Ready"
        } else {
            statusTextView.text = "Status: Services not fully available ($detail)"
        }
    }

    /** Handles notification subscription changes broadcasted by [BleService]. */
    private fun handleNotificationSubscriptionChanged(intent: Intent) {
        val isSubscribed = intent.getBooleanExtra(BleService.EXTRA_IS_SUBSCRIBED, false)
        // mIsSubscribedUi is not used anymore, direct update from service state is preferred
        subscribeNotifyButton.text = if (isSubscribed) "Stop recording" else "Start recording"
        Toast.makeText(this@MainActivity, "Notifications ${if(isSubscribed) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
    }

    /** Handles scan failed events broadcasted by [BleService]. */
    private fun handleScanFailed(intent: Intent) {
        val errorCode = intent.getIntExtra(BleService.EXTRA_SCAN_ERROR_CODE, 0)
        statusTextView.text = "Status: Scan failed ($errorCode)"
        Toast.makeText(this@MainActivity, "Scan failed: $errorCode", Toast.LENGTH_LONG).show()
    }

    /** Handles device found events broadcasted by [BleService]. */
    private fun handleDeviceFound(intent: Intent) {
        val message = intent.getStringExtra(BleService.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "Device found broadcast: $message")
        if (!statusTextView.text.contains("Scanning")) { // Don't overwrite "Scanning..."
            statusTextView.text = message ?: "Device found..."
        }
    }

    /**
     * Called when the activity is first created.
     * Initializes UI components, sets click listeners, and prepares the initial UI state.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectButton = findViewById(R.id.connectButton)
        statusTextView = findViewById(R.id.statusTextView)
        getBatteryButton = findViewById(R.id.getBatteryButton)
        batteryTextView = findViewById(R.id.batteryTextView)
        subscribeNotifyButton = findViewById(R.id.subscribeNotifyButton)
        notifyDataTextView = findViewById(R.id.notifyDataTextView)
        notifyDataTextView.movementMethod = ScrollingMovementMethod()

        connectButton.setOnClickListener {
            handleConnectButtonClick()
        }

        getBatteryButton.setOnClickListener {
            bleService?.requestBatteryLevelUpdate()
        }

        subscribeNotifyButton.setOnClickListener {
            bleService?.requestToggleNotify()
        }

        updateUiInitial()
    }

    /**
     * Handles clicks on the connect/disconnect/stop scan/cancel button.
     * Behavior depends on the current connection state of the [BleService].
     */
    private fun handleConnectButtonClick() {
        if (isServiceBound && bleService != null) {
            when (bleService!!.getCurrentConnectionState()) {
                BleService.ConnectionState.CONNECTED -> {
                    bleService?.requestDisconnect()
                }
                BleService.ConnectionState.DISCONNECTED -> {
                    // Ensure permissions are checked before starting scan
                    if (checkAndRequestPermissions()) {
                        initiateBleConnection()
                    }
                }
                BleService.ConnectionState.SCANNING, BleService.ConnectionState.CONNECTING -> {
                    // Optional: Implement scan stop / cancel connect here if desired
                    Log.d(TAG, "Scan/Connection already in progress.")
                    bleService?.requestDisconnect() // Or a more specific stopScan if available
                }
            }
        } else {
            // Service not bound, try to start and bind
            if (checkAndRequestPermissions()) {
                initiateBleConnection()
            }
        }
    }

    /**
     * Initiates the BLE connection process.
     * Ensures the [BleService] is started and attempts to bind to it.
     * If already bound and disconnected, it requests a new scan.
     */
    private fun initiateBleConnection() {
        // Start the service to ensure it's running (especially for foreground capabilities)
        // The ACTION_START_SCAN intent action also helps the service to initialize with the target device address.
        startBleServiceWithAction(BleService.ACTION_START_SCAN)

        // Bind to it if not already bound. Binding also happens in onStart, this ensures it if called standalone.
        if (!isServiceBound) {
            val serviceIntent = Intent(this, BleService::class.java)
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } else {
            // Already bound, just request scan if not already scanning/connected
            if (bleService?.getCurrentConnectionState() == BleService.ConnectionState.DISCONNECTED) {
                 bleService?.requestStartScan()
            }
        }
    }


    /**
     * Called when the activity is becoming visible to the user.
     * Binds to [BleService] and registers the [BroadcastReceiver] for service updates.
     */
    override fun onStart() {
        super.onStart()
        // Bind to BleService
        Intent(this, BleService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        val filter = IntentFilter().apply {
            addAction(BleService.ACTION_STATUS_UPDATE)
            addAction(BleService.ACTION_GATT_CONNECTED)
            addAction(BleService.ACTION_GATT_DISCONNECTED)
            addAction(BleService.ACTION_BATTERY_LEVEL_UPDATE)
            addAction(BleService.ACTION_NOTIFICATION_DATA_UPDATE)
            addAction(BleService.ACTION_SERVICES_DISCOVERED_STATUS)
            addAction(BleService.ACTION_NOTIFICATION_SUBSCRIPTION_CHANGED)
            addAction(BleService.ACTION_SCAN_FAILED)
            addAction(BleService.ACTION_DEVICE_FOUND)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(bleUpdateReceiver, filter)

        // No longer sending ACTION_REQUEST_CURRENT_STATE, will update UI in onServiceConnected
        Log.d(TAG, "MainActivity onStart: Bound to service and registered broadcast receiver.")
    }

    /**
     * Called when the activity is no longer visible to the user.
     * Unbinds from [BleService] and unregisters the [BroadcastReceiver].
     */
    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
            bleService = null
            Log.d(TAG, "MainActivity onStop: Unbound from service.")
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleUpdateReceiver)
        Log.d(TAG, "MainActivity onStop: Unregistered broadcast receiver.")
    }

    /**
     * Called before the activity is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        // Service lifecycle is managed by itself or explicit stop commands.
        // No specific action needed here to stop the service unless desired.
        Log.d(TAG, "MainActivity onDestroy")
    }


    /**
     * Checks for necessary BLE and other permissions.
     * If permissions are not granted, it launches the permission request flow.
     * @return True if all permissions are already granted, false otherwise.
     */
    private fun checkAndRequestPermissions(): Boolean {
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $permissionsToRequest")
            requestMultiplePermissions.launch(permissionsToRequest.toTypedArray())
            false // Permissions are being requested.
        } else {
            Log.d(TAG, "All necessary permissions already granted for Activity.")
            // Proceed to check Bluetooth adapter state only if permissions are granted.
            checkAndEnableBluetooth()
        }
    }

    /**
     * Checks if Bluetooth is enabled on the device.
     * Shows a Toast message if Bluetooth is not supported or not enabled.
     * @return True if Bluetooth is supported and enabled, false otherwise.
     */
    private fun checkAndEnableBluetooth(): Boolean {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device.", Toast.LENGTH_LONG).show()
            return false
        }

        // This check is somewhat redundant if ACCESS_FINE_LOCATION or BLUETOOTH_CONNECT is granted,
        // as those are the primary gatekeepers for adapter interaction.
        // However, it doesn't hurt as a safeguard for very old scenarios.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Legacy BLUETOOTH permission not granted when checking if enabled (pre-S).")
            Toast.makeText(this, "Bluetooth permission missing (legacy).", Toast.LENGTH_LONG).show()
            return false
        }

        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is not enabled. Please enable it.", Toast.LENGTH_LONG).show()
            // Consider using ActivityResultLauncher for ACTION_REQUEST_ENABLE for a modern approach.
            // val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // requestBluetoothEnable.launch(enableBtIntent)
            return false
        }
        return true
    }


    /**
     * Starts the [BleService] with a specific action.
     * This is used to ensure the service is running (e.g., for foreground operation)
     * and to pass initial commands or data like the target device address.
     *
     * @param action The action string for the Intent to start the service.
     */
    private fun startBleServiceWithAction(action: String) {
        if (!checkAndEnableBluetooth() && (action == BleService.ACTION_START_SCAN || action == BleService.ACTION_CONNECT_DEVICE)) {
            Toast.makeText(this, "Bluetooth not enabled or supported.", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, BleService::class.java).apply {
            this.action = action // Action is still useful for onStartCommand logic
            putExtra(BleService.EXTRA_DEVICE_ADDRESS, TARGET_DEVICE_ADDRESS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Log.d(TAG, "Sent action '$action' to BleService via startBleServiceWithAction.")
    }

    /**
     * Updates the UI elements (buttons, TextViews) based on the current state
     * obtained from the bound [BleService]. This function is called when the service
     * is connected or when a significant state-change broadcast is received.
     */
    private fun updateUiBasedOnServiceState() {
        if (!isServiceBound || bleService == null) {
            updateUiInitial()
            return
        }

        val service = bleService!!
        val currentState = service.getCurrentConnectionState()
        Log.d(TAG, "Updating UI based on service state: $currentState")

        when (currentState) {
            BleService.ConnectionState.CONNECTED -> {
                connectButton.text = "Disconnect"
                statusTextView.text = "Status: Connected" // More specific status comes from broadcast
                getBatteryButton.isEnabled = service.isBatteryCharacteristicReady()
                subscribeNotifyButton.isEnabled = service.isNotifyCharacteristicReady()
                subscribeNotifyButton.text = if (service.getSubscriptionState()) "Stop recording" else "Start recording"
            }
            BleService.ConnectionState.DISCONNECTED -> {
                connectButton.text = "Connect to Wearable"
                statusTextView.text = "Status: Disconnected"
                updateUiInitial()
            }
            BleService.ConnectionState.SCANNING -> {
                connectButton.text = "Stop Scan" // Or "Scanning..."
                statusTextView.text = "Status: Scanning..."
                updateUiInitial()
            }
            BleService.ConnectionState.CONNECTING -> {
                connectButton.text = "Cancel" // Or "Connecting..."
                statusTextView.text = "Status: Connecting..."
                updateUiInitial()
            }
        }
        // Battery and notification data TextViews are updated by their specific broadcasts,
        // but their enabled state can be set here based on characteristic readiness.
    }

    /**
     * Sets the UI to an initial or default disconnected state.
     * Disables buttons that require an active connection or ready characteristics.
     */
    private fun updateUiInitial() {
        connectButton.text = "Connect to Wearable"
        getBatteryButton.isEnabled = false
        subscribeNotifyButton.isEnabled = false
        subscribeNotifyButton.text = "Start recording"
        // statusTextView will be set by broadcasts or connection state changes.
        // batteryTextView.text = "Battery: --%" // Set by broadcast or on connected
        // notifyDataTextView.text = "Notification Data: (waiting)" // Set by broadcast or on connected
    }
}