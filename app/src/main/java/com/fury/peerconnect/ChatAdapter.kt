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

    // --- NEW HELPER FUNCTIONS FOR DATABASE ---

    // 1. Clear List (Used before reloading history)
    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    // 2. Set Full List (Used to display loaded history)
    fun setMessages(history: List<ChatMessage>) {
        messages.clear()
        messages.addAll(history)
        notifyDataSetChanged()
    }

    // -----------------------------------------

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    override fun getItemViewType(position: Int): Int {
        val message = messages[position]
        // Using your variable: senderName
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

        // Note: If you added a timestamp TextView to your XML, initialize it here.

        fun bind(msg: ChatMessage) {
            // Using your variables: messageBody and senderName
            textMessage.text = msg.messageBody
            textSender.text = msg.senderName
        }
    }
}