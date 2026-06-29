package com.example.myapplication.data

data class MediaItem(
    val id: String,
    val url: String,
    val type: MediaType,
    val filename: String,
    val referer: String = "",
    val status: DownloadStatus = DownloadStatus.PENDING,
    val extraData: Map<String, String> = emptyMap()
)

enum class MediaType {
    IMAGE, VIDEO, AUDIO
}

enum class DownloadStatus {
    PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}
