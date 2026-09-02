package com.mardous.booming.data.local.lyrics

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object RadioEpgFetcher {
    private const val TAG = "RadioEpgFetcher"

    suspend fun fetchEpgForRadio(stationName: String): String = withContext(Dispatchers.IO) {
        val cleanName = stationName.replace(Regex("""\[Radio\]|\s+"""), "").trim()
        if (cleanName.isEmpty()) return@withContext "📻 当前电台：未知\n暂无节目单数据"

        try {
            // 1. 通过搜索接口获取电台的 Channel ID
            val searchUrl = "https://search.qingting.fm/v3/search?k=${Uri.encode(cleanName)}&t=channel"
            val searchRes = httpGet(searchUrl)
            
            var channelId = -1
            if (searchRes != null) {
                val dataObj = JSONObject(searchRes).optJSONObject("data")
                val channels = dataObj?.optJSONArray("data")
                if (channels != null && channels.length() > 0) {
                    // 取相似度最高的第一条数据
                    channelId = channels.getJSONObject(0).optInt("id", -1)
                }
            }

            if (channelId == -1) {
                return@withContext "📻 当前正在收听：$cleanName\n\n📡 纯享直播流，暂无详细排期数据"
            }

            // 2. 根据 Channel ID 获取今日的节目单
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val todayStr = dateFormat.format(Date())
            // 当天时间（用于标记当前正在播放的节目）
            val cal = Calendar.getInstance()
            val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val epgUrl = "https://i.qingting.fm/capi/channel/$channelId/programs/$todayStr"
            val epgRes = httpGet(epgUrl) ?: return@withContext "📻 $cleanName\n节目单服务暂时不可用"

            val programsArray = JSONObject(epgRes).optJSONArray("data")
                ?: return@withContext "📻 $cleanName\n今日无节目排期"

            val sb = StringBuilder()
            sb.append("📡 ").append(cleanName).append(" 今日节目单\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━\n\n")

            for (i in 0 until programsArray.length()) {
                val prog = programsArray.getJSONObject(i)
                val title = prog.optString("title", "未知节目")
                val startTime = prog.optString("start_time", "")
                val endTime = prog.optString("end_time", "")
                val broadcasters = prog.optJSONArray("broadcasters")
                
                var djNames = ""
                if (broadcasters != null && broadcasters.length() > 0) {
                    val djs = mutableListOf<String>()
                    for (j in 0 until broadcasters.length()) {
                        djs.add(broadcasters.getJSONObject(j).optString("username"))
                    }
                    if (djs.isNotEmpty()) djNames = " 🎤 " + djs.joinToString("/")
                }

                // 转换时间用于判断是否为正在直播
                var prefix = "⏳ "
                if (startTime.length >= 5 && endTime.length >= 5) {
                    val sMin = timeToMinutes(startTime)
                    val eMin = timeToMinutes(endTime)
                    if (currentMinutes in sMin until eMin) {
                        prefix = "🔴 [正在直播] "
                    } else if (currentMinutes > eMin) {
                        prefix = "✅ " // 已播完
                    }
                }
                
                // 格式化输出: [08:00 - 09:00] 节目名称
                val displayStart = if (startTime.length >= 5) startTime.substring(0, 5) else startTime
                val displayEnd = if (endTime.length >= 5) endTime.substring(0, 5) else endTime
                
                sb.append(prefix).append("[").append(displayStart).append(" - ").append(displayEnd).append("] ")
                  .append(title).append(djNames).append("\n\n")
            }
            
            return@withContext sb.toString().trimEnd()

        } catch (e: Exception) {
            Log.e(TAG, "获取节目单失败", e)
            return@withContext "📻 当前正在收听：$cleanName\n网络异常，节目单拉取失败"
        }
    }

    private fun timeToMinutes(timeStr: String): Int {
        return try {
            val parts = timeStr.split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        } catch (e: Exception) {
            -1
        }
    }

    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}