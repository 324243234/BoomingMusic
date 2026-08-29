package com.mardous.booming.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object NeteaseDailyApi {
    private const val NETEASE_API_DOMAIN = "https://my-wangyi-api.onrender.com"
    private const val PREF_NAME = "netease_config"
    private const val KEY_COOKIE = "user_cookie"

    // 默认留空，确保源码中无隐私泄露
    private const val DEFAULT_COOKIE = ""

    fun getCookie(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COOKIE, DEFAULT_COOKIE) ?: DEFAULT_COOKIE
    }

    fun saveCookie(context: Context, cookie: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_COOKIE, cookie.trim()).apply()
    }

    // 🌟 核心防冲突：动态组装 URL。如果本地有 Cookie 就带上并覆盖 Render；如果没有就发原生链接让 Render 处理。
    private fun buildUrlWithCookie(baseUrl: String, cookie: String): URL {
        return if (cookie.isNotEmpty()) {
            val encodedCookie = URLEncoder.encode(cookie, "UTF-8")
            val separator = if (baseUrl.contains("?")) "&" else "?"
            URL("$baseUrl$separator" + "cookie=$encodedCookie")
        } else {
            URL(baseUrl)
        }
    }

    // 1. 唤醒并刷新 Cookie (打破 15 天失效)
    suspend fun wakeUpAndRefresh(context: Context) = withContext(Dispatchers.IO) {
        val cookie = getCookie(context)
        try {
            val url = buildUrlWithCookie("$NETEASE_API_DOMAIN/login/refresh", cookie)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 60000 
            conn.readTimeout = 60000
            conn.responseCode 
        } catch (e: Exception) { e.printStackTrace() }
    }

    // 2. 获取每日推荐歌曲
    suspend fun fetchDailyRecommend(context: Context): List<JSONObject> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<JSONObject>()
        val cookie = getCookie(context)
        
        try {
            val url = buildUrlWithCookie("$NETEASE_API_DOMAIN/recommend/songs", cookie)
            val conn = url.openConnection() as HttpURLConnection
            
            // 极致宽容冷启动超时，防止免费容器睡死
            conn.connectTimeout = 60000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            if (conn.responseCode == 200) {
                val jsonRes = conn.inputStream.bufferedReader().readText()
                val dataObj = JSONObject(jsonRes).optJSONObject("data")
                dataObj?.optJSONArray("dailySongs")?.let { dailySongs ->
                    for (i in 0 until dailySongs.length()) {
                        resultList.add(dailySongs.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return@withContext resultList
    }

    // 3. 同步红心到云端
    suspend fun likeSong(context: Context, songId: Long): Boolean = withContext(Dispatchers.IO) {
        val cookie = getCookie(context)
        try {
            val url = buildUrlWithCookie("$NETEASE_API_DOMAIN/like?id=$songId", cookie)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 60000
            conn.readTimeout = 60000
            if (conn.responseCode == 200) return@withContext true
        } catch (e: Exception) { e.printStackTrace() }
        return@withContext false
    }
}