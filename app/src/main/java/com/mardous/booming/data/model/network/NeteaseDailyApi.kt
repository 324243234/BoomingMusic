package com.mardous.booming.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
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
            conn.responseCode // 触发请求
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
            val url = buildUrlWithCookie("$baseUrl/recommend/songs", cookie)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 45000
            conn.readTimeout = 45000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            
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
}