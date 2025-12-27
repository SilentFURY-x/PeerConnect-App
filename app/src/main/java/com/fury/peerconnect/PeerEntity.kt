package com.fury.peerconnect

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val endpointId: String, // Unique ID (e.g., "FuryUser-1234")
    val name: String,
    val lastSeenTimestamp: Long,
    val isOnline: Boolean = false // We will reset this to false when app starts
)