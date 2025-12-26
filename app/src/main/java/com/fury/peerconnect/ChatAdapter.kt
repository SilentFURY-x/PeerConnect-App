package com.fury.peerconnect

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(
    private val myNickName: String
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = ArrayList<ChatMessage>()
    private val TYPE_ME = 1
    private val TYPE_OTHER = 2

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    override fun getItemViewType(position: Int): Int {
        val message = messages[position]
        // FIX: Used senderName to match ChatMessage.kt
        return if (message.senderName == myNickName) TYPE_ME else TYPE_OTHER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_ME) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message_me, parent, false)
            MessageViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message_other, parent, false)
            MessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as MessageViewHolder).bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textMessage: TextView = itemView.findViewById(R.id.textMessage)
        private val textSender: TextView = itemView.findViewById(R.id.textSender)

        fun bind(msg: ChatMessage) {
            // FIX: Used proper property names
            textMessage.text = msg.messageBody
            textSender.text = msg.senderName
        }
    }
}