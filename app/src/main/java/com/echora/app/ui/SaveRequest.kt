package com.echora.app.ui

data class SaveRequest(
    val minutes: Int,
    val uri: android.net.Uri? = null,
    val error: String? = null
)