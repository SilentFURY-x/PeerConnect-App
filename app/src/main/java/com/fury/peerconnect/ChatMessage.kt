package com.fury.peerconnect

import java.io.Serializable

data class ChatMessage(
    val senderId: String,   // "FuryUser-1234"
    val message: String,    // "Hello World"
    val timestamp: Long
) : Serializable