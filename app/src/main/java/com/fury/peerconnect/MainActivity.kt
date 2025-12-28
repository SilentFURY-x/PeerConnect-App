package com.fury.peerconnect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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
    private var isPairingMode = false
    private var isHost = false
    private var myNickName: String = ""

    // CHAT STATE
    private var currentChatPeerName: String? = null
    private var currentChatEndpointId: String? = null

    // TRACKING
    private val pendingConnections = mutableMapOf<String, String>()
    private val pendingPayloads = mutableMapOf<Long, Long>()

    // Track active file transfers
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()
    private val incomingFileNames = mutableMapOf<Long, String>() // Map Payload ID to Filename

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

    // UI: File Sharing
    private lateinit var btnAttach: Button
    private lateinit var btnExitChat: Button

    // --- PERMISSIONS (FIXED FOR ANDROID 12) ---
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Android 13+ (No Storage permission needed for Downloads)
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Android 10, 11, 12 (Scoped Storage - No WRITE needed for Downloads)
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        // Android 9 and lower (Needs WRITE_EXTERNAL_STORAGE)
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
            // Only start radio AFTER permissions are granted
            resetRadio()
            startAutoMode()
        } else {
            Toast.makeText(this, "Permissions Denied. App won't work.", Toast.LENGTH_LONG).show()
        }
    }

    // --- FILE SHARING: PICKER ---
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

        // 1. Reset Status & Load History
        lifecycleScope.launch(Dispatchers.IO) {
            db.peerDao().setAllOffline()
            loadPeersFromDb()
        }

        // 2. Initialize UI
        statusText = findViewById(R.id.statusText)
        chatStatusText = findViewById(R.id.chatStatusText)
        btnAddContact = findViewById(R.id.btnHost)

        // Hide unused buttons
        findViewById<Button>(R.id.btnJoin).visibility = View.GONE
        findViewById<Button>(R.id.btnDisconnect).visibility = View.GONE

        layoutConnection = findViewById(R.id.layoutConnection)
        layoutChat = findViewById(R.id.layoutChat)
        btnExitChat = findViewById(R.id.btnExitChat)

        peersRecyclerView = findViewById(R.id.peersRecyclerView)
        peersRecyclerView.layoutManager = LinearLayoutManager(this)

        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatRecyclerView.layoutManager = LinearLayoutManager(this)

        editMessage = findViewById(R.id.editMessage)
        btnSend = findViewById(R.id.btnSend)

        // CRITICAL: Ensure your XML actually has this ID
        btnAttach = findViewById(R.id.btnAttach)

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

        btnAttach.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            filePickerLauncher.launch(intent)
        }

        btnExitChat.setOnClickListener { closeChat() }

        // 5. STARTUP LOGIC
        if (hasPermissions()) {
            resetRadio()
            startAutoMode()
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
        if (currentChatEndpointId != null) return

        // Stop previous role
        resetRadio()

        isHost = !isHost

        handler.postDelayed({
            if (isHost) startAdvertising() else startDiscovery()
        }, 600)

        val randomDelay = (6000..10000).random().toLong()
        handler.postDelayed(roleSwitchRunnable, randomDelay)
    }

    private fun resetRadio() {
        // Safe check to avoid crashes if permissions were revoked
        if (!hasPermissions()) return
        Nearby.getConnectionsClient(this).stopAdvertising()
        Nearby.getConnectionsClient(this).stopDiscovery()
        Nearby.getConnectionsClient(this).stopAllEndpoints()
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

    // --- DISCONNECTION HELPER (FAIL FAST) ---
    private fun handleExplicitDisconnect(endpointId: String) {
        if (!pendingConnections.containsKey(endpointId) && currentChatEndpointId != endpointId) return

        Log.e(TAG, "Handling Explicit Disconnect for $endpointId")

        // FIX: Ensure UI updates happen on Main Thread
        runOnUiThread {
            if (endpointId == currentChatEndpointId) {
                currentChatEndpointId = null
                updateStatus("Offline (Connection Lost)")
            }
        }

        pendingConnections.remove(endpointId)
        Nearby.getConnectionsClient(this).disconnectFromEndpoint(endpointId)

        lifecycleScope.launch(Dispatchers.IO) {
            db.peerDao().setAllOffline()
            loadPeersFromDb()
        }

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
                if (isPairingMode) {
                    isPairingMode = false
                    runOnUiThread {
                        btnAddContact.text = "Add New Contact"
                        btnAddContact.setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.peerDao().insertPeer(PeerEntity(name = peerName, endpointId = endpointId, lastSeenTimestamp = System.currentTimeMillis(), isOnline = true))
                        loadPeersFromDb()
                    }
                    startAutoMode()
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    db.peerDao().insertPeer(PeerEntity(name = peerName, endpointId = endpointId, lastSeenTimestamp = System.currentTimeMillis(), isOnline = true))

                    withContext(Dispatchers.Main) {
                        peerAdapter.updatePeerStatus(peerName, true)
                        if (currentChatPeerName == peerName) {
                            currentChatEndpointId = endpointId
                            updateStatus("Connected")
                        }
                    }

                    // RESILIENCY
                    val pendingMsgs = db.messageDao().getUnsentMessages(peerName)
                    if (pendingMsgs.isNotEmpty()) {
                        withContext(Dispatchers.Main) { updateStatus("Sending ${pendingMsgs.size} offline messages...") }

                        for (msg in pendingMsgs) {
                            val encryptedText = SecurityHelper.encrypt(msg.text)
                            val chatPayload = ChatMessage(myNickName, encryptedText, msg.timestamp)
                            val payload = Payload.fromBytes(serialize(chatPayload))

                            pendingPayloads[payload.id] = msg.id.toLong()

                            Nearby.getConnectionsClient(this@MainActivity)
                                .sendPayload(endpointId, payload)
                                .addOnFailureListener {
                                    pendingPayloads.remove(payload.id)
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
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val receivedBytes = payload.asBytes()!!
                    try {
                        val msg = deserialize(receivedBytes)
                        lifecycleScope.launch(Dispatchers.IO) {
                            val decryptedBody = SecurityHelper.decrypt(msg.messageBody)

                            // 1. CATCH THE FILENAME
                            if (decryptedBody.startsWith("[FILE]:")) {
                                val cleanName = decryptedBody.removePrefix("[FILE]:")

                                // We don't know the payload ID of the file yet (or maybe we do),
                                // but usually, the file payload arrives separately.
                                // For simplicity in this demo, we assume the next file payload
                                // belongs to this name, or we use a timestamp fallback.
                                // Ideally, you send a JSON with {filename, payloadId}.
                                // For now, let's just log it and show UI.

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
                    } catch (e: Exception) {
                        Log.e(TAG, "Deserialization failed", e)
                    }
                }
                Payload.Type.FILE -> {
                    // Track the incoming file
                    incomingFilePayloads[payload.id] = payload
                    Log.d(TAG, "File Payload Started: ${payload.id}")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Debugging: Log status to see if it even reaches here
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) {
                val progress = (100 * update.bytesTransferred / update.totalBytes).toInt()
                if (progress % 25 == 0) Log.d(TAG, "Transfer: $progress%")
            }

            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                // 1. Text Success
                val dbMsgId = pendingPayloads[update.payloadId]
                if (dbMsgId != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.messageDao().markAsSent(dbMsgId.toInt())
                        pendingPayloads.remove(update.payloadId)
                    }
                }

                // 2. File Success
                val filePayload = incomingFilePayloads[update.payloadId]
                if (filePayload != null) {
                    incomingFilePayloads.remove(update.payloadId)

                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            // FIX: Get the ParcelFileDescriptor, NOT the Java File
                            val pfd = filePayload.asFile()?.asParcelFileDescriptor()

                            val fileName = "PeerConnect_${System.currentTimeMillis()}.jpg"

                            // Pass the PFD to our new function
                            val success = copyToDownloads(pfd, fileName)

                            withContext(Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(this@MainActivity, "File Saved: $fileName", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "Save Failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "File Save Crash", e)
                        }
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
            val msgEntity = MessageEntity(
                senderId = myNickName,
                receiverId = peerName,
                text = messageText,
                timestamp = System.currentTimeMillis(),
                isSent = false
            )
            val newMsgId = db.messageDao().insertMessage(msgEntity)

            val history = db.messageDao().getChatHistory(myNickName, peerName)
            withContext(Dispatchers.Main) { updateChatUI(history) }

            if (canTrySending) {
                val encryptedText = SecurityHelper.encrypt(messageText)
                val chatMessage = ChatMessage(myNickName, encryptedText, System.currentTimeMillis())

                try {
                    val payload = Payload.fromBytes(serialize(chatMessage))
                    pendingPayloads[payload.id] = newMsgId
                    Nearby.getConnectionsClient(this@MainActivity)
                        .sendPayload(endpointId!!, payload)
                        .addOnFailureListener {
                            pendingPayloads.remove(payload.id)
                            handleExplicitDisconnect(endpointId)
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Serialization Failed", e)
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
        resetRadio()
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
        resetRadio()
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

    // Helper to extract filename from URI
    private fun getFileNameFromUri(uri: android.net.Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) name = it.getString(index)
            }
        }
        return name
    }

    private fun sendFile(uri: android.net.Uri, fileName: String) {
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

    private fun copyToDownloads(inputPFD: android.os.ParcelFileDescriptor?, fileName: String): Boolean {
        if (inputPFD == null) {
            Log.e(TAG, "Save Failed: Input PFD is null")
            return false
        }

        return try {
            // Create an input stream directly from the system descriptor
            // This bypasses the "Permission Denied" file path check
            val inputStream = android.os.ParcelFileDescriptor.AutoCloseInputStream(inputPFD)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // --- ANDROID 10+ (MediaStore) ---
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw java.io.IOException("MediaStore insertion failed")

                resolver.openOutputStream(uri).use { output ->
                    if (output == null) throw java.io.IOException("Output stream is null")
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }

                contentValues.clear()
                contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                Log.d(TAG, "File saved to MediaStore: $uri")
                true

            } else {
                // --- ANDROID 9 & OLDER ---
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()

                var finalFile = java.io.File(downloadsDir, fileName)
                var counter = 1
                while (finalFile.exists()) {
                    val nameWithoutExt = fileName.substringBeforeLast(".")
                    val ext = fileName.substringAfterLast(".", "")
                    finalFile = java.io.File(downloadsDir, "$nameWithoutExt($counter).$ext")
                    counter++
                }

                java.io.FileOutputStream(finalFile).use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "File saved to Legacy Path: ${finalFile.absolutePath}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL SAVE ERROR: ${e.message}", e)
            return false
        }
    }
}