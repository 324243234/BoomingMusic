package com.mardous.booming.data.local.lyrics

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

        // 🌟 1. 央广 CNR / 国际台 CRI 标准化（完美对接百度电视指南）
        if (cleanName.contains("中国之声") || cleanName.equals("CNR1", true)) cleanName = "中国之声"
        else if (cleanName.contains("经济之声") || cleanName.equals("CNR2", true)) cleanName = "经济之声"
        else if (cleanName.contains("音乐之声") || cleanName.equals("CNR3", true)) cleanName = "音乐之声"
        else if (cleanName.contains("经典音乐") || cleanName.equals("CNR4", true)) cleanName = "经典音乐广播"
        else if (cleanName.contains("台海之声") || cleanName.contains("中华之声") || cleanName.equals("CNR5", true)) cleanName = "台海之声"
        else if (cleanName.contains("神州之声") || cleanName.equals("CNR6", true)) cleanName = "神州之声"
        else if (cleanName.contains("大湾区") || cleanName.contains("华夏之声") || cleanName.equals("CNR7", true)) cleanName = "大湾区之声"
        else if (cleanName.contains("民族之声") || cleanName.equals("CNR8", true)) cleanName = "民族之声"
        else if (cleanName.contains("文艺之声") || cleanName.equals("CNR9", true)) cleanName = "文艺之声"
        else if (cleanName.contains("老年之声") || cleanName.equals("CNR10", true)) cleanName = "老年之声"
        else if (cleanName.contains("藏语广播") || cleanName.equals("CNR11", true)) cleanName = "藏语广播"
        else if (cleanName.contains("阅读之声") || cleanName.equals("CNR12", true)) cleanName = "阅读之声"
        else if (cleanName.contains("娱乐广播") || cleanName.equals("CNR13", true)) cleanName = "娱乐广播"
        else if (cleanName.contains("香港之声") || cleanName.equals("CNR14", true)) cleanName = "香港之声"
        else if (cleanName.contains("交通广播") || cleanName.equals("CNR15", true)) cleanName = "中国交通广播"
        else if (cleanName.contains("乡村之声") || cleanName.equals("CNR16", true)) cleanName = "中国乡村之声"
        else if (cleanName.contains("环球资讯") || cleanName.equals("CRI", true)) cleanName = "环球资讯广播"
        else if (cleanName.contains("轻松调频") || cleanName.equals("EZFM", true)) cleanName = "轻松调频"
        else if (cleanName.contains("劲曲调频") || cleanName.equals("HITFM", true)) cleanName = "劲曲调频"

        // 🌟 2. 凤凰卫视系强力标准化：直接归一为短名
        var isPhoenix = false
        var phoenixShortName = ""
        if (cleanName.contains("凤凰")) {
            isPhoenix = true
            val norm = cleanName.replace("咨询", "资讯")
            when {
                norm.contains("资讯") -> { phoenixShortName = "凤凰资讯"; cleanName = "凤凰资讯" }
                norm.contains("电影") -> { phoenixShortName = "凤凰电影"; cleanName = "凤凰电影" }
                norm.contains("香港") -> { phoenixShortName = "凤凰香港"; cleanName = "凤凰香港" }
                else -> { phoenixShortName = "凤凰中文"; cleanName = "凤凰中文" }
            }
        }

        if (cleanName.endsWith("卫视台")) {
            cleanName = cleanName.replace("卫视台", "卫视")
        }

        if (cleanName.isEmpty()) return@withContext "[00:00.00]📻 当前频道：$stationName\n[00:00.10]📡 纯享直播流"

        if (isRadioStation && !cleanName.contains("之声") && !cleanName.contains("广播") && !cleanName.contains("调频")) {
            return@withContext "[00:00.00]📻 电台直播：$cleanName\n[00:00.10]📡 纯享音频流\n[00:00.20]✨ 若有实时节目，将在此高亮显示"
        }

        try {
            val result = withTimeoutOrNull(4500L) {
                var validPrograms: List<JSONObject>? = null
                val dateCompact = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

                // A. CCTV 官方直通车
                if (cleanName.startsWith("CCTV", ignoreCase = true)) {
                    val cctvCode = cleanName.lowercase().replace("+", "plus")
                    val cntvUrl = "https://api.cntv.cn/epg/getEpgInfoByChannelNew?c=$cctvCode&serviceId=tvcctv&d=$dateCompact"
                    val cntvRes = httpGet(cntvUrl)
                    if (cntvRes != null) {
                        validPrograms = parseCntvPrograms(cntvRes, cctvCode)
                    }
                }

                // B. 凤凰卫视绝对防卡死专线（屏蔽境外域名，使用国内极速镜像）
                if (validPrograms == null && isPhoenix) {
                    val safePhoenixUrls = listOf(
                        "https://epg.v1.mk/api/diyp/?ch=${Uri.encode(phoenixShortName)}",
                        "http://epg.aptvapp.com/api/diyp/?ch=${Uri.encode(phoenixShortName)}",
                        "http://epg.51zmt.top:8000/api/diyp/?ch=${Uri.encode(phoenixShortName)}",
                        "https://diyp.288448.xyz/?ch=${Uri.encode(phoenixShortName)}"
                    )
                    for (targetUrl in safePhoenixUrls) {
                        val res = httpGet(targetUrl) ?: continue
                        val programs = parseUniversalEpgPrograms(res)
                        if (!programs.isNullOrEmpty()) {
                            validPrograms = programs
                            break
                        }
                    }
                }

                // C. 百度阿拉丁电视指南接口（覆盖全量卫视及 CNR/CRI）
                if (validPrograms == null && !isPhoenix) {
                    val baiduUrl = "https://opendata.baidu.com/api.php?resource_id=28266&from_mid=1&format=json&ie=utf-8&oe=utf-8&query=${Uri.encode(cleanName + "节目表")}"
                    val baiduRes = httpGet(baiduUrl)
                    if (baiduRes != null) validPrograms = parseBaiduPrograms(baiduRes)
                }

                // D. 内地卫视：51zmt 网页直提 (80 端口)
                if (validPrograms == null && !isPhoenix) {
                    val webUrl = "http://51zmt.top/channel/${Uri.encode(cleanName)}/"
                    val htmlRes = httpGet(webUrl)
                    if (htmlRes != null) validPrograms = parse51zmtWebHtml(htmlRes)
                }

                // E. 内地卫视：CNTV 官方卫视频道接口兜底
                if (validPrograms == null && !isPhoenix && SATELLITE_CODE_MAP.containsKey(cleanName)) {
                    val code = SATELLITE_CODE_MAP[cleanName]!!
                    var cntvRes = httpGet("https://api.cntv.cn/epg/getEpgInfoByChannelNew?c=$code&serviceId=cbox&d=$dateCompact")
                    if (cntvRes == null || !cntvRes.contains("\"list\"")) {
                        cntvRes = httpGet("https://api.cntv.cn/epg/getEpgInfoByChannelNew?c=$code&serviceId=tvcctv&d=$dateCompact")
                    }
                    if (cntvRes != null) validPrograms = parseCntvPrograms(cntvRes, code)
                }

                // F. 极速通用节点终极兜底
                if (validPrograms == null && !isPhoenix) {
                    val candidateUrls = listOf(
                        "http://epg.51zmt.top:8000/api/diyp/?ch=${Uri.encode(cleanName)}",
                        "https://epg.v1.mk/api/diyp/?ch=${Uri.encode(cleanName)}",
                        "http://epg.aptvapp.com/api/diyp/?ch=${Uri.encode(cleanName)}"
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
                validPrograms
            }

            if (result.isNullOrEmpty()) {
                return@withContext "[00:00.00]📺 正在收听：$cleanName\n[00:00.10]📡 直播流连接成功 (今日暂无详细排期)"
            }

            // 🌟 魔法转换：将时间戳计算相对差值，转换为歌词 LRC 格式
            val cal = Calendar.getInstance()
            val nowMs = System.currentTimeMillis()

            val sb = StringBuilder()
            sb.append("[00:00.00]📡 $cleanName 今日节目单\n")
            var basePastTime = 100L // 过去节目的递增底数

            for (i in result.indices) {
                val prog = result[i]
                val title = prog.optString("title", "未知节目")
                val startTime = prog.optString("start", "")
                var endTime = prog.optString("end", "")

                if (endTime.isEmpty() && i + 1 < result.size) {
                    endTime = result[i + 1].optString("start", "")
                }

                val timeDisplay = if (endTime.isNotEmpty()) "[$startTime - $endTime]" else "[$startTime]"
                val lineText = "$timeDisplay $title"

                if (startTime.length >= 4) {
                    val sMin = timeToMinutes(startTime)
                    if (sMin != -1) {
                        val startCal = Calendar.getInstance()
                        startCal.set(Calendar.HOUR_OF_DAY, sMin / 60)
                        startCal.set(Calendar.MINUTE, sMin % 60)
                        startCal.set(Calendar.SECOND, 0)
                        val progStartMs = startCal.timeInMillis

                        val offsetMs = progStartMs - nowMs

                        // 如果是过去的节目，赋予 0.1秒、0.2秒... 以确保持续在上方滚动
                        // 如果是将来的节目，赋予其真实未来的等待时间戳！
                        val lrcTimeMs = if (offsetMs <= 0) {
                            basePastTime += 100L
                            basePastTime
                        } else {
                            offsetMs
                        }

                        val safeLrcTimeMs = if (lrcTimeMs < 0) 0L else lrcTimeMs
                        val min = safeLrcTimeMs / 60000
                        val sec = (safeLrcTimeMs % 60000) / 1000
                        val ms = (safeLrcTimeMs % 1000) / 10
                        val timeStr = String.format(Locale.getDefault(), "[%02d:%02d.%02d]", min, sec, ms)
                        sb.append("$timeStr $lineText\n")
                    } else {
                        sb.append("[00:00.00] $lineText\n")
                    }
                } else {
                    sb.append("[00:00.00] $lineText\n")
                }
            }

            return@withContext sb.toString().trimEnd()

        } catch (e: Exception) {
            Log.e(TAG, "获取节目单失败", e)
            return@withContext "[00:00.00]📺 当前频道：$cleanName\n[00:00.10]📡 直播流连接成功"
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
            val todayShort = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date())
            var targetProgramArray: JSONArray? = null

            for (i in 0 until resultArray.length()) {
                val dayObj = resultArray.getJSONObject(i)
                val date = dayObj.optString("date", "")
                if (date.contains(todayStr) || date.contains(todayShort) || date.contains("今天") || date.contains("今日")) {
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

            conn.connectTimeout = 1200
            conn.readTimeout = 1200
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