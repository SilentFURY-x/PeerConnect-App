package com.fury.peerconnect

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo

class PeerAdapter(private val onPeerClicked: (String, String) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // Two lists: One for active discovery, one for history
    private val onlinePeers = mutableListOf<Pair<String, DiscoveredEndpointInfo>>()
    private val offlinePeers = mutableListOf<PeerEntity>()

    // View Types
    private val TYPE_ONLINE = 1
    private val TYPE_OFFLINE = 2

    fun addPeer(endpointId: String, info: DiscoveredEndpointInfo) {
        // FIX: Remove by NAME, not ID.
        // If "Arjun" comes online, remove any "Arjun" from the offline list.
        offlinePeers.removeAll { it.name == info.endpointName }

        // Add to online list
        if (onlinePeers.none { it.first == endpointId }) {
            onlinePeers.add(endpointId to info)
            notifyDataSetChanged()
        }
    }

    // NEW: Function to load history
    fun updateList(history: List<PeerEntity>) {
        offlinePeers.clear()

        // FIX: Only add to "Offline" list if their NAME is not currently Online
        for (peer in history) {
            val isNameOnline = onlinePeers.any { it.second.endpointName == peer.name }

            // We also avoid duplicates within the offline list itself
            val isNameAlreadyInOffline = offlinePeers.any { it.name == peer.name }

            if (!isNameOnline && !isNameAlreadyInOffline) {
                offlinePeers.add(peer)
            }
        }
        notifyDataSetChanged()
    }

    fun clearPeers() {
        onlinePeers.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (position < onlinePeers.size) TYPE_ONLINE else TYPE_OFFLINE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val viewHolder = holder as PeerViewHolder

        if (getItemViewType(position) == TYPE_ONLINE) {
            // --- ONLINE PEER ---
            val (id, info) = onlinePeers[position]
            viewHolder.bindOnline(info.endpointName, id)
            viewHolder.itemView.setOnClickListener { onPeerClicked(id, info.endpointName) }
        } else {
            // --- OFFLINE PEER ---
            val offlinePos = position - onlinePeers.size
            if (offlinePos >= 0 && offlinePos < offlinePeers.size) {
                val peer = offlinePeers[offlinePos]
                viewHolder.bindOffline(peer.name)

                // Clicking offline user does nothing (or shows toast)
                viewHolder.itemView.setOnClickListener {
                    // Optional: You could show "Last seen: ${Date(peer.lastSeenTimestamp)}"
                }
            }
        }
    }

    override fun getItemCount(): Int = onlinePeers.size + offlinePeers.size

    class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.peerName)
        private val statusText: TextView = itemView.findViewById(R.id.statusText)

        fun bindOnline(name: String, id: String) {
            nameText.text = name
            nameText.setTextColor(android.graphics.Color.BLACK)

            statusText.text = "Tap to Connect"
            statusText.setTextColor(android.graphics.Color.parseColor("#4CAF50")) // Green
        }

        fun bindOffline(name: String) {
            nameText.text = name
            nameText.setTextColor(android.graphics.Color.GRAY) // Grey out name

            statusText.text = "Offline / Out of Range"
            statusText.setTextColor(android.graphics.Color.GRAY) // Grey status
        }
    }
}