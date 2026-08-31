package com.mardous.booming.data.local.lyrics.ttml

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.media.isArtistNameUnknown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

object MetadataFetcher {
    private const val TAG = "MetadataFetcher"

    data class FetchResult(
        val lrcWithTrans: String?,
        val coverBytes: ByteArray?
    )

    private data class MatchResult(val platform: Int, val score: Int, val albumMid: String? = null, val songMid: String? = null, val songId: Long = 0L)

    private fun normalizeStr(input: String?): String {
        if (input == null) return ""
        return input.lowercase().replace(Regex("""[^\w\u4e00-\u9fa5]"""), "")
    }

    suspend fun fetchMetadata(song: Song, needLrc: Boolean, needCover: Boolean, context: Context? = null): FetchResult {
        val artist = if (song.isArtistNameUnknown()) "" else song.artistName
        return fetchMetadataRaw(context, song.title, artist, song.albumName ?: "", song.duration, needLrc, needCover)
    }

    suspend fun fetchMetadataRaw(context: Context? = null, title: String, artist: String, album: String, duration: Long, needLrc: Boolean, needCover: Boolean): FetchResult = withContext(Dispatchers.IO) {
        val cleanTitle = title.replace(Regex("""^\s*\d{1,4}\s*[-_.]?\s*"""), "").replace(Regex("""\(.*?\)|\[.*?\]|【.*?】"""), "").trim()
        val primaryArtist = artist.split(Regex("""[/,&、;+]|\band\b""")).firstOrNull()?.trim() ?: ""

        val strictQuery = "$primaryArtist $cleanTitle $album".trim()
        val looseQuery = "$primaryArtist $cleanTitle".trim()
        val loosestQuery = cleanTitle

        var bestNetease: MatchResult? = null
        var bestQQ: MatchResult? = null

        val (n1, q1) = racePlatforms(context, strictQuery, title, artist, album, duration)
        bestNetease = n1; bestQQ = q1

        if (bestNetease == null && bestQQ == null && strictQuery != looseQuery) {
            val (n2, q2) = racePlatforms(context, looseQuery, title, artist, album, duration)
            if (n2 != null) bestNetease = n2
            if (q2 != null) bestQQ = q2
        }

        if (bestNetease == null && bestQQ == null && looseQuery != loosestQuery) {
            val (n3, q3) = racePlatforms(context, loosestQuery, title, artist, album, duration)
            if (n3 != null) bestNetease = n3
            if (q3 != null) bestQQ = q3
        }

        var finalLrc: String? = null
        var finalCover: ByteArray? = null

        if (needLrc) {
            if (bestNetease != null) finalLrc = getNeteaseLrc(context, bestNetease.songId)
            if (finalLrc.isNullOrBlank() && bestQQ != null) finalLrc = getQQLrc(context, bestQQ.songMid!!)
        }

        if (needCover) {
            if (bestQQ != null) finalCover = getQQCover(bestQQ.albumMid!!)
            if (finalCover == null && bestNetease != null) finalCover = getNeteaseCover(context, bestNetease.songId)
        }

        return@withContext FetchResult(finalLrc, finalCover)
    }

    private suspend fun racePlatforms(context: Context?, query: String, title: String, artist: String, album: String, duration: Long): Pair<MatchResult?, MatchResult?> = coroutineScope {
        val nTask = async { fetchNeteaseMeta(context, query, title, artist, album, duration) }
        val qTask = async { fetchQQMeta(context, query, title, artist, album, duration) }
        Pair(nTask.await(), qTask.await())
    }

