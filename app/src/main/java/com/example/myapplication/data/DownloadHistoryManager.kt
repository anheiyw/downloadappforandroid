package com.example.myapplication.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DownloadedMedia(
    val id: Long,
    val filename: String,
    val uri: Uri,
    val type: MediaType,
    val timestamp: Long = System.currentTimeMillis(),
    val thumbnailUri: Uri? = null
)

class DownloadHistoryManager(private val context: Context) {
    private val historyFile = File(context.filesDir, "download_history.json")

    suspend fun saveMedia(media: DownloadedMedia) = withContext(Dispatchers.IO) {
        val history = loadHistory().toMutableList()
        history.add(0, media) // Add to beginning
        saveHistory(history)
    }

    suspend fun loadHistory(): List<DownloadedMedia> = withContext(Dispatchers.IO) {
        if (!historyFile.exists()) return@withContext emptyList()

        try {
            val json = historyFile.readText()
            val array = JSONArray(json)
            val list = mutableListOf<DownloadedMedia>()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                list.add(DownloadedMedia(
                    id = item.getLong("id"),
                    filename = item.getString("filename"),
                    uri = Uri.parse(item.getString("uri")),
                    type = MediaType.valueOf(item.getString("type")),
                    timestamp = item.getLong("timestamp"),
                    thumbnailUri = item.optString("thumbnailUri")?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
                ))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun deleteMedia(media: DownloadedMedia) = withContext(Dispatchers.IO) {
        val history = loadHistory().filter { it.id != media.id }.toMutableList()
        saveHistory(history)

        // Try to delete the actual file
        try {
            context.contentResolver.delete(media.uri, null, null)
        } catch (e: Exception) {
            // File might already be deleted
        }
    }

    private fun saveHistory(history: List<DownloadedMedia>) {
        val array = JSONArray()
        history.forEach { media ->
            val json = JSONObject()
            json.put("id", media.id)
            json.put("filename", media.filename)
            json.put("uri", media.uri.toString())
            json.put("type", media.type.name)
            json.put("timestamp", media.timestamp)
            media.thumbnailUri?.let { json.put("thumbnailUri", it.toString()) }
            array.put(json)
        }
        historyFile.writeText(array.toString())
    }
}
