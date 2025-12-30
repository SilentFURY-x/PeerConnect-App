package com.fury.peerconnect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.fury.peerconnect.ui.MessageEntity

@Dao
interface MessageDao {
    @Insert
    suspend fun insertMessage(msg: MessageEntity): Long

    // Get chat history between ME and ONE FRIEND
    // This complicated query gets messages where:
    // (Sender is ME AND Receiver is FRIEND) OR (Sender is FRIEND AND Receiver is ME)
    @Query("SELECT * FROM messages WHERE (senderId = :myId AND receiverId = :friendId) OR (senderId = :friendId AND receiverId = :myId) ORDER BY timestamp ASC")
    suspend fun getChatHistory(myId: String, friendId: String): List<MessageEntity>

    // Get all unsent messages for a specific friend (For Resiliency)
    @Query("SELECT * FROM messages WHERE receiverId = :friendId AND isSent = 0")
    suspend fun getUnsentMessages(friendId: String): List<MessageEntity>

    // Mark a specific message as sent
    @Query("UPDATE messages SET isSent = 1 WHERE id = :msgId")
    suspend fun markAsSent(msgId: Int)
}