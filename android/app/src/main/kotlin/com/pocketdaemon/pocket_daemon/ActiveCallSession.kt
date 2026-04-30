package com.pocketdaemon.pocket_daemon

interface ActiveCallSession {
    fun start()
    fun stop()
    fun awaitTermination()
}
