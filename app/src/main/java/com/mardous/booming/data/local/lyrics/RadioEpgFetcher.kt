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
import java.util.TimeZone
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

        var fjtvChannelId: String? = null
        var qtfmChannelId: String? = null
        val lowerStation = stationName.lowercase()
        
        // 地方台精准路由
        if (Regex("福建新闻|fm\\s*1036|fm\\s*103\\.6").containsMatchIn(lowerStation)) {
            fjtvChannelId = "665247887399424000"
            cleanName = "福建新闻广播"
        } else if (Regex("福建东南|am\\s*585").containsMatchIn(lowerStation)) {
            fjtvChannelId = "665247519068229632"
            cleanName = "福建东南广播"
        } else if (Regex("福建经济|福建经接|fm\\s*961|fm\\s*96\\.1").containsMatchIn(lowerStation)) {
            fjtvChannelId = "665247815009931264"
            cleanName = "福建经济广播"
        } else if (Regex("福建交通|fm\\s*1007|fm\\s*100\\.7").containsMatchIn(lowerStation)) {
            fjtvChannelId = "665247838078603264"
            cleanName = "福建交通广播"
        } else if (Regex("福建都市|fm\\s*987|fm\\s*98\\.7").containsMatchIn(lowerStation)) {
            fjtvChannelId = "665247862439120896"
            cleanName = "福建都市广播"
        } else if (Regex("福州交通|fm\\s*876").containsMatchIn(lowerStation)) {
            qtfmChannelId = "5026"
            cleanName = "福州交通之声"
        } else if (Regex("福州新闻|5025").containsMatchIn(lowerStation)) {
            qtfmChannelId = "5025"
            cleanName = "福州新闻广播"
        } else if (Regex("左海之声|901|3937").containsMatchIn(lowerStation)) {
            qtfmChannelId = "3937"
            cleanName = "左海之声"
        } else if (Regex("泉州904交通之声广播电台|904|15318189").containsMatchIn(lowerStation)) {
            qtfmChannelId = "15318189"
            cleanName = "泉州904交通之声"
        } else if (Regex("泉州广播电视台889新闻综合广播|889|15318346").containsMatchIn(lowerStation)) {
            qtfmChannelId = "15318346"
            cleanName = "泉州广播电视台889新闻综合广播"
        } else if (Regex("厦门交通旅游广播|107|1738").containsMatchIn(lowerStation)) {
            qtfmChannelId = "1738"
            cleanName = "厦门交通旅游广播"
        } else if (Regex("厦门综合广播|1107|1737").containsMatchIn(lowerStation)) {
            qtfmChannelId = "1737"
            cleanName = "厦门综合广播"
        }

        val cctvMatch = Regex("""(?i)^cctv[-\s]*(\d+\+?).*""").find(cleanName)
        if (cctvMatch != null) {
            cleanName = "CCTV${cctvMatch.groupValues[1]}"
        }

        when {
            cleanName.contains("中国之声") || cleanName.equals("CNR1", true) -> cleanName = "中国之声"
            cleanName.contains("经济之声") || cleanName.equals("CNR2", true) -> cleanName = "经济之声"
            cleanName == "音乐之声" || cleanName.equals("CNR3", true) -> cleanName = "音乐之声"
            cleanName == "经典音乐广播" || cleanName.equals("CNR4", true) -> cleanName = "经典音乐广播"
            cleanName.contains("台海之声") || cleanName.contains("中华之声") || cleanName.equals("CNR5", true) -> cleanName = "台海之声"
            cleanName.contains("神州之声") || cleanName.equals("CNR6", true) -> cleanName = "神州之声"
            cleanName.contains("大湾区之声") || cleanName.contains("华夏之声") || cleanName.equals("CNR7", true) -> cleanName = "大湾区之声"
            cleanName == "民族之声" || cleanName.equals("CNR8", true) -> cleanName = "民族之声"
            cleanName == "文艺之声" || cleanName.equals("CNR9", true) -> cleanName = "文艺之声"
            cleanName == "老年之声" || cleanName.equals("CNR10", true) -> cleanName = "老年之声"
            cleanName == "阅读之声" || cleanName.equals("CNR12", true) -> cleanName = "阅读之声"
            // 严格匹配，防止误伤地方台
            cleanName == "交通广播" || cleanName == "中国交通广播" || cleanName.equals("CNR15", true) -> cleanName = "中国交通广播"
            cleanName.contains("环球资讯") || cleanName.equals("CRI", true) -> cleanName = "环球资讯广播"
            cleanName.contains("轻松调频") || cleanName.equals("EZFM", true) -> cleanName = "轻松调频"
            cleanName.contains("劲曲调频") || cleanName.equals("HITFM", true) -> cleanName = "劲曲调频"
        }

        var isPhoenix = false
        var phoenixKind = 0
        if (cleanName.contains("凤凰")) {
            isPhoenix = true
            val norm = cleanName.replace("咨询", "资讯")
            when {
                norm.contains("资讯") -> { phoenixKind = 1; cleanName = "凤凰资讯" }
                norm.contains("电影") -> { phoenixKind = 2; cleanName = "凤凰电影" }
                norm.contains("香港") -> { phoenixKind = 3; cleanName = "凤凰香港" }
                else -> { phoenixKind = 0; cleanName = "凤凰中文" }
            }
        }

        if (cleanName.endsWith("卫视台")) {
            cleanName = cleanName.replace("卫视台", "卫视")
        }

        if (cleanName.isEmpty()) return@withContext "📻 当前频道：$stationName\n\n📡 纯享直播流"

        if (isRadioStation && !cleanName.contains("之声") && !cleanName.contains("广播") && !cleanName.contains("调频") && !cleanName.contains("卫视")) {
            return@withContext "📻 电台直播：$cleanName\n\n📡 纯享音频流✨"
        }

        try {
            // 增加超时时间，防止国内广电服务器响应过慢
            val maxWaitMs = if (isPhoenix) 6000L else 4000L

            val result = withTimeoutOrNull(maxWaitMs) {
                var validPrograms: List<JSONObject>? = null
                val dashDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                }
                val todayDashStr = dashDateFormat.format(Date())
                val dateCompact = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

                if (cleanName.startsWith("CCTV", ignoreCase = true)) {
                    val cctvCode = cleanName.lowercase().replace("+", "plus")
                    val cntvUrl = "https://api.cntv.cn/epg/getEpgInfoByChannelNew?c=$cctvCode&serviceId=tvcctv&d=$dateCompact"
                    val cntvRes = httpGet(cntvUrl, timeoutMs = 2000)
                    if (cntvRes != null) validPrograms = parseCntvPrograms(cntvRes, cctvCode)
                }

                // 🌟 福建台专属请求
                if (validPrograms == null && fjtvChannelId != null) {
                    val fjtvUrl = "https://radio.fjtv.net/m2o/program_switch.php?channel_id=$fjtvChannelId&dates=$todayDashStr&shownums=7"
                    val htmlRes = httpGet(fjtvUrl, timeoutMs = 3000, referer = "https://radio.fjtv.net/")
                    if (htmlRes != null) validPrograms = parseFjtvWebHtml(htmlRes)
                }

                // 🌟 蜻蜓FM 专属请求
                if (validPrograms == null && qtfmChannelId != null) {
                    val qtfmUrl = "https://m.qtfm.cn/channels/$qtfmChannelId/"
                    val htmlRes = httpGet(qtfmUrl, timeoutMs = 3000, referer = "https://m.qtfm.cn/")
                    if (htmlRes != null) validPrograms = parseQtfmWebHtml(htmlRes)
                }

                // 凤凰及其他兜底逻辑...
                if (validPrograms == null && isPhoenix) {
                    val officialApiUrl = "https://ne883dbn.ifeng.com/phtvperiodlist?from=$todayDashStr&to=$todayDashStr&callback=parseData"
                    val officialRes = httpGet(officialApiUrl, timeoutMs = 3000, referer = "https://phtv.ifeng.com/")
                    if (officialRes != null) {
                        validPrograms = parsePhoenixOfficialPrograms(officialRes, phoenixKind, todayDashStr)
                    }

                    if (validPrograms == null) {
                        val phoenixTargetUrls = when (phoenixKind) {
                            0 -> listOf("https://epg.pw/api/epg.json?channel_id=561392", "https://epg.pw/api/epg.json?channel_id=410378")
                            1 -> listOf("https://epg.pw/api/epg.json?channel_id=561393", "https://epg.pw/api/epg.json?channel_id=410355")
                            else -> listOf("https://epg.pw/api/epg.json?channel_name=${Uri.encode(cleanName)}")
                        }

                        for (targetUrl in phoenixTargetUrls) {
                            val res = httpGet(targetUrl, timeoutMs = 2500) ?: continue
                            val programs = parseUniversalEpgPrograms(res, todayDashStr)
                            if (!programs.isNullOrEmpty()) {
                                validPrograms = programs
                                break
                            }
                        }
                    }
                }

                if (validPrograms == null && !isPhoenix) {
                    val baiduUrl = "https://opendata.baidu.com/api.php?resource_id=28266&from_mid=1&format=json&ie=utf-8&oe=utf-8&query=${Uri.encode(cleanName + "节目表")}"
                    val baiduRes = httpGet(baiduUrl, timeoutMs = 2000)
                    if (baiduRes != null) validPrograms = parseBaiduPrograms(baiduRes)
                }

                if (validPrograms == null && !isPhoenix) {
                    val webUrl = "http://51zmt.top/channel/${Uri.encode(cleanName)}/"
                    val htmlRes = httpGet(webUrl, timeoutMs = 2000)
                    if (htmlRes != null) validPrograms = parse51zmtWebHtml(htmlRes)
                }

                if (validPrograms == null && !isPhoenix && SATELLITE_CODE_MAP.containsKey(cleanName)) {
                    val code = SATELLITE_CODE_MAP[cleanName]!!
                    var cntvRes = httpGet("https://api.cntv.cn/epg/getEpgInfoByChannelNew?c=$code&serviceId=cbox&d=$dateCompact", timeoutMs = 1500)
                    if (cntvRes == null || !cntvRes.contains("\"list\"")) {
                        cntvRes = httpGet("https://api.cntv.cn/epg/getEpgInfoByChannelNew?c=$code&serviceId=tvcctv&d=$dateCompact", timeoutMs = 1500)
                    }
                    if (cntvRes != null) validPrograms = parseCntvPrograms(cntvRes, code)
                }

                validPrograms
            }

            if (result.isNullOrEmpty()) {
                return@withContext "📺 正在收听：$cleanName\n\n📡 直播流连接成功 (今日暂无详细排期)"
            }

            val cal = Calendar.getInstance()
            val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val sb = StringBuilder()
            sb.append("📡 $cleanName 今日节目单\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━\n\n")

            for (i in result.indices) {
                val prog = result[i]
                val title = prog.optString("title", "未知节目")
                val startTime = prog.optString("start", "")
                var endTime = prog.optString("end", "")

                if (endTime.isEmpty() && i + 1 < result.size) {
                    endTime = result[i + 1].optString("start", "")
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
            return@withContext "📺 当前频道：$cleanName\n\n📡 直播流连接成功"
        }
    }

    // 🌟 终极版 蜻蜓FM 提取引擎（正则优先提取 + 栈匹配兜底），无视任何 JSON 异常
    private fun parseQtfmWebHtml(html: String): List<JSONObject>? {
        return try {
            val list = mutableListOf<JSONObject>()
            // 方法1：无视 JSON 语法结构，强行正则挖出 today 数组内的所有数据
            val todayRegex = Regex(""""today":\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            val todayMatch = todayRegex.find(html)
            if (todayMatch != null) {
                val todayBlock = todayMatch.groupValues[1]
                val itemRegex = Regex("""\{([^}]+)\}""")
                val items = itemRegex.findAll(todayBlock)
                for (item in items) {
                    val content = item.groupValues[1]
                    val durationMatch = Regex(""""durationStr":\s*"([^"]+)"""").find(content)
                    val titleMatch = Regex(""""title":\s*"([^"]+)"""").find(content)
                    
                    if (titleMatch != null) {
                        val title = titleMatch.groupValues[1].replace("\\\"", "\"").replace("\\u002F", "/")
                        var start = ""
                        var end = ""
                        if (durationMatch != null) {
                            val durationStr = durationMatch.groupValues[1]
                            if (durationStr.contains("~")) {
                                val parts = durationStr.split("~")
                                start = parts[0].trim()
                                end = parts[1].trim()
                            } else if (durationStr.contains("-")) {
                                val parts = durationStr.split("-")
                                start = parts[0].trim()
                                end = parts[1].trim()
                            }
                        }
                        // 兜底时间
                        if (start.isEmpty()) {
                            val startIsoMatch = Regex(""""startTime":\s*"([^"]+)"""").find(content)
                            if (startIsoMatch != null) {
                                start = formatTime(startIsoMatch.groupValues[1])
                            }
                        }
                        if (start.isNotEmpty() && title.isNotEmpty()) {
                            list.add(JSONObject().apply {
                                put("title", title)
                                put("start", start)
                                put("end", end)
                            })
                        }
                    }
                }
            }
            
            if (list.isNotEmpty()) {
                return list
            }

            // 方法2：原生栈提取兜底
            val startToken = "window.__initStores ="
            val startIndex = html.indexOf(startToken)
            if (startIndex != -1) {
                val jsonStart = html.indexOf("{", startIndex)
                if (jsonStart != -1) {
                    var depth = 0
                    var jsonEnd = -1
                    for (i in jsonStart until html.length) {
                        if (html[i] == '{') depth++
                        else if (html[i] == '}') {
                            depth--
                            if (depth == 0) {
                                jsonEnd = i + 1
                                break
                            }
                        }
                    }
                    if (jsonEnd != -1) {
                        val jsonStr = html.substring(jsonStart, jsonEnd)
                        val root = JSONObject(jsonStr)
                        val todayArr = root.optJSONObject("ChannelStore")?.optJSONObject("playBill")?.optJSONArray("today")
                        if (todayArr != null) {
                            for (i in 0 until todayArr.length()) {
                                val item = todayArr.getJSONObject(i)
                                val title = item.optString("title", "").trim()
                                val durationStr = item.optString("durationStr", "")
                                var start = ""
                                var end = ""
                                if (durationStr.contains("~")) {
                                    val parts = durationStr.split("~")
                                    start = parts[0].trim()
                                    end = parts[1].trim()
                                }
                                if (title.isNotEmpty() && start.isNotEmpty()) {
                                    list.add(JSONObject().apply {
                                        put("title", title)
                                        put("start", start)
                                        put("end", end)
                                    })
                                }
                            }
                        }
                    }
                }
            }
            if (list.isNotEmpty()) list else null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseFjtvWebHtml(html: String): List<JSONObject>? {
        return try {
            val cleanHtml = html.replace(Regex("""(?is)<script.*?</script>"""), "")
                .replace(Regex("""(?is)<style.*?</style>"""), "")

            val lines = cleanHtml.replace(Regex("""<[^>]+>"""), "\n")
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val list = mutableListOf<JSONObject>()
            val timeRegex = Regex("""^(\d{2}:\d{2})""") 

            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val timeMatch = timeRegex.find(line)
                if (timeMatch != null) {
                    val start = timeMatch.groupValues[1] 
                    if (i + 1 < lines.size) {
                        val nextLine = lines[i + 1]
                        if (!timeRegex.containsMatchIn(nextLine) && !nextLine.contains("当前直播") && !nextLine.contains("返回直播")) {
                            val cleanTitle = nextLine
                                .replace("&ensp;", " ")
                                .replace("&nbsp;", " ")
                                .replace("&amp;", "&")
                                .trim()

                            list.add(JSONObject().apply {
                                put("title", cleanTitle)
                                put("start", start)
                                put("end", "") 
                            })
                            i++ 
                        }
                    }
                }
                i++
            }
            if (list.isNotEmpty()) list else null
        } catch (e: Exception) {
            null
        }
    }

    private fun parsePhoenixOfficialPrograms(jsonpStr: String, channelKind: Int, targetDate: String): List<JSONObject>? {
        return try {
            val startIndex = jsonpStr.indexOf("{")
            val endIndex = jsonpStr.lastIndexOf("}")
            if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) return null
            val jsonStr = jsonpStr.substring(startIndex, endIndex + 1)
            
            val rootObj = JSONObject(jsonStr)
            val dataObj = rootObj.optJSONObject("data") ?: return null
            
            val dayData = dataObj.optJSONObject(targetDate) ?: return null
            
            val targetKey = when (channelKind) {
                1 -> "phtvNews"
                2 -> "phtvMovie"
                3 -> "phtvHK"
                else -> "phtvChinese"
            }
            
            val programArray = dayData.optJSONArray(targetKey) ?: return null
            
            val validList = mutableListOf<JSONObject>()
            for (i in 0 until programArray.length()) {
                val item = programArray.getJSONObject(i)
                val title = item.optString("title", "").trim()
                val time = item.optString("time", "").trim()
                
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

    private fun parseUniversalEpgPrograms(jsonStr: String, targetDateStr: String = ""): List<JSONObject>? {
        return try {
            val trimmed = jsonStr.trim()
            val epgArray: JSONArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("programs")
                        ?: root.optJSONArray("epg_data")
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

                if (targetDateStr.isNotEmpty() && rawStart.contains("-")) {
                    val datePart = rawStart.substringBefore(" ").substringBefore("T")
                    if (datePart.isNotEmpty() && datePart != targetDateStr) continue
                }

                val cleanTitle = rawTitle
                    .replace("&ensp;", " ")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace(Regex("""(?i)[-_\s]*免费使用.*"""), "")
                    .replace(Regex("""(?i)diyp.*"""), "")
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

    // 🌟 终极穿甲弹请求头：全要素模仿 Chrome 手机端浏览器，直接打穿对方云 WAF 防火墙
    private fun httpGet(urlString: String, timeoutMs: Int = 4000, referer: String? = null): String? {
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

            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            
            // 完整模拟你抓包抓出来的头部特征，专门克制防爬拦截
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            conn.setRequestProperty("Connection", "keep-alive")
            conn.setRequestProperty("DNT", "1")
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1")
            conn.setRequestProperty("Sec-Fetch-Dest", "document")
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate")
            conn.setRequestProperty("Sec-Fetch-Site", "none")
            conn.setRequestProperty("Sec-Fetch-User", "?1")

            if (referer != null) {
                conn.setRequestProperty("Referer", referer)
            }

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