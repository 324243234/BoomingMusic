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
        val isRadioStation = Regex("""(?i)(广播|之声|电台|调频|fm|am)""").containsMatchIn(stationName)

        var cleanName = stationName
            .replace(Regex("""(?i)\[Radio\]"""), "")
            .replace(Regex("""(?i)[-\s_]*(fm|am)\s*\d+\.?\d*"""), "")
            .replace(Regex("""\(.*?\)|\[.*?\]|【.*?】|<.*?>"""), "")
            .replace(Regex("""(?i)(高清|测试|网络|直播|伴音|音频|纯音|4k|8k|fhd).*$"""), "")
            .replace(Regex("""\s+"""), "")
            .trim()

        // 📺 CCTV 频道极致标准化
        val cctvMatch = Regex("""(?i)^cctv[-\s]*(\d+\+?).*""").find(cleanName)
        if (cctvMatch != null) {
            cleanName = "CCTV-${cctvMatch.groupValues[1]}"
        }

        // 📺 凤凰卫视极致匹配
        cleanName = cleanName.replace("凤凰卫视中文台", "凤凰中文")
        cleanName = cleanName.replace("凤凰卫视资讯台", "凤凰资讯")
        cleanName = cleanName.replace("凤凰卫视电影台", "凤凰电影")

        if (cleanName.endsWith("卫视台")) {
            cleanName = cleanName.replace("卫视台", "卫视")
        }

        if (cleanName.isEmpty()) return@withContext "📻 当前频道：$stationName\n📡 纯享直播流"

        if (isRadioStation) {
            return@withContext "📻 电台直播：$cleanName\n\n📡 纯享音频流\n✨ (若该电台支持，界面将实时提示正在播放的歌曲/节目)"
        }

        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val todayStr = dateFormat.format(Date())

            val urlA = "https://epg.112114.xyz/?ch=${Uri.encode(cleanName)}&date=$todayStr"
            val urlB = "http://epg.51zmt.top:8000/api/diyp/?ch=${Uri.encode(cleanName)}&date=$todayStr"

            var jsonRes = httpGet(urlA)
            if (jsonRes == null || !isValidEpg(jsonRes)) {
                jsonRes = httpGet(urlB)
            }

            if (jsonRes == null) {
                return@withContext "📺 正在收听：$cleanName\n\n📡 直播流连接成功 (公益 EPG 节点暂时拥堵)"
            }

            val rootObj = JSONObject(jsonRes)
            val epgData = rootObj.optJSONArray("epg_data")

            if (epgData == null || epgData.length() == 0) {
                return@withContext "📺 正在收听：$cleanName\n\n📡 直播流连接成功 (暂未收录该频道排期)"
            }

            val validPrograms = mutableListOf<JSONObject>()
            for (i in 0 until epgData.length()) {
                val prog = epgData.getJSONObject(i)
                val rawTitle = prog.optString("title", "").trim()
                val start = prog.optString("start", "")
                
                // 🌟 核心修复 1：洗掉 HTML 转义字符和烦人的免费水印
                val title = rawTitle
                    .replace("&ensp;", " ")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace(Regex("""(?i)[-_\s]*免费使用.*"""), "")
                    .replace(Regex("""(?i)diyp.*"""), "")
                    .replace("112114", "")
                    .trim()
                
                // 🌟 核心修复 2：撤销对 "转播" 的误杀，仅拦截真正的无用占位符
                if (title.isEmpty() || title == "精彩节目" || title == "未知节目" || title == "无节目") continue
                if (start.isEmpty()) continue
                
                prog.put("title_clean", title)
                validPrograms.add(prog)
            }

            if (validPrograms.isEmpty()) {
                return@withContext "📺 正在收听：$cleanName\n\n📡 直播流连接成功 (今日暂无详细排期)"
            }

            val cal = Calendar.getInstance()
            val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val sb = StringBuilder()
            val realChannelName = rootObj.optString("channel_name", cleanName)
            sb.append("📡 ").append(realChannelName).append(" 今日节目单\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━\n\n")

            for (prog in validPrograms) {
                val title = prog.optString("title_clean", "未知节目")
                val startTime = prog.optString("start", "")
                val endTime = prog.optString("end", "")
                
                var prefix = "⏳ "
                if (startTime.length >= 4 && endTime.length >= 4) {
                    val sMin = timeToMinutes(startTime)
                    val eMin = timeToMinutes(endTime)
                    
                    if (sMin != -1 && eMin != -1) {
                        var adjustedEMin = eMin
                        var adjustedCur = currentMinutes
                        
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
            return@withContext "📺 当前频道：$cleanName\n📡 直播流连接成功"
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