    private suspend fun fetchNeteaseMeta(context: Context?, query: String, localTitle: String, localArtist: String, localAlbum: String, localDur: Long): MatchResult? {
        val publicUrl = "https://music.163.com/api/search/get/web?s=${Uri.encode(query)}&type=1&limit=15"
        var res = httpGet(publicUrl, timeoutMs = 4000)
        var songs = runCatching { JSONObject(res ?: "").optJSONObject("result")?.optJSONArray("songs") }.getOrNull()
        
        // 🛡️ 网易云：主从双活降级兜底
        if (songs == null || songs.length() == 0) {
            val baseUrl = context?.let { com.mardous.booming.data.network.ApiConfigManager.getNeteaseBaseUrl(it) } ?: com.mardous.booming.data.network.ApiConfigManager.DEFAULT_NETEASE_DOMAIN
            res = httpGet("$baseUrl/search?keywords=${Uri.encode(query)}&type=1&limit=15", timeoutMs = 45000)
            songs = runCatching { JSONObject(res ?: "").optJSONObject("result")?.optJSONArray("songs") }.getOrNull() ?: return null
        }
        
        val validItems = mutableListOf<MatchResult>()
        for (i in 0 until songs.length()) {
            val item = songs.getJSONObject(i)
            val rArtist = (0 until (item.optJSONArray("artists")?.length() ?: 0)).joinToString("") { item.optJSONArray("artists")?.getJSONObject(it)?.optString("name") ?: "" }
            val score = calculateMatchScore(localTitle, localArtist, localAlbum, localDur, item.optString("name"), rArtist, item.optJSONObject("album")?.optString("name") ?: "", item.optLong("duration", 0L))
            if (score > 0) validItems.add(MatchResult(2, score, songId = item.optLong("id", 0L)))
        }
        return validItems.maxByOrNull { it.score }
    }

    private suspend fun fetchQQMeta(context: Context?, query: String, localTitle: String, localArtist: String, localAlbum: String, localDur: Long): MatchResult? {
        val publicUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=15&p=1&w=${Uri.encode(query)}"
        var resStr = httpGet(publicUrl, timeoutMs = 4000)
        var start = resStr?.indexOf("{") ?: -1
        var end = resStr?.lastIndexOf("}") ?: -1
        
        var list = if (start != -1 && end != -1) runCatching { JSONObject(resStr!!.substring(start, end + 1)).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") }.getOrNull() else null
        
        // 🛡️ QQ 音乐：主从双活降级兜底（当公开接口被阻断时，瞬间唤醒 QQ Render 私有节点）
        if (list == null || list.length() == 0) {
            val qqBaseUrl = context?.let { com.mardous.booming.data.network.ApiConfigManager.getQqBaseUrl(it) } ?: com.mardous.booming.data.network.ApiConfigManager.DEFAULT_QQ_DOMAIN
            Log.w(TAG, "QQ音乐搜索遭遇拦截，切换至自定义/Render私有服并宽限 45 秒冷启动...")
            resStr = httpGet("$qqBaseUrl/search?key=${Uri.encode(query)}&limit=15", timeoutMs = 45000)
            list = runCatching { JSONObject(resStr ?: "").optJSONObject("data")?.optJSONArray("list") }.getOrNull() ?: return null
        }
        
        val validItems = mutableListOf<MatchResult>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val rArtist = (0 until (item.optJSONArray("singer")?.length() ?: 0)).joinToString("") { item.optJSONArray("singer")?.getJSONObject(it)?.optString("name") ?: "" }
            val score = calculateMatchScore(localTitle, localArtist, localAlbum, localDur, item.optString("songname"), rArtist, item.optString("albumname"), item.optLong("interval", 0L) * 1000L)
            val albumMid = item.optString("albummid").takeIf { it.isNotBlank() } ?: item.optString("album_mid")
            if (score > 0) validItems.add(MatchResult(3, score, albumMid = albumMid, songMid = item.optString("songmid")))
        }
        return validItems.maxByOrNull { it.score }
    }

