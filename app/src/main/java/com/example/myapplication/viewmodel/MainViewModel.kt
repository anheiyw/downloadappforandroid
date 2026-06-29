package com.example.myapplication.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.MediaItem
import com.example.myapplication.data.DownloadStatus
import com.example.myapplication.download.DownloadManager
import com.example.myapplication.download.DownloadCancelledException
import com.example.myapplication.download.DownloadPausedException
import com.example.myapplication.network.MediaExtractor
import com.example.myapplication.network.SharedCookieJar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.Manifest

data class PlatformState(
    val inputUrl: String = "",
    val isLoading: Boolean = false,
    val mediaItems: List<MediaItem> = emptyList(),
    val errorMessage: String? = null,
    val downloadProgress: Map<String, Float> = emptyMap()
)

data class UiState(
    val twitterState: PlatformState = PlatformState(),
    val instagramState: PlatformState = PlatformState(),
    val bilibiliState: PlatformState = PlatformState()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedCookieJar = SharedCookieJar()
    private val mediaExtractor = MediaExtractor(sharedCookieJar)
    private val downloadManager = DownloadManager(application, sharedCookieJar)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ===== Twitter =====

    fun updateTwitterUrl(url: String) {
        _uiState.value = _uiState.value.copy(
            twitterState = _uiState.value.twitterState.copy(inputUrl = url, errorMessage = null)
        )
    }

    fun fetchTwitterMedia() {
        val url = _uiState.value.twitterState.inputUrl.trim()
        if (url.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                twitterState = _uiState.value.twitterState.copy(errorMessage = "Please enter a URL")
            )
            return
        }

        if (!url.contains("twitter.com") && !url.contains("x.com")) {
            _uiState.value = _uiState.value.copy(
                twitterState = _uiState.value.twitterState.copy(errorMessage = "Please enter a valid Twitter/X URL")
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                twitterState = _uiState.value.twitterState.copy(
                    isLoading = true, errorMessage = null, mediaItems = emptyList()
                )
            )

            val result = mediaExtractor.extractTwitterMedia(url)
            result.fold(
                onSuccess = { mediaInfo ->
                    _uiState.value = _uiState.value.copy(
                        twitterState = _uiState.value.twitterState.copy(
                            isLoading = false, mediaItems = mediaInfo.items
                        )
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        twitterState = _uiState.value.twitterState.copy(
                            isLoading = false, errorMessage = error.message ?: "Failed to fetch media"
                        )
                    )
                }
            )
        }
    }

    // ===== Instagram =====

    fun updateInstagramUrl(url: String) {
        _uiState.value = _uiState.value.copy(
            instagramState = _uiState.value.instagramState.copy(inputUrl = url, errorMessage = null)
        )
    }

    fun fetchInstagramMedia() {
        val url = _uiState.value.instagramState.inputUrl.trim()
        if (url.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                instagramState = _uiState.value.instagramState.copy(errorMessage = "Please enter a URL")
            )
            return
        }

        if (!url.contains("instagram.com")) {
            _uiState.value = _uiState.value.copy(
                instagramState = _uiState.value.instagramState.copy(errorMessage = "Please enter a valid Instagram URL")
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                instagramState = _uiState.value.instagramState.copy(
                    isLoading = true, errorMessage = null, mediaItems = emptyList()
                )
            )

            val result = mediaExtractor.extractInstagramMedia(url)
            result.fold(
                onSuccess = { mediaInfo ->
                    _uiState.value = _uiState.value.copy(
                        instagramState = _uiState.value.instagramState.copy(
                            isLoading = false, mediaItems = mediaInfo.items
                        )
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        instagramState = _uiState.value.instagramState.copy(
                            isLoading = false, errorMessage = error.message ?: "Failed to fetch media"
                        )
                    )
                }
            )
        }
    }

    // ===== Bilibili =====

    fun updateBilibiliUrl(url: String) {
        _uiState.value = _uiState.value.copy(
            bilibiliState = _uiState.value.bilibiliState.copy(inputUrl = url, errorMessage = null)
        )
    }

    fun fetchBilibiliMedia() {
        val url = _uiState.value.bilibiliState.inputUrl.trim()
        if (url.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                bilibiliState = _uiState.value.bilibiliState.copy(errorMessage = "Please enter a URL")
            )
            return
        }

        if (!url.contains("bilibili.com") && !url.contains("b23.tv") && !url.contains("BV")) {
            _uiState.value = _uiState.value.copy(
                bilibiliState = _uiState.value.bilibiliState.copy(errorMessage = "Please enter a valid Bilibili URL")
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                bilibiliState = _uiState.value.bilibiliState.copy(
                    isLoading = true, errorMessage = null, mediaItems = emptyList()
                )
            )

            val result = mediaExtractor.extractBilibiliMedia(url)
            result.fold(
                onSuccess = { mediaInfo ->
                    _uiState.value = _uiState.value.copy(
                        bilibiliState = _uiState.value.bilibiliState.copy(
                            isLoading = false, mediaItems = mediaInfo.items
                        )
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        bilibiliState = _uiState.value.bilibiliState.copy(
                            isLoading = false, errorMessage = error.message ?: "Failed to fetch media"
                        )
                    )
                }
            )
        }
    }

    // ===== Download =====

    fun downloadMedia(item: MediaItem, platform: String = "instagram") {
        if (!checkStoragePermission()) {
            updateErrorMessage("Storage permission required", platform)
            return
        }


        viewModelScope.launch {
            updateItemStatus(item.id, DownloadStatus.DOWNLOADING, platform)

            val result = downloadManager.downloadFile(
                url = item.url,
                filename = item.filename,
                type = item.type,
                referer = item.referer,
                itemId = item.id,
                extraData = item.extraData
            ) { progress ->
                updateDownloadProgress(item.id, progress, platform)
            }

            val finalResult = if (result.isFailure && platform == "instagram") {
                val errorMsg = result.exceptionOrNull()?.message ?: ""
                if (errorMsg.contains("403") || errorMsg.contains("Forbidden")) {
                    val freshUrl = refreshInstagramUrl(item.id)
                    if (freshUrl != null) {
                        downloadManager.downloadFile(
                            url = freshUrl,
                            filename = item.filename,
                            type = item.type,
                            referer = item.referer,
                            itemId = item.id,
                            extraData = item.extraData
                        ) { progress ->
                            updateDownloadProgress(item.id, progress, platform)
                        }
                    } else {
                        result
                    }
                } else {
                    result
                }
            } else {
                result
            }

            finalResult.fold(
                onSuccess = {
                    updateItemStatus(item.id, DownloadStatus.COMPLETED, platform)
                },
                onFailure = { error ->
                    when (error) {
                        is DownloadCancelledException -> {
                            updateItemStatus(item.id, DownloadStatus.CANCELLED, platform)
                        }
                        is DownloadPausedException -> {
                            updateItemStatus(item.id, DownloadStatus.PAUSED, platform)
                        }
                        else -> {
                            updateItemStatus(item.id, DownloadStatus.FAILED, platform)
                            updateErrorMessage(error.message, platform)
                        }
                    }
                }
            )
        }
    }

    fun cancelDownload(item: MediaItem, platform: String = "instagram") {
        downloadManager.cancelDownload(item.id)
        updateItemStatus(item.id, DownloadStatus.CANCELLED, platform)
    }

    fun pauseDownload(item: MediaItem, platform: String = "instagram") {
        downloadManager.pauseDownload(item.id)
        updateItemStatus(item.id, DownloadStatus.PAUSED, platform)
    }

    fun resumeDownload(item: MediaItem, platform: String = "instagram") {
        downloadManager.clearStatus(item.id)
        downloadMedia(item.copy(status = DownloadStatus.PENDING), platform)
    }

    private suspend fun refreshInstagramUrl(itemId: String): String? {
        val inputUrl = _uiState.value.instagramState.inputUrl.trim()
        if (inputUrl.isEmpty()) return null

        return try {
            val result = mediaExtractor.extractInstagramMedia(inputUrl)
            result.getOrNull()?.items?.find { it.id == itemId }?.url?.also { freshUrl ->
                val updatedItems = _uiState.value.instagramState.mediaItems.map {
                    if (it.id == itemId) it.copy(url = freshUrl) else it
                }
                _uiState.value = _uiState.value.copy(
                    instagramState = _uiState.value.instagramState.copy(mediaItems = updatedItems)
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun downloadAll(platform: String = "instagram") {
        val state = getPlatformState(platform)
        val pendingItems = state.mediaItems.filter { it.status == DownloadStatus.PENDING }
        pendingItems.forEach { downloadMedia(it, platform) }
    }

    fun clearMedia(platform: String = "instagram") {
        updatePlatformState(platform) { PlatformState() }
    }

    // ===== Private helpers =====

    private fun getPlatformState(platform: String): PlatformState {
        return when (platform) {
            "twitter" -> _uiState.value.twitterState
            "bilibili" -> _uiState.value.bilibiliState
            else -> _uiState.value.instagramState
        }
    }

    private fun updatePlatformState(platform: String, transform: (PlatformState) -> PlatformState) {
        val current = _uiState.value
        _uiState.value = when (platform) {
            "twitter" -> current.copy(twitterState = transform(current.twitterState))
            "bilibili" -> current.copy(bilibiliState = transform(current.bilibiliState))
            else -> current.copy(instagramState = transform(current.instagramState))
        }
    }

    private fun updateItemStatus(itemId: String, status: DownloadStatus, platform: String) {
        updatePlatformState(platform) { state ->
            state.copy(mediaItems = state.mediaItems.map {
                if (it.id == itemId) it.copy(status = status) else it
            })
        }
    }

    private fun updateDownloadProgress(itemId: String, progress: Float, platform: String) {
        updatePlatformState(platform) { state ->
            val progressMap = state.downloadProgress.toMutableMap()
            progressMap[itemId] = progress
            state.copy(downloadProgress = progressMap)
        }
    }

    private fun updateErrorMessage(message: String?, platform: String) {
        updatePlatformState(platform) { state ->
            state.copy(errorMessage = message)
        }
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
