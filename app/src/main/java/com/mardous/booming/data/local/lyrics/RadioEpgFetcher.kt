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
        // 🌟 1. 清洗电台名称，去除 M3U 源里常带的垃圾后缀，确保命中公益接口数据库
        val cleanName = stationName
            .replace(Regex("""(?i)\[Radio\]"""), "")
            .replace(Regex("""(?i)[-\s_]*(fm|am)\s*\d+\.?\d*"""), "")
            .replace(Regex("""\(.*?\)|\[.*?\]|【.*?】|<.*?>"""), "")
            .replace(Regex("""(?i)(高清|测试|网络|直播).*$"""), "")
            .replace(Regex("""\s+"""), "")
            .trim()

        if (cleanName.isEmpty()) return@withContext "📻 当前电台：$stationName\n📡 纯享直播流"

        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val todayStr = dateFormat.format(Date())

            // 🌟 2. 社区公益 EPG 接口源 (TVBox / DIYP 影音圈公认最稳节点)
            val urlA = "https://epg.112114.xyz/?ch=${Uri.encode(cleanName)}&date=$todayStr"
            val urlB = "http://epg.51zmt.top:8000/api/diyp/?ch=${Uri.encode(cleanName)}&date=$todayStr"

            // 优先请求主节点，失败则无缝切换备用节点
            var jsonRes = httpGet(urlA)
            if (jsonRes == null || !isValidEpg(jsonRes)) {
                jsonRes = httpGet(urlB)
            }

            if (jsonRes == null) {
                return@withContext "📻 正在收听：$cleanName\n\n📡 直播流连接成功 (公益 EPG 节点暂时拥堵)"
            }

            val rootObj = JSONObject(jsonRes)
            val epgData = rootObj.optJSONArray("epg_data")

            // 接口成功返回了，但该电台太冷门未被收录
            if (epgData == null || epgData.length() == 0) {
                return@withContext "📻 正在收听：$cleanName\n\n📡 直播流连接成功 (EPG库暂未收录该电台排期)"
            }

            // 🌟 3. 格式化排期表，并基于手机系统时间进行直播高亮
            val cal = Calendar.getInstance()
            val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val sb = StringBuilder()
            val realChannelName = rootObj.optString("channel_name", cleanName)
            sb.append("📡 ").append(realChannelName).append(" 今日节目单\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━\n\n")

            for (i in 0 until epgData.length()) {
                val prog = epgData.getJSONObject(i)
                val title = prog.optString("title", "未知节目")
                val startTime = prog.optString("start", "")
                val endTime = prog.optString("end", "")
                
                var prefix = "⏳ "
                if (startTime.length >= 4 && endTime.length >= 4) {
                    val sMin = timeToMinutes(startTime)
                    val eMin = timeToMinutes(endTime)
                    
                    if (sMin != -1 && eMin != -1) {
                        var adjustedEMin = eMin
                        var adjustedCur = currentMinutes
                        
                        // 修复逻辑死角：处理跨天午夜节目 (如 23:30 - 00:30)
                        if (eMin < sMin) {
                            adjustedEMin += 24 * 60
                            if (currentMinutes <= eMin) {
                                adjustedCur += 24 * 60
                            }
                        }

                        if (adjustedCur in sMin until adjustedEMin) {
                            prefix = "🔴 [正在直播] "
                        } else if (adjustedCur >= adjustedEMin) {
                            prefix = "✅ " 
                        }
                    }
                }
                
                sb.append(prefix).append("[").append(startTime).append(" - ").append(endTime).append("] ")
                  .append(title).append("\n\n")
            }
            
            return@withContext sb.toString().trimEnd()

        } catch (e: Exception) {
            Log.e(TAG, "获取节目单失败", e)
            return@withContext "📻 当前正在收听：$cleanName\n📡 直播流连接成功"
        }
    }

    private fun isValidEpg(jsonStr: String): Boolean {
        return try {
            val obj = JSONObject(jsonStr)
            obj.has("epg_data") && obj.optJSONArray("epg_data")?.length() ?: 0 > 0
        } catch (e: Exception) {
            false
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
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            // 伪装标准浏览器请求头
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}