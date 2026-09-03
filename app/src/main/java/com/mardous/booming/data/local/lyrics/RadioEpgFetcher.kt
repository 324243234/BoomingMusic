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
            val dateCompact = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

            var validPrograms: List<JSONObject>? = null

            // 🌟 1. CCTV 频道官方直通车：直接调用央视网官方接口，永久免费无额度上限
            if (cleanName.startsWith("CCTV", ignoreCase = true)) {
                val cctvCode = cleanName.lowercase().replace("+", "plus")
                val cntvUrl = "https://api.cntv.cn/epg/getEpgInfoByChannelNew?c=$cctvCode&serviceId=tvcctv&d=$dateCompact"
                val cntvRes = httpGet(cntvUrl)
                if (cntvRes != null) {
                    validPrograms = parseCntvPrograms(cntvRes, cctvCode)
                }
            }

            // 🌟 2. 卫视与地方台：多节点轮询与深度预检验
            if (validPrograms == null) {
                val candidateUrls = listOf(
                    "http://epg.51zmt.top:8000/api/diyp/?ch=${Uri.encode(cleanName)}&date=$todayStr",
                    "http://diyp.112114.xyz/?ch=${Uri.encode(cleanName)}",
                    "https://diyp.288448.xyz/?ch=${Uri.encode(cleanName)}",
                    "http://epg.erw.cc/api/diyp/?ch=${Uri.encode(cleanName)}",
                    "https://epg.v1.mk/api/diyp/?ch=${Uri.encode(cleanName)}",
                    "https://epg.112114.xyz/?ch=${Uri.encode(cleanName)}&date=$todayStr",
                    "https://epg.51zmt.top:8001/api/diyp/?ch=${Uri.encode(cleanName)}"
                )

                for (targetUrl in candidateUrls) {
                    val res = httpGet(targetUrl) ?: continue
                    val programs = parseAndValidatePrograms(res)
                    if (!programs.isNullOrEmpty()) {
                        validPrograms = programs
                        break // 🌟 只有抓取到真实的有效节目时才跳出循环！
                    }
                }
            }

            if (validPrograms.isNullOrEmpty()) {
                return@withContext "📺 正在收听：$cleanName\n\n📡 直播流连接成功 (暂未收录该频道排期或源离线)"
            }

            // 🌟 3. 构建排期界面并根据系统当前时间高亮
            val cal = Calendar.getInstance()
            val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val sb = StringBuilder()
            sb.append("📡 ").append(cleanName).append(" 今日节目单\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━\n\n")

            for (i in validPrograms.indices) {
                val prog = validPrograms[i]
                val title = prog.optString("title", "未知节目")
                val startTime = prog.optString("start", "")
                var endTime = prog.optString("end", "")

                // 兜底补齐缺失的结束时间（取下一节目的开始时间）
                if (endTime.isEmpty() && i + 1 < validPrograms.size) {
                    endTime = validPrograms[i + 1].optString("start", "")
                }

                var prefix = "⏳ "
                if (startTime.length >= 4) {
                    val sMin = timeToMinutes(startTime)
                    val eMin = if (endTime.length >= 4) timeToMinutes(endTime) else sMin + 45

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

                val timeDisplay = if (endTime.isNotEmpty()) "[$startTime - $endTime]" else "[$startTime]"
                sb.append(prefix).append(timeDisplay).append(" ").append(title).append("\n\n")
            }

            return@withContext sb.toString().trimEnd()

        } catch (e: Exception) {
            Log.e(TAG, "获取节目单失败", e)
            return@withContext "📺 当前频道：$cleanName\n📡 直播流连接成功"
        }
    }

    // 🌟 核心过滤：将带有“额度”、“占位符”的虚假节目全部剔除，返回纯净列表
    private fun parseAndValidatePrograms(jsonStr: String): List<JSONObject>? {
        return try {
            val obj = JSONObject(jsonStr)
            val epgData = obj.optJSONArray("epg_data") ?: return null
            if (epgData.length() == 0) return null

            val validList = mutableListOf<JSONObject>()
            for (i in 0 until epgData.length()) {
                val prog = epgData.getJSONObject(i)
                val rawTitle = prog.optString("title", "").ifEmpty { prog.optString("name", "") }.trim()
                val startTime = prog.optString("start", "").ifEmpty { prog.optString("start_time", "") }
                val endTime = prog.optString("end", "").ifEmpty { prog.optString("end_time", "") }

                val cleanTitle = rawTitle
                    .replace("&ensp;", " ")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace(Regex("""(?i)[-_\s]*免费使用.*"""), "")
                    .replace(Regex("""(?i)diyp.*"""), "")
                    .replace("112114", "")
                    .trim()

                // 核心防线：屏蔽任何包含“额度已用完”等变相报错的虚假数据
                if (cleanTitle.isEmpty() ||
                    cleanTitle.contains("额度") ||
                    cleanTitle.contains("赞助") ||
                    cleanTitle.contains("公众号") ||
                    cleanTitle == "精彩节目" ||
                    cleanTitle == "未知节目" ||
                    cleanTitle == "无节目" ||
                    cleanTitle == "节目精彩"
                ) continue

                if (startTime.isEmpty()) continue

                val validProg = JSONObject().apply {
                    put("title", cleanTitle)
                    put("start", startTime)
                    put("end", endTime)
                }
                validList.add(validProg)
            }

            if (validList.isNotEmpty()) validList else null
        } catch (e: Exception) {
            null
        }
    }

    // 🌟 解析 CNTV 央视官方接口返回数据
    private fun parseCntvPrograms(jsonStr: String, cctvCode: String): List<JSONObject>? {
        return try {
            val root = JSONObject(jsonStr)
            val dataObj = root.optJSONObject("data") ?: return null
            val channelObj = dataObj.optJSONObject(cctvCode) ?: return null
            val list = channelObj.optJSONArray("list") ?: return null
            if (list.length() == 0) return null

            val result = mutableListOf<JSONObject>()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val title = item.optString("title", "").trim()
                val start = item.optString("startTime", "")
                val end = item.optString("endTime", "")
                if (title.isNotEmpty() && start.isNotEmpty()) {
                    result.add(JSONObject().apply {
                        put("title", title)
                        put("start", start)
                        put("end", end)
                    })
                }
            }
            if (result.isNotEmpty()) result else null
        } catch (e: Exception) {
            null
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

            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            conn.setRequestProperty("Accept", "application/json")

            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}