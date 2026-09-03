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

    // 🌟 全国主流省级卫视拼音代号映射表 (直连 CNTV/CBox 官方权威节点)
    private val SATELLITE_CODE_MAP = mapOf(
        "天津卫视" to "tianjin", "北京卫视" to "beijing", "东方卫视" to "dongfang",
        "上海卫视" to "dongfang", "江苏卫视" to "jiangsu", "浙江卫视" to "zhejiang",
        "湖南卫视" to "hunan", "广东卫视" to "guangdong", "深圳卫视" to "shenzhen",
        "山东卫视" to "shandong", "安徽卫视" to "anhui", "河南卫视" to "henan",
        "湖北卫视" to "hubei", "辽宁卫视" to "liaoning", "黑龙江卫视" to "heilongjiang",
        "吉林卫视" to "jilin", "重庆卫视" to "chongqing", "四川卫视" to "sichuan",
        "河北卫视" to "hebei", "江西卫视" to "jiangxi", "贵州卫视" to "guizhou",
        "山西卫视" to "shanxi", "东南卫视" to "dongnan", "福建卫视" to "dongnan",
        "广西卫视" to "guangxi", "海南卫视" to "hainan", "云南卫视" to "yunnan",
        "陕西卫视" to "shaanxi", "甘肃卫视" to "gansu", "青海卫视" to "qinghai",
        "宁夏卫视" to "ningxia", "新疆卫视" to "xinjiang", "内蒙古卫视" to "neimenggu",
        "西藏卫视" to "xizang"
    )

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
            val dateCompact = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

            // 🌟 1. CCTV 频道官方直通车
            if (cleanName.startsWith("CCTV", ignoreCase = true)) {
                val cctvCode = cleanName.lowercase().replace("+", "plus")
                val cntvUrl = "https://api.cntv.cn/epg/getEpgInfoByChannelNew?c=$cctvCode&serviceId=tvcctv&d=$dateCompact"
                val cntvRes = httpGet(cntvUrl)
                if (cntvRes != null) {
                    validPrograms = parseCntvPrograms(cntvRes, cctvCode)
                }
            }

            // 🌟 2. 卫视与地方台：第一级（51zmt 网页直提）
            if (validPrograms == null) {
                val webUrl = "http://51zmt.top/channel/${Uri.encode(cleanName)}/"
                val htmlRes = httpGet(webUrl)
                if (htmlRes != null) {
                    validPrograms = parse51zmtWebHtml(htmlRes)
                }
            }

            // 🌟 3. 卫视与地方台：第二级（CNTV/CBox 官方卫视频道接口）
            if (validPrograms == null && SATELLITE_CODE_MAP.containsKey(cleanName)) {
                val code = SATELLITE_CODE_MAP[cleanName]!!
                var cntvRes = httpGet("https://api.cntv.cn/epg/getEpgInfoByChannelNew?c=$code&serviceId=cbox&d=$dateCompact")
                if (cntvRes == null || !cntvRes.contains("\"list\"")) {
                    cntvRes = httpGet("https://api.cntv.cn/epg/getEpgInfoByChannelNew?c=$code&serviceId=tvcctv&d=$dateCompact")
                }
                if (cntvRes != null) {
                    validPrograms = parseCntvPrograms(cntvRes, code)
                }
            }

            // 🌟 4. 卫视与地方台：第三级（百度阿拉丁电视指南官方通道）
            if (validPrograms == null) {
                val baiduUrl = "https://opendata.baidu.com/api.php?resource_id=28266&from_mid=1&format=json&ie=utf-8&oe=utf-8&query=${Uri.encode(cleanName + "节目表")}"
                val baiduRes = httpGet(baiduUrl)
                if (baiduRes != null) {
                    validPrograms = parseBaiduPrograms(baiduRes)
                }
            }

            // 🌟 5. 卫视与地方台：第四级（DIYP 社区多节点轮询兜底）
            if (validPrograms == null) {
                val candidateUrls = listOf(
                    "http://51zmt.top/api/diyp/?ch=${Uri.encode(cleanName)}",
                    "http://epg.51zmt.top:8000/api/diyp/?ch=${Uri.encode(cleanName)}",
                    "https://epg.v1.mk/api/diyp/?ch=${Uri.encode(cleanName)}",
                    "http://diyp.112114.xyz/?ch=${Uri.encode(cleanName)}"
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

            // 构建排期界面并根据系统时间高亮
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

    private fun parse51zmtWebHtml(html: String): List<JSONObject>? {
        return try {
            val cleanHtml = html.replace(Regex("""(?is)<script.*?</script>"""), "")
                .replace(Regex("""(?is)<style.*?</style>"""), "")

            val lines = cleanHtml.replace(Regex("""<[^>]+>"""), "\n")
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val list = mutableListOf<JSONObject>()
            val timeRangeRegex = Regex("""^(\d{2}:\d{2})\s*-\s*(\d{2}:\d{2})$""")

            for (i in lines.indices) {
                val line = lines[i]
                val rangeMatch = timeRangeRegex.find(line)
                if (rangeMatch != null) {
                    val start = rangeMatch.groupValues[1]
                    val end = rangeMatch.groupValues[2]

                    for (j in (i + 1) until minOf(i + 4, lines.size)) {
                        val nextLine = lines[j]
                        if (!nextLine.matches(Regex("""^\d{2}:\d{2}.*""")) &&
                            !nextLine.contains("节目") &&
                            !nextLine.contains("星期") &&
                            !nextLine.contains("今天") &&
                            nextLine.length in 1..40
                        ) {
                            val cleanTitle = nextLine
                                .replace("&ensp;", " ")
                                .replace("&nbsp;", " ")
                                .replace("&amp;", "&")
                                .trim()

                            list.add(JSONObject().apply {
                                put("title", cleanTitle)
                                put("start", start)
                                put("end", end)
                            })
                            break
                        }
                    }
                }
            }
            if (list.isNotEmpty()) list else null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseCntvPrograms(jsonStr: String, channelCode: String): List<JSONObject>? {
        return try {
            val root = JSONObject(jsonStr)
            val dataObj = root.optJSONObject("data") ?: return null

            var channelObj = dataObj.optJSONObject(channelCode)
            if (channelObj == null) {
                val keys = dataObj.keys()
                if (keys.hasNext()) {
                    channelObj = dataObj.optJSONObject(keys.next())
                }
            }
            if (channelObj == null) return null

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

    private fun parseBaiduPrograms(jsonStr: String): List<JSONObject>? {
        return try {
            val root = JSONObject(jsonStr)
            val dataArray = root.optJSONArray("data") ?: return null
            if (dataArray.length() == 0) return null
            val firstData = dataArray.getJSONObject(0)
            val resultArray = firstData.optJSONArray("result") ?: return null
            if (resultArray.length() == 0) return null

            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            var targetProgramArray: JSONArray? = null

            for (i in 0 until resultArray.length()) {
                val dayObj = resultArray.getJSONObject(i)
                val date = dayObj.optString("date", "")
                if (date == todayStr || date.contains("今天") || date.contains("今日")) {
                    targetProgramArray = dayObj.optJSONArray("program")
                    break
                }
            }
            if (targetProgramArray == null) {
                targetProgramArray = resultArray.getJSONObject(0).optJSONArray("program")
            }
            if (targetProgramArray == null || targetProgramArray.length() == 0) return null

            val validList = mutableListOf<JSONObject>()
            for (i in 0 until targetProgramArray.length()) {
                val item = targetProgramArray.getJSONObject(i)
                val title = item.optString("name", "").ifEmpty { item.optString("title", "") }.trim()
                val time = item.optString("time", "").ifEmpty { item.optString("start", "") }
                if (title.isNotEmpty() && time.isNotEmpty()) {
                    validList.add(JSONObject().apply {
                        put("title", title)
                        put("start", formatTime(time))
                        put("end", "")
                    })
                }
            }
            if (validList.isNotEmpty()) validList else null
        } catch (e: Exception) {
            null
        }
    }

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

                val rawEnd = prog.optString("end", "")
                    .ifEmpty { prog.optString("endTime", "") }
                    .ifEmpty { prog.optString("end_time", "") }

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
                    cleanTitle == "精彩节目" ||
                    cleanTitle == "未知节目" ||
                    cleanTitle == "无节目"
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

    private fun formatTime(timeVal: String): String {
        val str = timeVal.trim()
        if (str.isEmpty()) return ""

        val timestamp = str.toLongOrNull()
        if (timestamp != null && timestamp > 1000000000L) {
            val ms = if (timestamp < 100000000000L) timestamp * 1000L else timestamp
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        }

        if (str.contains(" ") && str.contains(":")) {
            val timePart = str.substringAfter(" ")
            val parts = timePart.split(":")
            if (parts.size >= 2) {
                return "${parts[0].padStart(2, '0')}:${parts[1].padStart(2, '0')}"
            }
        }

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

            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")

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