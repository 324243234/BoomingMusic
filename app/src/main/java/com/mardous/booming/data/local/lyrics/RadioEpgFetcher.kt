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
        // 🌟 1. 终极名称清洗引擎：剔除 FM/AM、频率、括号、横杠、分辨率、直播等“脏后缀”
        val cleanName = stationName
            .replace(Regex("""(?i)\[Radio\]"""), "") // 剥离本地注入的前缀
            .replace(Regex("""(?i)[-\s_]*(fm|am)\s*\d+\.?\d*"""), "") // 剥离 FM103.9, AM747 等
            .replace(Regex("""\(.*?\)|\[.*?\]|【.*?】|<.*?>"""), "") // 剥离所有括号及内部内容
            .replace(Regex("""(?i)(高清|测试|网络|直播).*$"""), "") // 剥离转播源特征词汇
            .replace(Regex("""\s+"""), "") // 消除残留空格
            .trim()

        if (cleanName.isEmpty()) return@withContext "📻 当前电台：$stationName\n暂无节目单数据"

        try {
            var channelId = -1
            
            // 🌟 2. 方案 A：使用稳定的 WAPI 聚合接口
            val searchUrlA = "https://i.qingting.fm/wapi/search?kw=${Uri.encode(cleanName)}&pi=1&pz=5"
            val searchResA = httpGet(searchUrlA)
            
            if (searchResA != null) {
                val dataObj = JSONObject(searchResA).optJSONObject("data")
                // WAPI 返回的是 channels 数组
                val channels = dataObj?.optJSONArray("channels")
                if (channels != null && channels.length() > 0) {
                    channelId = channels.getJSONObject(0).optInt("id", -1)
                }
            }

            // 🌟 3. 方案 B：如果 A 失败，无缝切换 V3 搜索接口兜底
            if (channelId == -1) {
                val searchUrlB = "https://search.qingting.fm/v3/search?k=${Uri.encode(cleanName)}&t=channel"
                val searchResB = httpGet(searchUrlB)
                if (searchResB != null) {
                    val dataObj = JSONObject(searchResB).optJSONObject("data")
                    // V3 返回的是 data 数组
                    val channels = dataObj?.optJSONArray("data")
                    if (channels != null && channels.length() > 0) {
                        channelId = channels.getJSONObject(0).optInt("id", -1)
                    }
                }
            }

            // 依然查不到，说明是非主流网络台或彻底改名，执行优雅退出
            if (channelId == -1) {
                return@withContext "📻 正在收听：$cleanName\n\n📡 纯享直播流，未匹配到该台的排期数据"
            }

            // 🌟 4. 根据 Channel ID 获取今日节目单
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

            // 🌟 5. 格式化排版并高亮当前时段
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
                    // 精准比对当天系统时钟，打上“正在直播”标签
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

    // 🌟 6. 严苛的防爬伪装头部
    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Referer", "https://www.qingting.fm/") // 绕过防盗链核心
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