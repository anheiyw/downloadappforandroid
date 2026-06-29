package com.example.myapplication.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class BilibiliWbiSign {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private const val TAG = "BilibiliWbiSign"
        private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 27, 36, 40,
            12, 31, 14, 50, 12, 28, 10, 41, 3, 39, 31, 6, 16, 40, 54, 4,
            32, 42, 25, 22, 20, 32, 59, 7, 50, 26, 28, 20, 22, 40, 25, 9,
            22, 17, 37, 42, 13, 13, 31, 36, 55, 39, 1, 45, 27, 26, 56, 51
        )
    }

    private var cachedImgKey: String? = null
    private var cachedSubKey: String? = null
    private var cacheTime: Long = 0
    private val CACHE_DURATION = 10 * 60 * 1000L

    private fun getMixinKey(orig: String): String {
        val sb = StringBuilder()
        for (i in MIXIN_KEY_ENC_TAB.indices) {
            if (i < orig.length && MIXIN_KEY_ENC_TAB[i] < orig.length) {
                sb.append(orig[MIXIN_KEY_ENC_TAB[i]])
            }
        }
        return sb.toString()
    }

    suspend fun getWbiKeys(): Pair<String, String> = withContext(Dispatchers.IO) {
        if (cachedImgKey != null && cachedSubKey != null &&
            System.currentTimeMillis() - cacheTime < CACHE_DURATION
        ) {
            return@withContext Pair(cachedImgKey!!, cachedSubKey!!)
        }

        try {
            val request = Request.Builder()
                .url(NAV_URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://www.bilibili.com")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Nav API HTTP ${response.code}")
            }

            val body = response.body?.string() ?: throw Exception("Empty nav response")
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: throw Exception("No data in nav response")
            val wbiImg = data.optJSONObject("wbi_img") ?: throw Exception("No wbi_img in nav response")

            val imgUrl = wbiImg.optString("img_url", "")
            val subUrl = wbiImg.optString("sub_url", "")

            cachedImgKey = imgUrl.substringAfterLast("/").substringBefore(".")
            cachedSubKey = subUrl.substringAfterLast("/").substringBefore(".")
            cacheTime = System.currentTimeMillis()

            Log.d(TAG, "Wbi keys refreshed: img_key=${cachedImgKey?.take(8)}... sub_key=${cachedSubKey?.take(8)}...")
            Pair(cachedImgKey!!, cachedSubKey!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get wbi keys: ${e.message}")
            throw e
        }
    }

    suspend fun signParams(params: Map<String, String>): Map<String, String> {
        val (imgKey, subKey) = getWbiKeys()
        val mixinKey = getMixinKey(imgKey + subKey)

        val mutableParams = params.toMutableMap()
        mutableParams["wts"] = (System.currentTimeMillis() / 1000).toString()

        val sortedParams = mutableParams.toSortedMap()
        val queryStr = sortedParams.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        val md5 = MessageDigest.getInstance("MD5")
        val hash = md5.digest((queryStr + mixinKey).toByteArray())
            .joinToString("") { "%02x".format(it) }

        val result = mutableParams.toMutableMap()
        result["w_rid"] = hash
        return result
    }

    fun buildSignedUrl(baseUrl: String, params: Map<String, String>): String {
        val queryStr = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return if (baseUrl.contains("?")) "$baseUrl&$queryStr" else "$baseUrl?$queryStr"
    }
}
