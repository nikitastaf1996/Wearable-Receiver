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

class MainActivity : AppCompatActivity() {

    private lateinit var connectButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var getBatteryButton: Button
    private lateinit var batteryTextView: TextView
    private lateinit var subscribeNotifyButton: Button
    private lateinit var notifyDataTextView: TextView

    private var mIsConnectedUi: Boolean = false // UI state, service manages actual connection
    private var mIsSubscribedUi: Boolean = false // UI state

    // TODO: If you want the Activity to set the target device, you'd pass this to the service
    private val TARGET_DEVICE_ADDRESS = "78:02:B7:78:38:39" // Keep consistent with Service or pass it

    companion object {
        private const val TAG = "MainActivity"
    }


    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            permissions.entries.forEach {
                if (!it.value) allGranted = false
            }
            if (allGranted) {
                Log.d(TAG, "All permissions granted by user.")
                // Now that permissions are granted, we can tell the service to proceed if needed
                // e.g., start scanning if that was the intent
                if (!mIsConnectedUi) { // Only start scan if not already trying to connect/connected
                    startBleServiceWithAction(BleService.ACTION_START_SCAN)
                }
            } else {
                Log.e(TAG, "One or more permissions denied by user.")
                Toast.makeText(this, "Permissions denied. BLE features may not work.", Toast.LENGTH_LONG).show()
            }
        }

    private val bleUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BleService.ACTION_STATUS_UPDATE -> {
                    val message = intent.getStringExtra(BleService.EXTRA_STATUS_MESSAGE) ?: "Status unknown"
                    statusTextView.text = message
                    Log.d(TAG, "UI Status Update: $message")
                }
                BleService.ACTION_GATT_CONNECTED -> {
                    mIsConnectedUi = true
                    connectButton.text = "Disconnect"
                    statusTextView.text = "Status: Connected (Waiting for services)"
                    // Reset UI for new connection
                    batteryTextView.text = "Battery: --%"
                    getBatteryButton.isEnabled = false
                    notifyDataTextView.text = "Notification Data: (waiting)"
                    subscribeNotifyButton.isEnabled = false
                    mIsSubscribedUi = false
                }
                BleService.ACTION_GATT_DISCONNECTED -> {
                    mIsConnectedUi = false
                    connectButton.text = "Connect to Wearable"
                    statusTextView.text = "Status: Disconnected"
                    batteryTextView.text = "Battery: --%"
                    getBatteryButton.isEnabled = false
                    notifyDataTextView.text = "Notification Data: (disconnected)"
                    subscribeNotifyButton.isEnabled = false
                    mIsSubscribedUi = false
                }
                BleService.ACTION_BATTERY_LEVEL_UPDATE -> {
                    val level = intent.getIntExtra(BleService.EXTRA_BATTERY_LEVEL, -1)
                    batteryTextView.text = when {
                        level >= 0 -> "Battery: $level%"
                        level == -1 -> "Battery: Empty/Error"
                        level == -2 -> "Battery: Read Fail"
                        else -> "Battery: N/A"
                    }
                }
                BleService.ACTION_NOTIFICATION_DATA_UPDATE -> {
                    val data = intent.getStringExtra(BleService.EXTRA_NOTIFICATION_DATA) ?: ""
                    notifyDataTextView.text = data // Append new data
                }
                BleService.ACTION_SERVICES_DISCOVERED_STATUS -> {
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
                BleService.ACTION_NOTIFICATION_SUBSCRIPTION_CHANGED -> {
                    mIsSubscribedUi = intent.getBooleanExtra(BleService.EXTRA_IS_SUBSCRIBED, false)
                    subscribeNotifyButton.text = if (mIsSubscribedUi) "Stop recording" else "Start recording"
                    Toast.makeText(this@MainActivity, "Notifications ${if(mIsSubscribedUi) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
                }
                BleService.ACTION_SCAN_FAILED -> {
                    val errorCode = intent.getIntExtra(BleService.EXTRA_SCAN_ERROR_CODE, 0)
                    statusTextView.text = "Status: Scan failed ($errorCode)"
                    Toast.makeText(this@MainActivity, "Scan failed: $errorCode", Toast.LENGTH_LONG).show()
                }
                BleService.ACTION_DEVICE_FOUND -> {
                    val message = intent.getStringExtra(BleService.EXTRA_STATUS_MESSAGE)
                    // You could update a list of found devices here if you had one
                    Log.i(TAG, "Device found broadcast: $message")
                    if (!statusTextView.text.contains("Scanning")) { // don't overwrite "Scanning..."
                        statusTextView.text = message ?: "Device found..."
                    }
                }
            }
        }
    }

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
            if (mIsConnectedUi) {
                startBleServiceWithAction(BleService.ACTION_DISCONNECT)
            } else {
                // This will trigger the permission request if needed,
                // and the callback above will handle starting the scan.
                if (checkAndRequestPermissions()) {
                    // Permissions were already granted, and BT is enabled.
                    // Safe to tell the service to start scanning.
                    Log.d(TAG, "Permissions already granted. Telling service to start scan.")
                    startBleServiceWithAction(BleService.ACTION_START_SCAN)
                }
            }
        }

        getBatteryButton.setOnClickListener {
            startBleServiceWithAction(BleService.ACTION_REQUEST_BATTERY)
        }

        subscribeNotifyButton.setOnClickListener {
            startBleServiceWithAction(BleService.ACTION_TOGGLE_NOTIFY)
        }

        // Initial UI state
        getBatteryButton.isEnabled = false
        subscribeNotifyButton.isEnabled = false
        statusTextView.text = "Status: Idle"
    }

    override fun onStart() {
        super.onStart()
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

        if(BleService.isServiceRunning){
            startBleServiceWithAction(BleService.ACTION_REQUEST_CURRENT_STATE);
        }

        Log.d(TAG, "Requested current state from BleService")

        // You might want to query the service for its current state here if it's already running
        // For simplicity, we assume the service will broadcast its state if it changes.
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(bleUpdateReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Decide if you want to stop the service when the app is fully closed.
        // If you want it to run indefinitely until explicitly stopped by user action (e.g. a "stop service" button):
        // Do nothing here to stop the service.
        // If you want to stop it when the main activity is destroyed:
        // startBleServiceWithAction(BleService.ACTION_STOP_SERVICE) // Uncomment if service should stop with MainActivity
        Log.d(TAG, "MainActivity onDestroy")
    }


    private fun checkAndRequestPermissions(): Boolean {
        val requiredPermissions = mutableListOf<String>()
        // No BLUETOOTH/BLUETOOTH_ADMIN needed here for SDK 31+ for the *app*
        // The service will use them, but the app mainly needs SCAN and CONNECT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            // FOREGROUND_SERVICE_CONNECTED_DEVICE is for the service, not typically requested by activity directly
        } else {
            // Pre-S, legacy permissions
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // FOREGROUND_SERVICE permission is needed if targeting Android P (API 28) or higher for the service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }
        // For Android 13 (Tiramisu, API 33) and above, for notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }


        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $permissionsToRequest")
            requestMultiplePermissions.launch(permissionsToRequest.toTypedArray())
            false // Permissions are being requested
        } else {
            Log.d(TAG, "All necessary permissions already granted for Activity.")
            // Also check Bluetooth adapter state
            if (!checkAndEnableBluetooth()) {
                false
            } else {
                true // Permissions granted and BT enabled
            }
        }
    }

    private fun checkAndEnableBluetooth(): Boolean {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device.", Toast.LENGTH_LONG).show()
            return false
        }

        // Basic BLUETOOTH perm check for older devices, primarily handled by checkAndRequestPermissions now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH permission not granted when checking if enabled (pre-S).")
            Toast.makeText(this, "Bluetooth permission missing.", Toast.LENGTH_LONG).show()
            return false
        }


        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is not enabled. Please enable it.", Toast.LENGTH_LONG).show()
            // For a better UX, you could launch an intent to enable Bluetooth:
            // val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT) // Handle result in onActivityResult
            return false
        }
        return true
    }


    private fun startBleServiceWithAction(action: String) {
        if (!checkAndEnableBluetooth() && (action == BleService.ACTION_START_SCAN || action == BleService.ACTION_CONNECT_DEVICE)) {
            Toast.makeText(this, "Bluetooth not enabled or supported.", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, BleService::class.java).apply {
            this.action = action
            // Pass the target device address if needed, especially for direct connect or specific scan
            putExtra(BleService.EXTRA_DEVICE_ADDRESS, TARGET_DEVICE_ADDRESS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Log.d(TAG, "Sent action '$action' to BleService.")
    }
}