    private fun calculateMatchScore(localTitle: String, localArtist: String, localAlbum: String, localDur: Long, rTitle: String, rArtist: String, rAlbum: String, rDurMs: Long): Int {
        val normLt = normalizeStr(localTitle)
        val normRt = normalizeStr(rTitle)
        if (normLt.isEmpty() || normRt.isEmpty()) return -1
        val titleIntersect = normLt.contains(normRt) || normRt.contains(normLt)
        val localArtists = localArtist.split(Regex("""[/,&、;+]|\band\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b""")).map { normalizeStr(it) }.filter { it.isNotEmpty() }
        val normRa = normalizeStr(rArtist)
        var artistMatch = false
        if (localArtists.isEmpty()) {
            artistMatch = true
        } else {
            val primary = localArtists[0]
            if (normRa.contains(primary) || primary.contains(normRa)) artistMatch = true
            else if (localArtists.any { normRa.contains(it) || it.contains(normRa) }) artistMatch = true
        }
        val durDiff = if (localDur > 0L && rDurMs > 0L) abs(localDur - rDurMs) else 999999L
        val durMatch = localDur > 0L && rDurMs > 0L && durDiff <= 5000L
        if (durMatch && titleIntersect && artistMatch) return 350
        if (!titleIntersect || !artistMatch) return -1
        var score = 100
        if (normLt == normRt) score += 400 else score += 50
        val mixKws = Regex("remix|mix|dj|版|extended|club|edit|live|现场", RegexOption.IGNORE_CASE)
        val ltFull = "$localTitle $localAlbum"
        val lRemix = mixKws.containsMatchIn(ltFull)
        val rRemix = mixKws.containsMatchIn(rTitle)
        if (lRemix != rRemix) return -1
        if (localDur > 0L && rDurMs > 0L) {
            if (durDiff <= 3500L) score += (1000L - durDiff).toInt()
            else if (durDiff <= 8000L) score += (400L - durDiff).toInt()
            else if (durDiff <= 15000L) score -= 200
            else return -1
        }
        val normLaAlb = normalizeStr(localAlbum)
        val normRaAlb = normalizeStr(rAlbum)
        if (normLaAlb.isNotEmpty() && normRaAlb.isNotEmpty()) {
            if (normLaAlb == normRaAlb) score += 500
            else if (normLaAlb.contains(normRaAlb) || normRaAlb.contains(normLaAlb)) score += 200
        }
        return score
    }

    private suspend fun getQQCover(albumMid: String): ByteArray? {
        val resolutions = listOf(800, 600, 500, 300)
        for (res in resolutions) {
            val url = "https://y.qq.com/music/photo_new/T002R${res}x${res}M000${albumMid}.jpg"
            val bytes = httpGetBytes(url)
            if (bytes != null && bytes.size > 5000) return bytes 
        }
        return null
    }

    private suspend fun getNeteaseCover(context: Context?, songId: Long): ByteArray? {
        var res = httpGet("https://music.163.com/api/song/detail?ids=[$songId]", timeoutMs = 4000)
        var picUrl = runCatching { JSONObject(res ?: "").optJSONArray("songs")?.getJSONObject(0)?.optJSONObject("al")?.optString("picUrl") }.getOrNull()
        
        // 🛡️ 封面被封锁时智能切换 Render
        if (picUrl.isNullOrBlank()) {
            val baseUrl = context?.let { com.mardous.booming.data.network.ApiConfigManager.getNeteaseBaseUrl(it) } ?: com.mardous.booming.data.network.ApiConfigManager.DEFAULT_NETEASE_DOMAIN
            res = httpGet("$baseUrl/song/detail?ids=$songId", timeoutMs = 45000)
            picUrl = runCatching { JSONObject(res ?: "").optJSONArray("songs")?.getJSONObject(0)?.optJSONObject("al")?.optString("picUrl") }.getOrNull()
        }
        
        if (!picUrl.isNullOrBlank()) return httpGetBytes(picUrl)
        return null
    }

    private suspend fun getNeteaseLrc(context: Context?, songId: Long): String? {
        var res = httpGet("https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1", timeoutMs = 4000)
        var obj = runCatching { JSONObject(res ?: "") }.getOrNull()
        
        if (obj == null || (!obj.has("lrc") && !obj.has("tlyric"))) {
            val baseUrl = context?.let { com.mardous.booming.data.network.ApiConfigManager.getNeteaseBaseUrl(it) } ?: com.mardous.booming.data.network.ApiConfigManager.DEFAULT_NETEASE_DOMAIN
            res = httpGet("$baseUrl/song/lyric?id=$songId", timeoutMs = 45000)
            obj = runCatching { JSONObject(res ?: "") }.getOrNull() ?: return null
        }
        
        val lrc = obj.optJSONObject("lrc")?.optString("lyric")
        val trans = obj.optJSONObject("tlyric")?.optString("lyric")
        return mergeLrcInterleave(lrc, trans)
    }

