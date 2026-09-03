package com.mardous.booming.data.local.lyrics

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
            var validPrograms: List<JSONObject>? = null

            // 🌟 1. CCTV 频道官方直通车
            if (cleanName.startsWith("CCTV", ignoreCase = true)) {
                val cctvCode = cleanName.lowercase().replace("+", "plus")
                val dateCompact = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                val cntvUrl = "https://api.cntv.cn/epg/getEpgInfoByChannelNew?c=$cctvCode&serviceId=tvcctv&d=$dateCompact"
                val cntvRes = httpGet(cntvUrl)
                if (cntvRes != null) {
                    validPrograms = parseCntvPrograms(cntvRes, cctvCode)
                }
            }

            // 🌟 2. 卫视与地方台：多节点轮询与全兼容解析
            if (validPrograms == null) {
                val candidateUrls = listOf(
                    "http://epg.51zmt.top:8000/api/diyp/?ch=${Uri.encode(cleanName)}",
                    "https://epg.v1.mk/api/diyp/?ch=${Uri.encode(cleanName)}",
                    "http://epg.aptvapp.com/api/diyp/?ch=${Uri.encode(cleanName)}",
                    "http://diyp.112114.xyz/?ch=${Uri.encode(cleanName)}",
                    "http://epg.erw.cc/api/diyp/?ch=${Uri.encode(cleanName)}",
                    "https://diyp.288448.xyz/?ch=${Uri.encode(cleanName)}",
                    "https://epg.pw/api/epg.json?channel_name=${Uri.encode(cleanName)}"
                )

                for (targetUrl in candidateUrls) {
                    val res = httpGet(targetUrl) ?: continue
                    val programs = parseUniversalEpgPrograms(res)
                    if (!programs.isNullOrEmpty()) {
                        validPrograms = programs
                        break
                    }
                }
            }

            if (validPrograms.isNullOrEmpty()) {
                return@withContext "📺 正在收听：$cleanName\n\n📡 直播流连接成功 (暂未收录该频道排期或源离线)"
            }

            // 🌟 3. 构建排期界面并根据系统时间高亮
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

    // 🌟 解析 CNTV 央视官方接口（将秒级时间戳格式化为 HH:mm）
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
                val rawStart = item.optString("startTime", "")
                val rawEnd = item.optString("endTime", "")

                val cleanStart = formatTime(rawStart)
                val cleanEnd = formatTime(rawEnd)

                if (title.isNotEmpty() && cleanStart.isNotEmpty()) {
                    result.add(JSONObject().apply {
                        put("title", title)
                        put("start", cleanStart)
                        put("end", cleanEnd)
                    })
                }
            }
            if (result.isNotEmpty()) result else null
        } catch (e: Exception) {
            null
        }
    }

    // 🌟 全兼容解析器：同时支持 JSON 数组与 JSON 对象，拦截伪装数据
    private fun parseUniversalEpgPrograms(jsonStr: String): List<JSONObject>? {
        return try {
            val trimmed = jsonStr.trim()
            val epgArray: JSONArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("epg_data")
                        ?: root.optJSONArray("programs")
                        ?: root.optJSONArray("data")
                        ?: root.optJSONArray("list")
                        ?: root.optJSONArray("epg")
                        ?: return null
                }
                else -> return null
            }

            if (epgArray.length() == 0) return null

            val validList = mutableListOf<JSONObject>()
            for (i in 0 until epgArray.length()) {
                val prog = epgArray.getJSONObject(i)
                val rawTitle = prog.optString("title", "")
                    .ifEmpty { prog.optString("name", "") }
                    .trim()

                val rawStart = prog.optString("start", "")
                    .ifEmpty { prog.optString("startTime", "") }
                    .ifEmpty { prog.optString("start_time", "") }
                    .ifEmpty { prog.optString("s", "") }

                val rawEnd = prog.optString("end", "")
                    .ifEmpty { prog.optString("endTime", "") }
                    .ifEmpty { prog.optString("end_time", "") }
                    .ifEmpty { prog.optString("e", "") }

                val cleanTitle = rawTitle
                    .replace("&ensp;", " ")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace(Regex("""(?i)[-_\s]*免费使用.*"""), "")
                    .replace(Regex("""(?i)diyp.*"""), "")
                    .replace("112114", "")
                    .trim()

                if (cleanTitle.isEmpty() ||
                    cleanTitle.contains("额度") ||
                    cleanTitle.contains("赞助") ||
                    cleanTitle.contains("公众号") ||
                    cleanTitle == "精彩节目" ||
                    cleanTitle == "未知节目" ||
                    cleanTitle == "无节目" ||
                    cleanTitle == "节目精彩"
                ) continue

                val cleanStart = formatTime(rawStart)
                val cleanEnd = formatTime(rawEnd)

                if (cleanStart.isEmpty()) continue

                validList.add(JSONObject().apply {
                    put("title", cleanTitle)
                    put("start", cleanStart)
                    put("end", cleanEnd)
                })
            }

            if (validList.isNotEmpty()) validList else null
        } catch (e: Exception) {
            null
        }
    }

    // 🌟 通用时间标准化函数：支持秒级时间戳、日期时间、时分秒
    private fun formatTime(timeVal: String): String {
        val str = timeVal.trim()
        if (str.isEmpty()) return ""

        // 1. 处理 10 位秒级时间戳 (如 CNTV 的 1788383880)
        val timestamp = str.toLongOrNull()
        if (timestamp != null && timestamp > 1000000000L) {
            val ms = if (timestamp < 100000000000L) timestamp * 1000L else timestamp
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        }

        // 2. 处理包含日期的字符串 (如 "2026-09-03 15:18:00")
        if (str.contains(" ") && str.contains(":")) {
            val timePart = str.substringAfter(" ")
            val parts = timePart.split(":")
            if (parts.size >= 2) {
                return "${parts[0].padStart(2, '0')}:${parts[1].padStart(2, '0')}"
            }
        }

        // 3. 处理标准 "HH:mm" 或 "HH:mm:ss"
        if (str.contains(":")) {
            val parts = str.split(":")
            if (parts.size >= 2) {
                val h = parts[0].filter { it.isDigit() }.padStart(2, '0')
                val m = parts[1].filter { it.isDigit() }.padStart(2, '0')
                return "$h:$m"
            }
        }

        return str
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

            conn.connectTimeout = 3500
            conn.readTimeout = 3500
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