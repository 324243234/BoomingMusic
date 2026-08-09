package com.mardous.booming.data.local.lyrics.ttml

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.media.isArtistNameUnknown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Inflater

/**
 * TTML 级联网络获取引擎 (增强版：严格匹配+数据清洗)
 * 寻源路径：Apple Music (CN/US) -> AMLL DB (Apple) -> QQ 音乐搜索 -> AMLL DB (QQ) -> QQ 音乐 QRC -> QRC 解密并转 TTML
 */
object TtmlFetcher {

    private const val TAG = "TtmlFetcher"
    // Apple Music 临时 fallback Token
    private const val APPLE_TOKEN = "eyJhbGciOiJFUzI1NiIsImtpZCI6MldVTUZPQjA2MyJ9.eyJpc3MiOiJBNTZEUjg1TTRTIiwiaWF0IjoxNTc4NTI2NzI2LCJleHAiOjE3NzA0MzYzMjZ9.S6x2XGf7OqS6cZJ_3eG0W8gA4vN4aT3q9Z1aW3bX5cY"
    
    // 🌟 1. 清洗标题：去除开头的序号(如 01, 00 - ) 及括号内的无用后缀 (如 (Live), 【Remix】)
    private fun cleanTitle(title: String?): String {
        if (title == null) return ""
        var t = title
        t = t.replace(Regex("""^\s*\d{1,4}\s*[-_.]?\s*"""), "")
        t = t.replace(Regex("""\(.*?\)|\（.*?\）|\[.*?\]|\【.*?\】"""), "")
        return t.trim()
    }

    // 🌟 2. 标准化字符串：仅保留字母、数字、中文，并全部转为小写，用于严格比对
    private fun normalizeStr(input: String?): String {
        if (input == null) return ""
        return input.lowercase().replace(Regex("""[^\w\u4e00-\u9fa5]"""), "")
    }

