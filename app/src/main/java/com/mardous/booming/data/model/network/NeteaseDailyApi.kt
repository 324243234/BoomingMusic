package com.mardous.booming.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object NeteaseDailyApi {
    // 默认的 Render 兜底域名
    const val DEFAULT_DOMAIN = "https://my-wangyi-api.onrender.com"
    const val DEFAULT_QQ_DOMAIN = "https://my-qqmusic-api.onrender.com"
    
    private const val PREF_NAME = "netease_config"
    private const val KEY_COOKIE = "user_cookie"
    private const val KEY_DOMAIN = "custom_domain"
    private const val KEY_QQ_DOMAIN = "qq_custom_domain"
    private const val DEFAULT_COOKIE = ""

    // --- 网易 API 域名 ---
    fun getBaseUrl(context: Context): String {
        val custom = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_DOMAIN, "") ?: ""
        return if (custom.isNotBlank()) custom.removeSuffix("/") else DEFAULT_DOMAIN
    }
    fun getCustomDomain(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_DOMAIN, "") ?: ""
    }

    // --- QQ 音乐 API 域名 ---
    fun getQqBaseUrl(context: Context): String {
        val custom = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_QQ_DOMAIN, "") ?: ""
        return if (custom.isNotBlank()) custom.removeSuffix("/") else DEFAULT_QQ_DOMAIN
    }
    fun getQqCustomDomain(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_QQ_DOMAIN, "") ?: ""
    }

    // --- 网易 Cookie ---
    fun getCookie(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_COOKIE, DEFAULT_COOKIE) ?: DEFAULT_COOKIE
    }

    // 🌟 统一保存设置
    fun saveConfig(context: Context, domain: String, cookie: String, qqDomain: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DOMAIN, domain.trim())
            .putString(KEY_COOKIE, cookie.trim())
            .putString(KEY_QQ_DOMAIN, qqDomain.trim())
            .apply()
    }

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
        val cookie = getCookie(context)
        try {
            val url = buildUrlWithCookie("${getBaseUrl(context)}/login/refresh", cookie)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 60000 
            conn.readTimeout = 60000
            conn.responseCode 
        } catch (e: Exception) { e.printStackTrace() }
    }

    suspend fun fetchDailyRecommend(context: Context): List<JSONObject> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<JSONObject>()
        val cookie = getCookie(context)
        try {
            val url = buildUrlWithCookie("${getBaseUrl(context)}/recommend/songs", cookie)
            val conn = url.openConnection() as HttpURLConnection
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

    suspend fun likeSong(context: Context, songId: Long): Boolean = withContext(Dispatchers.IO) {
        val cookie = getCookie(context)
        try {
            val url = buildUrlWithCookie("${getBaseUrl(context)}/like?id=$songId", cookie)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 60000
            conn.readTimeout = 60000
            if (conn.responseCode == 200) return@withContext true
        } catch (e: Exception) { e.printStackTrace() }
        return@withContext false
    }
}