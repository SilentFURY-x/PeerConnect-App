package com.fury.peerconnect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class MainActivity : AppCompatActivity() {

    // Unique ID for our app (The "Radio Channel")
    private val SERVICE_ID = "com.fury.peerconnect_v2"
    private val TAG = "PeerConnectDebug"

    private val REQUEST_CHECK_SETTINGS = 1001
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isDiscovering = false
    private var myNickName: String = ""
    private var connectedEndpointId: String? = null

    // UI Elements
    private lateinit var statusText: TextView
    private lateinit var btnHost: Button
    private lateinit var btnJoin: Button
    private lateinit var btnDisconnect: Button

    // NEW UI Elements for Chat
    private lateinit var layoutConnection: ConstraintLayout
    private lateinit var layoutChat: ConstraintLayout
    private lateinit var btnExitChat: Button
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var editMessage: EditText
    private lateinit var btnSend: Button

    // Adapter
    private lateinit var chatAdapter: ChatAdapter

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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. INITIALIZE ALL VIEWS (This prevents the crash!)
        statusText = findViewById(R.id.statusText)
        btnHost = findViewById(R.id.btnHost)
        btnJoin = findViewById(R.id.btnJoin)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        layoutConnection = findViewById(R.id.layoutConnection)
        layoutChat = findViewById(R.id.layoutChat)
        btnExitChat = findViewById(R.id.btnExitChat)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        editMessage = findViewById(R.id.editMessage)
        btnSend = findViewById(R.id.btnSend)

        // Setup RecyclerView
        chatRecyclerView.layoutManager = LinearLayoutManager(this)

        Nearby.getConnectionsClient(this).stopAllEndpoints()

        if (!hasPermissions()) {
            permissionLauncher.launch(requiredPermissions)
        }

        // --- BUTTON LISTENERS ---
        btnHost.setOnClickListener {
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
                Toast.makeText(this, "Reset Complete", Toast.LENGTH_SHORT).show()
            }
        }

        btnExitChat.setOnClickListener {
            resetRadio {
                Toast.makeText(this, "Left Chat", Toast.LENGTH_SHORT).show()
            }
        }

        btnSend.setOnClickListener {
            val text = editMessage.text.toString()
            if (text.isNotEmpty()) {
                sendMessage(text)
                editMessage.setText("")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // (Keep your existing GPS check logic here, omitted for brevity but don't delete it)
        if (requestCode == 1001) { /* ... */ }
    }

    // --- LOGIC ---

    private fun startAdvertising() {
        Nearby.getConnectionsClient(this).stopDiscovery()
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).setLowPower(false).build()

        Nearby.getConnectionsClient(this)
            .startAdvertising(myNickName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
            .addOnSuccessListener {
                statusText.text = "Status: Advertising... (Waiting for peers)"
            }
            .addOnFailureListener { e ->
                statusText.text = "Status: Failed to Advertise"
            }
    }

    private fun startDiscovery() {
        isDiscovering = true
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        Nearby.getConnectionsClient(this)
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener {
                statusText.text = "Status: Scanning ($myNickName)..."

                // Pulse Logic
                handler.postDelayed({
                    if (isDiscovering) {
                        Nearby.getConnectionsClient(this@MainActivity).stopDiscovery()
                        startDiscovery()
                    }
                }, 6000)
            }
            .addOnFailureListener {
                statusText.text = "Status: Discovery Failed"
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            isDiscovering = false
            handler.removeCallbacksAndMessages(null)
            statusText.text = "Found: ${info.endpointName}. Connecting..."

            Nearby.getConnectionsClient(this@MainActivity)
                .requestConnection(myNickName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { startDiscovery() }
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Nearby.getConnectionsClient(this@MainActivity).stopAdvertising()
            Nearby.getConnectionsClient(this@MainActivity).stopDiscovery()
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
            statusText.text = "Status: Accepting connection..."
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    statusText.text = "Status: CONNECTED"
                    connectedEndpointId = endpointId

                    // SWITCH SCREENS
                    layoutConnection.visibility = android.view.View.GONE
                    layoutChat.visibility = android.view.View.VISIBLE

                    Nearby.getConnectionsClient(this@MainActivity).stopAdvertising()
                    Nearby.getConnectionsClient(this@MainActivity).stopDiscovery()
                }
                else -> resetRadio()
            }
        }

        override fun onDisconnected(endpointId: String) {
            resetRadio()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedBytes = payload.asBytes()!!
                val receivedMessage = deserialize(receivedBytes)

                // Add to Adapter
                chatAdapter.addMessage(receivedMessage)
                chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendMessage(messageText: String) {
        if (connectedEndpointId == null) return

        val chatMessage = ChatMessage(myNickName, messageText, System.currentTimeMillis())
        val bytes = serialize(chatMessage)

        Nearby.getConnectionsClient(this).sendPayload(connectedEndpointId!!, Payload.fromBytes(bytes))

        // Add to my own UI
        chatAdapter.addMessage(chatMessage)
        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun resetRadio(onFinished: () -> Unit = {}) {
        isDiscovering = false
        handler.removeCallbacksAndMessages(null)
        connectedEndpointId = null

        // SWITCH BACK TO MAIN SCREEN
        layoutConnection.visibility = android.view.View.VISIBLE
        layoutChat.visibility = android.view.View.GONE

        Nearby.getConnectionsClient(this).stopAllEndpoints()
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()

        statusText.text = "Status: Resetting..."
        val randomNum = (1000..9999).random()
        myNickName = "FuryUser-$randomNum"

        // RE-INIT ADAPTER (New Identity = New Adapter)
        chatAdapter = ChatAdapter(myNickName)
        chatRecyclerView.adapter = chatAdapter

        statusText.postDelayed({
            statusText.text = "Status: Ready ($myNickName)"
            onFinished()
        }, 1500)
    }

    // --- HELPERS ---
    private fun serialize(message: ChatMessage): ByteArray {
        val outputStream = java.io.ByteArrayOutputStream()
        val objectStream = java.io.ObjectOutputStream(outputStream)
        objectStream.writeObject(message)
        return outputStream.toByteArray()
    }

    private fun deserialize(bytes: ByteArray): ChatMessage {
        val inputStream = java.io.ByteArrayInputStream(bytes)
        val objectStream = java.io.ObjectInputStream(inputStream)
        return objectStream.readObject() as ChatMessage
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
}