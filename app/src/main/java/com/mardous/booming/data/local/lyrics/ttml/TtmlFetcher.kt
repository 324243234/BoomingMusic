package com.mardous.booming.data.local.lyrics.ttml

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.media.isArtistNameUnknown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Inflater

/**
 * TTML 级联网络获取引擎 (移植 PC 端高精深度评分 + 基因级残缺标点修复)
 * 优先级: Apple Music -> 网易云音乐 -> QQ音乐
 */
object TtmlFetcher {

    private const val TAG = "TtmlFetcher"
    private const val APPLE_TOKEN = "eyJhbGciOiJFUzI1NiIsImtpZCI6MldVTUZPQjA2MyJ9.eyJpc3MiOiJBNTZEUjg1TTRTIiwiaWF0IjoxNTc4NTI2NzI2LCJleHAiOjE3NzA0MzYzMjZ9.S6x2XGf7OqS6cZJ_3eG0W8gA4vN4aT3q9Z1aW3bX5cY"

    private data class LyricSpan(var start: Long, var dur: Long, var text: String)
    private data class MatchResult(val score: Int, val id: String, val mid: String? = null, val lrc: String? = null, val trans: String? = null, val yrc: String? = null, val qrc: String? = null)

    private fun cleanTitle(title: String?): String {
        if (title == null) return ""
        var t = title.replace(Regex("""^\s*\d{1,4}\s*[-_.]?\s*"""), "")
        t = t.replace(Regex("""\(.*?(Remaster|Live|翻唱|伴奏|现场|DJ).*?\)"""), "")
        t = t.replace(Regex("""\[.*?\]|\【.*?\】"""), "")
        return t.trim()
    }

    private fun normalizeStr(input: String?): String {
        if (input == null) return ""
        return input.lowercase().replace(Regex("""[^\w\u4e00-\u9fa5]"""), "")
    }

    // ==========================================
    // 高精深度评分算法：抗干扰防偏位 (1:1 移植 PC 端)
    // ==========================================
    private fun calculateMatchScore(localSong: Song, rTitle: String, rArtist: String, rAlbum: String, rDurMs: Long): Int {
        val normLt = normalizeStr(localSong.title)
        val normRt = normalizeStr(rTitle)
        if (normLt.isEmpty() || normRt.isEmpty()) return -1
        if (!normLt.contains(normRt) && !normRt.contains(normLt)) return -1

        val ltFull = "${localSong.title} ${localSong.albumName}".lowercase()
        val lLive = ltFull.contains("live") || ltFull.contains("现场")
        val rLive = rTitle.lowercase().contains("live") || rTitle.lowercase().contains("现场")
        if (lLive != rLive) return -1

        val lRemix = ltFull.contains("remix") || ltFull.contains("dj") || ltFull.contains("版") || ltFull.contains("mix")
        val rRemix = rTitle.lowercase().contains("remix") || rTitle.lowercase().contains("dj") || rTitle.lowercase().contains("版") || rTitle.lowercase().contains("mix")
        if (lRemix != rRemix) return -1

        val rawArtist = if (localSong.isArtistNameUnknown()) "" else localSong.artistName
        val localArtists = rawArtist.split(Regex("""[/,&、;]| and """)).map { normalizeStr(it) }.filter { it.isNotEmpty() }
        val normRa = normalizeStr(rArtist)

        var artistMatch = false
        if (localArtists.isEmpty()) {
            artistMatch = true
        } else {
            val primary = localArtists[0]
            if (normRa.contains(primary) || primary.contains(normRa)) artistMatch = true
            else if (localArtists.any { normRa.contains(it) || it.contains(normRa) }) artistMatch = true
        }
        if (!artistMatch) return -1

        var score = 100
        val lDur = localSong.duration
        if (lDur > 0L && rDurMs > 0L) {
            val diff = Math.abs(lDur - rDurMs)
            if (diff <= 3500L) score += (1000L - diff).toInt()
            else if (diff <= 8000L) score += (400L - diff).toInt()
            else return -1 // 严格剔除时长不对的版本
        }

        val normLaAlb = normalizeStr(localSong.albumName ?: "")
        val normRaAlb = normalizeStr(rAlbum)
        if (normLaAlb.isNotEmpty() && normRaAlb.isNotEmpty()) {
            if (normLaAlb == normRaAlb) score += 500
            else if (normLaAlb.contains(normRaAlb) || normRaAlb.contains(normLaAlb)) score += 200
        }

        val compKws = Regex("best of|greatest hits|collection|精选|the ultimate|essential|platinum|anthology|soundtrack|ost", RegexOption.IGNORE_CASE)
        if (compKws.containsMatchIn(rAlbum) && !compKws.containsMatchIn(localSong.albumName ?: "")) score -= 800
        
        val remasterKws = Regex("remaster|deluxe|expanded|bonus|anniversary|edition", RegexOption.IGNORE_CASE)
        if (remasterKws.containsMatchIn(rAlbum) && !remasterKws.containsMatchIn(localSong.albumName ?: "")) score -= 300

        return score
    }

