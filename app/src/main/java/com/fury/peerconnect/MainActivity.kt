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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var targetPeerName: String = "" // Add this

    // Database
    private lateinit var db: AppDatabase

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

    // Peer List UI
    private lateinit var peersRecyclerView: RecyclerView
    private lateinit var peerAdapter: PeerAdapter

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

        db = AppDatabase.getDatabase(this)

// Reset everyone to "Offline" when app opens
        lifecycleScope.launch(Dispatchers.IO) {
            db.peerDao().setAllOffline()
            loadPeersFromDb() // Show the history immediately
        }

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

        // --- INITIALIZE PEER LIST VIEWS ---
        peersRecyclerView = findViewById(R.id.peersRecyclerView)
        peersRecyclerView.layoutManager = LinearLayoutManager(this)

        // --- SETUP PEER ADAPTER (Click Logic) ---
        peerAdapter = PeerAdapter { endpointId, endpointName ->

            targetPeerName = endpointName // <--- SAVE NAME HERE

            // 1. CRITICAL FIX: Kill the Pulse Timer immediately!
            isDiscovering = false
            handler.removeCallbacksAndMessages(null)

            // 2. Stop scanning (we found who we want)
            Nearby.getConnectionsClient(this).stopDiscovery()

            // 3. Update status
            statusText.text = "Connecting to $endpointName..."

            // 4. Request Connection manually
            Nearby.getConnectionsClient(this)
                .requestConnection(myNickName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener {
                    statusText.text = "Connection Failed"
                    // If connection fails, user can press Join again manually
                }
        }

        peersRecyclerView.adapter = peerAdapter

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

        checkIdentity()

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

                // Pulse Logic (Increased to 10 seconds for stability)
                handler.postDelayed({
                    // CRITICAL CHECK: Only restart if we are STILL discovering.
                    // If the user clicked a name, isDiscovering will be false, and this won't run.
                    if (isDiscovering) {
                        Nearby.getConnectionsClient(this@MainActivity).stopDiscovery()
                        startDiscovery()
                    }
                }, 10000) // Changed from 6000 to 10000
            }
            .addOnFailureListener {
                statusText.text = "Status: Discovery Failed"
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // 1. Log it
            Log.d(TAG, "Found: ${info.endpointName}")

            // 2. Update status to tell user what to do
            statusText.text = "Found peers! Select one below."

            // 3. ADD TO ADAPTER (Safety Check Included)
            // We do NOT stop discovery here. We keep listening for more peers.
            if (::peerAdapter.isInitialized) {
                peerAdapter.addPeer(endpointId, info)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Optional: Remove them from the list if they walk away
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            targetPeerName = info.endpointName
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

                    // SAVE TO DB
                    val newPeer = PeerEntity(
                        endpointId = endpointId,
                        name = targetPeerName, // <--- USE IT HERE
                        lastSeenTimestamp = System.currentTimeMillis(),
                        isOnline = true
                    )

                    lifecycleScope.launch(Dispatchers.IO) {
                        db.peerDao().insertPeer(newPeer)
                    }
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

        // --- CLEAR PEER LIST ---
        // We check 'isInitialized' to avoid crashes if the app just started
        if (::peerAdapter.isInitialized) {
            peerAdapter.clearPeers()
        }

        statusText.text = "Status: Resetting..."

        val userManager = UserManager(this)
        myNickName = userManager.getUsername() ?: "Unknown" // Load saved name

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

    private fun checkIdentity() {
        val userManager = UserManager(this)

        if (userManager.hasIdentity()) {
            // We already have a name, load it
            myNickName = userManager.getUsername()!!
            statusText.text = "Status: Ready ($myNickName)"

            // --- ADD THIS MISSING BLOCK ---
            chatAdapter = ChatAdapter(myNickName)
            chatRecyclerView.adapter = chatAdapter
            // -----------------------------

        } else {
            showNameInputDialog()
        }
    }

    private fun showNameInputDialog() {
        val input = EditText(this)
        input.hint = "Enter your unique ID/Name"

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Welcome to PeerConnect")
            .setMessage("Set your unique identity to be discovered by others.")
            .setView(input)
            .setCancelable(false) // User MUST enter a name
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    val userManager = UserManager(this)
                    userManager.saveUsername(name)
                    myNickName = name
                    statusText.text = "Status: Ready ($myNickName)"

                    // Re-init adapter with new name
                    chatAdapter = ChatAdapter(myNickName)
                    chatRecyclerView.adapter = chatAdapter
                } else {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    showNameInputDialog() // Ask again
                }
            }
            .create()

        dialog.show()
    }

    private fun loadPeersFromDb() {
        lifecycleScope.launch(Dispatchers.IO) {
            val savedPeers = db.peerDao().getAllPeers()

            // Switch to UI thread to update the adapter
            withContext(Dispatchers.Main) {
                // We need to teach PeerAdapter to accept List<PeerEntity>
                // For now, let's just log it to ensure it works
                Log.d(TAG, "Loaded ${savedPeers.size} peers from history")

                if (::peerAdapter.isInitialized) {
                    peerAdapter.updateList(savedPeers)
                }
            }
        }
    }
}