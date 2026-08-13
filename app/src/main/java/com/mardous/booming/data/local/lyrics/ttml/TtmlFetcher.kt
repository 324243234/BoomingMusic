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
 * TTML 级联网络获取引擎 (双语逐字增强版 - 支持单独翻译开关)
 * 优先级: Apple Music -> 网易云音乐 -> QQ音乐
 */
object TtmlFetcher {

    private const val TAG = "TtmlFetcher"
    private const val APPLE_TOKEN = "eyJhbGciOiJFUzI1NiIsImtpZCI6MldVTUZPQjA2MyJ9.eyJpc3MiOiJBNTZEUjg1TTRTIiwiaWF0IjoxNTc4NTI2NzI2LCJleHAiOjE3NzA0MzYzMjZ9.S6x2XGf7OqS6cZJ_3eG0W8gA4vN4aT3q9Z1aW3bX5cY"

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

    suspend fun fetchTtmlForSong(song: Song): String? = withContext(Dispatchers.IO) {
        val rawTitle = cleanTitle(song.title)
        val rawArtist = if (song.isArtistNameUnknown()) "" else song.artistName
        val rawAlbum = song.albumName ?: ""
        val localDurationMs = song.duration 
        
        val targetTitleNorm = normalizeStr(rawTitle)
        val targetAlbumNorm = normalizeStr(rawAlbum)
        val targetArtists = rawArtist.split(Regex("""&|,|、|/|与|和|feat\.?|ft\.?"""))
            .map { normalizeStr(it) }
            .filter { it.isNotEmpty() }

        if (targetTitleNorm.isEmpty()) return@withContext null

        val searchQuery = if (rawArtist.isBlank()) rawTitle else "$rawArtist $rawTitle"
        val cleanQuery = searchQuery.replace(Regex("""[-_／/]"""), " ").replace(Regex("""\s+"""), " ").trim()

        val isLocalLive = song.title.contains("live", ignoreCase = true) || song.title.contains("现场")
        val isLocalRemix = song.title.contains("remix", ignoreCase = true) || song.title.contains("dj", ignoreCase = true) || song.title.contains("版")

        try {
            // ========================================================
            // 通道一：Apple Music (CN & US) + AMLL DB 并发获取
            // ========================================================
            for (country in listOf("cn", "us")) {
                val searchUrl = "https://itunes.apple.com/search?term=${Uri.encode(cleanQuery)}&entity=song&limit=5&country=$country"
                val searchRes = httpGet(searchUrl)
                if (searchRes != null) {
                    val results = JSONObject(searchRes).optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        for (i in 0 until results.length()) {
                            val item = results.getJSONObject(i)
                            val trackName = item.optString("trackName")
                            val trackId = item.optString("trackId")
                            val remoteDurationMs = item.optLong("trackTimeMillis", 0L)
                            val remoteAlbumName = item.optString("collectionName")
                            
                            val isRemoteLive = trackName.contains("live", ignoreCase = true) || trackName.contains("现场")
                            val isRemoteRemix = trackName.contains("remix", ignoreCase = true) || trackName.contains("dj", ignoreCase = true) || trackName.contains("版")
                            
                            if (!isLocalLive && isRemoteLive) continue
                            if (!isLocalRemix && isRemoteRemix) continue

                            val normTrack = normalizeStr(trackName)
                            val isTitleMatch = normTrack.contains(targetTitleNorm) || targetTitleNorm.contains(normTrack)
                            
                            if (isTitleMatch && trackId.isNotBlank()) {
                                val isDurationMatch = Math.abs(localDurationMs - remoteDurationMs) <= 3000L
                                val normRemoteAlbum = normalizeStr(remoteAlbumName)
                                val isAlbumMatch = targetAlbumNorm.isEmpty() || normRemoteAlbum.contains(targetAlbumNorm) || targetAlbumNorm.contains(normRemoteAlbum)

                                if (isDurationMatch || (remoteDurationMs == 0L && isAlbumMatch)) {
                                    val ttmlResult = coroutineScope {
                                        val tasks = listOf(
                                            async { fetchAppleOfficial(trackId, country) },
                                            async { httpGet("https://amlldb.bikonoo.com/qq-lyrics/$trackId.ttml") }
                                        )
                                        tasks.awaitAll().firstOrNull { !it.isNullOrBlank() && it.length > 50 }
                                    }
                                    if (ttmlResult != null) return@withContext ttmlResult
                                    break
                                }
                            }
                        }
                    }
                }
            }

            // ========================================================
            // 通道二：网易云音乐搜索 + YRC (逐字) + Tlyric (翻译) 挂载
            // ========================================================
            val neteaseSearchUrl = "https://music.163.com/api/search/suggest/web?s=${Uri.encode(cleanQuery)}"
            val neteaseSearchRes = httpGet(neteaseSearchUrl)
            if (neteaseSearchRes != null) {
                val songs = JSONObject(neteaseSearchRes).optJSONObject("result")?.optJSONArray("songs")
                if (songs != null && songs.length() > 0) {
                    for (i in 0 until songs.length()) {
                        val item = songs.getJSONObject(i)
                        val songId = item.optInt("id", 0)
                        val songName = item.optString("name")
                        val artists = item.optJSONArray("artists")
                        val remoteArtistStr = if (artists != null) {
                            (0 until artists.length()).joinToString("") { normalizeStr(artists.getJSONObject(it).optString("name")) }
                        } else ""
                        
                        val isRemoteLive = songName.contains("live", ignoreCase = true) || songName.contains("现场")
                        val isRemoteRemix = songName.contains("remix", ignoreCase = true) || songName.contains("dj", ignoreCase = true) || songName.contains("版")

                        if (!isLocalLive && isRemoteLive) continue
                        if (!isLocalRemix && isRemoteRemix) continue

                        val normTrack = normalizeStr(songName)
                        val isTitleMatch = normTrack.contains(targetTitleNorm) || targetTitleNorm.contains(normTrack)
                        
                        var isArtistMatch = targetArtists.isEmpty()
                        if (targetArtists.isNotEmpty()) {
                            val primaryArtist = targetArtists[0]
                            val isPrimaryMatch = remoteArtistStr.contains(primaryArtist) || primaryArtist.contains(remoteArtistStr)
                            val hasAnyMatch = targetArtists.any { remoteArtistStr.contains(it) || it.contains(remoteArtistStr) }
                            isArtistMatch = isPrimaryMatch || hasAnyMatch
                        }

                        if (isTitleMatch && isArtistMatch && songId != 0) {
                            val yrcUrl = "https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1&yv=1"
                            val yrcRes = httpGet(yrcUrl)
                            if (yrcRes != null) {
                                val jsonObj = JSONObject(yrcRes)
                                val yrcData = jsonObj.optJSONObject("yrc")?.optString("lyric")
                                val tlyricData = jsonObj.optJSONObject("tlyric")?.optString("lyric")
                                
                                if (!yrcData.isNullOrBlank()) {
                                    val ttmlResult = parseYrcToTtml(yrcData, tlyricData)
                                    if (ttmlResult != null) return@withContext ttmlResult
                                }
                            }
                            break 
                        }
                    }
                }
            }

            // ========================================================
            // 通道三：QQ 音乐搜索 + (AMLL QQ DB / QRC 解密 + 翻译) 兜底
            // ========================================================
            val qqSearchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=5&p=1&w=${Uri.encode(cleanQuery)}"
            val qqSearchRes = httpGet(qqSearchUrl)
            if (qqSearchRes != null) {
                val start = qqSearchRes.indexOf("{")
                val end = qqSearchRes.lastIndexOf("}")
                if (start != -1 && end != -1) {
                    val qqJson = JSONObject(qqSearchRes.substring(start, end + 1))
                    val list = qqJson.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                    
                    if (list != null && list.length() > 0) {
                        for (i in 0 until list.length()) {
                            val item = list.getJSONObject(i)
                            val songName = item.optString("songname")
                            val remoteAlbumName = item.optString("albumname")
                            val remoteDurationMs = item.optLong("interval", 0L) * 1000L
                            val remoteArtistStr = item.optJSONArray("singer")?.let { singers ->
                                (0 until singers.length()).joinToString("") { normalizeStr(singers.getJSONObject(it).optString("name")) }
                            } ?: ""

                            val isRemoteLive = songName.contains("live", ignoreCase = true) || songName.contains("现场")
                            val isRemoteRemix = songName.contains("remix", ignoreCase = true) || songName.contains("dj", ignoreCase = true) || songName.contains("伴奏")
                            
                            if (!isLocalLive && isRemoteLive) continue
                            if (!isLocalRemix && isRemoteRemix) continue

                            val normTrack = normalizeStr(songName)
                            val isTitleMatch = normTrack.contains(targetTitleNorm) || targetTitleNorm.contains(normTrack)
                            
                            var isArtistMatch = targetArtists.isEmpty()
                            if (targetArtists.isNotEmpty()) {
                                val primaryArtist = targetArtists[0]
                                val isPrimaryMatch = remoteArtistStr.contains(primaryArtist) || primaryArtist.contains(remoteArtistStr)
                                val hasAnyMatch = targetArtists.any { remoteArtistStr.contains(it) || it.contains(remoteArtistStr) }
                                isArtistMatch = isPrimaryMatch || hasAnyMatch
                            }

                            if (isTitleMatch && isArtistMatch) {
                                val isDurationMatch = Math.abs(localDurationMs - remoteDurationMs) <= 3000L
                                val normRemoteAlbum = normalizeStr(remoteAlbumName)
                                val isAlbumMatch = targetAlbumNorm.isEmpty() || normRemoteAlbum.contains(targetAlbumNorm) || targetAlbumNorm.contains(normRemoteAlbum)

                                if (isDurationMatch || (remoteDurationMs == 0L && isAlbumMatch)) {
                                    val songmid = item.optString("songmid")
                                    if (songmid.isNotBlank()) {
                                        val ttmlResult = coroutineScope {
                                            val tasks = listOf(
                                                async { httpGet("https://amlldb.bikonoo.com/qq-lyrics/$songmid.ttml") },
                                                async { 
                                                    val (qrcHex, transBase64) = fetchQqQrc(item)
                                                    if (!qrcHex.isNullOrBlank()) {
                                                        val rawQrc = decryptQrc(qrcHex)
                                                        val transText = try {
                                                            if (!transBase64.isNullOrBlank()) {
                                                                String(Base64.decode(transBase64, Base64.NO_WRAP), Charsets.UTF_8)
                                                            } else null
                                                        } catch(e: Exception) { null }

                                                        if (!rawQrc.isNullOrBlank()) parseQrcToTtmlDirectly(rawQrc, transText) else null
                                                    } else null
                                                }
                                            )
                                            tasks.awaitAll().firstOrNull { !it.isNullOrBlank() && it.length > 50 }
                                        }
                                        if (ttmlResult != null) return@withContext ttmlResult
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Fetch TTML failed", e)
        }
        return@withContext null
    }

    // ========================================================
    // 工具层：YRC / TTML 互转及双语合成格式化引擎
    // ========================================================

    private fun parseYrcToTtml(yrcText: String, tlyricText: String? = null): String? {
        val transMap = parseLrcTranslations(tlyricText)
        val lines = yrcText.split(Regex("""\r?\n"""))
        val ttmlParagraphs = mutableListOf<String>()

        for (line in lines) {
            val cl = line.trim()
            if (cl.isEmpty()) continue

            val lineMatch = Regex("""^\[(\d+),\s*(\d+)\]\s*(.*)""").find(cl)
            if (lineMatch != null) {
                val lineStart = lineMatch.groupValues[1].toLong()
                val lineDur = lineMatch.groupValues[2].toLong()
                val content = lineMatch.groupValues[3].trim()
                
                val spans = mutableListOf<String>()

                if (content.startsWith("{") && content.endsWith("}")) {
                    try {
                        val cArray = JSONObject(content).optJSONArray("c")
                        if (cArray != null) {
                            for (i in 0 until cArray.length()) {
                                val wordObj = cArray.getJSONObject(i)
                                val tx = wordObj.optString("tx")
                                val t = wordObj.optLong("t")
                                val d = wordObj.optLong("d")
                                if (tx.isNotEmpty() && d > 0) {
                                    val safeText = escapeXml(tx)
                                    spans.add("        <span begin=\"${msToTimeStr(t)}\" end=\"${msToTimeStr(t + d)}\">$safeText</span>")
                                }
                            }
                        }
                    } catch (e: Exception) {}
                } else {
                    val wordPattern = Regex("""\((\d+),\s*(\d+)(?:,\d+)?\)([^\(]+)""")
                    wordPattern.findAll(content).forEach { match ->
                        val t = match.groupValues[1].toLong()
                        val d = match.groupValues[2].toLong()
                        val tx = match.groupValues[3]
                        val safeText = escapeXml(tx)
                        if (safeText.isNotEmpty() && d > 0) {
                            spans.add("        <span begin=\"${msToTimeStr(t)}\" end=\"${msToTimeStr(t + d)}\">$safeText</span>")
                        }
                    }
                }

                if (spans.isNotEmpty()) {
                    val pBuilder = StringBuilder()
                    pBuilder.append("      <p begin=\"${msToTimeStr(lineStart)}\" end=\"${msToTimeStr(lineStart + lineDur)}\">\n")
                    pBuilder.append(spans.joinToString("\n"))

                    // 🌟 注入翻译：带有 xmlns 与 ttm:role 完美兼容 TTML 解析器
                    val matchedTrans = findMatchedTranslation(lineStart, transMap)
                    if (!matchedTrans.isNullOrBlank()) {
                        pBuilder.append("\n        <span ttm:role=\"translation\">${escapeXml(matchedTrans)}</span>")
                    }

                    pBuilder.append("\n      </p>")
                    ttmlParagraphs.add(pBuilder.toString())
                }
            }
        }

        if (ttmlParagraphs.isEmpty()) return null

        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
               "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\">\n" +
               "  <body>\n    <div>\n" +
               ttmlParagraphs.joinToString("\n") +
               "\n    </div>\n  </body>\n</tt>"
    }

    private fun parseQrcToTtmlDirectly(rawQrcText: String, transText: String? = null): String? {
        val transMap = parseLrcTranslations(transText)
        var text = rawQrcText.trim()
        val xmlMatch = Regex("""LyricContent="([^"]+)"""", RegexOption.IGNORE_CASE).find(text)
        if (xmlMatch != null) text = xmlMatch.groupValues[1]

        text = text.replace(Regex("""&#(\d+);""")) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")

        val wordPattern = Regex("""([^\(\)\r\n]+)\((\d+),\s*(\d+)(?:,\d+)*\)""")
        if (!wordPattern.containsMatchIn(text)) return null

        val lines = text.split(Regex("""\r?\n"""))
        val ttmlParagraphs = mutableListOf<String>()

        for (line in lines) {
            var cl = line.replace(Regex("""^\[\d{2,}:\d{2}(?:\.\d+)?\]"""), "").trim()
            cl = cl.replace(Regex("""^\[\d+,\d+\]"""), "").trim()
            if (cl.isEmpty()) continue

            if (Regex("""^(词|曲|编曲|监制|混音|吉他|贝斯|和声|录音|发行|出品|OP|SP)[：:]""").containsMatchIn(cl)) continue

            val words = mutableListOf<Triple<String, Long, Long>>()
            wordPattern.findAll(cl).forEach { match ->
                var rawText = match.groupValues[1]
                rawText = rawText.replace(Regex("""\[\d+,\d+\]"""), "")
                val start = match.groupValues[2].toLong()
                val dur = match.groupValues[3].toLong()
                if (rawText.isNotEmpty()) words.add(Triple(rawText, start, start + dur))
            }

            if (words.isEmpty()) continue

            var lineStart = Long.MAX_VALUE
            var lineEnd = 0L
            val spans = mutableListOf<String>()

            for (w in words) {
                if (w.second < lineStart) lineStart = w.second
                if (w.third > lineEnd) lineEnd = w.third

                val safeText = escapeXml(w.first)
                if (safeText.isNotEmpty() && w.third > w.second) {
                    spans.add("        <span begin=\"${msToTimeStr(w.second)}\" end=\"${msToTimeStr(w.third)}\">$safeText</span>")
                }
            }

            if (spans.isNotEmpty()) {
                val pBuilder = StringBuilder()
                pBuilder.append("      <p begin=\"${msToTimeStr(lineStart)}\" end=\"${msToTimeStr(lineEnd)}\">\n")
                pBuilder.append(spans.joinToString("\n"))

                // 🌟 注入翻译
                val matchedTrans = findMatchedTranslation(lineStart, transMap)
                if (!matchedTrans.isNullOrBlank()) {
                    pBuilder.append("\n        <span ttm:role=\"translation\">${escapeXml(matchedTrans)}</span>")
                }

                pBuilder.append("\n      </p>")
                ttmlParagraphs.add(pBuilder.toString())
            }
        }

        if (ttmlParagraphs.isEmpty()) return null

        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
               "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\">\n" +
               "  <body>\n    <div>\n" +
               ttmlParagraphs.joinToString("\n") +
               "\n    </div>\n  </body>\n</tt>"
    }

    // --- 新增：LRC 翻译提取器，支持高达 1500ms 的轴对齐误差 ---
    private fun parseLrcTranslations(lrcText: String?): Map<Long, String> {
        if (lrcText.isNullOrBlank()) return emptyMap()
        val map = mutableMapOf<Long, String>()
        val linePattern = Regex("""^\[(\d{2,}):(\d{2})(?:\.(\d{2,3}))?\](.*)""")
        
        lrcText.split(Regex("""\r?\n""")).forEach { line ->
            val match = linePattern.find(line.trim())
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val msStr = match.groupValues[3]
                val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                val totalMs = min * 60000 + sec * 1000 + ms
                val text = match.groupValues[4].trim()
                if (text.isNotBlank()) {
                    map[totalMs] = text
                }
            }
        }
        return map
    }

    private fun findMatchedTranslation(lineStartMs: Long, transMap: Map<Long, String>): String? {
        if (transMap.isEmpty()) return null
        // 允许 +-1500ms 毫秒级别的误差容错匹配
        return transMap.entries.firstOrNull { Math.abs(it.key - lineStartMs) <= 1500L }?.value
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun msToTimeStr(ms: Long): String {
        var seconds = ms / 1000
        val remMs = ms % 1000
        val minutes = (seconds / 60) % 60
        val hours = seconds / 3600
        seconds %= 60
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, remMs)
    }

    private fun httpGet(urlString: String): String? {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            if (urlString.contains("163.com")) {
                conn.setRequestProperty("Referer", "https://music.163.com/")
            } else if (urlString.contains("qq.com")) {
                conn.setRequestProperty("Referer", "https://y.qq.com/")
            }
            
            if (conn.responseCode == 200) {
                return conn.inputStream.bufferedReader().readText()
            }
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
                val lyric = dataObj?.optString("lyric")
                val trans = dataObj?.optString("trans")
                return Pair(lyric, trans)
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