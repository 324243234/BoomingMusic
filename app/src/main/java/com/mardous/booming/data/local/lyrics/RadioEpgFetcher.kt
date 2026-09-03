package com.mardous.booming.data.local.lyrics

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object RadioEpgFetcher {
    private const val TAG = "RadioEpgFetcher"

    val currentIcyMetadata = MutableStateFlow<String>("")

    suspend fun fetchEpgForRadio(stationName: String): String = withContext(Dispatchers.IO) {
        val isRadioStation = Regex("""(?i)(广播|之声|电台|调频|fm|am)""").containsMatchIn(stationName)

        var cleanName = stationName
            .replace(Regex("""(?i)\[Radio\]"""), "")
            .replace(Regex("""(?i)[-\s_]*(fm|am)\s*\d+\.?\d*"""), "")
            .replace(Regex("""\(.*?\)|\[.*?\]|【.*?】|<.*?>"""), "")
            .replace(Regex("""(?i)(高清|测试|网络|直播|伴音|音频|纯音|4k|8k|fhd).*$"""), "")
            .replace(Regex("""\s+"""), "")
            .trim()

        val cctvMatch = Regex("""(?i)^cctv[-\s]*(\d+\+?).*""").find(cleanName)
        if (cctvMatch != null) {
            cleanName = "CCTV${cctvMatch.groupValues[1]}"
        }

        cleanName = cleanName.replace("凤凰卫视中文台", "凤凰中文")
        cleanName = cleanName.replace("凤凰卫视资讯台", "凤凰资讯")
        cleanName = cleanName.replace("凤凰卫视电影台", "凤凰电影")

        if (cleanName.endsWith("卫视台")) {
            cleanName = cleanName.replace("卫视台", "卫视")
        }

        if (cleanName.isEmpty()) return@withContext "📻 当前频道：$stationName\n📡 纯享直播流"

        if (isRadioStation) {
            return@withContext "📻 电台直播：$cleanName\n\n📡 纯享音频流\n✨ (若该电台支持，界面将实时提示正在播放的节目)"
        }

        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val todayStr = dateFormat.format(Date())

            // 🌟 聚合多节点轮询（涵盖 51zmt 标准口、SSL 口以及开源镜像）
            val candidateUrls = listOf(
                "http://epg.51zmt.top:8000/api/diyp/?ch=${Uri.encode(cleanName)}&date=$todayStr",
                "https://epg.51zmt.top:8001/api/diyp/?ch=${Uri.encode(cleanName)}&date=$todayStr",
                "https://epg.v1.mk/api/diyp/?ch=${Uri.encode(cleanName)}&date=$todayStr"
            )

            var jsonRes: String? = null
            for (targetUrl in candidateUrls) {
                val res = httpGet(targetUrl)
                if (res != null && isValidEpg(res)) {
                    jsonRes = res
                    break
                }
            }

            if (jsonRes == null) {
                return@withContext "📺 正在收听：$cleanName\n\n📡 直播流连接成功 (暂未收录该频道排期或节点离线)"
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
                
                val title = rawTitle
                    .replace("&ensp;", " ")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace(Regex("""(?i)[-_\s]*免费使用.*"""), "")
                    .replace(Regex("""(?i)diyp.*"""), "")
                    .replace("112114", "")
                    .trim()
                
                if (title.isEmpty() || title == "精彩节目" || title == "未知节目" || title == "无节目" || title.contains("额度")) continue
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
            val data = obj.optJSONArray("epg_data")
            data != null && data.length() > 0
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
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            
            // 针对 HTTPS 请求注入宽松信任，防止 8001 自签名证书导致中断
            if (conn is HttpsURLConnection) {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
                })
                val sc = SSLContext.getInstance("SSL")
                sc.init(null, trustAllCerts, SecureRandom())
                conn.sslSocketFactory = sc.socketFactory
                conn.setHostnameVerifier { _, _ -> true }
            }

            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            conn.setRequestProperty("Accept", "application/json")
            
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "请求接口异常: $urlString, 错误: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}