package com.fury.peerconnect

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PeerDao {
    // Save a peer. If they exist, update them (REPLACE).
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: PeerEntity)

    // Get all peers
    @Query("SELECT * FROM peers ORDER BY isOnline DESC, lastSeenTimestamp DESC")
    suspend fun getAllPeers(): List<PeerEntity>

    // Reset everyone to Offline (Call this when app starts)
    @Query("UPDATE peers SET isOnline = 0")
    suspend fun setAllOffline()

    // Check if a peer exists
    @Query("SELECT EXISTS(SELECT 1 FROM peers WHERE endpointId = :id)")
    suspend fun isKnownPeer(id: String): Boolean
}