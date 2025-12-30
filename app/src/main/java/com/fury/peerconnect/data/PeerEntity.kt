package com.fury.peerconnect.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    // 'name' is now the Unique Key.
    @PrimaryKey val name: String,

    val endpointId: String, // This will update every time they reconnect
    val lastSeenTimestamp: Long,
    val isOnline: Boolean = false
)