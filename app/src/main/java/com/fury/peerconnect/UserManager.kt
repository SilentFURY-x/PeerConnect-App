package com.fury.peerconnect

import android.content.Context
import android.content.SharedPreferences

class UserManager(context: Context) {
    private val PREFS_NAME = "PeerConnectPrefs"
    private val KEY_USERNAME = "username"
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Save the username
    fun saveUsername(name: String) {
        prefs.edit().putString(KEY_USERNAME, name).apply()
    }

    // Get the username (returns null if not set yet)
    fun getUsername(): String? {
        return prefs.getString(KEY_USERNAME, null)
    }

    // Check if user exists
    fun hasIdentity(): Boolean {
        return !getUsername().isNullOrEmpty()
    }
}