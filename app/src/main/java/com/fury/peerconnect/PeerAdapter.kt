package com.fury.peerconnect

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PeerAdapter(private val onPeerClicked: (PeerEntity) -> Unit) :
    RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    private val peers = mutableListOf<PeerEntity>()

    fun updateList(newPeers: List<PeerEntity>) {
        peers.clear()
        peers.addAll(newPeers)
        notifyDataSetChanged()
    }

    // Helper to update a specific peer's status without reloading everything
    fun updatePeerStatus(name: String, isOnline: Boolean) {
        val index = peers.indexOfFirst { it.name == name }
        if (index != -1) {
            // Create a copy with updated status
            peers[index] = peers[index].copy(isOnline = isOnline)
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        val peer = peers[position]
        holder.bind(peer)
        holder.itemView.setOnClickListener { onPeerClicked(peer) }
    }

    override fun getItemCount(): Int = peers.size

    class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.peerName)
        private val statusText: TextView = itemView.findViewById(R.id.statusText)

        fun bind(peer: PeerEntity) {
            nameText.text = peer.name

            if (peer.isOnline) {
                statusText.text = "● Online"
                statusText.setTextColor(Color.parseColor("#4CAF50")) // Green
                nameText.setTextColor(Color.BLACK)
            } else {
                statusText.text = "Offline"
                statusText.setTextColor(Color.GRAY)
                nameText.setTextColor(Color.GRAY)
            }
        }
    }
}