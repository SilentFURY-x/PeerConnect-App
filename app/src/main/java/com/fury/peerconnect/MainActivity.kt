package com.fury.peerconnect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    // --- CONFIGURATION ---
    private val STRATEGY = Strategy.P2P_STAR
    private val SERVICE_ID = "com.fury.peerconnect_v2"
    private val TAG = "PeerConnectDebug"

    // --- STATE VARIABLES ---
    private var isPairingMode = false // true = accept strangers; false = friends only
    private var isHost = false // Are we currently Advertising or Discovering?
    private var myNickName: String = ""

    // CHAT STATE
    private var currentChatPeerName: String? = null
    private var currentChatEndpointId: String? = null // Only exists if they are online

    // TRACKING
    private val pendingConnections = mutableMapOf<String, String>() // Endpoint ID -> Name
    private val pendingPayloads = mutableMapOf<Long, Long>() // PayloadID -> Database MessageID

    // AUTO-LOOP HANDLER
    private val handler = Handler(Looper.getMainLooper())
    private val roleSwitchRunnable = Runnable { switchRoles() }

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
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            Toast.makeText(this, "Permissions Granted!", Toast.LENGTH_SHORT).show()
            startAutoMode()
        } else {
            Toast.makeText(this, "Permissions Denied. App won't work.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)

        // 1. Reset Status & Load History
        lifecycleScope.launch(Dispatchers.IO) {
            db.peerDao().setAllOffline() // Everyone starts offline
            loadPeersFromDb()
        }

        // 2. Initialize UI
        statusText = findViewById(R.id.statusText)
        chatStatusText = findViewById(R.id.chatStatusText)

        btnAddContact = findViewById(R.id.btnHost)
        btnAddContact.text = "Add New Contact"

        findViewById<Button>(R.id.btnJoin).visibility = android.view.View.GONE
        findViewById<Button>(R.id.btnDisconnect).visibility = android.view.View.GONE

        layoutConnection = findViewById(R.id.layoutConnection)
        layoutChat = findViewById(R.id.layoutChat)
        btnExitChat = findViewById(R.id.btnExitChat)

        peersRecyclerView = findViewById(R.id.peersRecyclerView)
        peersRecyclerView.layoutManager = LinearLayoutManager(this)

        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatRecyclerView.layoutManager = LinearLayoutManager(this)

        editMessage = findViewById(R.id.editMessage)
        btnSend = findViewById(R.id.btnSend)

        // 3. SETUP ADAPTER
        peerAdapter = PeerAdapter { peer ->
            openChat(peer.name, if (peer.isOnline) peer.endpointId else null)
        }
        peersRecyclerView.adapter = peerAdapter

        checkIdentity()

        // 4. LISTENERS
        btnAddContact.setOnClickListener {
            if (isPairingMode) {
                isPairingMode = false
                btnAddContact.text = "Add New Contact"
                btnAddContact.setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))
                startAutoMode()
            } else {
                showPairingDialog()
            }
        }

        btnSend.setOnClickListener {
            val text = editMessage.text.toString()
            if (text.isNotEmpty()) sendMessage(text)
        }

        btnExitChat.setOnClickListener { closeChat() }

        Nearby.getConnectionsClient(this).stopAllEndpoints()

        if (hasPermissions()) startAutoMode() else permissionLauncher.launch(requiredPermissions)
    }

    // --- NAVIGATION ---

    private fun openChat(peerName: String, endpointId: String?) {
        currentChatPeerName = peerName
        currentChatEndpointId = endpointId

        lifecycleScope.launch(Dispatchers.IO) {
            val history = db.messageDao().getChatHistory(myNickName, peerName)
            withContext(Dispatchers.Main) { updateChatUI(history) }
        }

        layoutConnection.visibility = android.view.View.GONE
        layoutChat.visibility = android.view.View.VISIBLE

        updateStatus(if (endpointId != null) "Connected" else "Offline")
        findViewById<TextView>(R.id.chatHeader).text = peerName
    }

    private fun closeChat() {
        currentChatPeerName = null
        currentChatEndpointId = null
        layoutChat.visibility = android.view.View.GONE
        layoutConnection.visibility = android.view.View.VISIBLE
        updateStatus("Status: Scanning...")
    }

    // --- ENGINE: AUTO-CONNECT LOOP ---

    private fun startAutoMode() {
        handler.removeCallbacks(roleSwitchRunnable)
        switchRoles()
    }

    private fun switchRoles() {
        // If chatting, don't switch roles (keep connection alive)
        if (currentChatEndpointId != null) return

        val client = Nearby.getConnectionsClient(this)
        client.stopAdvertising()
        client.stopDiscovery()

        isHost = !isHost

        // CACHE FLUSH: 600ms delay to let Bluetooth radio reset
        handler.postDelayed({
            if (isHost) startAdvertising() else startDiscovery()
        }, 600)

        // STABLE TIMING: 6-10s to allow P2P_STAR to stabilize
        val randomDelay = (6000..10000).random().toLong()
        handler.postDelayed(roleSwitchRunnable, randomDelay)
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).setLowPower(false).build()
        Nearby.getConnectionsClient(this)
            .startAdvertising(myNickName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
            .addOnSuccessListener { updateStatus("Auto: Advertising...") }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(this)
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener { updateStatus("Auto: Scanning...") }
    }

    // --- DISCONNECTION HELPER (FAIL FAST) ---
    private fun handleExplicitDisconnect(endpointId: String) {
        // Prevent double-processing
        if (!pendingConnections.containsKey(endpointId) && currentChatEndpointId != endpointId) return

        Log.e(TAG, "Handling Explicit Disconnect for $endpointId")

        // 1. Update UI state if active chat
        if (endpointId == currentChatEndpointId) {
            currentChatEndpointId = null
            updateStatus("Offline (Connection Lost)")
        }
        pendingConnections.remove(endpointId)

        // 2. Kill connection in API
        Nearby.getConnectionsClient(this).disconnectFromEndpoint(endpointId)

        // 3. Mark Offline in DB
        lifecycleScope.launch(Dispatchers.IO) {
            db.peerDao().setAllOffline()
            loadPeersFromDb()
        }

        // 4. RESTART SEARCH IMMEDIATELY
        startAutoMode()
    }

    // --- CALLBACKS ---

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val foundName = info.endpointName
            lifecycleScope.launch(Dispatchers.IO) {
                val isKnown = db.peerDao().isKnownPeer(foundName)
                withContext(Dispatchers.Main) {
                    if (isKnown || isPairingMode) {
                        Nearby.getConnectionsClient(this@MainActivity)
                            .requestConnection(myNickName, endpointId, connectionLifecycleCallback)
                        handler.removeCallbacks(roleSwitchRunnable)
                    }
                }
            }
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val incomingName = info.endpointName
            pendingConnections[endpointId] = incomingName

            // INSTANT ACCEPT
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)

            lifecycleScope.launch(Dispatchers.IO) {
                val isKnown = db.peerDao().isKnownPeer(incomingName)
                if (!isKnown && !isPairingMode) {
                    // Stranger -> Disconnect
                    Nearby.getConnectionsClient(this@MainActivity).disconnectFromEndpoint(endpointId)
                } else {
                    handler.removeCallbacks(roleSwitchRunnable)
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val peerName = pendingConnections[endpointId] ?: return

            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                // 1. PAIRING LOGIC
                if (isPairingMode) {
                    isPairingMode = false
                    btnAddContact.text = "Add New Contact"
                    btnAddContact.setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))

                    lifecycleScope.launch(Dispatchers.IO) {
                        db.peerDao().insertPeer(PeerEntity(name = peerName, endpointId = endpointId, lastSeenTimestamp = System.currentTimeMillis(), isOnline = true))
                        loadPeersFromDb()
                    }
                    startAutoMode() // Restart loop to background
                }

                // 2. UPDATE DB & UI
                lifecycleScope.launch(Dispatchers.IO) {
                    db.peerDao().insertPeer(PeerEntity(name = peerName, endpointId = endpointId, lastSeenTimestamp = System.currentTimeMillis(), isOnline = true))

                    withContext(Dispatchers.Main) {
                        peerAdapter.updatePeerStatus(peerName, true)
                        if (currentChatPeerName == peerName) {
                            currentChatEndpointId = endpointId
                            updateStatus("Connected")
                        }
                    }

                    // 3. RESILIENCY (Send Pending)
                    val pendingMsgs = db.messageDao().getUnsentMessages(peerName)
                    if (pendingMsgs.isNotEmpty()) {
                        withContext(Dispatchers.Main) { updateStatus("Sending ${pendingMsgs.size} offline messages...") }

                        for (msg in pendingMsgs) {
                            // --- FIX: Encrypt plaintext from DB before sending ---
                            val encryptedText = SecurityHelper.encrypt(msg.text)

                            val chatPayload = ChatMessage(myNickName, encryptedText, msg.timestamp)
                            val payload = Payload.fromBytes(serialize(chatPayload))

                            // Map pending payload
                            pendingPayloads[payload.id] = msg.id.toLong()

                            Nearby.getConnectionsClient(this@MainActivity)
                                .sendPayload(endpointId, payload)
                                .addOnFailureListener {
                                    pendingPayloads.remove(payload.id)
                                    // If fail here, we don't force disconnect yet, just leave pending
                                }
                        }
                    }
                }
            } else {
                startAutoMode()
            }
        }

        override fun onDisconnected(endpointId: String) {
            handleExplicitDisconnect(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedBytes = payload.asBytes()!!
                val msg = deserialize(receivedBytes)

                lifecycleScope.launch(Dispatchers.IO) {
                    // --- NEW CODE: DECRYPTION ---
                    val decryptedBody = SecurityHelper.decrypt(msg.messageBody)
                    // ----------------------------

                    db.messageDao().insertMessage(MessageEntity(
                        senderId = msg.senderName,
                        receiverId = myNickName,
                        text = decryptedBody, // <--- Store Decrypted text
                        timestamp = msg.time,
                        isSent = true
                    ))

                    if (currentChatPeerName == msg.senderName) {
                        val history = db.messageDao().getChatHistory(myNickName, msg.senderName)
                        withContext(Dispatchers.Main) { updateChatUI(history) }
                    }
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
                    }
                }
            }
        }
    }

    // --- MESSAGING (PESSIMISTIC SENDING) ---

    private fun sendMessage(messageText: String) {
        val peerName = currentChatPeerName ?: return
        val endpointId = currentChatEndpointId
        val canTrySending = endpointId != null

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. SAVE AS PENDING
            // IMPORTANT: Save the ORIGINAL 'messageText' to your DB,
            // otherwise you won't be able to read what you just wrote.
            val msgEntity = MessageEntity(
                senderId = myNickName,
                receiverId = peerName,
                text = messageText, // <--- Store Plaintext locally
                timestamp = System.currentTimeMillis(),
                isSent = false
            )
            val newMsgId = db.messageDao().insertMessage(msgEntity)

            // 2. SHOW IN UI
            val history = db.messageDao().getChatHistory(myNickName, peerName)
            withContext(Dispatchers.Main) { updateChatUI(history) }

            // 3. TRY SEND
            if (canTrySending) {
                // --- NEW CODE: ENCRYPTION ---
                // Encrypt the text before putting it into the ChatMessage object
                val encryptedText = SecurityHelper.encrypt(messageText)

                // We send 'encryptedText' over the air
                val chatMessage = ChatMessage(myNickName, encryptedText, System.currentTimeMillis())
                // ----------------------------

                val payload = Payload.fromBytes(serialize(chatMessage))

                pendingPayloads[payload.id] = newMsgId

                Nearby.getConnectionsClient(this@MainActivity)
                    .sendPayload(endpointId!!, payload)
                    .addOnFailureListener {
                        // FAIL FAST
                        Log.e(TAG, "Send Failed. Disconnecting.")
                        pendingPayloads.remove(payload.id)
                        handleExplicitDisconnect(endpointId)
                    }
            }
        }
        editMessage.setText("")
    }

    // --- HELPERS ---

    private fun showPairingDialog() {
        val options = arrayOf("Receive (Host)", "Send (Join)")
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
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
        Nearby.getConnectionsClient(this).stopDiscovery()
        Nearby.getConnectionsClient(this).stopAdvertising()
        isPairingMode = true
        isHost = true
        updateStatus("Pairing: Hosting (Visible)...")
        btnAddContact.text = "Hosting... (Tap to Cancel)"
        btnAddContact.setBackgroundColor(android.graphics.Color.RED)
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).setLowPower(false).build()
        Nearby.getConnectionsClient(this).startAdvertising(myNickName, SERVICE_ID, connectionLifecycleCallback, options)
    }

    private fun startManualJoin() {
        handler.removeCallbacks(roleSwitchRunnable)
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()
        isPairingMode = true
        isHost = false
        updateStatus("Pairing: Scanning...")
        btnAddContact.text = "Scanning... (Tap to Cancel)"
        btnAddContact.setBackgroundColor(android.graphics.Color.BLUE)
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(this).startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
    }

    private fun updateStatus(text: String) {
        statusText.text = text
        chatStatusText.text = text
        if (text.contains("Offline") || text.contains("Waiting") || text.contains("Scanning")) {
            chatStatusText.setBackgroundColor(android.graphics.Color.RED)
        } else if (text.contains("Connected")) {
            chatStatusText.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            chatStatusText.setBackgroundColor(android.graphics.Color.parseColor("#333333"))
        }
    }

    private fun updateChatUI(history: List<MessageEntity>) {
        val chatMessages = history.map { entity ->
            ChatMessage(senderName = entity.senderId, messageBody = entity.text, time = entity.timestamp)
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
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
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
        return requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }
}