package com.fury.peerconnect.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.fury.peerconnect.ui.MessageEntity

// version is now 2, and MessageEntity is added to entities array
@Database(entities = [PeerEntity::class, MessageEntity::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao // ADD THIS LINE

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "peer_connect_db"
                )
                    .fallbackToDestructiveMigration() //(Prevents crash on upgrade)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}