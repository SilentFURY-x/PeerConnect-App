package com.fury.peerconnect

import java.io.Serializable
data class ChatMessage(
    val senderName: String,
    val messageBody: String,
    val time: Long
) : Serializable