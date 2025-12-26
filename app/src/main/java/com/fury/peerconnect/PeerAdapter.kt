package com.fury.peerconnect

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo

class PeerAdapter(
    private val onPeerClicked: (String, String) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    private val peers = ArrayList<Pair<String, DiscoveredEndpointInfo>>()

    fun addPeer(endpointId: String, info: DiscoveredEndpointInfo) {
        // Prevent duplicates: Only add if not already in list
        if (peers.none { it.first == endpointId }) {
            peers.add(Pair(endpointId, info))
            // This updates the UI immediately
            notifyItemInserted(peers.size - 1)
        }
    }

    fun clearPeers() {
        peers.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        // Make sure 'item_peer' exists!
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        val (id, info) = peers[position]
        holder.bind(info.endpointName, id)
    }

    override fun getItemCount() = peers.size

    inner class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val peerName: TextView = itemView.findViewById(R.id.peerName)

        fun bind(name: String, id: String) {
            peerName.text = name
            itemView.setOnClickListener {
                onPeerClicked(id, name)
            }
        }
    }
}