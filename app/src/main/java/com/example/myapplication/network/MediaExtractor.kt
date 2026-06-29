package com.example.myapplication.network

import android.util.Log
import com.example.myapplication.data.MediaItem
import com.example.myapplication.data.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class MediaInfo(
    val items: List<MediaItem>,
    val referer: String = ""
)

class SharedCookieJar : CookieJar {
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val domain = url.host.removePrefix("www.")
        val stored = cookieStore.getOrPut(domain) { mutableListOf() }
        for (cookie in cookies) {
            val existingIndex = stored.indexOfFirst { it.name == cookie.name }
            if (existingIndex >= 0) {
                stored[existingIndex] = cookie
            } else {
                stored.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val domain = url.host.removePrefix("www.")
        val cookies = mutableListOf<Cookie>()
        for ((storedDomain, storedCookies) in cookieStore) {
            if (domain.endsWith(storedDomain.removePrefix(".")) || storedDomain.endsWith(domain)) {
                cookies.addAll(storedCookies.filter { !it.expiresAt.let { exp -> exp < System.currentTimeMillis() && exp != 0L } })
            }
        }
        return cookies
    }

    fun getAllCookiesForDomain(domain: String): List<Cookie> {
        val cleanDomain = domain.removePrefix("www.")
        val cookies = mutableListOf<Cookie>()
        for ((storedDomain, storedCookies) in cookieStore) {
            if (cleanDomain.endsWith(storedDomain.removePrefix(".")) || storedDomain.endsWith(cleanDomain)) {
                cookies.addAll(storedCookies)
            }
        }
        return cookies
    }

    fun getCookieHeader(domain: String): String {
        return getAllCookiesForDomain(domain).joinToString("; ") { "${it.name}=${it.value}" }
    }
}

class MediaExtractor(val cookieJar: SharedCookieJar = SharedCookieJar()) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(cookieJar)
        .build()

    companion object {
        private const val TAG = "MediaExtractor"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val MOBILE_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"

        fun upgradeInstagramImageUrl(url: String): String {
            return url
                .replace(Regex("/s\\d+x\\d+/"), "/s1080x1080/")
                .replace(Regex("(_s)\\d+x\\d+"), "$11080x1080")
        }
    }

    // ==================== Twitter/X ====================

