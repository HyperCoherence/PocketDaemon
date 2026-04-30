package com.pocketdaemon.pocket_daemon

data class TrustedContactConfig(
    val number: String,
    val name: String,
    val relation: String = "",
    val prompt: String = "",
)
