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

    // 🌟 终极防封杀方案：内置全国核心重点电台的固定频道 ID（绕过易被拦截的搜索接口）
    private val KNOWN_STATIONS = mapOf(
        "中国之声" to 386, "经济之声" to 388, "音乐之声" to 387, "经典音乐广播" to 389, 
        "中华之声" to 390, "神州之声" to 391, "华夏之声" to 392, "环球资讯广播" to 1005,
        "大湾区之声" to 4898, "台海之声" to 5424, "中国交通广播" to 4945, "中国乡村之声" to 4930,

        "北京交通广播" to 1006, "北京音乐广播" to 1004, "北京新闻广播" to 1007,
        "上海交通广播" to 1051, "动感101" to 1045, "LoveRadio" to 1044, "上海新闻广播" to 1043,
        "广东羊城交通台" to 1198, "广东音乐之声" to 1196, "珠江经济台" to 1197, "广州交通电台" to 1206,
        "深圳交通广播" to 1256, "深圳音乐广播" to 1255, "深圳新闻频率" to 1254,
        
        "浙江交通之声" to 4892, "浙江音乐调频" to 1133, "杭州交通经济广播" to 1144,
        "江苏交通广播" to 1099, "江苏音乐台" to 1100, "南京交通广播" to 1113,
        "四川交通广播" to 1342, "四川音乐广播" to 1344, "成都交通广播" to 1358,
        "湖南交通广播" to 1213, "楚天交通广播" to 1184, "湖北交通广播" to 1184,
        "福建交通广播" to 1238, "山东交通广播" to 1083, "河南交通广播" to 1157,
        "安徽交通广播" to 1121, "河北交通广播" to 1072, "辽宁交通广播" to 1018,
        "陕西交通广播" to 1374, "重庆交通广播" to 1332, "天津交通广播" to 1012,
        "黑龙江交通广播" to 1029,"吉林交通广播" to 1023,"江西交通广播" to 1226
    )

    suspend fun fetchEpgForRadio(stationName: String): String = withContext(Dispatchers.IO) {
        // 1. 强力清洗电台名称
        val cleanName = stationName
            .replace(Regex("""(?i)\[Radio\]"""), "")
            .replace(Regex("""(?i)[-\s_]*(fm|am)\s*\d+\.?\d*"""), "")
            .replace(Regex("""\(.*?\)|\[.*?\]|【.*?】|<.*?>"""), "")
            .replace(Regex("""(?i)(高清|测试|网络|直播).*$"""), "")
            .replace(Regex("""\s+"""), "")
            .trim()

        if (cleanName.isEmpty()) return@withContext "📻 当前电台：$stationName\n暂无节目单数据"

        try {
            // 🌟 2. 优先命中本地防封杀白名单 (包含关系匹配，比如 "FM103.9北京交通广播" 也能命中 "北京交通广播")
            var channelId = -1
            for ((key, id) in KNOWN_STATIONS) {
                if (cleanName.contains(key) || key.contains(cleanName)) {
                    channelId = id
                    break
                }
            }

            // 3. 如果本地白名单没命中，再尝试用搜索接口兜底
            if (channelId == -1) {
                val searchUrl = "https://search.qingting.fm/v3/search?k=${Uri.encode(cleanName)}&t=channel"
                val searchRes = httpGet(searchUrl)
                if (searchRes != null) {
                    val dataObj = JSONObject(searchRes).optJSONObject("data")
                    val channels = dataObj?.optJSONArray("data")
                    if (channels != null && channels.length() > 0) {
                        channelId = channels.getJSONObject(0).optInt("id", -1)
                    }
                }
            }

            if (channelId == -1) {
                return@withContext "📻 正在收听：$cleanName\n\n📡 纯享直播流，未匹配到该台的排期数据"
            }

            // 🌟 4. 拉取真正的时间排期数据 (这个接口不限制 sign，绝对稳定)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val todayStr = dateFormat.format(Date())
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

                var prefix = "⏳ "
                if (startTime.length >= 5 && endTime.length >= 5) {
                    val sMin = timeToMinutes(startTime)
                    val eMin = timeToMinutes(endTime)
                    if (currentMinutes in sMin until eMin) {
                        prefix = "🔴 [正在直播] "
                    } else if (currentMinutes >= eMin) {
                        prefix = "✅ " 
                    }
                }
                
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
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            // 伪装微信浏览器头部，降低被拦截概率
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; MicroMessenger/8.0.15) AppleWebKit/537.36")
            conn.setRequestProperty("Referer", "https://m.qingting.fm/")
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