    suspend fun fetchTtmlForSong(song: Song): String? = withContext(Dispatchers.IO) {
        val rawTitle = cleanTitle(song.title)
        val rawArtist = if (song.isArtistNameUnknown()) "" else song.artistName
        val primaryArtist = rawArtist.split(Regex("[/&,、]| and ")).firstOrNull()?.trim() ?: ""

        if (rawTitle.isEmpty()) return@withContext null

        val query = if (primaryArtist.isBlank()) rawTitle else "$primaryArtist $rawTitle"
        val cleanQuery = query.replace(Regex("""[-_／/]"""), " ").replace(Regex("""\s+"""), " ").trim()

        try {
            // 🌟 严格并发扫描：基于高精评分同时抓取三大平台的最优元数据
            val (appleMatch, neteaseMatch, qqMatch) = coroutineScope {
                val aTask = async { fetchBestAppleMatch(cleanQuery, song) }
                val nTask = async { fetchBestNeteaseMatch(cleanQuery, song) }
                val qTask = async { fetchBestQQMatch(cleanQuery, song) }
                Triple(aTask.await(), nTask.await(), qTask.await())
            }

            // 1. Apple Music 官方及 AMLLDB (并发冲刺)
            if (appleMatch != null) {
                val ttml = raceAppleAndAmll(appleMatch.id, appleMatch.mid ?: "us") // mid stores country
                if (ttml != null) {
                    val mergedTrans = mutableMapOf<Long, String>()
                    mergedTrans.putAll(parseLrcTranslations(neteaseMatch?.trans))
                    mergedTrans.putAll(parseLrcTranslations(qqMatch?.trans))
                    return@withContext injectTranslationIntoTtml(ttml, mergedTrans)
                }
            }

            // 2. 网易云音乐 (解析 YRC 并基因补全)
            if (neteaseMatch?.yrc != null) {
                val transMap = parseLrcTranslations(neteaseMatch.trans)
                val lrcMap = parseLrcTranslations(neteaseMatch.lrc)
                val ttml = parseYrcToTtml(neteaseMatch.yrc, transMap, lrcMap)
                if (ttml != null) return@withContext ttml
            }

            // 3. QQ音乐 (解密 QRC 并基因补全)
            if (qqMatch?.qrc != null) {
                val transMap = parseLrcTranslations(qqMatch.trans)
                val lrcMap = parseLrcTranslations(qqMatch.lrc)
                val rawQrc = decryptQrc(qqMatch.qrc)
                if (rawQrc != null) {
                    val ttml = parseQrcToTtmlDirectly(rawQrc, transMap, lrcMap)
                    if (ttml != null) return@withContext ttml
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Fetch TTML failed", e)
        }
        return@withContext null
    }

    // ==========================================
    // 基因级溯源补全与闪烁修复引擎
    // ==========================================
    private fun healSpansWithLrc(spans: MutableList<LyricSpan>, lrcLine: String?): MutableList<LyricSpan> {
        if (spans.isEmpty()) return spans
        
        // 1. 吞并孤立的标点符号，防止 TTML 单标点频闪
        val healed = mutableListOf<LyricSpan>()
        val punctRegex = Regex("^[),.?!:;~’”\\]}\\-]+$")

        for (sp in spans) {
            if (healed.isNotEmpty() && punctRegex.matches(sp.text.trim())) {
                val prev = healed.last()
                val newDur = (sp.start + sp.dur) - prev.start
                val sText = prev.text
                val rText = sText.trimEnd()
                val spaces = sText.substring(rText.length)
                healed[healed.size - 1] = LyricSpan(prev.start, newDur, rText + sp.text.trim() + spaces)
            } else {
                healed.add(sp)
            }
        }

        if (lrcLine.isNullOrBlank()) return healed

        // 2. 修复 API 遗漏的左侧/右侧残缺括号
        val orig = lrcLine.replace("（", "(").replace("）", ")").replace("  ", " ")
        var spanText = healed.joinToString("") { it.text }.replace("（", "(").replace("）", ")")

        val leftBrackets = listOf("(", "[", "【", "{", "\"", "'")
        for (bracket in leftBrackets) {
            if (orig.contains(bracket) && !spanText.contains(bracket)) {
                val idx = orig.indexOf(bracket)
                val match = Regex("""\w+""").find(orig.substring(idx))
                if (match != null) {
                    val word = match.value
                    for (i in healed.indices) {
                        if (healed[i].text.contains(word)) {
                            healed[i].text = bracket + healed[i].text
                            spanText += bracket
                            break
                        }
                    }
                }
            }
        }

        val rightBrackets = listOf(")", "]", "】", "}", "\"", "'")
        for (bracket in rightBrackets) {
            if (orig.contains(bracket) && !spanText.contains(bracket)) {
                val idx = orig.indexOf(bracket)
                val matchList = Regex("""\w+""").findAll(orig.substring(0, idx)).toList()
                if (matchList.isNotEmpty()) {
                    val word = matchList.last().value
                    for (i in healed.indices.reversed()) {
                        if (healed[i].text.contains(word)) {
                            val sText = healed[i].text
                            val rText = sText.trimEnd()
                            val spaces = sText.substring(rText.length)
                            healed[i].text = rText + bracket + spaces
                            spanText += bracket
                            break
                        }
                    }
                }
            }
        }
        return healed
    }

    // ==========================================
    // 高精网络抓取 API
    // ==========================================
    private suspend fun fetchBestAppleMatch(query: String, song: Song): MatchResult? {
        val validItems = mutableListOf<MatchResult>()
        for (country in listOf("cn", "us")) {
            val searchUrl = "https://itunes.apple.com/search?term=${Uri.encode(query)}&entity=song&limit=10&country=$country"
            val searchRes = httpGet(searchUrl) ?: continue
            val results = runCatching { JSONObject(searchRes).optJSONArray("results") }.getOrNull() ?: continue
            
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val score = calculateMatchScore(
                    song, item.optString("trackName"), item.optString("artistName"),
                    item.optString("collectionName"), item.optLong("trackTimeMillis", 0L)
                )
                if (score > 0) validItems.add(MatchResult(score, item.optString("trackId"), country))
            }
        }
        return validItems.maxByOrNull { it.score }
    }

    private suspend fun fetchBestNeteaseMatch(query: String, song: Song): MatchResult? {
        val searchUrl = "https://music.163.com/api/search/get/web?s=${Uri.encode(query)}&type=1&limit=10"
        val searchRes = httpGet(searchUrl) ?: return null
        val songs = runCatching { JSONObject(searchRes).optJSONObject("result")?.optJSONArray("songs") }.getOrNull() ?: return null
        
        val validItems = mutableListOf<MatchResult>()
        for (i in 0 until songs.length()) {
            val item = songs.getJSONObject(i)
            val rArtist = (0 until (item.optJSONArray("artists")?.length() ?: 0)).joinToString("") { item.optJSONArray("artists")?.getJSONObject(it)?.optString("name") ?: "" }
            val score = calculateMatchScore(song, item.optString("name"), rArtist, item.optJSONObject("album")?.optString("name") ?: "", item.optLong("duration", 0L))
            if (score > 0) validItems.add(MatchResult(score, item.optInt("id", 0).toString()))
        }
        val best = validItems.maxByOrNull { it.score } ?: return null
        
        val lyricRes = httpGet("https://music.163.com/api/song/lyric?id=${best.id}&lv=-1&kv=-1&tv=-1&yv=1")
        if (lyricRes != null) {
            val jsonObj = runCatching { JSONObject(lyricRes) }.getOrNull()
            return best.copy(
                yrc = jsonObj?.optJSONObject("yrc")?.optString("lyric"),
                lrc = jsonObj?.optJSONObject("lrc")?.optString("lyric"),
                trans = jsonObj?.optJSONObject("tlyric")?.optString("lyric")
            )
        }
        return null
    }

    private suspend fun fetchBestQQMatch(query: String, song: Song): MatchResult? {
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=10&p=1&w=${Uri.encode(query)}"
        val searchRes = httpGet(searchUrl) ?: return null
        val start = searchRes.indexOf("{"); val end = searchRes.lastIndexOf("}")
        if (start == -1 || end == -1) return null
        val list = runCatching { JSONObject(searchRes.substring(start, end + 1)).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") }.getOrNull() ?: return null
        
        val validItems = mutableListOf<Triple<Int, JSONObject, String>>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val rArtist = (0 until (item.optJSONArray("singer")?.length() ?: 0)).joinToString("") { item.optJSONArray("singer")?.getJSONObject(it)?.optString("name") ?: "" }
            val score = calculateMatchScore(song, item.optString("songname"), rArtist, item.optString("albumname"), item.optLong("interval", 0L) * 1000L)
            if (score > 0) validItems.add(Triple(score, item, item.optString("songmid")))
        }
        
        val best = validItems.maxByOrNull { it.first } ?: return null
        val songItem = best.second
        val (qrcHex, transBase64) = fetchQqQrc(songItem)
        val lrcRes = httpGet("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${best.third}&format=json&nobase64=1")
        
        return MatchResult(
            score = best.first,
            id = songItem.optString("songid"),
            mid = best.third,
            qrc = qrcHex,
            lrc = runCatching { JSONObject(lrcRes!!).optString("lyric") }.getOrNull(),
            trans = runCatching { if (!transBase64.isNullOrBlank()) String(Base64.decode(transBase64, Base64.NO_WRAP), Charsets.UTF_8) else null }.getOrNull()
        )
    }

    private suspend fun raceAppleAndAmll(trackId: String, country: String): String? {
        return coroutineScope {
            val tasks = listOf(
                async { fetchAppleOfficial(trackId, country) },
                async { httpGet("https://amlldb.bikonoo.com/apple/$trackId.ttml") },
                async { httpGet("https://amlldb.bikonoo.com/qq-lyrics/$trackId.ttml") }
            )
            tasks.awaitAll().firstOrNull { !it.isNullOrBlank() && it.length > 50 }
        }
    }

    // ==========================================
    // 解析与 XML 构建
    // ==========================================
    private fun injectTranslationIntoTtml(ttml: String, transMap: Map<Long, String>): String {
        if (transMap.isEmpty() || ttml.contains("x-translation")) return ttml

        return runCatching {
            var modified = ttml
            if (!modified.contains("xmlns:amll=")) modified = modified.replaceFirst("<tt ", "<tt xmlns:amll=\"http://www.example.com/ns/amll\" ")
            if (!modified.contains("xmlns:itunes=")) modified = modified.replaceFirst("<tt ", "<tt xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" itunes:timing=\"Word\" ")
            if (!modified.contains("xmlns:ttm=")) modified = modified.replaceFirst("<tt ", "<tt xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" ")

            val segments = modified.split("</p>")
            if (segments.size <= 1) return ttml

            val sb = StringBuilder()
            for (i in 0 until segments.size - 1) {
                val segment = segments[i].trimEnd()
                val beginIdx = segment.indexOf("begin=\"")
                if (beginIdx != -1) {
                    val startQuote = beginIdx + 7
                    val endQuote = segment.indexOf("\"", startQuote)
                    if (endQuote != -1) {
                        val timeStr = segment.substring(startQuote, endQuote)
                        val ms = timeStrToMs(timeStr)
                        val trans = findMatchedTranslation(ms, transMap)
                        if (!trans.isNullOrBlank()) {
                            sb.append(segment).append("<span ttm:role=\"x-translation\" xml:lang=\"zh-Hans\">").append(escapeXml(trans)).append("</span></p>")
                            continue
                        }
                    }
                }
                sb.append(segment).append("</p>")
            }
            sb.append(segments.last())
            sb.toString()
        }.getOrDefault(ttml)
    }

    private fun parseYrcToTtml(yrcText: String, transMap: Map<Long, String>, lrcMap: Map<Long, String>): String? {
        return runCatching {
            val lines = yrcText.split(Regex("""\r?\n"""))
            val ttmlParagraphs = mutableListOf<String>()
            var lineIndex = 1 

            for (line in lines) {
                val cl = line.trim()
                if (cl.isEmpty()) continue

                val lineMatch = Regex("""^\[(\d+),\s*(\d+)\]\s*(.*)""").find(cl)
                if (lineMatch != null) {
                    val lineStart = lineMatch.groupValues[1].toLongOrNull() ?: continue
                    val lineDur = lineMatch.groupValues[2].toLongOrNull() ?: 0L
                    val content = lineMatch.groupValues[3].trim()
                    
                    val rawSpans = mutableListOf<LyricSpan>()

                    if (content.startsWith("{") && content.endsWith("}")) {
                        runCatching {
                            val cArray = JSONObject(content).optJSONArray("c")
                            if (cArray != null) {
                                for (i in 0 until cArray.length()) {
                                    val wordObj = cArray.getJSONObject(i)
                                    val tx = wordObj.optString("tx")
                                    val t = wordObj.optLong("t")
                                    val d = wordObj.optLong("d")
                                    if (tx.isNotEmpty() && d > 0) rawSpans.add(LyricSpan(t, d, tx))
                                }
                            }
                        }
                    } else {
                        val wordPattern = Regex("""\((\d+),\s*(\d+)(?:,\d+)?\)([^\(]+)""")
                        wordPattern.findAll(content).forEach { match ->
                            val t = match.groupValues[1].toLongOrNull() ?: 0L
                            val d = match.groupValues[2].toLongOrNull() ?: 0L
                            val tx = match.groupValues[3]
                            if (tx.isNotEmpty() && d > 0) rawSpans.add(LyricSpan(t, d, tx))
                        }
                    }

                    // 🌟 调用基因补全修复标点与括号
                    val lrcLine = findMatchedTranslation(lineStart, lrcMap)
                    val spans = healSpansWithLrc(rawSpans, lrcLine)

                    if (spans.isNotEmpty()) {
                        val pBuilder = StringBuilder()
                        pBuilder.append("<p begin=\"${msToTimeStr(lineStart)}\" end=\"${msToTimeStr(lineStart + lineDur)}\" itunes:key=\"L$lineIndex\" ttm:agent=\"v1\">")
                        pBuilder.append(spans.joinToString("") { "<span begin=\"${msToTimeStr(it.start)}\" end=\"${msToTimeStr(it.start + it.dur)}\">${escapeXml(it.text)}</span>" }) 

                        val matchedTrans = findMatchedTranslation(lineStart, transMap)
                        if (!matchedTrans.isNullOrBlank()) {
                            pBuilder.append("<span ttm:role=\"x-translation\" xml:lang=\"zh-Hans\">${escapeXml(matchedTrans)}</span>")
                        }

                        pBuilder.append("</p>")
                        ttmlParagraphs.add(pBuilder.toString())
                        lineIndex++
                    }
                }
            }
            if (ttmlParagraphs.isEmpty()) return null
            buildAppleTtml(ttmlParagraphs)
        }.getOrNull()
    }

    private fun parseQrcToTtmlDirectly(rawQrcText: String, transMap: Map<Long, String>, lrcMap: Map<Long, String>): String? {
        return runCatching {
            var text = rawQrcText.trim()
            val xmlMatch = Regex("""LyricContent="([^"]+)"""", RegexOption.IGNORE_CASE).find(text)
            if (xmlMatch != null) text = xmlMatch.groupValues[1]

            text = text.replace(Regex("""&#(\d+);""")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")

            val wordPattern = Regex("""([^\(\)\r\n]+)\((\d+),\s*(\d+)(?:,\d+)*\)""")
            if (!wordPattern.containsMatchIn(text)) return null

            val lines = text.split(Regex("""\r?\n"""))
            val ttmlParagraphs = mutableListOf<String>()
            var lineIndex = 1

            for (line in lines) {
                var cl = line.replace(Regex("""^\[\d{2,}:\d{2}(?:\.\d+)?\]"""), "").trim()
                cl = cl.replace(Regex("""^\[\d+,\d+\]"""), "").trim()
                if (cl.isEmpty()) continue
                if (Regex("""^(词|曲|编曲|监制|混音|吉他|贝斯|和声|录音|发行|出品|OP|SP)[：:]""").containsMatchIn(cl)) continue

                val rawSpans = mutableListOf<LyricSpan>()
                wordPattern.findAll(cl).forEach { match ->
                    var rawText = match.groupValues[1]
                    rawText = rawText.replace(Regex("""\[\d+,\d+\]"""), "")
                    val start = match.groupValues[2].toLongOrNull() ?: 0L
                    val dur = match.groupValues[3].toLongOrNull() ?: 0L
                    if (rawText.isNotEmpty()) rawSpans.add(LyricSpan(start, dur, rawText))
                }

                if (rawSpans.isEmpty()) continue

                var lineStart = Long.MAX_VALUE
                var lineEnd = 0L
                for (w in rawSpans) {
                    if (w.start < lineStart) lineStart = w.start
                    if ((w.start + w.dur) > lineEnd) lineEnd = w.start + w.dur
                }

                // 🌟 调用基因补全修复
                val lrcLine = findMatchedTranslation(lineStart, lrcMap)
                val spans = healSpansWithLrc(rawSpans, lrcLine)

                if (spans.isNotEmpty()) {
                    val pBuilder = StringBuilder()
                    pBuilder.append("<p begin=\"${msToTimeStr(lineStart)}\" end=\"${msToTimeStr(lineEnd)}\" itunes:key=\"L$lineIndex\" ttm:agent=\"v1\">")
                    pBuilder.append(spans.joinToString("") { "<span begin=\"${msToTimeStr(it.start)}\" end=\"${msToTimeStr(it.start + it.dur)}\">${escapeXml(it.text)}</span>" })

                    val matchedTrans = findMatchedTranslation(lineStart, transMap)
                    if (!matchedTrans.isNullOrBlank()) {
                        pBuilder.append("<span ttm:role=\"x-translation\" xml:lang=\"zh-Hans\">${escapeXml(matchedTrans)}</span>")
                    }

                    pBuilder.append("</p>")
                    ttmlParagraphs.add(pBuilder.toString())
                    lineIndex++
                }
            }
            if (ttmlParagraphs.isEmpty()) return null
            buildAppleTtml(ttmlParagraphs)
        }.getOrNull()
    }

    private fun buildAppleTtml(paragraphs: List<String>): String {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
               "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:amll=\"http://www.example.com/ns/amll\" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" itunes:timing=\"Word\">\n" +
               "  <head>\n    <metadata>\n      <ttm:agent type=\"person\" xml:id=\"v1\"/>\n    </metadata>\n  </head>\n" +
               "  <body dur=\"9:59.999\">\n    <div>\n      " +
               paragraphs.joinToString("\n      ") +
               "\n    </div>\n  </body>\n</tt>"
    }

    // ==========================================
    // 时间转换与基础工具
    // ==========================================
    private fun parseLrcTranslations(lrcText: String?): Map<Long, String> {
        if (lrcText.isNullOrBlank()) return emptyMap()
        val map = mutableMapOf<Long, String>()
        runCatching {
            lrcText.split(Regex("""\r?\n""")).forEach { line ->
                val cl = line.trim()
                if (cl.startsWith("[")) {
                    val closeBracket = cl.indexOf("]")
                    if (closeBracket > 1) {
                        val timePart = cl.substring(1, closeBracket)
                        val textPart = cl.substring(closeBracket + 1).trim()
                        if (textPart.isNotBlank()) {
                            val ms = lrcTimeToMs(timePart)
                            if (ms >= 0) map[ms] = textPart
                        }
                    }
                }
            }
        }
        return map
    }

    private fun lrcTimeToMs(timeStr: String): Long {
        return runCatching {
            val parts = timeStr.split(":")
            if (parts.size >= 2) {
                val min = parts[0].toLongOrNull() ?: 0L
                val secParts = parts[1].split(".")
                val sec = secParts[0].toLongOrNull() ?: 0L
                var ms = 0L
                if (secParts.size > 1) {
                    val msStr = secParts[1].trim()
                    ms = if (msStr.length == 2) (msStr.toLongOrNull() ?: 0L) * 10 else (msStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
                }
                min * 60000L + sec * 1000L + ms
            } else -1L
        }.getOrDefault(-1L)
    }

    private fun findMatchedTranslation(lineStartMs: Long, transMap: Map<Long, String>): String? {
        if (transMap.isEmpty()) return null
        return transMap.entries.firstOrNull { Math.abs(it.key - lineStartMs) <= 1500L }?.value
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    }

    private fun msToTimeStr(ms: Long): String {
        var seconds = ms / 1000
        val remMs = ms % 1000
        val minutes = (seconds / 60) % 60
        val hours = seconds / 3600
        seconds %= 60
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, remMs)
    }

    private fun timeStrToMs(timeStr: String): Long {
        return runCatching {
            var ms = 0L
            val parts = timeStr.split(":")
            if (parts.size == 3) {
                ms += (parts[0].toLongOrNull() ?: 0L) * 3600000L
                ms += (parts[1].toLongOrNull() ?: 0L) * 60000L
                val secParts = parts[2].split(".")
                ms += (secParts[0].toLongOrNull() ?: 0L) * 1000L
                if (secParts.size > 1) ms += secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            } else if (parts.size == 2) {
                ms += (parts[0].toLongOrNull() ?: 0L) * 60000L
                val secParts = parts[1].split(".")
                ms += (secParts[0].toLongOrNull() ?: 0L) * 1000L
                if (secParts.size > 1) ms += secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            } else if (parts.size == 1) {
                val secParts = parts[0].split(".")
                ms += (secParts[0].toLongOrNull() ?: 0L) * 1000L
                if (secParts.size > 1) ms += secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            }
            ms
        }.getOrDefault(0L)
    }

    private fun httpGet(urlString: String): String? {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (urlString.contains("163.com")) conn.setRequestProperty("Referer", "https://music.163.com/")
            else if (urlString.contains("qq.com")) conn.setRequestProperty("Referer", "https://y.qq.com/")
            if (conn.responseCode == 200) return conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {}
        return null
    }

    private fun fetchAppleOfficial(trackId: String, country: String): String? {
        val url = URL("https://amp-api.music.apple.com/v1/catalog/$country/songs/$trackId/lyrics")
        try {
            val conn = url.openConnection() as HttpURLConnection
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Authorization", "Bearer $APPLE_TOKEN")
            conn.setRequestProperty("Origin", "https://music.apple.com")
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                return json.optJSONArray("data")?.getJSONObject(0)?.optJSONObject("attributes")?.optString("ttml")
            }
        } catch (e: Exception) {}
        return null
    }

    private fun fetchQqQrc(songObj: JSONObject): Pair<String?, String?> {
        try {
            val songName = songObj.optString("songname")
            val artist = songObj.optJSONArray("singer")?.optJSONObject(0)?.optString("name") ?: ""
            val album = songObj.optString("albumname")
            val duration = songObj.optInt("interval", 0)
            val songId = songObj.optInt("songid", 0)

            val paramObj = JSONObject().apply {
                put("albumName", Base64.encodeToString(album.toByteArray(), Base64.NO_WRAP))
                put("crypt", 1); put("ct", 19); put("cv", 2111)
                put("interval", duration)
                put("lrc_t", 0); put("qrc", 1); put("qrc_t", 0); put("roma", 1); put("roma_t", 0)
                put("singerName", Base64.encodeToString(artist.toByteArray(), Base64.NO_WRAP))
                put("songID", songId)
                put("songName", Base64.encodeToString(songName.toByteArray(), Base64.NO_WRAP))
                put("trans", 1); put("trans_t", 0); put("type", 0)
            }
            
            val reqObj = JSONObject().apply {
                put("module", "music.musichallSong.PlayLyricInfo")
                put("method", "GetPlayLyricInfo")
                put("param", paramObj)
            }

            val bodyObj = JSONObject().apply {
                put("comm", JSONObject().apply { put("ct", 19); put("cv", 2111) })
                put("request", reqObj)
            }

            val url = URL("https://u.y.qq.com/cgi-bin/musicu.fcg")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(bodyObj.toString().toByteArray())

            if (conn.responseCode == 200) {
                val resJson = JSONObject(conn.inputStream.bufferedReader().readText())
                val dataObj = resJson.optJSONObject("request")?.optJSONObject("data")
                return Pair(dataObj?.optString("lyric"), dataObj?.optString("trans"))
            }
        } catch (e: Exception) {}
        return Pair(null, null)
    }

    private fun decryptQrc(hexStr: String): String? {
        try {
            val decodedBytes = hexStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val decryptedBytes = QQMusicDES.decrypt(decodedBytes)
            
            val inflater = Inflater()
            inflater.setInput(decryptedBytes)
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) break
                outputStream.write(buffer, 0, count)
            }
            outputStream.close()
            return outputStream.toString("UTF-8")
        } catch (e: Exception) {
            Log.e(TAG, "Native QQMusicDES Decrypt Error", e)
        }
        return null
    }
}