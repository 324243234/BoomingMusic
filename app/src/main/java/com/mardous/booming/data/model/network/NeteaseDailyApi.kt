package com.mardous.booming.data.network

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object NeteaseDailyApi {
    // 复用你已有的自建 API 域名
    private const val NETEASE_API_DOMAIN = "https://my-wangyi-api.onrender.com"

    // 1. 获取每日推荐歌曲 (需要你的自建 API 已配置好默认登录态 Cookie)
    suspend fun fetchDailyRecommend(): List<JSONObject> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<JSONObject>()
        try {
            val url = URL("$NETEASE_API_DOMAIN/recommend/songs")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            if (conn.responseCode == 200) {
                val jsonRes = conn.inputStream.bufferedReader().readText()
                val dataObj = JSONObject(jsonRes).optJSONObject("data")
                val dailySongs = dataObj?.optJSONArray("dailySongs")
                if (dailySongs != null) {
                    for (i in 0 until dailySongs.length()) {
                        resultList.add(dailySongs.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return@withContext resultList
    }

    // 2. 同步红心到云端
    suspend fun likeSong(songId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$NETEASE_API_DOMAIN/like?id=$songId")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            if (conn.responseCode == 200) return@withContext true
        } catch (e: Exception) { e.printStackTrace() }
        return@withContext false
    }
}