package com.example.appmobile.data.remote

object DreamAppSession {
    @Volatile
    var token: String? = null
        private set

    fun start(sessionToken: String) {
        token = sessionToken
    }

    fun clear() {
        token = null
    }
}