    suspend fun extractTwitterMedia(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            val tweetId = extractTweetId(url)
                ?: return@withContext Result.failure(Exception("Invalid Twitter URL"))

            try {
                val result = fetchFromVxTwitter(tweetId)
                if (result.isSuccess && result.getOrNull()?.items?.isNotEmpty() == true) {
                    return@withContext result
                }
            } catch (_: Exception) { }

            try {
                val result = fetchFromFxTwitter(tweetId)
                if (result.isSuccess && result.getOrNull()?.items?.isNotEmpty() == true) {
                    return@withContext result
                }
            } catch (_: Exception) { }

            Result.failure(Exception("Failed to fetch tweet."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchFromVxTwitter(tweetId: String): Result<MediaInfo> {
        return try {
            val request = Request.Builder()
                .url("https://api.vxtwitter.com/Twitter/status/$tweetId")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "vxtwitter API error: ${response.code}")
                return Result.failure(Exception("API error ${response.code}"))
            }

            val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))
            Log.d(TAG, "vxtwitter response: ${body.take(500)}")
            val json = JSONObject(body)
            val items = mutableListOf<MediaItem>()

            val mediaExtended = json.optJSONArray("media_extended")
            if (mediaExtended != null) {
                for (i in 0 until mediaExtended.length()) {
                    val media = mediaExtended.getJSONObject(i)
                    val type = media.optString("type", "photo")

                    if (type == "video" || type == "gif") {
                        val videoUrl = media.optString("url")
                        if (videoUrl.isNotEmpty()) {
                            items.add(MediaItem(
                                id = "$tweetId-v$i",
                                url = videoUrl,
                                type = MediaType.VIDEO,
                                filename = "twitter_${tweetId}_$i.mp4",
                                referer = "https://x.com/x/status/$tweetId"
                            ))
                        }
                    } else {
                        // photo or any other non-video type
                        val imageUrl = media.optString("url")
                        if (imageUrl.isNotEmpty()) {
                            items.add(MediaItem(
                                id = "$tweetId-i$i",
                                url = appendOrigParam(imageUrl),
                                type = MediaType.IMAGE,
                                filename = "twitter_${tweetId}_$i.jpg",
                                referer = "https://x.com/x/status/$tweetId"
                            ))
                        }
                    }
                }
            }

            if (items.isEmpty()) {
                val photos = json.optJSONArray("photos")
                if (photos != null) {
                    for (i in 0 until photos.length()) {
                        val photoUrl = photos.getString(i)
                        items.add(MediaItem(
                            id = "$tweetId-p$i",
                            url = appendOrigParam(photoUrl),
                            type = MediaType.IMAGE,
                            filename = "twitter_${tweetId}_$i.jpg",
                            referer = "https://x.com/x/status/$tweetId"
                        ))
                    }
                }
            }

            if (items.isEmpty()) Result.failure(Exception("No media found"))
            else Result.success(MediaInfo(items, "https://x.com/x/status/$tweetId"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchFromFxTwitter(tweetId: String): Result<MediaInfo> {
        return try {
            val request = Request.Builder()
                .url("https://api.fxtwitter.com/statuses/$tweetId")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "fxtwitter API error: ${response.code}")
                return Result.failure(Exception("API error ${response.code}"))
            }

            val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))
            Log.d(TAG, "fxtwitter response: ${body.take(500)}")
            val json = JSONObject(body)
            val tweet = json.optJSONObject("tweet") ?: json
            val items = mutableListOf<MediaItem>()

            // fxtwitter returns media as {"all": [...]} object
            var mediaArray = tweet.optJSONArray("media")
            if (mediaArray == null) {
                val mediaObj = tweet.optJSONObject("media")
                mediaArray = mediaObj?.optJSONArray("all")
            }
            if (mediaArray != null) {
                for (i in 0 until mediaArray.length()) {
                    val media = mediaArray.getJSONObject(i)
                    val type = media.optString("type", "photo")

                    if (type == "video" || type == "gif") {
                        val videoUrl = media.optString("url")
                        if (videoUrl.isNotEmpty()) {
                            items.add(MediaItem(
                                id = "$tweetId-v$i",
                                url = videoUrl,
                                type = MediaType.VIDEO,
                                filename = "twitter_${tweetId}_$i.mp4",
                                referer = "https://x.com/x/status/$tweetId"
                            ))
                        }
                    } else {
                        val imageUrl = media.optString("url")
                        if (imageUrl.isNotEmpty()) {
                            items.add(MediaItem(
                                id = "$tweetId-i$i",
                                url = appendOrigParam(imageUrl),
                                type = MediaType.IMAGE,
                                filename = "twitter_${tweetId}_$i.jpg",
                                referer = "https://x.com/x/status/$tweetId"
                            ))
                        }
                    }
                }
            }

            if (items.isEmpty()) Result.failure(Exception("No media found"))
            else Result.success(MediaInfo(items, "https://x.com/x/status/$tweetId"))
        } catch (e: Exception) {
            Log.d(TAG, "fxtwitter exception: ${e.message}")
            Result.failure(e)
        }
    }

    private fun appendOrigParam(url: String): String {
        return if (url.contains("?")) url.split("?")[0] + "?name=orig" else "$url?name=orig"
    }

    // ==================== Instagram ====================

    suspend fun extractInstagramMedia(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = url.split("?")[0].trimEnd('/')
            val shortcode = extractInstagramShortcode(cleanUrl) ?: System.currentTimeMillis().toString()

            Log.d(TAG, "Parsing Instagram URL: $cleanUrl, shortcode: $shortcode")

            var items = emptyList<MediaItem>()
            var lastError = ""

            // Method 1: Embed page (reliable for images)
            items = tryFetchFromEmbed(cleanUrl, shortcode)
            if (items.isNotEmpty()) {
                Log.d(TAG, "Embed succeeded: ${items.size} items")
            } else {
                lastError = "Embed page failed"
            }

            // Method 2: Instagram mobile page
            if (items.isEmpty()) {
                items = tryFetchFromInstagramMobile(cleanUrl, shortcode)
                if (items.isNotEmpty()) {
                    Log.d(TAG, "Mobile page succeeded: ${items.size} items")
                } else {
                    lastError = "Mobile page failed"
                }
            }

            // Method 3: Instagram page (HTML parse)
            if (items.isEmpty()) {
                items = tryFetchFromJsonApi(cleanUrl, shortcode)
                if (items.isNotEmpty()) {
                    Log.d(TAG, "Page parse succeeded: ${items.size} items")
                } else {
                    lastError = "Page parse failed"
                }
            }

            if (items.isEmpty()) {
                Log.e(TAG, "All methods failed. Last error: $lastError")
                return@withContext Result.failure(
                    Exception("No media found. $lastError. Make sure the post is public and contains images.")
                )
            }

            Log.d(TAG, "Parsing complete: ${items.size} items found")
            Result.success(MediaInfo(items, cleanUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Parsing exception: ${e.message}")
            Result.failure(e)
        }
    }

    // ===== Method 1: Instagram ?__a=1 JSON API =====

    private fun tryFetchFromJsonApi(url: String, shortcode: String): List<MediaItem> {
        val apiUrls = listOf(
            "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis",
            "https://www.instagram.com/reel/$shortcode/?__a=1&__d=dis",
            "https://www.instagram.com/tv/$shortcode/?__a=1&__d=dis",
            "https://www.instagram.com/p/$shortcode/",
            "https://www.instagram.com/reel/$shortcode/"
        )

        val csrfToken = cookieJar.getAllCookiesForDomain("instagram.com")
            .find { it.name == "csrftoken" }?.value ?: ""

        for (apiUrl in apiUrls) {
            try {
                val requestBuilder = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://www.instagram.com/")
                    .header("X-IG-App-ID", "936619743392459")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "same-origin")

                if (csrfToken.isNotEmpty()) {
                    requestBuilder.header("X-CSRFToken", csrfToken)
                    requestBuilder.header("X-Requested-With", "XMLHttpRequest")
                }

                val request = requestBuilder.build()

                val response = client.newCall(request).execute()
                Log.d(TAG, "JSON API [$apiUrl] response: ${response.code}, length: ${response.body?.contentLength()}")

                if (!response.isSuccessful) {
                    response.close()
                    continue
                }

                val body = response.body?.string() ?: continue
                if (body.length < 100) {
                    Log.d(TAG, "JSON API body too short: ${body.take(100)}")
                    continue
                }

                val trimmed = body.trim()

                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    try {
                        val json = JSONObject(body)
                        val itemsFromJson = parseInstagramJsonApiResponse(json, shortcode, url)
                        if (itemsFromJson.isNotEmpty()) return itemsFromJson
                    } catch (e: Exception) {
                        Log.d(TAG, "JSON API parse error: ${e.message}")
                    }
                }

                val htmlItems = mutableListOf<MediaItem>()
                parseInstagramHtml(body, htmlItems, shortcode, url)
                if (htmlItems.isNotEmpty()) {
                    Log.d(TAG, "JSON API: HTML parse succeeded from $apiUrl")
                    return htmlItems
                }

                Log.d(TAG, "JSON API: no media found in response from $apiUrl")
            } catch (e: Exception) {
                Log.d(TAG, "JSON API error for $apiUrl: ${e.message}")
            }
        }

        return emptyList()
    }

    private fun parseInstagramJsonApiResponse(json: JSONObject, shortcode: String, referer: String): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        val graphql = json.optJSONObject("graphql")
        val itemsMedia = json.optJSONObject("items")
        val media = json.optJSONObject("media")

        if (graphql != null) {
            val shortcodeMedia = graphql.optJSONObject("shortcode_media")
            if (shortcodeMedia != null) {
                extractMediaFromPost(shortcodeMedia, items, shortcode, referer)
                if (items.isNotEmpty()) return items
            }
        }

        if (itemsMedia != null) {
            extractMediaFromPost(itemsMedia, items, shortcode, referer)
            if (items.isNotEmpty()) return items
        }

        if (media != null) {
            val itemsArray = media.optJSONArray("items")
            if (itemsArray != null && itemsArray.length() > 0) {
                val firstItem = itemsArray.optJSONObject(0)
                if (firstItem != null) {
                    extractMediaFromPost(firstItem, items, shortcode, referer)
                    if (items.isNotEmpty()) return items
                }
            }
            extractMediaFromPost(media, items, shortcode, referer)
            if (items.isNotEmpty()) return items
        }

        val data = json.optJSONObject("data")
        if (data != null) {
            val shortcodeMedia = data.optJSONObject("shortcode_media")
                ?: data.optJSONObject("media")
                ?: data.optJSONObject("post")
            if (shortcodeMedia != null) {
                extractMediaFromPost(shortcodeMedia, items, shortcode, referer)
                if (items.isNotEmpty()) return items
            }
        }

        val item = json.optJSONObject("item")
        if (item != null) {
            extractMediaFromPost(item, items, shortcode, referer)
            if (items.isNotEmpty()) return items
        }

        val directMedia = json.optJSONObject("shortcode_media")
        if (directMedia != null) {
            extractMediaFromPost(directMedia, items, shortcode, referer)
            if (items.isNotEmpty()) return items
        }

        val videoVersions = json.optJSONArray("video_versions")
        if (videoVersions != null && videoVersions.length() > 0) {
            val bestVideo = videoVersions.optJSONObject(0)
            val videoUrl = bestVideo?.optString("url", "") ?: ""
            if (videoUrl.isNotEmpty()) {
                items.add(MediaItem(
                    id = "$shortcode-video",
                    url = videoUrl,
                    type = MediaType.VIDEO,
                    filename = "instagram_$shortcode.mp4",
                    referer = referer
                ))
                return items
            }
        }

        return items
    }

    private fun parseInstagramHtml(body: String, items: MutableList<MediaItem>, shortcode: String, url: String) {
        // 1. Search for video URLs directly in HTML source (most reliable for videos)
        val mp4Pattern = Regex("""(https?://[^"'\s\\<>]+fbcdn\.net/[^"'\s\\<>]+\.mp4[^"'\s\\<>]*)""")
        for (match in mp4Pattern.findAll(body)) {
            val videoUrl = match.groupValues[1].unescapeInstagramUrl()
            if (videoUrl.startsWith("http") && videoUrl.contains(".mp4")) {
                Log.d(TAG, "Found MP4 URL in HTML: ${videoUrl.take(80)}...")
                items.add(MediaItem(
                    id = "$shortcode-video",
                    url = videoUrl,
                    type = MediaType.VIDEO,
                    filename = "instagram_$shortcode.mp4",
                    referer = url
                ))
                return
            }
        }

        // 2. Search for video_url in any JSON-like structure
        val videoUrlPattern = Regex(""""video_url"\s*:\s*"(https?://[^"]+\.mp4[^"]*)""")
        for (match in videoUrlPattern.findAll(body)) {
            val videoUrl = match.groupValues[1].unescapeInstagramUrl()
            if (videoUrl.startsWith("http") && videoUrl.contains(".mp4")) {
                Log.d(TAG, "Found video_url in HTML: ${videoUrl.take(80)}...")
                items.add(MediaItem(
                    id = "$shortcode-video",
                    url = videoUrl,
                    type = MediaType.VIDEO,
                    filename = "instagram_$shortcode.mp4",
                    referer = url
                ))
                return
            }
        }

        // 3. Try JSON-LD structured data
        parseJsonLd(body, items, shortcode, url)
        if (items.isNotEmpty()) return

        // 4. Try og:meta
        parseOgMeta(body, items, shortcode, url)
        if (items.isNotEmpty()) return

        // 5. Try JS data patterns
        val jsDataPatterns = listOf(
            "require(\"TimeSliceImpl\").guard(function(){bigPipe.onPageletArrive(",
            "window.__additionalDataLoaded",
            "window._sharedData"
        )
        for (marker in jsDataPatterns) {
            val idx = body.indexOf(marker)
            if (idx >= 0) {
                val section = body.substring(idx, minOf(idx + 500000, body.length))
                val jsonStart = section.indexOf("{")
                if (jsonStart >= 0) {
                    try {
                        val jsonStr = extractBalancedJson(section, jsonStart)
                        if (jsonStr.isNotEmpty()) {
                            val json = JSONObject(jsonStr)
                            parseSharedDataJson(json, items, shortcode, url)
                            if (items.isNotEmpty()) return
                        }
                    } catch (_: Exception) { }
                }
            }
        }

        // 6. Last resort: search for any CDN URL with video indicators
        val cdnVideoPatterns = listOf(
            Regex(""""playable_url"\s*:\s*"(https?://[^"]+)"""),
            Regex(""""progressive_url"\s*:\s*"(https?://[^"]+)"""),
            Regex(""""dash_url"\s*:\s*"(https?://[^"]+\.mp4[^"]*)"""),
            Regex(""""contentUrl"\s*:\s*"(https?://[^"]+\.mp4[^"]*)""")
        )
        for (pattern in cdnVideoPatterns) {
            val match = pattern.find(body)
            if (match != null) {
                val videoUrl = match.groupValues[1].unescapeInstagramUrl()
                if (videoUrl.startsWith("http") && videoUrl.contains(".mp4")) {
                    Log.d(TAG, "Found CDN video URL in HTML: ${videoUrl.take(80)}...")
                    items.add(MediaItem(
                        id = "$shortcode-video",
                        url = videoUrl,
                        type = MediaType.VIDEO,
                        filename = "instagram_$shortcode.mp4",
                        referer = url
                    ))
                    return
                }
            }
        }
    }

    private fun extractBalancedJson(text: String, startIdx: Int): String {
        var depth = 0
        var i = startIdx
        var inString = false
        var escape = false

        while (i < text.length) {
            val ch = text[i]
            if (escape) {
                escape = false
                i++
                continue
            }
            if (ch == '\\' && inString) {
                escape = true
                i++
                continue
            }
            if (ch == '"') {
                inString = !inString
                i++
                continue
            }
            if (!inString) {
                when (ch) {
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth <= 0) {
                            return text.substring(startIdx, i + 1)
                        }
                    }
                }
            }
            i++
        }
        return ""
    }

    // ===== Method 1: Instagram mobile page =====

    private fun tryFetchFromInstagramMobile(url: String, shortcode: String): List<MediaItem> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Cache-Control", "no-cache")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "Mobile page response: ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            Log.d(TAG, "Mobile page body length: ${body.length}")

            val items = mutableListOf<MediaItem>()

            parseInstagramHtml(body, items, shortcode, url)
            if (items.isNotEmpty()) return items

            items
        } catch (e: Exception) {
            Log.d(TAG, "Mobile page error: ${e.message}")
            emptyList()
        }
    }

    private fun parseJsonLd(body: String, items: MutableList<MediaItem>, shortcode: String, url: String) {
        val pattern = Regex("""<script[^>]*type="application/ld\+json"[^>]*>([^<]+)</script>""")
        for (match in pattern.findAll(body)) {
            try {
                val jsonStr = match.groupValues[1]
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("&#39;", "'")

                val json = JSONObject(jsonStr)

                // Collect all media first, then filter: if video exists, drop thumbnail images
                val rawItems = mutableListOf<Pair<Boolean, String>>() // (isVideo, url)

                // Check @graph array
                val graph = json.optJSONArray("@graph")
                if (graph != null) {
                    for (i in 0 until graph.length()) {
                        val obj = graph.optJSONObject(i) ?: continue
                        val type = obj.optString("@type", "")
                        if (type == "ImageObject" || type == "VideoObject") {
                            val mediaUrl = obj.optString("contentUrl", "")
                                .ifEmpty { obj.optString("url", "") }
                            if (mediaUrl.isNotEmpty() && mediaUrl.startsWith("http")) {
                                val isVideo = type == "VideoObject" || mediaUrl.contains(".mp4")
                                rawItems.add(isVideo to mediaUrl)
                            }
                        }
                    }
                }

                // Single object
                if (rawItems.isEmpty()) {
                    val type = json.optString("@type", "")
                    if (type == "ImageObject" || type == "VideoObject") {
                        val mediaUrl = json.optString("contentUrl", "").ifEmpty { json.optString("url", "") }
                        if (mediaUrl.isNotEmpty() && mediaUrl.startsWith("http")) {
                            val isVideo = type == "VideoObject" || mediaUrl.contains(".mp4")
                            rawItems.add(isVideo to mediaUrl)
                        }
                    }
                }

                // Filter: if any video exists, skip image entries (they're just thumbnails)
                val hasVideo = rawItems.any { it.first }
                rawItems.forEachIndexed { i, (isVideo, mediaUrl) ->
                    if (hasVideo && !isVideo) return@forEachIndexed
                    items.add(MediaItem(
                        id = "$shortcode-$i",
                        url = if (!isVideo) upgradeInstagramImageUrl(mediaUrl) else mediaUrl,
                        type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                        filename = "instagram_${shortcode}_$i.${if (isVideo) "mp4" else "jpg"}",
                        referer = url
                    ))
                }

                if (items.isNotEmpty()) return
            } catch (_: Exception) { }
        }
    }

    private fun parseOgMeta(body: String, items: MutableList<MediaItem>, shortcode: String, url: String) {
        val ogVideo = Regex("""<meta[^>]*property="og:video(?::url)?"[^>]*content="([^"]+)"""").find(body)
        if (ogVideo != null) {
            val videoUrl = ogVideo.groupValues[1].unescapeHtml()
            if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                items.add(MediaItem(
                    id = "$shortcode-video",
                    url = videoUrl,
                    type = MediaType.VIDEO,
                    filename = "instagram_${shortcode}.mp4",
                    referer = url
                ))
            }
        }

        val ogImage = Regex("""<meta[^>]*property="og:image"[^>]*content="([^"]+)"""").find(body)
        if (ogImage != null) {
            val imageUrl = ogImage.groupValues[1].unescapeHtml()
            if (items.none { it.type == MediaType.VIDEO }) {
                items.add(MediaItem(
                    id = "$shortcode-img",
                    url = upgradeInstagramImageUrl(imageUrl),
                    type = MediaType.IMAGE,
                    filename = "instagram_$shortcode.jpg",
                    referer = url
                ))
            }
        }
    }

    // ===== Embed page =====

    private fun tryFetchFromEmbed(url: String, shortcode: String): List<MediaItem> {
        return try {
            val embedUrl = url.trimEnd('/') + "/embed/"
            Log.d(TAG, "Embed: fetching $embedUrl")
            val request = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "Embed response: ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            Log.d(TAG, "Embed body length: ${body.length}")

            val items = mutableListOf<MediaItem>()

            // 1. Try og:video (works for images, sometimes for videos)
            parseOgMeta(body, items, shortcode, url)
            if (items.isNotEmpty()) {
                Log.d(TAG, "Embed: og:meta found ${items.size} items")
                return items
            }

            // 2. Try video_src attribute in video/source tags
            val videoSrcPatterns = listOf(
                Regex("""<video[^>]*\ssrc="([^"]+\.mp4[^"]*)""""),
                Regex("""<source[^>]*\ssrc="([^"]+\.mp4[^"]*)""""),
                Regex("""video_src["\s:=]+"(https://[^"]+\.mp4[^"]*)"""),
                Regex(""""video_url"\s*:\s*"(https://[^"]+\.mp4[^"]*)"""),
                Regex(""""url"\s*:\s*"(https://[^"]*fbcdn[^"]*\.mp4[^"]*)"""),
                Regex(""""src"\s*:\s*"(https://[^"]*\.mp4[^"]*)""")
            )

            for (pattern in videoSrcPatterns) {
                val match = pattern.find(body)
                if (match != null) {
                    val videoUrl = match.groupValues[1].unescapeInstagramUrl()
                    if (videoUrl.startsWith("http") && videoUrl.contains(".mp4")) {
                        Log.d(TAG, "Embed: found video URL via pattern: ${videoUrl.take(80)}...")
                        items.add(MediaItem(
                            id = "$shortcode-video",
                            url = videoUrl,
                            type = MediaType.VIDEO,
                            filename = "instagram_$shortcode.mp4",
                            referer = url
                        ))
                        return items
                    }
                }
            }

            // 3. Search for any CDN video URL in the page source
            val cdnVideoPattern = Regex("""(https://[^"\s<>]+fbcdn\.net/[^"\s<>]+\.mp4[^"\s<>]*)""")
            val cdnMatch = cdnVideoPattern.find(body)
            if (cdnMatch != null) {
                val videoUrl = cdnMatch.groupValues[1].unescapeInstagramUrl()
                Log.d(TAG, "Embed: found CDN video URL: ${videoUrl.take(80)}...")
                items.add(MediaItem(
                    id = "$shortcode-video",
                    url = videoUrl,
                    type = MediaType.VIDEO,
                    filename = "instagram_$shortcode.mp4",
                    referer = url
                ))
                return items
            }

            // 4. Try parsing JavaScript data (s_video_src, VideoPlayer config)
            val jsVideoPatterns = listOf(
                Regex("""s_video_src["\s:=]+["'](https://[^"']+\.mp4[^"']*)"""),
                Regex(""""contentUrl"\s*:\s*"(https://[^"]+\.mp4[^"]*)"""),
                Regex("""playable_url_quality_hd["\s:=]+["'](https://[^"']+\.mp4[^"']*)"""),
                Regex("""playable_url["\s:=]+["'](https://[^"']+\.mp4[^"']*)"""),
                Regex("""progressive_url["\s:=]+["'](https://[^"']+\.mp4[^"']*)"""),
                Regex("""dash_url["\s:=]+["'](https://[^"']+\.mp4[^"']*)""")
            )

            for (pattern in jsVideoPatterns) {
                val match = pattern.find(body)
                if (match != null) {
                    val videoUrl = match.groupValues[1].unescapeInstagramUrl()
                    if (videoUrl.startsWith("http") && videoUrl.contains(".mp4")) {
                        Log.d(TAG, "Embed: found JS video URL: ${videoUrl.take(80)}...")
                        items.add(MediaItem(
                            id = "$shortcode-video",
                            url = videoUrl,
                            type = MediaType.VIDEO,
                            filename = "instagram_$shortcode.mp4",
                            referer = url
                        ))
                        return items
                    }
                }
            }

            // 5. Try gql_data section (works for images)
            val gqlIdx = body.indexOf("gql_data")
            if (gqlIdx >= 0) {
                val section = body.substring(gqlIdx, minOf(gqlIdx + 250000, body.length))
                val seenUrls = mutableSetOf<String>()

                var pos = 0
                var imgCount = 0
                val imageItems = mutableListOf<MediaItem>()
                while (true) {
                    val displayIdx = section.indexOf("display_url", pos)
                    if (displayIdx < 0) break

                    val httpsIdx = section.indexOf("https:", displayIdx)
                    if (httpsIdx < 0 || httpsIdx > displayIdx + 30) {
                        pos = displayIdx + 1
                        continue
                    }

                    val rawUrl = extractUrlFromSection(section, httpsIdx)
                    val displayUrl = rawUrl.unescapeInstagramUrl()

                    if (displayUrl.startsWith("http") && !seenUrls.contains(displayUrl)) {
                        seenUrls.add(displayUrl)
                        imgCount++
                        imageItems.add(MediaItem(
                            id = "$shortcode-$imgCount",
                            url = displayUrl,
                            type = MediaType.IMAGE,
                            filename = "instagram_${shortcode}_$imgCount.jpg",
                            referer = url
                        ))
                    }

                    pos = httpsIdx + rawUrl.length
                }

                val videoItems = mutableListOf<MediaItem>()
                pos = 0
                while (true) {
                    val videoIdx = section.indexOf("video_url", pos)
                    if (videoIdx < 0) break

                    val httpsIdx = section.indexOf("https:", videoIdx)
                    if (httpsIdx < 0 || httpsIdx > videoIdx + 30) {
                        pos = videoIdx + 1
                        continue
                    }

                    val rawUrl = extractUrlFromSection(section, httpsIdx)
                    val videoUrl = rawUrl.unescapeInstagramUrl()

                    if (videoUrl.startsWith("http") && videoUrl.contains(".mp4")) {
                        videoItems.add(MediaItem(
                            id = "$shortcode-video-${videoItems.size}",
                            url = videoUrl,
                            type = MediaType.VIDEO,
                            filename = "instagram_${shortcode}_video.mp4",
                            referer = url
                        ))
                    }

                    pos = httpsIdx + rawUrl.length
                }

                if (videoItems.isNotEmpty()) {
                    items.addAll(videoItems)
                } else {
                    items.addAll(imageItems)
                }
            }

            // 6. Last resort: try extracting from bigPipe/onPageletArrive data
            if (items.isEmpty()) {
                val bigPipeIdx = body.indexOf("onPageletArrive")
                if (bigPipeIdx >= 0) {
                    val section = body.substring(bigPipeIdx, minOf(bigPipeIdx + 500000, body.length))
                    val jsonStart = section.indexOf("{")
                    if (jsonStart >= 0) {
                        try {
                            val jsonStr = extractBalancedJson(section, jsonStart)
                            if (jsonStr.isNotEmpty()) {
                                val json = JSONObject(jsonStr)
                                val htmlContent = json.optJSONObject("content")?.optString("__html", "") ?: ""
                                if (htmlContent.isNotEmpty()) {
                                    val cdnMatch2 = Regex("""(https://[^"\\<>]+fbcdn\.net/[^"\\<>]+\.mp4[^"\\<>]*)""").find(htmlContent)
                                    if (cdnMatch2 != null) {
                                        val videoUrl = cdnMatch2.groupValues[1].unescapeInstagramUrl()
                                        Log.d(TAG, "Embed: found video in bigPipe: ${videoUrl.take(80)}...")
                                        items.add(MediaItem(
                                            id = "$shortcode-video",
                                            url = videoUrl,
                                            type = MediaType.VIDEO,
                                            filename = "instagram_$shortcode.mp4",
                                            referer = url
                                        ))
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
            }

            if (items.isEmpty()) {
                Log.d(TAG, "Embed: no video or image found in page")
            }

            items
        } catch (e: Exception) {
            Log.d(TAG, "Embed error: ${e.message}")
            emptyList()
        }
    }

    // ===== Shared parsing helpers =====

    private fun parseApiLinksArray(links: JSONArray, shortcode: String, referer: String): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            for (i in 0 until links.length()) {
                val linkObj = links.optJSONObject(i) ?: continue
                val url = linkObj.optString("url", "")
                val type = linkObj.optString("type", "image")
                if (url.isNotEmpty() && url.startsWith("http")) {
                    val isVideo = type == "video" || url.contains(".mp4")
                    items.add(MediaItem(
                        id = "$shortcode-$i",
                        url = if (!isVideo) upgradeInstagramImageUrl(url) else url,
                        type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                        filename = "instagram_${shortcode}_$i.${if (isVideo) "mp4" else "jpg"}",
                        referer = referer
                    ))
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "parseApiLinksArray error: ${e.message}")
        }
        return items
    }

    private fun parseApiJsonResponse(body: String, shortcode: String, referer: String): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            val json = JSONObject(body)

            val arrayFieldNames = listOf("medias", "media", "data", "urls", "items", "resources", "downloads", "results", "list")
            var mediaArray: JSONArray? = null

            for (fieldName in arrayFieldNames) {
                val arr = json.optJSONArray(fieldName)
                if (arr != null && arr.length() > 0) {
                    mediaArray = arr
                    break
                }
            }

            // Check nested objects
            if (mediaArray == null) {
                val nestedObjFields = listOf("data", "result", "response", "body", "content")
                for (objField in nestedObjFields) {
                    val nestedObj = json.optJSONObject(objField) ?: continue
                    for (arrField in arrayFieldNames) {
                        val arr = nestedObj.optJSONArray(arrField)
                        if (arr != null && arr.length() > 0) {
                            mediaArray = arr
                            break
                        }
                    }
                    if (mediaArray != null) break
                }
            }

            if (mediaArray != null) {
                for (i in 0 until mediaArray.length()) {
                    val item = mediaArray.optJSONObject(i)
                    if (item != null) {
                        val mediaUrl = extractUrlFromJsonObject(item)
                        val typeStr = item.optString("type", "image").lowercase()
                        if (mediaUrl.isNotEmpty() && mediaUrl != "null") {
                            val isVideo = typeStr == "video" || typeStr == "reel" || mediaUrl.contains(".mp4")
                            items.add(MediaItem(
                                id = "$shortcode-$i",
                                url = if (!isVideo) upgradeInstagramImageUrl(mediaUrl) else mediaUrl,
                                type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                                filename = "instagram_${shortcode}_$i.${if (isVideo) "mp4" else "jpg"}",
                                referer = referer
                            ))
                        }
                    } else {
                        val strUrl = mediaArray.optString(i, "")
                        if (strUrl.isNotEmpty() && strUrl != "null" && strUrl.startsWith("http")) {
                            val isVideo = strUrl.contains(".mp4")
                            items.add(MediaItem(
                                id = "$shortcode-$i",
                                url = if (!isVideo) upgradeInstagramImageUrl(strUrl) else strUrl,
                                type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                                filename = "instagram_${shortcode}_$i.${if (isVideo) "mp4" else "jpg"}",
                                referer = referer
                            ))
                        }
                    }
                }
            }

            // Single video/image fallback
            if (items.isEmpty()) {
                val videoUrl = extractUrlFromJsonByKeys(json,
                    listOf("download_url", "downloadUrl", "video_url", "videoUrl"))
                if (videoUrl.isNotEmpty() && videoUrl != "null") {
                    items.add(MediaItem(
                        id = "$shortcode-video",
                        url = videoUrl,
                        type = MediaType.VIDEO,
                        filename = "instagram_$shortcode.mp4",
                        referer = referer
                    ))
                }
            }

            if (items.isEmpty()) {
                val imageUrl = extractUrlFromJsonByKeys(json,
                    listOf("download_url", "downloadUrl", "image_url", "imageUrl", "display_url", "thumbnail", "url"))
                if (imageUrl.isNotEmpty() && imageUrl != "null") {
                    items.add(MediaItem(
                        id = "$shortcode-img",
                        url = upgradeInstagramImageUrl(imageUrl),
                        type = MediaType.IMAGE,
                        filename = "instagram_$shortcode.jpg",
                        referer = referer
                    ))
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "API JSON parsing failed: ${e.message}")
        }
        return items
    }

    private fun extractUrlFromJsonObject(obj: JSONObject): String {
        val keys = listOf("download_url", "downloadUrl", "video_url", "videoUrl",
            "url", "src", "link", "image_url", "imageUrl", "thumbnail", "display_url")
        for (key in keys) {
            val value = obj.optString(key, "")
            if (value.isNotEmpty() && value != "null" && value.startsWith("http")) {
                return value
            }
        }
        return ""
    }

    private fun extractUrlFromJsonByKeys(json: JSONObject, keys: List<String>): String {
        for (key in keys) {
            val value = json.optString(key, "")
            if (value.isNotEmpty() && value != "null" && value.startsWith("http")) {
                return value
            }
        }
        return ""
    }

    private fun extractMediaFromPost(post: JSONObject, items: MutableList<MediaItem>, shortcode: String, url: String) {
        try {
            val mediaType = post.optInt("media_type", 0)
            val isVideo = post.optBoolean("is_video", false) || mediaType == 2 || mediaType == 4

            if (isVideo) {
                var videoUrl = post.optString("video_url", "")
                if (videoUrl.isEmpty()) {
                    val videoVersions = post.optJSONArray("video_versions")
                    if (videoVersions != null && videoVersions.length() > 0) {
                        videoUrl = videoVersions.optJSONObject(0)?.optString("url", "") ?: ""
                    }
                }
                if (videoUrl.isNotEmpty()) {
                    items.add(MediaItem(
                        id = "$shortcode-video",
                        url = videoUrl,
                        type = MediaType.VIDEO,
                        filename = "instagram_${shortcode}.mp4",
                        referer = url
                    ))
                    return
                }
            }

            if (!isVideo) {
                var imageUrl = post.optString("display_url", "")
                    .ifEmpty { post.optString("image_url", "") }
                    .ifEmpty { post.optString("thumbnail_url", "") }
                    .ifEmpty { post.optString("thumbnail", "") }
                if (imageUrl.isEmpty()) {
                    val imageVersions = post.optJSONArray("image_versions2")
                    if (imageVersions != null && imageVersions.length() > 0) {
                        val candidates = imageVersions.optJSONObject(0)?.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            imageUrl = candidates.optJSONObject(0)?.optString("url", "") ?: ""
                        }
                    }
                }
                if (imageUrl.isEmpty()) {
                    val resources = post.optJSONArray("resources")
                    if (resources != null && resources.length() > 0) {
                        for (i in 0 until resources.length()) {
                            val resource = resources.optJSONObject(i) ?: continue
                            val resUrl = resource.optString("display_url", "")
                                .ifEmpty { resource.optString("src", "") }
                            if (resUrl.isNotEmpty()) {
                                items.add(MediaItem(
                                    id = "$shortcode-$i",
                                    url = upgradeInstagramImageUrl(resUrl),
                                    type = MediaType.IMAGE,
                                    filename = "instagram_${shortcode}_$i.jpg",
                                    referer = url
                                ))
                            }
                        }
                        return
                    }
                }
                if (imageUrl.isNotEmpty() && items.none { it.url == imageUrl }) {
                    items.add(MediaItem(
                        id = "$shortcode-img",
                        url = upgradeInstagramImageUrl(imageUrl),
                        type = MediaType.IMAGE,
                        filename = "instagram_$shortcode.jpg",
                        referer = url
                    ))
                }
            }

            val edgeSidecar = post.optJSONObject("edge_sidecar_to_children")
            if (edgeSidecar != null) {
                val edges = edgeSidecar.optJSONArray("edges")
                if (edges != null && edges.length() > 0) {
                    items.clear()
                    for (i in 0 until edges.length()) {
                        val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                        val isMediaVideo = node.optBoolean("is_video", false)

                        if (isMediaVideo) {
                            val videoUrl = node.optString("video_url", "")
                            if (videoUrl.isNotEmpty()) {
                                items.add(MediaItem(
                                    id = "$shortcode-carousel-$i",
                                    url = videoUrl,
                                    type = MediaType.VIDEO,
                                    filename = "instagram_${shortcode}_$i.mp4",
                                    referer = url
                                ))
                            }
                        } else {
                            val displayUrl = node.optString("display_url", "")
                                .ifEmpty { node.optString("image_url", "") }
                            if (displayUrl.isNotEmpty()) {
                                items.add(MediaItem(
                                    id = "$shortcode-carousel-$i",
                                    url = upgradeInstagramImageUrl(displayUrl),
                                    type = MediaType.IMAGE,
                                    filename = "instagram_${shortcode}_$i.jpg",
                                    referer = url
                                ))
                            }
                        }
                    }
                }
            }

            val carouselMedia = post.optJSONArray("carousel_media")
            if (carouselMedia != null && carouselMedia.length() > 0 && items.isEmpty()) {
                items.clear()
                for (i in 0 until carouselMedia.length()) {
                    val carouselItem = carouselMedia.optJSONObject(i) ?: continue
                    val carouselMediaType = carouselItem.optInt("media_type", 0)
                    val isCarouselVideo = carouselMediaType == 2 || carouselMediaType == 4 || carouselItem.optBoolean("is_video", false)

                    if (isCarouselVideo) {
                        var videoUrl = carouselItem.optString("video_url", "")
                        if (videoUrl.isEmpty()) {
                            val videoVersions = carouselItem.optJSONArray("video_versions")
                            if (videoVersions != null && videoVersions.length() > 0) {
                                videoUrl = videoVersions.optJSONObject(0)?.optString("url", "") ?: ""
                            }
                        }
                        if (videoUrl.isNotEmpty()) {
                            items.add(MediaItem(
                                id = "$shortcode-carousel-$i",
                                url = videoUrl,
                                type = MediaType.VIDEO,
                                filename = "instagram_${shortcode}_$i.mp4",
                                referer = url
                            ))
                        }
                    } else {
                        var imageUrl = carouselItem.optString("display_url", "")
                            .ifEmpty { carouselItem.optString("image_url", "") }
                        if (imageUrl.isEmpty()) {
                            val imageVersions = carouselItem.optJSONArray("image_versions2")
                            if (imageVersions != null && imageVersions.length() > 0) {
                                val candidates = imageVersions.optJSONObject(0)?.optJSONArray("candidates")
                                if (candidates != null && candidates.length() > 0) {
                                    imageUrl = candidates.optJSONObject(0)?.optString("url", "") ?: ""
                                }
                            }
                        }
                        if (imageUrl.isNotEmpty()) {
                            items.add(MediaItem(
                                id = "$shortcode-carousel-$i",
                                url = upgradeInstagramImageUrl(imageUrl),
                                type = MediaType.IMAGE,
                                filename = "instagram_${shortcode}_$i.jpg",
                                referer = url
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "extractMediaFromPost error: ${e.message}")
        }
    }

    private fun parseSharedDataJson(json: JSONObject, items: MutableList<MediaItem>, shortcode: String, url: String) {
        try {
            val data = json.optJSONObject("data") ?: json
            val graphql = data.optJSONObject("graphql") ?: data
            val shortcodeMedia = graphql.optJSONObject("shortcode_media")
                ?: graphql.optJSONObject("media")
                ?: graphql.optJSONObject("post")

            if (shortcodeMedia != null) {
                extractMediaFromPost(shortcodeMedia, items, shortcode, url)
            }
        } catch (e: Exception) {
            Log.d(TAG, "parseSharedDataJson error: ${e.message}")
        }
    }

    // ===== URL extraction helpers =====

    private fun extractUrlFromSection(section: String, startIdx: Int): String {
        var end = startIdx
        while (end < section.length && end < startIdx + 4000) {
            val ch = section[end]
            if (ch == '\\' && end + 1 < section.length && section[end + 1] == '"') break
            if (ch == '"' && (end + 1 >= section.length || section[end + 1] in listOf(',', '}', ' '))) break
            end++
        }
        return section.substring(startIdx, end)
    }

    private fun extractInstagramShortcode(url: String): String? {
        val pattern = Pattern.compile("""instagram\.com/(?:p|reel|reels|tv|stories)/([^/?]+)""")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractTweetId(url: String): String? {
        val pattern = Pattern.compile("""status/(\d+)""")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    // ===== String extension helpers =====

    private fun String.unescapeHtml(): String {
        return this.unescapeUnicode()
            .replace("\\/", "/")
            .replace("\\\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("\\\"", "\"")
    }

    private fun String.unescapeInstagramUrl(): String {
        return this.unescapeUnicode()
            .replace("\\/", "/")
            .replace("\\\\/", "/")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")
    }

    /** Handle JS-style \uXXXX unicode escapes: \u0025 → %, \u0026 → &, etc. */
    private fun String.unescapeUnicode(): String {
        return replace(Regex("""\\u([0-9a-fA-F]{4})""")) { match ->
            val code = match.groupValues[1].toInt(16)
            code.toChar().toString()
        }
    }

    // ==================== Bilibili ====================

    private val wbiSign = BilibiliWbiSign()

    suspend fun extractBilibiliMedia(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            val resolvedUrl = resolveShortUrl(url)
            val bvid = extractBvid(resolvedUrl)
                ?: return@withContext Result.failure(Exception("Invalid Bilibili URL, cannot extract BV ID"))

            Log.d(TAG, "Bilibili: bvid=$bvid")

            val videoInfo = getBilibiliVideoInfo(bvid)
                ?: return@withContext Result.failure(Exception("Failed to get video info, video may not exist or require login"))

            val aid = videoInfo.optString("aid", "")
            val cid = videoInfo.optString("cid", "")
            val title = videoInfo.optString("title", "bilibili_video")
            val pic = videoInfo.optString("pic", "")

            if (aid.isEmpty() || cid.isEmpty()) {
                return@withContext Result.failure(Exception("Failed to get video aid/cid"))
            }

            Log.d(TAG, "Bilibili: aid=$aid cid=$cid title=$title")

            val dashResult = getBilibiliDashPlayUrl(aid, cid)
            if (dashResult != null) {
                val videoUrl = dashResult.optString("videoUrl", "")
                val audioUrl = dashResult.optString("audioUrl", "")
                val qualityDesc = dashResult.optString("qualityDesc", "")

                if (videoUrl.isNotEmpty()) {
                    val items = mutableListOf<MediaItem>()
                    val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(50)

                    items.add(MediaItem(
                        id = "${bvid}-video",
                        url = videoUrl,
                        type = MediaType.VIDEO,
                        filename = "bilibili_${bvid}.mp4",
                        referer = "https://www.bilibili.com/video/$bvid",
                        extraData = if (audioUrl.isNotEmpty()) mapOf(
                            "audioUrl" to audioUrl,
                            "title" to safeTitle,
                            "quality" to qualityDesc
                        ) else emptyMap()
                    ))

                    if (pic.isNotEmpty()) {
                        items.add(MediaItem(
                            id = "${bvid}-cover",
                            url = pic,
                            type = MediaType.IMAGE,
                            filename = "bilibili_${bvid}_cover.jpg",
                            referer = "https://www.bilibili.com/video/$bvid"
                        ))
                    }

                    Log.d(TAG, "Bilibili: DASH video found, quality=$qualityDesc")
                    return@withContext Result.success(MediaInfo(items, "https://www.bilibili.com/video/$bvid"))
                }
            }

            Result.failure(Exception("Failed to get video download URL. The video may require login or be region-restricted."))
        } catch (e: Exception) {
            Log.e(TAG, "Bilibili extraction error: ${e.message}")
            Result.failure(e)
        }
    }

    private fun resolveShortUrl(url: String): String {
        if (!url.contains("b23.tv")) return url
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com")
                .build()
            client.newBuilder()
                .followRedirects(true)
                .build()
                .newCall(request).execute().use { response ->
                    response.request.url.toString()
                }
        } catch (e: Exception) {
            Log.d(TAG, "Short URL resolution failed: ${e.message}")
            url
        }
    }

    private fun extractBvid(url: String): String? {
        val patterns = listOf(
            Regex("""BV[a-zA-Z0-9]+"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[0]
        }
        return null
    }

    private suspend fun getBilibiliVideoInfo(bvid: String): JSONObject? {
        return try {
            val params = mapOf("bvid" to bvid)
            val signedParams = wbiSign.signParams(params)
            val apiUrl = wbiSign.buildSignedUrl("https://api.bilibili.com/x/web-interface/wbi/view", signedParams)

            Log.d(TAG, "Bilibili: fetching video info from $apiUrl")

            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "Bilibili video info HTTP ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val code = json.optInt("code", -1)
            if (code != 0) {
                Log.d(TAG, "Bilibili video info API error: code=$code message=${json.optString("message", "")}")
                return null
            }

            val data = json.optJSONObject("data") ?: return null
            val pages = data.optJSONArray("pages")
            val firstPage = if (pages != null && pages.length() > 0) pages.optJSONObject(0) else null

            val result = JSONObject()
            result.put("aid", data.optString("aid", ""))
            result.put("cid", firstPage?.optString("cid", "") ?: data.optString("cid", ""))
            result.put("title", data.optString("title", ""))
            result.put("pic", data.optString("pic", ""))
            result
        } catch (e: Exception) {
            Log.e(TAG, "Get video info error: ${e.message}")
            null
        }
    }

    private suspend fun getBilibiliDashPlayUrl(aid: String, cid: String): JSONObject? {
        return try {
            val params = mapOf(
                "avid" to aid,
                "cid" to cid,
                "qn" to "80",
                "fnval" to "16",
                "fourk" to "1",
                "otype" to "json"
            )
            val signedParams = wbiSign.signParams(params)
            val apiUrl = wbiSign.buildSignedUrl("https://api.bilibili.com/x/player/wbi/playurl", signedParams)

            Log.d(TAG, "Bilibili: fetching playurl from $apiUrl")

            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.bilibili.com")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.d(TAG, "Bilibili playurl HTTP ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val code = json.optInt("code", -1)
            if (code != 0) {
                Log.d(TAG, "Bilibili playurl API error: code=$code message=${json.optString("message", "")}")
                return null
            }

            val data = json.optJSONObject("data") ?: return null
            val dash = data.optJSONObject("dash")

            if (dash != null) {
                val videoArray = dash.optJSONArray("video")
                val audioArray = dash.optJSONArray("audio")

                val bestVideo = chooseBestBilibiliStream(videoArray, preferAvc = true)
                val videoUrl = getBilibiliStreamUrl(bestVideo)
                if (videoUrl.isNotEmpty()) {
                    val qualityDesc = bestVideo?.optString("codecid", "")?.let { codecId ->
                        val qualityMap = mapOf("7" to "AVC", "12" to "HEVC", "13" to "AV1")
                        val desc = data.optJSONArray("accept_description")?.optString(0, "") ?: ""
                        desc.ifEmpty { qualityMap[codecId] ?: "Unknown" }
                    } ?: ""

                    val bestAudio = chooseBestBilibiliStream(audioArray, preferAvc = false)
                    val audioUrl = getBilibiliStreamUrl(bestAudio)

                    val result = JSONObject()
                    result.put("videoUrl", videoUrl)
                    result.put("audioUrl", audioUrl)
                    result.put("qualityDesc", qualityDesc)
                    return result
                }
            }

            val durlArray = data.optJSONArray("durl")
            if (durlArray != null && durlArray.length() > 0) {
                val bestDurl = chooseBestBilibiliDurl(durlArray)
                val videoUrl = bestDurl?.let { durl ->
                    durl.optString("url", "")
                        .ifEmpty { durl.optJSONArray("backup_url")?.optString(0, "") ?: "" }
                } ?: ""
                if (videoUrl.isNotEmpty()) {
                    val result = JSONObject()
                    result.put("videoUrl", videoUrl)
                    result.put("audioUrl", "")
                    result.put("qualityDesc", data.optString("quality", "HTTP"))
                    return result
                }
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Get playurl error: ${e.message}")
            null
        }
    }

    private fun chooseBestBilibiliStream(streams: JSONArray?, preferAvc: Boolean): JSONObject? {
        if (streams == null || streams.length() == 0) return null
        var best: JSONObject? = null
        var bestScore = Long.MIN_VALUE
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val codecId = stream.optInt("codecid", 0)
            val codecs = stream.optString("codecs", "")
            val compatibleBonus = if (preferAvc && (codecId == 7 || codecs.contains("avc", ignoreCase = true))) {
                1_000_000_000L
            } else {
                0L
            }
            val score = compatibleBonus + stream.optLong("bandwidth", 0L) + stream.optLong("id", 0L) * 1_000L
            if (score > bestScore) {
                best = stream
                bestScore = score
            }
        }
        return best
    }

    private fun chooseBestBilibiliDurl(durls: JSONArray): JSONObject? {
        var best: JSONObject? = null
        var bestSize = Long.MIN_VALUE
        for (i in 0 until durls.length()) {
            val item = durls.optJSONObject(i) ?: continue
            val size = item.optLong("size", 0L)
            if (best == null || size > bestSize) {
                best = item
                bestSize = size
            }
        }
        return best
    }

    private fun getBilibiliStreamUrl(stream: JSONObject?): String {
        if (stream == null) return ""
        return stream.optString("baseUrl", "")
            .ifEmpty { stream.optString("base_url", "") }
            .ifEmpty { stream.optString("url", "") }
    }
}
