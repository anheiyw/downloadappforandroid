package com.example.myapplication.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.DownloadedMedia
import com.example.myapplication.download.DownloadManager
import com.example.myapplication.network.SharedCookieJar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class GalleryState(
    val mediaList: List<DownloadedMedia> = emptyList(),
    val groupedMedia: Map<String, List<DownloadedMedia>> = emptyMap(),
    val isLoading: Boolean = false,
    val selectedMedia: DownloadedMedia? = null,
    val viewMode: ViewMode = ViewMode.GRID,
    val selectedItems: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false
)

enum class ViewMode {
    GRID, LIST
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val downloadManager = DownloadManager(application, SharedCookieJar())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault())

    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()

    init {
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val history = downloadManager.getDownloadHistory()
            val grouped = history.groupBy { media ->
                displayDateFormat.format(Date(media.timestamp))
            }
            _state.value = _state.value.copy(
                mediaList = history,
                groupedMedia = grouped,
                isLoading = false,
                selectedItems = emptySet(),
                isSelectionMode = false
            )
        }
    }

    fun selectMedia(media: DownloadedMedia?) {
        _state.value = _state.value.copy(selectedMedia = media)
    }

    fun toggleSelection(mediaId: Long) {
        val currentSelected = _state.value.selectedItems.toMutableSet()
        if (currentSelected.contains(mediaId)) {
            currentSelected.remove(mediaId)
        } else {
            currentSelected.add(mediaId)
        }
        _state.value = _state.value.copy(
            selectedItems = currentSelected,
            isSelectionMode = currentSelected.isNotEmpty()
        )
    }

    fun selectAll() {
        val allIds = _state.value.mediaList.map { it.id }.toSet()
        _state.value = _state.value.copy(
            selectedItems = allIds,
            isSelectionMode = true
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(
            selectedItems = emptySet(),
            isSelectionMode = false
        )
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val selectedIds = _state.value.selectedItems
            val toDelete = _state.value.mediaList.filter { selectedIds.contains(it.id) }
            toDelete.forEach { media ->
                downloadManager.deleteMedia(media)
            }
            loadHistory()
        }
    }

    fun deleteMedia(media: DownloadedMedia) {
        viewModelScope.launch {
            downloadManager.deleteMedia(media)
            loadHistory()
        }
    }

    fun toggleViewMode() {
        val newMode = if (_state.value.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
        _state.value = _state.value.copy(viewMode = newMode)
    }
}
