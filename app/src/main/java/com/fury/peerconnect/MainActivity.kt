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
    private val SERVICE_ID = "com.fury.peerconnect"
    private val TAG = "PeerConnectDebug"

    // UI Elements
    private lateinit var statusText: TextView
    private lateinit var btnHost: Button
    private lateinit var btnJoin: Button

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        btnHost = findViewById(R.id.btnHost)
        btnJoin = findViewById(R.id.btnJoin)

        // Request permissions immediately
        if (!hasPermissions()) {
            permissionLauncher.launch(requiredPermissions)
        }

        // BUTTON LISTENERS
        btnHost.setOnClickListener {
            startAdvertising()
        }

        btnJoin.setOnClickListener {
            startDiscovery()
        }
    }

    // --- 1. ADVERTISING (HOST) LOGIC ---
    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()

        // "ArjunHost" is the nickname others will see
        Nearby.getConnectionsClient(this)
            .startAdvertising(
                "FuryHost",
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
    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        Nearby.getConnectionsClient(this)
            .startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                discoveryOptions
            )
            .addOnSuccessListener {
                statusText.text = "Status: Discovering... (Scanning)"
                Log.d(TAG, "Discovery started")
            }
            .addOnFailureListener { e ->
                statusText.text = "Status: Failed to Start Discovery"
                Log.e(TAG, "Discovery failed", e)
            }
    }

    // --- 3. CALLBACKS (THE HANDSHAKE) ---

    // Callback for DISCOVERERS: "I found someone!"
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Found endpoint: ${info.endpointName}")
            statusText.text = "Found: ${info.endpointName}. Requesting connection..."

            // Automatically request connection when found
            Nearby.getConnectionsClient(this@MainActivity)
                .requestConnection("FuryJoiner", endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost")
        }
    }

    // Callback for BOTH: "Connection initiated/Result"
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        // Step A: Connection Initiated (Security Check)
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated with: ${info.endpointName}")

            // In a real app, you show a "Accept?" dialog here.
            // For this recruitment task, we AUTO-ACCEPT to save time.
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
            statusText.text = "Status: Accepting connection..."
        }

        // Step B: Connection Result (Did it work?)
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    statusText.text = "Status: CONNECTED to $endpointId"
                    Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()
                    // STOP Advertising/Discovery to save battery once connected
                    Nearby.getConnectionsClient(this@MainActivity).stopAdvertising()
                    Nearby.getConnectionsClient(this@MainActivity).stopDiscovery()
                }
                else -> {
                    statusText.text = "Status: Connection Rejected/Failed"
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            statusText.text = "Status: Disconnected"
        }
    }

    // --- 4. DATA HANDLING (We will use this in Phase 3) ---
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // TODO: Receive messages here
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Bytes transferred progress
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}