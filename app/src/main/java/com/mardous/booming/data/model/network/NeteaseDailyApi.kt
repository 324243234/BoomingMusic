package com.mardous.booming.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object NeteaseDailyApi {

    private fun buildUrlWithCookie(baseUrl: String, cookie: String): URL {
        return if (cookie.isNotEmpty()) {
            val encodedCookie = URLEncoder.encode(cookie, "UTF-8")
            val separator = if (baseUrl.contains("?")) "&" else "?"
            URL("$baseUrl$separator" + "cookie=$encodedCookie")
        } else {
            URL(baseUrl)
        }
    }

    suspend fun wakeUpAndRefresh(context: Context) = withContext(Dispatchers.IO) {
        val baseUrl = ApiConfigManager.getNeteaseBaseUrl(context)
        val cookie = ApiConfigManager.getCookie(context)
        var conn: HttpURLConnection? = null
        try {
            val url = buildUrlWithCookie("$baseUrl/login/refresh", cookie)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 45000 
            conn.readTimeout = 45000
            conn.requestMethod = "GET"
            conn.responseCode
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun fetchDailyRecommend(context: Context): List<JSONObject> = withContext(Dispatchers.IO) {
    val resultList = mutableListOf<JSONObject>()
    val baseUrl = ApiConfigManager.getNeteaseBaseUrl(context)
    val cookie = ApiConfigManager.getCookie(context)
    var conn: HttpURLConnection? = null
    try {
        // 🌟 强制加入 afresh=true 和 _t 时间戳，击穿 Node 与网易云服务端的跨天缓存
        val timestamp = System.currentTimeMillis()
        val url = buildUrlWithCookie("$baseUrl/recommend/songs?afresh=true&_t=$timestamp", cookie)
        conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 45000
        conn.readTimeout = 45000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.setRequestProperty("Cookie", cookie) // 显式挂载 Cookie 请求头
        
        if (conn.responseCode == 200) {
            val jsonRes = conn.inputStream.bufferedReader().use { it.readText() }
            val dataObj = JSONObject(jsonRes).optJSONObject("data")
            dataObj?.optJSONArray("dailySongs")?.let { dailySongs ->
                for (i in 0 until dailySongs.length()) {
                    resultList.add(dailySongs.getJSONObject(i))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        conn?.disconnect()
    }
    return@withContext resultList
}

    suspend fun likeSong(context: Context, songId: Long): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = ApiConfigManager.getNeteaseBaseUrl(context)
        val cookie = ApiConfigManager.getCookie(context)
        var conn: HttpURLConnection? = null
        try {
            val url = buildUrlWithCookie("$baseUrl/like?id=$songId", cookie)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 45000
            conn.readTimeout = 45000
            if (conn.responseCode == 200) return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            conn?.disconnect()
        }
        return@withContext false
    }

    // 🌟 核心：批量解析直接可播放的 HTTPS 直链（彻底解决 m702 等无证书节点的卡顿）
    suspend fun fetchRealUrls(context: Context, songIds: List<Long>): Map<Long, String> = withContext(Dispatchers.IO) {
        val urlMap = mutableMapOf<Long, String>()
        if (songIds.isEmpty()) return@withContext urlMap

        val baseUrl = ApiConfigManager.getNeteaseBaseUrl(context)
        val cookie = ApiConfigManager.getCookie(context)

        // 通道 1：优先通过新版 /song/url/v1 批量解析官方高音质 CDN 直链
        try {
            val idsStr = songIds.joinToString(",")
            // 🌟 降级为标准音质，确保所有日推免流歌曲100%下发真实播放直链
           val url = buildUrlWithCookie("$baseUrl/song/url/v1?id=$idsStr&level=standard", cookie)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (conn.responseCode == 200) {
                val jsonRes = conn.inputStream.bufferedReader().use { it.readText() }
                val dataArr = JSONObject(jsonRes).optJSONArray("data")
                if (dataArr != null) {
                    for (i in 0 until dataArr.length()) {
                        val obj = dataArr.getJSONObject(i)
                        val id = obj.optLong("id", 0L)
                        val realUrl = obj.optString("url", "")
                        if (id != 0L && realUrl.isNotEmpty() && realUrl != "null") {
                            // 强转 HTTPS，并把无证书的旧节点(m702等)替换为合法的 m7c 节点
                            urlMap[id] = realUrl.replace("http://", "https://")
                                .replace(Regex("m\\d+c?\\.music\\.126\\.net"), "m7c.music.126.net")
                        }
                    }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 通道 2：对未命中直链的歌曲，使用外链 302 重定向兜底
        val missingIds = songIds.filter { !urlMap.containsKey(it) || urlMap[it].isNullOrEmpty() }
        if (missingIds.isNotEmpty()) {
            val deferreds = missingIds.map { id ->
                async {
                    var finalUrl: String? = null
                    var conn: HttpURLConnection? = null
                    try {
                        val outerUrl = URL("https://music.163.com/song/media/outer/url?id=$id.mp3")
                        conn = outerUrl.openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        val location = conn.getHeaderField("Location")
                        if (!location.isNullOrEmpty() && !location.contains("music.163.com/404")) {
                            // 同样强转 HTTPS 并替换为安全 CDN 节点
                            finalUrl = location.replace("http://", "https://")
                                .replace(Regex("m\\d+c?\\.music\\.126\\.net"), "m7c.music.126.net")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        conn?.disconnect()
                    }
                    id to finalUrl
                }
            }
            deferreds.awaitAll().forEach { (id, directUrl) ->
                if (!directUrl.isNullOrEmpty()) {
                    urlMap[id] = directUrl
                }
            }
        }

        return@withContext urlMap
    }
}