    suspend fun fetchTtmlForSong(song: Song): String? = withContext(Dispatchers.IO) {
        // 数据清洗
        val rawTitle = cleanTitle(song.title)
        val rawArtist = if (song.isArtistNameUnknown()) "" else song.artistName
        
        // 生成最终用于比较的标准形态
        val targetTitleNorm = normalizeStr(rawTitle)
        val targetArtistNorm = normalizeStr(rawArtist)
        
        if (targetTitleNorm.isEmpty()) return@withContext null

        // 构建搜索词
        val searchQuery = if (rawArtist.isBlank()) rawTitle else "$rawArtist $rawTitle"
        val cleanQuery = searchQuery.replace(Regex("""[-_／/]"""), " ").replace(Regex("""\s+"""), " ").trim()

        try {
            // ========================================================
            // 阶段一：尝试 Apple Music (CN & US) 与 AMLL (Apple ID)
            // ========================================================
            for (country in listOf("cn", "us")) {
                val searchUrl = "https://itunes.apple.com/search?term=${Uri.encode(cleanQuery)}&entity=song&limit=5&country=$country"
                val searchRes = httpGet(searchUrl)
                if (searchRes != null) {
                    val json = JSONObject(searchRes)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        for (i in 0 until results.length()) {
                            val item = results.getJSONObject(i)
                            val trackName = item.optString("trackName")
                            val artistName = item.optString("artistName")
                            val trackId = item.optString("trackId")

                            // 🌟 严格校验机制：校验结果是否真正匹配目标
                            val normTrack = normalizeStr(trackName)
                            val normArtist = normalizeStr(artistName)
                            val titleMatch = normTrack.contains(targetTitleNorm) || targetTitleNorm.contains(normTrack)
                            val artistMatch = targetArtistNorm.isEmpty() || normArtist.contains(targetArtistNorm) || targetArtistNorm.contains(normArtist)

                            if (titleMatch && artistMatch && trackId.isNotBlank()) {
                                // 尝试 Apple 官方 API
                                val appleTtml = fetchAppleOfficial(trackId, country)
                                if (!appleTtml.isNullOrBlank() && appleTtml.length > 50) return@withContext appleTtml
                                
                                // 尝试 AMLL 数据库 (Apple ID)
                                val amllAppleTtml = httpGet("https://amlldb.bikonoo.com/qq-lyrics/$trackId.ttml")
                                if (!amllAppleTtml.isNullOrBlank() && amllAppleTtml.length > 50) return@withContext amllAppleTtml
                                
                                // 一旦匹配到目标歌曲但均获取失败，跳出当前循环寻找下一首
                                break 
                            }
                        }
                    }
                }
            }

            // ========================================================
            // 阶段二：尝试 QQ 音乐搜索与 AMLL (QQ ID) / 官方 QRC
            // ========================================================
            val qqSearchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=5&p=1&w=${Uri.encode(cleanQuery)}"
            val qqSearchRes = httpGet(qqSearchUrl)
            if (qqSearchRes != null) {
                val start = qqSearchRes.indexOf("{")
                val end = qqSearchRes.lastIndexOf("}")
                if (start != -1 && end != -1) {
                    val jsonStr = qqSearchRes.substring(start, end + 1)
                    val qqJson = JSONObject(jsonStr)
                    val list = qqJson.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                    if (list != null && list.length() > 0) {
                        for (i in 0 until list.length()) {
                            val item = list.getJSONObject(i)
                            val songName = item.optString("songname")
                            val artistName = item.optJSONArray("singer")?.optJSONObject(0)?.optString("name") ?: ""
                            
                            // 🌟 严格校验机制：校验结果是否真正匹配目标
                            val normTrack = normalizeStr(songName)
                            val normArtist = normalizeStr(artistName)
                            val titleMatch = normTrack.contains(targetTitleNorm) || targetTitleNorm.contains(normTrack)
                            val artistMatch = targetArtistNorm.isEmpty() || normArtist.contains(targetArtistNorm) || targetArtistNorm.contains(normArtist)

                            if (titleMatch && artistMatch) {
                                val songmid = item.optString("songmid")
                                
                                if (songmid.isNotBlank()) {
                                    // 尝试 AMLL 数据库 (QQ MID)
                                    val amllQqTtml = httpGet("https://amlldb.bikonoo.com/qq-lyrics/$songmid.ttml")
                                    if (!amllQqTtml.isNullOrBlank() && amllQqTtml.length > 50) return@withContext amllQqTtml
                                    
                                    // 尝试抓取 QQ 音乐官方 QRC 并解密转码
                                    val qrcHex = fetchQqQrc(item)
                                    if (!qrcHex.isNullOrBlank()) {
                                        val rawQrc = decryptQrc(qrcHex)
                                        if (!rawQrc.isNullOrBlank()) {
                                            val ttml = parseQrcToTtmlDirectly(rawQrc)
                                            if (!ttml.isNullOrBlank()) return@withContext ttml
                                        }
                                    }
                                }
                                break // 找到严格匹配的条目后，不再检查其他无关条目
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

    private fun fetchAppleOfficial(trackId: String, country: String): String? {
        val url = URL("https://amp-api.music.apple.com/v1/catalog/$country/songs/$trackId/lyrics")
        try {
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Authorization", "Bearer $APPLE_TOKEN")
            conn.setRequestProperty("Origin", "https://music.apple.com")
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    return data.getJSONObject(0).optJSONObject("attributes")?.optString("ttml")
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun fetchQqQrc(songObj: JSONObject): String? {
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
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(bodyObj.toString().toByteArray())

            if (conn.responseCode == 200) {
                val resJson = JSONObject(conn.inputStream.bufferedReader().readText())
                return resJson.optJSONObject("request")?.optJSONObject("data")?.optString("lyric")
            }
        } catch (e: Exception) {}
        return null
    }

    private fun decryptQrc(hexStr: String): String? {
        try {
            val decodedBytes = hexStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val decryptedBytes = QQMusicDES.decrypt(decodedBytes)
            
            val inflater = java.util.zip.Inflater()
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

    private fun parseQrcToTtmlDirectly(rawQrcText: String): String? {
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

                val safeText = w.first.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                if (safeText.isNotEmpty() && w.third > w.second) {
                    spans.add("        <span begin=\"${msToTimeStr(w.second)}\" end=\"${msToTimeStr(w.third)}\">$safeText</span>")
                }
            }

            if (spans.isNotEmpty()) {
                ttmlParagraphs.add(
                    "      <p begin=\"${msToTimeStr(lineStart)}\" end=\"${msToTimeStr(lineEnd)}\">\n" +
                    spans.joinToString("\n") +
                    "\n      </p>"
                )
            }
        }

        if (ttmlParagraphs.isEmpty()) return null

        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
               "<tt xmlns=\"http://www.w3.org/ns/ttml\">\n" +
               "  <body>\n" +
               "    <div>\n" +
               ttmlParagraphs.joinToString("\n") +
               "\n    </div>\n" +
               "  </body>\n" +
               "</tt>"
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
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.setRequestProperty("Referer", "https://y.qq.com/")
            if (conn.responseCode == 200) {
                return conn.inputStream.bufferedReader().readText()
            }
        } catch (e: Exception) {}
        return null
    }
}