    private suspend fun getQQLrc(context: Context?, songMid: String): String? {
        val publicUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songMid&format=json&nobase64=1"
        var res = httpGet(publicUrl, timeoutMs = 4000)
        var obj = runCatching { JSONObject(res ?: "") }.getOrNull()
        
        // 🛡️ QQ 歌词接口报错或拦截时智能切换 Render
        if (obj == null || !obj.has("lyric")) {
            val qqBaseUrl = context?.let { com.mardous.booming.data.network.ApiConfigManager.getQqBaseUrl(it) } ?: com.mardous.booming.data.network.ApiConfigManager.DEFAULT_QQ_DOMAIN
            res = httpGet("$qqBaseUrl/lyric?songmid=$songMid", timeoutMs = 45000)
            // QQMusicApi 通常将数据包在 data 层
            obj = runCatching { JSONObject(res ?: "").optJSONObject("data") }.getOrNull() ?: return null
        }
        
        val lrc = obj.optString("lyric")
        val transB64 = obj.optString("trans")
        val trans = if (transB64.isNotBlank()) String(Base64.decode(transB64, Base64.NO_WRAP), Charsets.UTF_8) else null
        return mergeLrcInterleave(lrc, trans)
    }

    private fun mergeLrcInterleave(lrc: String?, trans: String?): String? {
        if (lrc.isNullOrBlank()) return null
        val origLines = mutableListOf<Pair<Long, String>>()
        val headerLines = mutableListOf<String>()

        lrc.split("\n").forEach { line ->
            val trim = line.trim()
            if (trim.isEmpty()) return@forEach
            val match = Regex("""^\[(\d{2,}:\d{2}(?:\.\d{1,3})?)\](.*)""").find(trim)
            if (match != null) {
                val ms = timeStrToMs(match.groupValues[1])
                origLines.add(Pair(ms, trim))
            } else if (trim.startsWith("[") && trim.contains(":")) {
                headerLines.add(trim)
            }
        }
        origLines.sortBy { it.first }
        
        val transMap = mutableMapOf<Long, String>()
        if (!trans.isNullOrBlank()) {
            trans.split("\n").forEach { line ->
                val match = Regex("""^\[(\d{2,}:\d{2}(?:\.\d{1,3})?)\](.*)""").find(line.trim())
                if (match != null && match.groupValues[2].isNotBlank()) {
                    transMap[timeStrToMs(match.groupValues[1])] = match.groupValues[2].trim()
                }
            }
        }

        val result = StringBuilder()
        headerLines.forEach { result.appendLine(it) }

        origLines.forEach { (ms, lineText) ->
            result.appendLine(lineText)
            val matchedTrans = transMap.entries.firstOrNull { abs(it.key - ms) <= 1500L }?.value
            if (matchedTrans != null) {
                val tag = lineText.substringBefore("]") + "]"
                result.appendLine("$tag$matchedTrans")
            }
        }
        return result.toString().trimEnd()
    }

    private fun timeStrToMs(timeStr: String): Long {
        return runCatching {
            val parts = timeStr.split(":")
            val min = parts[0].toLongOrNull() ?: 0L
            val secParts = parts[1].split(".")
            val sec = secParts[0].toLongOrNull() ?: 0L
            var ms = 0L
            if (secParts.size > 1) {
                val msStr = secParts[1].trim()
                ms = if (msStr.length == 2) (msStr.toLongOrNull() ?: 0L) * 10 else (msStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
            }
            min * 60000L + sec * 1000L + ms
        }.getOrDefault(-1L)
    }

    private suspend fun httpGet(urlString: String, timeoutMs: Int = 10000): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.readTimeout = timeoutMs
            conn.connectTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (urlString.contains("163.com")) conn.setRequestProperty("Referer", "https://music.163.com/")
            else if (urlString.contains("qq.com")) conn.setRequestProperty("Referer", "https://y.qq.com/")
            if (conn.responseCode == 200) {
                return@withContext conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
        } finally {
            conn?.disconnect()
        }
        null
    }

    private suspend fun httpGetBytes(urlString: String): ByteArray? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.readTimeout = 15000
            conn.connectTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                return@withContext conn.inputStream.use { it.readBytes() }
            }
        } catch (e: Exception) {
        } finally {
            conn?.disconnect()
        }
        null
    }
}