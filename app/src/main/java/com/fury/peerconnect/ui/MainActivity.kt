package com.fury.peerconnect.ui

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fury.peerconnect.R
import com.fury.peerconnect.logic.SecurityHelper
import com.fury.peerconnect.logic.UserManager
import com.fury.peerconnect.data.AppDatabase
import com.fury.peerconnect.data.ChatMessage
import com.fury.peerconnect.data.PeerEntity
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class MainActivity : AppCompatActivity() {

    // --- CONFIGURATION ---
    private val STRATEGY = Strategy.P2P_STAR
    private val SERVICE_ID = "com.fury.peerconnect_v2"
    private val TAG = "PeerConnectDebug"

    // --- STATE VARIABLES ---
    private var isPairingMode = false
    private var isHost = false
    private var myNickName: String = ""

    private var isConnected = false

    // CHAT STATE
    private var currentChatPeerName: String? = null
    private var currentChatEndpointId: String? = null

    // Track discovered devices for manual selection
    private val discoveredEndpoints = mutableMapOf<String, String>()
    private var selectionDialog: AlertDialog? = null

    // TRACKING
    private val pendingConnections = mutableMapOf<String, String>()
    private val pendingPayloads = mutableMapOf<Long, Long>()

    // Track active file transfers
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()

    // AUTO-LOOP HANDLER
    private val handler = Handler(Looper.getMainLooper())
    private val roleSwitchRunnable = Runnable { switchRoles() }

    // FIX: Track the internal delayed runnable so we can cancel it too
    private var pendingRadioSwitch: Runnable? = null

    // --- DATA ---
    private lateinit var db: AppDatabase
    private lateinit var peerAdapter: PeerAdapter
    private lateinit var chatAdapter: ChatAdapter

    // --- UI ELEMENTS ---
    private lateinit var statusText: TextView
    private lateinit var chatStatusText: TextView
    private lateinit var btnAddContact: Button

    private lateinit var layoutConnection: ConstraintLayout
    private lateinit var layoutChat: ConstraintLayout

    private lateinit var peersRecyclerView: RecyclerView
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var editMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnAttach: Button
    private lateinit var btnExitChat: Button

    // --- PERMISSIONS ---
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            Toast.makeText(this, "Permissions Granted!", Toast.LENGTH_SHORT).show()
            // Check Location Switch, THEN start
            checkLocationAndRun {
                resetRadio()
                startAutoMode()
            }
        } else {
            Toast.makeText(this, "Permissions Denied. App won't work.", Toast.LENGTH_LONG).show()
        }
    }

    // --- LOCATION ENFORCER (NEW) ---
    private val locationResolutionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Location Enabled!", Toast.LENGTH_SHORT).show()
            resetRadio()
            startAutoMode()
        } else {
            updateStatus("Error: Location is Required!")
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri = result.data?.data
            if (uri != null) {
                val fileName = getFileNameFromUri(uri) ?: "Unknown_File"
                sendFile(uri, fileName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)

        lifecycleScope.launch(Dispatchers.IO) {
            db.peerDao().setAllOffline()
            loadPeersFromDb()
        }

        statusText = findViewById(R.id.statusText)
        chatStatusText = findViewById(R.id.chatStatusText)
        btnAddContact = findViewById(R.id.btnHost)

        layoutConnection = findViewById(R.id.layoutConnection)
        layoutChat = findViewById(R.id.layoutChat)
        btnExitChat = findViewById(R.id.btnExitChat)

        peersRecyclerView = findViewById(R.id.peersRecyclerView)
        peersRecyclerView.layoutManager = LinearLayoutManager(this)

        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatRecyclerView.layoutManager = LinearLayoutManager(this)

        editMessage = findViewById(R.id.editMessage)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)

        peerAdapter = PeerAdapter { peer ->
            openChat(peer.name, if (peer.isOnline) peer.endpointId else null)
        }
        peersRecyclerView.adapter = peerAdapter

        checkIdentity()

        // 4. LISTENERS (Updated with Location Check)
        btnAddContact.setOnClickListener {
            checkLocationAndRun {
                if (isPairingMode) {
                    isPairingMode = false
                    btnAddContact.text = "Add New Contact"
                    btnAddContact.setBackgroundColor(Color.parseColor("#6200EE"))
                    startAutoMode()
                } else {
                    showPairingDialog()
                }
            }
        }

        btnSend.setOnClickListener {
            val text = editMessage.text.toString()
            if (text.isNotEmpty()) sendMessage(text)
        }

        btnAttach.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            filePickerLauncher.launch(intent)
        }

        btnExitChat.setOnClickListener { closeChat() }

        Nearby.getConnectionsClient(this).stopAllEndpoints()

        // 5. STARTUP LOGIC
        if (hasPermissions()) {
            checkLocationAndRun {
                resetRadio()
                startAutoMode()
            }
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // --- NAVIGATION ---
    private fun openChat(peerName: String, endpointId: String?) {
        currentChatPeerName = peerName
        currentChatEndpointId = endpointId
        lifecycleScope.launch(Dispatchers.IO) {
            val history = db.messageDao().getChatHistory(myNickName, peerName)
            withContext(Dispatchers.Main) { updateChatUI(history) }
        }
        layoutConnection.visibility = View.GONE
        layoutChat.visibility = View.VISIBLE
        updateStatus(if (endpointId != null) "Connected" else "Offline")
        findViewById<TextView>(R.id.chatHeader).text = peerName
    }

    private fun closeChat() {
        currentChatPeerName = null
        currentChatEndpointId = null
        layoutChat.visibility = View.GONE
        layoutConnection.visibility = View.VISIBLE
        updateStatus("Status: Scanning...")
    }

    // --- ENGINE: AUTO-CONNECT LOOP ---
    private fun startAutoMode() {
        handler.removeCallbacks(roleSwitchRunnable)
        switchRoles()
    }

    private fun switchRoles() {
        if (currentChatEndpointId != null || isConnected) return

        resetRadio()
        isHost = !isHost

        // FIX: Assign to variable so we can cancel it in startManualHost
        pendingRadioSwitch = Runnable {
            if (!isConnected) {
                if (isHost) startAdvertising() else startDiscovery()
            }
        }
        handler.postDelayed(pendingRadioSwitch!!, 600)

        val randomDelay = (6000..10000).random().toLong()
        handler.postDelayed(roleSwitchRunnable, randomDelay)
    }

    private fun resetRadio() {
        if (!hasPermissions()) return
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()
        if (!isConnected) {
            Nearby.getConnectionsClient(this).stopAllEndpoints()
        }
        // FIX: Ensure pending start commands are cancelled
        if (pendingRadioSwitch != null) {
            handler.removeCallbacks(pendingRadioSwitch!!)
        }
    }

    private fun startAdvertising() {
        if (!hasPermissions()) return
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).setLowPower(false).build()
        Nearby.getConnectionsClient(this)
            .startAdvertising(myNickName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
            .addOnSuccessListener { updateStatus("Auto: Advertising...") }
            .addOnFailureListener { e -> Log.e(TAG, "Adv fail", e) }
    }

    private fun startDiscovery() {
        if (!hasPermissions()) return
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(this)
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { updateStatus("Auto: Scanning...") }
            .addOnFailureListener { e -> Log.e(TAG, "Disc fail", e) }
    }

    // --- DISCONNECTION HELPER ---
    private fun handleExplicitDisconnect(endpointId: String) {
        // 1. Check if this disconnect is relevant to us
        if (!pendingConnections.containsKey(endpointId) && currentChatEndpointId != endpointId) return

        isConnected = false

        // 2. UI Cleanup on Main Thread
        runOnUiThread {
            if (endpointId == currentChatEndpointId) {
                currentChatEndpointId = null
                updateStatus("Offline (Connection Lost)")
            }
        }

        // 3. Remove connection references
        pendingConnections.remove(endpointId)
        Nearby.getConnectionsClient(this).disconnectFromEndpoint(endpointId)

        // 4. DATABASE FIX: Update ONLY the disconnected peer
        lifecycleScope.launch(Dispatchers.IO) {
            // A. Get all peers
            val allPeers = db.peerDao().getAllPeers()

            // B. Find the specific peer who owns this endpointId
            val disconnectedPeer = allPeers.find { it.endpointId == endpointId }

            // C. Mark ONLY them as offline and save
            if (disconnectedPeer != null) {
                db.peerDao().insertPeer(disconnectedPeer.copy(isOnline = false))
            } else {
                // Fallback: If we couldn't match the ID, just ensure data consistency
                // (Optional) db.peerDao().setAllOffline()
            }

            // D. Refresh UI
            loadPeersFromDb()
        }

        // 5. Restart scanning/advertising
        startAutoMode()
    }

    // --- CALLBACKS ---
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val foundName = info.endpointName

            lifecycleScope.launch(Dispatchers.IO) {
                // Check if we already know this person
                val isKnown = db.peerDao().isKnownPeer(foundName)

                withContext(Dispatchers.Main) {
                    if (isKnown) {
                        // SCENARIO 1: Known Friend (Auto-Connect)
                        // This preserves your existing logic for friends
                        Nearby.getConnectionsClient(this@MainActivity)
                            .requestConnection(myNickName, endpointId, connectionLifecycleCallback)
                        handler.removeCallbacks(roleSwitchRunnable)
                    }
                    else if (isPairingMode) {
                        // SCENARIO 2: Unknown Device & User clicked "Add Contact" (Manual Selection)
                        // Do NOT request connection yet.
                        // Add to list and update the UI.
                        if (!discoveredEndpoints.containsKey(endpointId)) {
                            discoveredEndpoints[endpointId] = foundName
                            showDeviceSelectionDialog()
                        }
                    }
                }
            }
        }
        override fun onEndpointLost(endpointId: String) {
            // Optional: Remove from list if they go out of range
            if (isPairingMode) {
                discoveredEndpoints.remove(endpointId)
                showDeviceSelectionDialog()
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val incomingName = info.endpointName
            pendingConnections[endpointId] = incomingName
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
            lifecycleScope.launch(Dispatchers.IO) {
                val isKnown = db.peerDao().isKnownPeer(incomingName)
                if (!isKnown && !isPairingMode) {
                    Nearby.getConnectionsClient(this@MainActivity).disconnectFromEndpoint(endpointId)
                } else {
                    handler.removeCallbacks(roleSwitchRunnable)
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val peerName = pendingConnections[endpointId] ?: return

            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                isConnected = true
                handler.removeCallbacks(roleSwitchRunnable)
                if (pendingRadioSwitch != null) handler.removeCallbacks(pendingRadioSwitch!!)

                // 1. Reset UI if we were in pairing mode
                if (isPairingMode) {
                    isPairingMode = false
                    runOnUiThread {
                        btnAddContact.text = "Add New Contact"
                        btnAddContact.setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))
                    }
                }

                // 2. CRITICAL FIX: Database & UI Sync
                lifecycleScope.launch(Dispatchers.IO) {
                    // A. Update DB with the NEW Endpoint ID (This overwrites the old dead ID)
                    db.peerDao().insertPeer(
                        PeerEntity(
                            name = peerName,
                            endpointId = endpointId, // <--- The fresh ID
                            lastSeenTimestamp = System.currentTimeMillis(),
                            isOnline = true
                        )
                    )

                    // B. RELOAD THE FULL LIST from DB to get that fresh ID
                    val updatedPeers = db.peerDao().getAllPeers()

                    withContext(Dispatchers.Main) {
                        // C. Update Adapter with the WHOLE list (Fixes the "One-Way" bug)
                        if (::peerAdapter.isInitialized) {
                            peerAdapter.updateList(updatedPeers)
                        }

                        // D. If chat is already open, update the variable immediately
                        if (currentChatPeerName == peerName) {
                            currentChatEndpointId = endpointId
                            updateStatus("Connected")
                        }
                    }

                    // 3. Handle Offline Messages
                    val pendingMsgs = db.messageDao().getUnsentMessages(peerName)
                    if (pendingMsgs.isNotEmpty()) {
                        withContext(Dispatchers.Main) { updateStatus("Sending ${pendingMsgs.size} offline messages...") }
                        for (msg in pendingMsgs) {
                            val encryptedText = SecurityHelper.encrypt(msg.text)
                            val chatPayload = ChatMessage(myNickName, encryptedText, msg.timestamp)

                            try {
                                val payload = Payload.fromBytes(serialize(chatPayload))
                                pendingPayloads[payload.id] = msg.id.toLong()
                                Nearby.getConnectionsClient(this@MainActivity).sendPayload(endpointId, payload)
                                    .addOnFailureListener { pendingPayloads.remove(payload.id) }
                            } catch (e: Exception) {
                                Log.e(TAG, "Offline Msg Fail", e)
                            }
                        }
                    }
                }

                // 4. Restart the auto-loop (if not chatting)
                if (currentChatPeerName == null) {
                    startAutoMode()
                }

            } else {
                // Connection Rejected/Failed
                isConnected = false
                startAutoMode()
            }
        }
        override fun onDisconnected(endpointId: String) { handleExplicitDisconnect(endpointId) }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val receivedBytes = payload.asBytes()!!
                    try {
                        val msg = deserialize(receivedBytes)
                        lifecycleScope.launch(Dispatchers.IO) {
                            val decryptedBody = SecurityHelper.decrypt(msg.messageBody)
                            if (decryptedBody.startsWith("[FILE]:")) {
                                val cleanName = decryptedBody.removePrefix("[FILE]:")
                                val displayMsg = "📄 Shared a file: $cleanName"
                                db.messageDao().insertMessage(MessageEntity(
                                    senderId = msg.senderName, receiverId = myNickName,
                                    text = displayMsg, timestamp = msg.time, isSent = true
                                ))
                            } else {
                                db.messageDao().insertMessage(MessageEntity(
                                    senderId = msg.senderName, receiverId = myNickName,
                                    text = decryptedBody, timestamp = msg.time, isSent = true
                                ))
                            }
                            if (currentChatPeerName == msg.senderName) {
                                val history = db.messageDao().getChatHistory(myNickName, msg.senderName)
                                withContext(Dispatchers.Main) { updateChatUI(history) }
                            }
                        }
                    } catch (e: Exception) { Log.e(TAG, "Deserialization failed", e) }
                }
                Payload.Type.FILE -> {
                    incomingFilePayloads[payload.id] = payload
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val dbMsgId = pendingPayloads[update.payloadId]
                if (dbMsgId != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.messageDao().markAsSent(dbMsgId.toInt())
                        pendingPayloads.remove(update.payloadId)
                        // --- Check if queue is empty, then update Status ---
                        if (pendingPayloads.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                // Only update if we are still connected to this person
                                if (currentChatEndpointId == endpointId) {
                                    updateStatus("Connected")
                                }
                            }
                        }
                    }
                }
                val filePayload = incomingFilePayloads[update.payloadId]
                if (filePayload != null) {
                    incomingFilePayloads.remove(update.payloadId)
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val pfd = filePayload.asFile()?.asParcelFileDescriptor()
                            val fileName = "PeerConnect_${System.currentTimeMillis()}.jpg"
                            val success = copyToDownloads(pfd, fileName)
                            withContext(Dispatchers.Main) {
                                if (success) Toast.makeText(this@MainActivity, "File Saved!", Toast.LENGTH_LONG).show()
                                else Toast.makeText(this@MainActivity, "Save Failed", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) { Log.e(TAG, "File Save Crash", e) }
                    }
                }
            }
        }
    }

    // --- MESSAGING ---
    private fun sendMessage(messageText: String) {
        val peerName = currentChatPeerName ?: return
        val endpointId = currentChatEndpointId
        val canTrySending = endpointId != null

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. SAVE: Persist to DB immediately (Resiliency)
            val msgEntity = MessageEntity(
                senderId = myNickName,
                receiverId = peerName,
                text = messageText,
                timestamp = System.currentTimeMillis(),
                isSent = false
            )
            val newMsgId = db.messageDao().insertMessage(msgEntity)

            // 2. UI: Update chat screen so I see my own bubble
            val history = db.messageDao().getChatHistory(myNickName, peerName)
            withContext(Dispatchers.Main) { updateChatUI(history) }

            // 3. SEND: Attempt transmission
            if (canTrySending) {
                val encryptedText = SecurityHelper.encrypt(messageText)
                val chatMessage = ChatMessage(myNickName, encryptedText, System.currentTimeMillis())

                try {
                    val payload = Payload.fromBytes(serialize(chatMessage))
                    pendingPayloads[payload.id] = newMsgId

                    Nearby.getConnectionsClient(this@MainActivity)
                        .sendPayload(endpointId!!, payload)
                        .addOnFailureListener { e ->
                            // LOGIC FIX: Do NOT disconnect. Just mark as failed.
                            Log.e(TAG, "Send Payload Failed (Transient)", e)
                            pendingPayloads.remove(payload.id)

                            launch(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Message queued (Offline)", Toast.LENGTH_SHORT).show()
                            }
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Serialization Failed", e)
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Saved offline. Will send automatically.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        editMessage.setText("")
    }

    // --- HELPERS ---
    private fun checkLocationAndRun(action: () -> Unit) {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000
        ).build()
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
        val client = LocationServices.getSettingsClient(this)

        client.checkLocationSettings(builder.build())
            .addOnSuccessListener { action() }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        val request = IntentSenderRequest.Builder(exception.resolution).build()
                        locationResolutionLauncher.launch(request)
                    } catch (e: Exception) { }
                }
            }
    }

    private fun showPairingDialog() {
        val options = arrayOf("Receive (Host)", "Send (Join)")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Pairing Mode")
        builder.setItems(options) { _, which ->
            if (which == 0) startManualHost() else startManualJoin()
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
            startAutoMode()
        }
        builder.show()
    }

    private fun startManualHost() {
        handler.removeCallbacks(roleSwitchRunnable)
        if (pendingRadioSwitch != null) handler.removeCallbacks(pendingRadioSwitch!!) // FIX GHOST RUNNABLE

        // HARD RESET
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()
        Nearby.getConnectionsClient(this).stopAllEndpoints()

        isConnected = false
        isPairingMode = true
        isHost = true

        updateStatus("Initializing Radio...")
        btnAddContact.text = "Please Wait..."
        btnAddContact.setBackgroundColor(Color.DKGRAY)

        handler.postDelayed({
            updateStatus("Pairing: Hosting (Visible)...")
            btnAddContact.text = "Hosting... (Tap to Cancel)"
            btnAddContact.setBackgroundColor(Color.RED)
            val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).setLowPower(false).build()
            Nearby.getConnectionsClient(this)
                .startAdvertising(myNickName, SERVICE_ID, connectionLifecycleCallback, options)
                .addOnFailureListener { e ->
                    Log.e(TAG, "Manual Host Failed", e)
                    updateStatus("Error: Radio Failed. Retry.")
                    btnAddContact.text = "Retry"
                }
        }, 1000)
    }

    private fun startManualJoin() {
        handler.removeCallbacks(roleSwitchRunnable)
        if (pendingRadioSwitch != null) handler.removeCallbacks(pendingRadioSwitch!!)

        // HARD RESET
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()
        Nearby.getConnectionsClient(this).stopAllEndpoints()

        isConnected = false
        isPairingMode = true
        isHost = false

        // NEW: Clear previous scan results
        discoveredEndpoints.clear()

        updateStatus("Initializing Radio...")
        btnAddContact.text = "Please Wait..."
        btnAddContact.setBackgroundColor(Color.DKGRAY)

        handler.postDelayed({
            updateStatus("Pairing: Scanning...")
            btnAddContact.text = "Scanning... (Tap to Cancel)"
            btnAddContact.setBackgroundColor(Color.BLUE)
            val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

            // Note: endpointDiscoveryCallback is modified below
            Nearby.getConnectionsClient(this)
                .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                .addOnFailureListener { e ->
                    Log.e(TAG, "Manual Join Failed", e)
                    updateStatus("Error: Radio Failed. Retry.")
                    btnAddContact.text = "Retry"
                }
        }, 1000)
    }

    private fun updateStatus(text: String) {
        statusText.text = text
        chatStatusText.text = text
        if (text.contains("Offline") || text.contains("Waiting") || text.contains("Scanning")) {
            chatStatusText.setBackgroundColor(Color.RED)
        } else if (text.contains("Connected")) {
            chatStatusText.setBackgroundColor(Color.parseColor("#4CAF50"))
        } else {
            chatStatusText.setBackgroundColor(Color.parseColor("#333333"))
        }
    }

    private fun updateChatUI(history: List<MessageEntity>) {
        val chatMessages = history.map { entity ->
            ChatMessage(
                senderName = entity.senderId,
                messageBody = entity.text,
                time = entity.timestamp
            )
        }
        chatAdapter.setMessages(chatMessages)
        if (chatAdapter.itemCount > 0) chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun checkIdentity() {
        val userManager = UserManager(this)
        if (userManager.hasIdentity()) {
            myNickName = userManager.getUsername()!!
            updateStatus("Status: Ready ($myNickName)")
            chatAdapter = ChatAdapter(myNickName)
            chatRecyclerView.adapter = chatAdapter
        } else {
            showNameInputDialog()
        }
    }

    private fun showNameInputDialog() {
        val input = EditText(this)
        input.hint = "Enter your unique ID/Name"
        val dialog = AlertDialog.Builder(this)
            .setTitle("Welcome")
            .setView(input).setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    UserManager(this).saveUsername(name)
                    checkIdentity()
                } else {
                    showNameInputDialog()
                }
            }.create()
        dialog.show()
    }

    private fun loadPeersFromDb() {
        lifecycleScope.launch(Dispatchers.IO) {
            val savedPeers = db.peerDao().getAllPeers()
            withContext(Dispatchers.Main) {
                if (::peerAdapter.isInitialized) peerAdapter.updateList(savedPeers)
            }
        }
    }

    private fun serialize(message: ChatMessage): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val objectStream = ObjectOutputStream(outputStream)
        objectStream.writeObject(message)
        return outputStream.toByteArray()
    }

    private fun deserialize(bytes: ByteArray): ChatMessage {
        val inputStream = ByteArrayInputStream(bytes)
        val objectStream = ObjectInputStream(inputStream)
        return objectStream.readObject() as ChatMessage
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) name = it.getString(index)
            }
        }
        return name
    }

    private fun sendFile(uri: Uri, fileName: String) {
        if (currentChatEndpointId == null) return
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return
            val filePayload = Payload.fromFile(pfd)
            Nearby.getConnectionsClient(this).sendPayload(currentChatEndpointId!!, filePayload)
            val metaText = "[FILE]:$fileName"
            sendMessage(metaText)
            Toast.makeText(this, "Sending $fileName...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "File Error", e)
            Toast.makeText(this, "Error sending file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToDownloads(inputPFD: ParcelFileDescriptor?, fileName: String): Boolean {
        if (inputPFD == null) return false
        return try {
            val inputStream = ParcelFileDescriptor.AutoCloseInputStream(inputPFD)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IOException("MediaStore insertion failed")
                resolver.openOutputStream(uri).use { output ->
                    if (output == null) throw IOException("Output stream is null")
                    inputStream.use { input -> input.copyTo(output) }
                }
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                true
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                var finalFile = File(downloadsDir, fileName)
                var counter = 1
                while (finalFile.exists()) {
                    val nameWithoutExt = fileName.substringBeforeLast(".")
                    val ext = fileName.substringAfterLast(".", "")
                    finalFile = File(downloadsDir, "$nameWithoutExt($counter).$ext")
                    counter++
                }
                FileOutputStream(finalFile).use { output -> inputStream.use { input -> input.copyTo(output) } }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL SAVE ERROR: ${e.message}", e)
            return false
        }
    }

    private fun showDeviceSelectionDialog() {
        // If map is empty, dismiss dialog if it's showing
        if (discoveredEndpoints.isEmpty()) {
            selectionDialog?.dismiss()
            return
        }

        // Prepare data for the list
        val endpointIds = discoveredEndpoints.keys.toList()
        val names = discoveredEndpoints.values.toTypedArray()

        // Create or Update the Dialog
        // Note: Recreating the builder is the simplest way to refresh the list dynamically
        if (selectionDialog != null && selectionDialog!!.isShowing) {
            selectionDialog!!.dismiss()
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Found Devices")
        builder.setItems(names) { _, which ->
            // USER SELECTED A DEVICE
            val selectedEndpointId = endpointIds[which]
            val selectedName = names[which]

            Toast.makeText(this, "Connecting to $selectedName...", Toast.LENGTH_SHORT).show()

            // Initiate the connection now
            Nearby.getConnectionsClient(this)
                .requestConnection(myNickName, selectedEndpointId, connectionLifecycleCallback)

            // Stop scanning to prevent interference
            Nearby.getConnectionsClient(this).stopDiscovery()
            handler.removeCallbacks(roleSwitchRunnable)
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
            // Optional: Stop scanning if user cancels
            startAutoMode()
        }

        selectionDialog = builder.create()
        selectionDialog!!.show()
    }
}