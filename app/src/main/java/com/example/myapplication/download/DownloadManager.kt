package com.example.myapplication.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.myapplication.data.MediaType
import com.example.myapplication.data.DownloadedMedia
import com.example.myapplication.data.DownloadHistoryManager
import com.example.myapplication.network.SharedCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadCancelledException : Exception("Download cancelled")
class DownloadPausedException : Exception("Download paused")

class DownloadManager(private val context: Context, private val cookieJar: SharedCookieJar) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(cookieJar)
        .build()

    companion object {
        private const val TAG = "DownloadManager"
        private const val MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val historyManager = DownloadHistoryManager(context)
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val activeConnections = ConcurrentHashMap<String, HttpURLConnection>()
    private val cancelledIds = ConcurrentHashMap<String, Boolean>()
    private val pausedIds = ConcurrentHashMap<String, Boolean>()

    fun cancelDownload(itemId: String) {
        cancelledIds[itemId] = true
        pausedIds.remove(itemId)
        activeCalls[itemId]?.cancel()
        activeConnections[itemId]?.disconnect()
    }

    fun pauseDownload(itemId: String) {
        pausedIds[itemId] = true
        activeCalls[itemId]?.cancel()
        activeConnections[itemId]?.disconnect()
    }

    fun isCancelled(itemId: String): Boolean = cancelledIds.containsKey(itemId)

    fun isPaused(itemId: String): Boolean = pausedIds.containsKey(itemId)

    fun clearStatus(itemId: String) {
        cancelledIds.remove(itemId)
        pausedIds.remove(itemId)
        activeCalls.remove(itemId)
        activeConnections.remove(itemId)
    }

    private fun checkCancelPause(itemId: String) {
        if (cancelledIds.containsKey(itemId)) throw DownloadCancelledException()
        if (pausedIds.containsKey(itemId)) throw DownloadPausedException()
    }

    suspend fun downloadFile(
        url: String,
        filename: String,
        type: MediaType,
        referer: String = "",
        itemId: String = "",
        extraData: Map<String, String> = emptyMap(),
        onProgress: (Float) -> Unit = {}
    ): Result<DownloadedMedia> = withContext(Dispatchers.IO) {
        try {
            clearStatus(itemId)
            onProgress(0f)
            Log.d(TAG, "======== DOWNLOAD START ========")
            Log.d(TAG, "Filename: $filename, itemId: $itemId")

            val isInstagram = url.contains("cdninstagram") || url.contains("fbcdn") || url.contains("instagram.com")
            val isTwitter = url.contains("twimg.com") || url.contains("video.twimg.com")
            val isBilibili = url.contains("bilivideo.com") || url.contains("akamaized.net") || referer.contains("bilibili.com")

            if (isBilibili) {
                return@withContext downloadBilibiliDash(url, filename, type, referer, itemId, extraData, onProgress)
            }

            if (isInstagram) {
                val result = downloadWithUrlConnection(url, filename, type, referer, itemId, onProgress)
                if (result.isSuccess) return@withContext result
                if (result.isFailure && (result.exceptionOrNull() is DownloadCancelledException || result.exceptionOrNull() is DownloadPausedException)) {
                    return@withContext result
                }
                Log.d(TAG, "HttpURLConnection failed, trying OkHttp strategies...")
            }

            val strategies = buildRequestStrategies(url, referer, isInstagram, isTwitter)

            var lastError: Exception? = null
            for ((index, strategy) in strategies.withIndex()) {
                try {
                    checkCancelPause(itemId)
                    val request = strategy.build()
                    Log.d(TAG, "--- Strategy ${index + 1}/${strategies.size} ---")

                    val call = client.newCall(request)
                    if (itemId.isNotEmpty()) activeCalls[itemId] = call

                    val response = call.execute()
                    Log.d(TAG, "Response code: ${response.code}")

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()?.take(200) ?: "no body"
                        Log.d(TAG, "Error body: $errorBody")
                        lastError = Exception("HTTP ${response.code}")
                        response.close()
                        activeCalls.remove(itemId)
                        continue
                    }

                    val body = response.body ?: run {
                        lastError = Exception("Empty response body")
                        continue
                    }
                    val contentLength = body.contentLength()
                    Log.d(TAG, "Content length: $contentLength bytes")

                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveToMediaStore(filename, type, body.byteStream(), contentLength, itemId, onProgress)
                    } else {
                        saveToLegacyStorage(filename, type, body.byteStream(), contentLength, itemId, onProgress)
                    }

                    onProgress(1f)
                    Log.d(TAG, "======== DOWNLOAD SUCCESS ========")

                    val media = DownloadedMedia(
                        id = System.currentTimeMillis(),
                        filename = filename,
                        uri = uri,
                        type = type,
                        timestamp = System.currentTimeMillis()
                    )

                    historyManager.saveMedia(media)
                    activeCalls.remove(itemId)
                    return@withContext Result.success(media)
                } catch (e: DownloadCancelledException) {
                    activeCalls.remove(itemId)
                    return@withContext Result.failure(e)
                } catch (e: DownloadPausedException) {
                    activeCalls.remove(itemId)
                    return@withContext Result.failure(e)
                } catch (e: Exception) {
                    lastError = e
                    activeCalls.remove(itemId)
                    Log.d(TAG, "Strategy exception: ${e.message}")
                }
            }

            Log.e(TAG, "======== ALL STRATEGIES FAILED ========")
            Result.failure(lastError ?: Exception("Download failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun downloadWithUrlConnection(
        urlStr: String,
        filename: String,
        type: MediaType,
        referer: String,
        itemId: String,
        onProgress: (Float) -> Unit
    ): Result<DownloadedMedia> {
        return try {
            checkCancelPause(itemId)
            val url = URL(urlStr)
            var connection: HttpURLConnection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", DESKTOP_UA)
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.setRequestProperty("Referer", "https://www.instagram.com/")
            connection.setRequestProperty("Origin", "https://www.instagram.com")

            val cdnDomain = urlStr.substringAfter("://").substringBefore("/")
            val cookieHeader = cookieJar.getCookieHeader(cdnDomain)
            if (cookieHeader.isNotEmpty()) {
                connection.setRequestProperty("Cookie", cookieHeader)
            }

            if (itemId.isNotEmpty()) activeConnections[itemId] = connection

            var responseCode = connection.responseCode
            var redirectCount = 0
            while ((responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                        || responseCode == 307 || responseCode == 308) && redirectCount < 10
            ) {
                checkCancelPause(itemId)
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                connection = URL(newUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30000
                connection.readTimeout = 120000
                connection.setRequestProperty("User-Agent", DESKTOP_UA)
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Referer", "https://www.instagram.com/")
                val newCookies = cookieJar.getCookieHeader(newUrl.substringAfter("://").substringBefore("/"))
                if (newCookies.isNotEmpty()) connection.setRequestProperty("Cookie", newCookies)
                if (itemId.isNotEmpty()) activeConnections[itemId] = connection
                responseCode = connection.responseCode
                redirectCount++
            }

            checkCancelPause(itemId)

            if (responseCode != 200) {
                connection.disconnect()
                activeConnections.remove(itemId)
                return Result.failure(Exception("HttpURLConnection HTTP $responseCode"))
            }

            val contentLength = connection.contentLengthLong
            val inputStream = connection.inputStream

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(filename, type, inputStream, contentLength, itemId, onProgress)
            } else {
                saveToLegacyStorage(filename, type, inputStream, contentLength, itemId, onProgress)
            }

            onProgress(1f)
            connection.disconnect()
            activeConnections.remove(itemId)
            Log.d(TAG, "======== DOWNLOAD SUCCESS (HttpURLConnection) ========")

            val media = DownloadedMedia(
                id = System.currentTimeMillis(),
                filename = filename,
                uri = uri,
                type = type,
                timestamp = System.currentTimeMillis()
            )
            historyManager.saveMedia(media)
            Result.success(media)
        } catch (e: DownloadCancelledException) {
            activeConnections.remove(itemId)
            Result.failure(e)
        } catch (e: DownloadPausedException) {
            activeConnections.remove(itemId)
            Result.failure(e)
        } catch (e: Exception) {
            activeConnections.remove(itemId)
            Log.d(TAG, "HttpURLConnection exception: ${e.message}")
            Result.failure(e)
        }
    }

    private fun buildRequestStrategies(url: String, referer: String, isInstagram: Boolean, isTwitter: Boolean): List<Request.Builder> {
        val strategies = mutableListOf<Request.Builder>()

        if (isInstagram) {
            val igReferer = if (referer.isNotEmpty() && referer.contains("instagram")) {
                referer.trimEnd('/') + "/"
            } else {
                "https://www.instagram.com/"
            }
            val cdnDomain = url.substringAfter("://").substringBefore("/")
            val cookieHeader = cookieJar.getCookieHeader(cdnDomain)
            val preservedUrl = buildPreservedHttpUrl(url)

            strategies.add(buildIgRequest(preservedUrl, url, MOBILE_UA, igReferer, cookieHeader, withSecFetch = false))
            strategies.add(buildIgRequest(preservedUrl, url, DESKTOP_UA, igReferer, cookieHeader, withSecFetch = true))
            strategies.add(buildIgRequest(preservedUrl, url, DESKTOP_UA, null, cookieHeader, withSecFetch = false))
            strategies.add(buildIgRequest(preservedUrl, url, MOBILE_UA, null, cookieHeader, withSecFetch = false))
        } else if (isTwitter) {
            val tweetReferer = if (referer.isNotEmpty() && (referer.contains("x.com") || referer.contains("twitter.com"))) {
                referer
            } else {
                "https://x.com/"
            }
            strategies.add(Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "*/*")
                .header("Referer", tweetReferer)
                .header("Origin", "https://x.com")
            )
        } else if (referer.contains("bilibili.com") || url.contains("bilivideo.com") || url.contains("akamaized.net")) {
            val bilibiliReferer = if (referer.contains("bilibili.com")) referer else "https://www.bilibili.com"
            strategies.add(Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "*/*")
                .header("Referer", bilibiliReferer)
                .header("Origin", "https://www.bilibili.com")
            )
        } else {
            strategies.add(Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "*/*")
            )
        }

        return strategies
    }

    private fun buildIgRequest(
        preservedUrl: HttpUrl?,
        fallbackUrl: String,
        userAgent: String,
        referer: String?,
        cookieHeader: String,
        withSecFetch: Boolean
    ): Request.Builder {
        val builder = if (preservedUrl != null) {
            Request.Builder().url(preservedUrl)
        } else {
            Request.Builder().url(fallbackUrl)
        }
        builder.header("User-Agent", userAgent)
            .header("Accept", "*/*")
        if (referer != null) {
            builder.header("Referer", referer)
            builder.header("Origin", "https://www.instagram.com")
        }
        if (withSecFetch) {
            builder.header("Sec-Fetch-Dest", "video")
            builder.header("Sec-Fetch-Mode", "no-cors")
            builder.header("Sec-Fetch-Site", "same-site")
        }
        if (cookieHeader.isNotEmpty()) {
            builder.header("Cookie", cookieHeader)
        }
        return builder
    }

    private fun buildPreservedHttpUrl(url: String): HttpUrl? {
        return try {
            val questionMarkIdx = url.indexOf("?")
            if (questionMarkIdx >= 0) {
                val basePart = url.substring(0, questionMarkIdx)
                val queryPart = url.substring(questionMarkIdx + 1)
                val baseUrl = basePart.toHttpUrlOrNull() ?: return null
                baseUrl.newBuilder()
                    .encodedQuery(queryPart)
                    .build()
            } else {
                url.toHttpUrlOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToMediaStore(
        filename: String,
        type: MediaType,
        inputStream: java.io.InputStream,
        contentLength: Long,
        itemId: String,
        onProgress: (Float) -> Unit
    ): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, if (type == MediaType.VIDEO) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (type == MediaType.VIDEO) Environment.DIRECTORY_MOVIES + "/MediaDownloader"
                else Environment.DIRECTORY_PICTURES + "/MediaDownloader")
        }

        val contentResolver = context.contentResolver
        val collection = if (type == MediaType.VIDEO) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val uri = contentResolver.insert(collection, contentValues) ?: throw Exception("Failed to create MediaStore entry")

        contentResolver.openOutputStream(uri)?.use { outputStream ->
            val buffer = ByteArray(8192)
            var totalBytesRead = 0L
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                checkCancelPause(itemId)
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (contentLength > 0) {
                    onProgress(totalBytesRead.toFloat() / contentLength)
                }
            }
        }

        return uri
    }

    private fun saveToLegacyStorage(
        filename: String,
        type: MediaType,
        inputStream: java.io.InputStream,
        contentLength: Long,
        itemId: String,
        onProgress: (Float) -> Unit
    ): Uri {
        val folderName = if (type == MediaType.VIDEO) "Movies/MediaDownloader" else "Pictures/MediaDownloader"
        val folder = File(Environment.getExternalStorageDirectory(), folderName)
        if (!folder.exists()) folder.mkdirs()

        val file = File(folder, filename)
        FileOutputStream(file).use { outputStream ->
            val buffer = ByteArray(8192)
            var totalBytesRead = 0L
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                checkCancelPause(itemId)
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (contentLength > 0) {
                    onProgress(totalBytesRead.toFloat() / contentLength)
                }
            }
        }

        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        return Uri.fromFile(file)
    }

    fun openFile(uri: Uri, type: MediaType): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
        val contentUri = if (uri.scheme == "file") {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(uri.path!!))
        } else {
            uri
        }
        intent.setDataAndType(contentUri, if (type == MediaType.VIDEO) "video/mp4" else "image/jpeg")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    suspend fun getDownloadHistory(): List<DownloadedMedia> {
        return historyManager.loadHistory().filter { media -> checkUriExists(media.uri) }
    }

    private fun checkUriExists(uri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun deleteMedia(media: DownloadedMedia) {
        historyManager.deleteMedia(media)
    }

    private suspend fun downloadBilibiliDash(
        url: String,
        filename: String,
        type: MediaType,
        referer: String,
        itemId: String,
        extraData: Map<String, String>,
        onProgress: (Float) -> Unit
    ): Result<DownloadedMedia> = withContext(Dispatchers.IO) {
        try {
            if (type == MediaType.IMAGE) {
                val fileResult = downloadBilibiliFile(url, filename, type, referer, itemId, onProgress)
                if (fileResult.isSuccess) {
                    val file = fileResult.getOrThrow()
                    val uri = saveFileToMediaStore(file, filename, MediaType.IMAGE)
                    if (uri != null) {
                        onProgress(1f)
                        val media = DownloadedMedia(
                            id = System.currentTimeMillis(),
                            filename = filename,
                            uri = uri,
                            type = MediaType.IMAGE,
                            timestamp = System.currentTimeMillis()
                        )
                        historyManager.saveMedia(media)
                        return@withContext Result.success(media)
                    }
                }
                return@withContext Result.failure(fileResult.exceptionOrNull() ?: Exception("Image download failed"))
            }

            val cachePrefix = itemId.ifBlank { filename }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(80)
            val videoFile = File(context.cacheDir, "${cachePrefix}_video.m4s")
            val audioFile = File(context.cacheDir, "${cachePrefix}_audio.m4s")
            val mergedFile = File(context.cacheDir, "${cachePrefix}_merged.mp4")

            try {
                onProgress(0.05f)
                Log.d(TAG, "Bilibili DASH: downloading video stream...")
                val videoResult = downloadBilibiliFile(url, "${cachePrefix}_video.m4s", MediaType.VIDEO, referer, itemId) { p ->
                    onProgress(p * 0.45f)
                }
                if (videoResult.isFailure) {
                    return@withContext Result.failure(videoResult.exceptionOrNull() ?: Exception("Video stream download failed"))
                }

                checkCancelPause(itemId)

                val audioUrl = extraData["audioUrl"]
                if (audioUrl != null && audioUrl.isNotEmpty()) {
                    onProgress(0.5f)
                    Log.d(TAG, "Bilibili DASH: downloading audio stream...")
                    val audioResult = downloadBilibiliFile(audioUrl, "${cachePrefix}_audio.m4s", MediaType.AUDIO, referer, itemId) { p ->
                        onProgress(0.5f + p * 0.35f)
                    }
                    if (audioResult.isFailure) {
                        Log.w(TAG, "Audio stream download failed, saving video-only: ${audioResult.exceptionOrNull()?.message}")
                        val uri = saveFileToMediaStore(videoFile, filename, MediaType.VIDEO)
                        if (uri != null) {
                            onProgress(1f)
                            val media = DownloadedMedia(
                                id = System.currentTimeMillis(),
                                filename = filename,
                                uri = uri,
                                type = MediaType.VIDEO,
                                timestamp = System.currentTimeMillis()
                            )
                            historyManager.saveMedia(media)
                            return@withContext Result.success(media)
                        }
                        return@withContext Result.failure(Exception("Audio download failed and video-only save also failed"))
                    }
                }

                checkCancelPause(itemId)
                onProgress(0.85f)

                if (audioFile.exists() && videoFile.exists()) {
                    Log.d(TAG, "Bilibili DASH: merging audio+video...")
                    val mergeResult = MediaMerger.mergeAudioVideo(videoFile, audioFile, mergedFile)
                    if (mergeResult.isSuccess) {
                        val uri = saveFileToMediaStore(mergedFile, filename, MediaType.VIDEO)
                        if (uri != null) {
                            onProgress(1f)
                            val media = DownloadedMedia(
                                id = System.currentTimeMillis(),
                                filename = filename,
                                uri = uri,
                                type = MediaType.VIDEO,
                                timestamp = System.currentTimeMillis()
                            )
                            historyManager.saveMedia(media)
                            return@withContext Result.success(media)
                        }
                    } else {
                        Log.w(TAG, "Merge failed: ${mergeResult.exceptionOrNull()?.message}, trying video-only save")
                        val uri = saveFileToMediaStore(videoFile, filename, MediaType.VIDEO)
                        if (uri != null) {
                            onProgress(1f)
                            val media = DownloadedMedia(
                                id = System.currentTimeMillis(),
                                filename = filename,
                                uri = uri,
                                type = MediaType.VIDEO,
                                timestamp = System.currentTimeMillis()
                            )
                            historyManager.saveMedia(media)
                            return@withContext Result.success(media)
                        }
                    }
                } else if (videoFile.exists()) {
                    val uri = saveFileToMediaStore(videoFile, filename, MediaType.VIDEO)
                    if (uri != null) {
                        onProgress(1f)
                        val media = DownloadedMedia(
                            id = System.currentTimeMillis(),
                            filename = filename,
                            uri = uri,
                            type = MediaType.VIDEO,
                            timestamp = System.currentTimeMillis()
                        )
                        historyManager.saveMedia(media)
                        return@withContext Result.success(media)
                    }
                }

                Result.failure(Exception("Bilibili DASH download: all steps failed"))
            } finally {
                videoFile.delete()
                audioFile.delete()
                mergedFile.delete()
            }
        } catch (e: DownloadCancelledException) {
            Result.failure(e)
        } catch (e: DownloadPausedException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(Exception("Bilibili download failed: ${e.message}"))
        }
    }


    private suspend fun downloadBilibiliFile(
        url: String,
        filename: String,
        type: MediaType,
        referer: String,
        itemId: String,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            checkCancelPause(itemId)
            val bilibiliReferer = if (referer.contains("bilibili.com")) referer else "https://www.bilibili.com"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "*/*")
                .header("Referer", bilibiliReferer)
                .header("Origin", "https://www.bilibili.com")
                .build()

            val call = client.newCall(request)
            if (itemId.isNotEmpty()) activeCalls[itemId] = call

            val response = call.execute()
            if (!response.isSuccessful) {
                response.close()
                activeCalls.remove(itemId)
                return@withContext Result.failure(Exception("Bilibili HTTP ${response.code}"))
            }

            val body = response.body ?: run {
                activeCalls.remove(itemId)
                return@withContext Result.failure(Exception("Empty response"))
            }

            val contentLength = body.contentLength()
            val file = File(context.cacheDir, filename)

            file.outputStream().use { outputStream ->
                val buffer = ByteArray(8192)
                var totalBytesRead = 0L
                var bytesRead: Int

                body.byteStream().use { inputStream ->
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        checkCancelPause(itemId)
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            onProgress(totalBytesRead.toFloat() / contentLength)
                        }
                    }
                }
            }

            activeCalls.remove(itemId)
            Result.success(file)
        } catch (e: DownloadCancelledException) {
            activeCalls.remove(itemId)
            Result.failure(e)
        } catch (e: DownloadPausedException) {
            activeCalls.remove(itemId)
            Result.failure(e)
        } catch (e: Exception) {
            activeCalls.remove(itemId)
            Result.failure(e)
        }
    }

    private fun saveFileToMediaStore(sourceFile: File, filename: String, type: MediaType): Uri? {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, if (type == MediaType.VIDEO) "video/mp4" else "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (type == MediaType.VIDEO) Environment.DIRECTORY_MOVIES + "/MediaDownloader"
                    else Environment.DIRECTORY_PICTURES + "/MediaDownloader")
            }

            val contentResolver = context.contentResolver
            val collection = if (type == MediaType.VIDEO) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val uri = contentResolver.insert(collection, contentValues) ?: return null

            contentResolver.openOutputStream(uri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }

            uri
        } catch (e: Exception) {
            Log.e(TAG, "saveFileToMediaStore failed: ${e.message}")
            null
        }
    }
}
