package com.fury.peerconnect

import java.io.Serializable

data class ChatMessage(
    val senderName: String,  // We will use 'senderName' everywhere
    val messageBody: String, // We will use 'messageBody' everywhere
    val time: Long
) : Serializable