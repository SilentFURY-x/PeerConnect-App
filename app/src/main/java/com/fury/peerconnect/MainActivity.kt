package com.fury.peerconnect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class MainActivity : AppCompatActivity() {

    // Unique ID for our app (The "Radio Channel")
    private val SERVICE_ID = "com.fury.peerconnect_v2"

    // Request code to identify the "Turn On Location" action
    private val REQUEST_CHECK_SETTINGS = 1001
    private val TAG = "PeerConnectDebug"

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isDiscovering = false
    private var myNickName: String = ""

    private var connectedEndpointId: String? = null // Who are we connected to?

    // UI Elements
    private lateinit var statusText: TextView
    private lateinit var btnHost: Button
    private lateinit var btnJoin: Button

    private lateinit var btnDisconnect: Button

    // Strategy: P2P_STAR means 1 Host connects to N users (Like a hotspot)
    private val STRATEGY = Strategy.P2P_STAR

    // --- PERMISSIONS ---
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            Toast.makeText(this, "Permissions Granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissions Denied. App won't work.", Toast.LENGTH_LONG).show()
        }
    }


    override fun    onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        btnHost = findViewById(R.id.btnHost)
        btnJoin = findViewById(R.id.btnJoin)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        Nearby.getConnectionsClient(this).stopAllEndpoints()

        // Request permissions immediately
        if (!hasPermissions()) {
            permissionLauncher.launch(requiredPermissions)
        }

        // BUTTON LISTENERS
        btnHost.setOnClickListener {
            // Pass the "Start Action" as a callback to ensure it runs AFTER reset
            resetRadio {
                checkLocationAndStart { startAdvertising() }
            }
        }

        btnJoin.setOnClickListener {
            resetRadio {
                checkLocationAndStart { startDiscovery() }
            }
        }

        btnDisconnect.setOnClickListener {
            resetRadio {
                Toast.makeText(this, "Radio Killed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CHECK_SETTINGS) {
            if (resultCode == RESULT_OK) {
                // User clicked "OK" - GPS is now ON!
                Toast.makeText(this, "Location Enabled!", Toast.LENGTH_SHORT).show()
                // Retry the action (e.g., Discovery)
                startDiscovery()
            } else {
                // User clicked "No thanks" - We can't proceed
                Toast.makeText(this, "Location is required to find peers.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- 1. ADVERTISING (HOST) LOGIC ---
    private fun startAdvertising() {
        Nearby.getConnectionsClient(this).stopDiscovery()
        // FIX: Add .setLowPower(false) to force high performance
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .setLowPower(false)
            .build()

        Nearby.getConnectionsClient(this)
            .startAdvertising(
                myNickName,
                SERVICE_ID,
                connectionLifecycleCallback,
                advertisingOptions
            )
            .addOnSuccessListener {
                statusText.text = "Status: Advertising... (Waiting for peers)"
                Log.d(TAG, "Advertising started")
            }
            .addOnFailureListener { e ->
                statusText.text = "Status: Failed to Advertise"
                Log.e(TAG, "Advertising failed", e)
            }
    }

    // --- 2. DISCOVERY (CLIENT) LOGIC ---
    // --- 2. DISCOVERY (CLIENT) LOGIC [PULSING VERSION] ---
    private fun startDiscovery() {
        isDiscovering = true
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        Nearby.getConnectionsClient(this)
            .startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                discoveryOptions
            )
            .addOnSuccessListener {
                statusText.text = "Status: Scanning ($myNickName)..."
                Log.d(TAG, "Discovery started")

                // --- THE PULSE FIX ---
                // If we don't connect in 6 seconds, restart discovery to find "Old" hosts
                handler.postDelayed({
                    if (isDiscovering) {
                        Log.d(TAG, "Pulsing Discovery...")
                        Nearby.getConnectionsClient(this@MainActivity).stopDiscovery()
                        startDiscovery() // Recursive restart
                    }
                }, 6000)
            }
            .addOnFailureListener { e ->
                statusText.text = "Status: Discovery Failed"
                Log.e(TAG, "Discovery failed", e)
            }
    }

    // --- 3. CALLBACKS (THE HANDSHAKE) ---

    // Callback for DISCOVERERS: "I found someone!"
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // STOP THE PULSE immediately
            isDiscovering = false
            handler.removeCallbacksAndMessages(null)

            Log.d(TAG, "Found endpoint: ${info.endpointName}")
            statusText.text = "Found: ${info.endpointName}. Connecting..."

            // Automatically request connection
            Nearby.getConnectionsClient(this@MainActivity)
                .requestConnection(
                    myNickName,
                    endpointId,
                    connectionLifecycleCallback
                )
                .addOnFailureListener { e ->
                    statusText.text = "Error: Request Failed ${e.message}"
                    Log.e(TAG, "Request Connection Failed", e)

                    // IMPORTANT: If connection failed, we must restart scanning to try again
                    startDiscovery()
                }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost")
        }
    }

    // Callback for BOTH: "Connection initiated/Result"
    // Callback for BOTH: "Connection initiated/Result"
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        // Step A: Connection Initiated (Security Check)
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated with: ${info.endpointName}")

            // --- CHANGE 1: Stop BOTH Advertising and Discovery here ---
            // This ensures the radio goes silent to focus on the handshake.
            Nearby.getConnectionsClient(this@MainActivity).stopAdvertising()
            Nearby.getConnectionsClient(this@MainActivity).stopDiscovery() // <--- ADD THIS

            // Auto-Accept
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
            statusText.text = "Status: Accepting connection..."
        }

        // Step B: Connection Result (Did it work?)
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    statusText.text = "Status: CONNECTED to $endpointId"
                    Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()

                    connectedEndpointId = endpointId
                    // Double check they are stopped (Safety)
                    Nearby.getConnectionsClient(this@MainActivity).stopAdvertising()
                    Nearby.getConnectionsClient(this@MainActivity).stopDiscovery()
                }
                else -> {
                    statusText.text = "Status: Connection Rejected/Failed"
                    resetRadio()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            statusText.text = "Status: Disconnected"

            connectedEndpointId = null // Clear it
            // This ensures that if the other person leaves, my phone
            // automatically resets so I can switch roles immediately.
            resetRadio()
        }
    }

    // --- 4. DATA HANDLING (We will use this in Phase 3) ---
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // We received data!
            if (payload.type == Payload.Type.BYTES) {
                // 1. Get the bytes
                val receivedBytes = payload.asBytes()!!

                // 2. Unpack (Deserialize) back into a ChatMessage object
                val receivedMessage = deserialize(receivedBytes)

                // 3. Show it on the screen
                statusText.append("\n${receivedMessage.senderId}: ${receivedMessage.message}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // This is for progress bars (file transfer). We don't need it for text.
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkLocationAndStart(action: () -> Unit) {
        // 1. Create a Location Request (We just need it "Balanced" for Bluetooth)
        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000
        ).build()

        // 2. Create the Settings Request
        val builder = com.google.android.gms.location.LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)

        val client = com.google.android.gms.location.LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        // 3. SUCCESS: Location is already ON
        task.addOnSuccessListener {
            action() // Run the code we passed in (e.g., startDiscovery)
        }

        // 4. FAILURE: Location is OFF -> Show the Popup
        task.addOnFailureListener { exception ->
            if (exception is com.google.android.gms.common.api.ResolvableApiException) {
                // Location settings are not satisfied, but this can be fixed
                // by showing the user a dialog.
                try {
                    // Show the dialog by calling startResolutionForResult()
                    exception.startResolutionForResult(this@MainActivity, REQUEST_CHECK_SETTINGS)
                } catch (sendEx: android.content.IntentSender.SendIntentException) {
                    // Ignore the error.
                }
            } else {
                // Location settings are not satisfied and we can't fix it.
                Toast.makeText(this, "Location is required for this app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetRadio(onFinished: () -> Unit = {}) {
        // 1. Stop the pulse loop
        isDiscovering = false
        handler.removeCallbacksAndMessages(null)

        // 2. Stop Nearby Connections
        Nearby.getConnectionsClient(this).stopAllEndpoints()
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()

        // 3. Update UI
        statusText.text = "Status: Resetting radio...(Please wait)"

        // 4. Generate new Nickname (Crucial for the "New Identity" fix)
        val randomNum = (1000..9999).random()
        myNickName = "FuryUser-$randomNum"

        // 5. Force a 1.5 second delay to let the hardware flush
        statusText.postDelayed({
            statusText.text = "Status: Ready ($myNickName)"
            // THIS is what makes the buttons work:
            onFinished()
        }, 1500)
    }

    // --- HELPER 1: SERIALIZATION (Object -> Bytes) ---
    // Takes a ChatMessage and turns it into a ByteArray so we can send it.
    private fun serialize(message: ChatMessage): ByteArray {
        val outputStream = java.io.ByteArrayOutputStream()
        val objectStream = java.io.ObjectOutputStream(outputStream)
        objectStream.writeObject(message)
        return outputStream.toByteArray()
    }

    // --- HELPER 2: DESERIALIZATION (Bytes -> Object) ---
    // Takes the received ByteArray and rebuilds the ChatMessage object.
    private fun deserialize(bytes: ByteArray): ChatMessage {
        val inputStream = java.io.ByteArrayInputStream(bytes)
        val objectStream = java.io.ObjectInputStream(inputStream)
        return objectStream.readObject() as ChatMessage
    }

    private fun sendMessage(messageText: String) {
        // 1. Check if we are connected
        if (connectedEndpointId == null) {
            Toast.makeText(this, "Not connected to anyone!", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Create the Message Object
        val chatMessage = ChatMessage(
            senderId = myNickName,
            message = messageText,
            timestamp = System.currentTimeMillis()
        )

        // 3. Pack it (Serialize)
        val bytes = serialize(chatMessage)

        // 4. Send it!
        // Payload.fromBytes() wraps our byte array into a Google Nearby Payload
        Nearby.getConnectionsClient(this).sendPayload(connectedEndpointId!!, Payload.fromBytes(bytes))

        // 5. Update my own UI (so I can see what I sent)
        statusText.append("\nMe: $messageText")
    }
}