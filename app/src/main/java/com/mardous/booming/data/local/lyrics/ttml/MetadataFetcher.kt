package com.mardous.booming.data.local.lyrics.ttml

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.media.isArtistNameUnknown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * 静态封面与 LRC 歌词网络获取引擎
 * 完美复刻 Python 端的 3 级降级搜索策略与优中取优打分系统
 */
object MetadataFetcher {
    private const val TAG = "MetadataFetcher"
    private const val NETEASE_API_DOMAIN = "https://my-wangyi-api.onrender.com"

    data class FetchResult(
        val lrcWithTrans: String?,
        val coverBytes: ByteArray?
    )

    private data class MatchResult(val platform: Int, val score: Int, val albumMid: String? = null, val songMid: String? = null, val songId: Long = 0L)

    private fun normalizeStr(input: String?): String {
        if (input == null) return ""
        return input.lowercase().replace(Regex("""[^\w\u4e00-\u9fa5]"""), "")
    }

    // 🌟 1. 严格打分系统 (移植自 Python)
    private fun calculateMatchScore(localSong: Song, rTitle: String, rArtist: String, rAlbum: String, rDurMs: Long): Int {
        val normLt = normalizeStr(localSong.title)
        val normRt = normalizeStr(rTitle)
        if (normLt.isEmpty() || normRt.isEmpty()) return -1
        
        val titleIntersect = normLt.contains(normRt) || normRt.contains(normLt)

        val rawArtist = if (localSong.isArtistNameUnknown()) "" else localSong.artistName
        val localArtists = rawArtist.split(Regex("""[/,&、;+]|\band\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b""")).map { normalizeStr(it) }.filter { it.isNotEmpty() }
        val normRa = normalizeStr(rArtist)

        var artistMatch = false
        if (localArtists.isEmpty()) {
            artistMatch = true
        } else {
            val primary = localArtists[0]
            if (normRa.contains(primary) || primary.contains(normRa)) artistMatch = true
            else if (localArtists.any { normRa.contains(it) || it.contains(normRa) }) artistMatch = true
        }

        val lDur = localSong.duration
        val durDiff = if (lDur > 0L && rDurMs > 0L) abs(lDur - rDurMs) else 999999L
        val durMatch = lDur > 0L && rDurMs > 0L && durDiff <= 5000L

        // 兜底：时长对得上，且歌名/歌手有一点交集，强制算同一首歌
        if (durMatch && titleIntersect && artistMatch) return 350

        if (!titleIntersect) return -1
        if (!artistMatch) return -1

        var score = 100
        if (normLt == normRt) score += 400 else score += 50

        val mixKws = Regex("remix|mix|dj|版|extended|club|edit|live|现场", RegexOption.IGNORE_CASE)
        val ltFull = "${localSong.title} ${localSong.albumName}"
        val lRemix = mixKws.containsMatchIn(ltFull)
        val rRemix = mixKws.containsMatchIn(rTitle)
        if (lRemix != rRemix) return -1

        if (lDur > 0L && rDurMs > 0L) {
            if (durDiff <= 3500L) score += (1000L - durDiff).toInt()
            else if (durDiff <= 8000L) score += (400L - durDiff).toInt()
            else if (durDiff <= 15000L) score -= 200
            else return -1
        }

        val normLaAlb = normalizeStr(localSong.albumName)
        val normRaAlb = normalizeStr(rAlbum)
        if (normLaAlb.isNotEmpty() && normRaAlb.isNotEmpty()) {
            if (normLaAlb == normRaAlb) score += 500
            else if (normLaAlb.contains(normRaAlb) || normRaAlb.contains(normLaAlb)) score += 200
        }
        return score
    }

    // 🌟 2. 三级降级检索总控
    suspend fun fetchMetadata(song: Song, needLrc: Boolean, needCover: Boolean): FetchResult = withContext(Dispatchers.IO) {
        val cleanTitle = song.title.replace(Regex("""^\s*\d{1,4}\s*[-_.]?\s*"""), "").replace(Regex("""\(.*?\)|\[.*?\]|【.*?】"""), "").trim()
        val rawArtist = if (song.isArtistNameUnknown()) "" else song.artistName
        val primaryArtist = rawArtist.split(Regex("""[/,&、;+]|\band\b""")).firstOrNull()?.trim() ?: ""

        val strictQuery = "$primaryArtist $cleanTitle ${song.albumName ?: ""}".trim()
        val looseQuery = "$primaryArtist $cleanTitle".trim()
        val loosestQuery = cleanTitle

        var bestNetease: MatchResult? = null
        var bestQQ: MatchResult? = null

        // 阶梯 1: 歌名 + 歌手 + 专辑
        val (n1, q1) = racePlatforms(strictQuery, song)
        bestNetease = n1; bestQQ = q1

        // 阶梯 2: 歌名 + 歌手 (降级)
        if (bestNetease == null && bestQQ == null && strictQuery != looseQuery) {
            val (n2, q2) = racePlatforms(looseQuery, song)
            if (n2 != null) bestNetease = n2
            if (q2 != null) bestQQ = q2
        }

        // 阶梯 3: 仅歌名 (最低限度降级)
        if (bestNetease == null && bestQQ == null && looseQuery != loosestQuery) {
            val (n3, q3) = racePlatforms(loosestQuery, song)
            if (n3 != null) bestNetease = n3
            if (q3 != null) bestQQ = q3
        }

        var finalLrc: String? = null
        var finalCover: ByteArray? = null

        // LRC 优先网易云 -> QQ音乐
        if (needLrc) {
            if (bestNetease != null) finalLrc = getNeteaseLrc(bestNetease.songId)
            if (finalLrc.isNullOrBlank() && bestQQ != null) finalLrc = getQQLrc(bestQQ.songMid!!)
        }

        // 封面优先 QQ音乐 -> 网易云
        if (needCover) {
            if (bestQQ != null) finalCover = getQQCover(bestQQ.albumMid!!)
            if (finalCover == null && bestNetease != null) finalCover = getNeteaseCover(bestNetease.songId)
        }

        return@withContext FetchResult(finalLrc, finalCover)
    }

    private suspend fun racePlatforms(query: String, song: Song): Pair<MatchResult?, MatchResult?> = coroutineScope {
        val nTask = async { fetchNeteaseMeta(query, song) }
        val qTask = async { fetchQQMeta(query, song) }
        Pair(nTask.await(), qTask.await())
    }

    // ================== 各平台检索逻辑 ==================

    private suspend fun fetchNeteaseMeta(query: String, song: Song): MatchResult? {
        val url = "$NETEASE_API_DOMAIN/search?keywords=${Uri.encode(query)}&type=1&limit=15"
        val res = httpGet(url) ?: return null
        val songs = runCatching { JSONObject(res).optJSONObject("result")?.optJSONArray("songs") }.getOrNull() ?: return null
        
        val validItems = mutableListOf<MatchResult>()
        for (i in 0 until songs.length()) {
            val item = songs.getJSONObject(i)
            val rArtist = (0 until (item.optJSONArray("artists")?.length() ?: 0)).joinToString("") { item.optJSONArray("artists")?.getJSONObject(it)?.optString("name") ?: "" }
            val score = calculateMatchScore(song, item.optString("name"), rArtist, item.optJSONObject("album")?.optString("name") ?: "", item.optLong("duration", 0L))
            if (score > 0) validItems.add(MatchResult(2, score, songId = item.optLong("id", 0L)))
        }
        return validItems.maxByOrNull { it.score }
    }

    private suspend fun fetchQQMeta(query: String, song: Song): MatchResult? {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=15&p=1&w=${Uri.encode(query)}"
        val resStr = httpGet(url) ?: return null
        val start = resStr.indexOf("{"); val end = resStr.lastIndexOf("}")
        if (start == -1) return null
        val list = runCatching { JSONObject(resStr.substring(start, end + 1)).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") }.getOrNull() ?: return null
        
        val validItems = mutableListOf<MatchResult>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val rArtist = (0 until (item.optJSONArray("singer")?.length() ?: 0)).joinToString("") { item.optJSONArray("singer")?.getJSONObject(it)?.optString("name") ?: "" }
            val score = calculateMatchScore(song, item.optString("songname"), rArtist, item.optString("albumname"), item.optLong("interval", 0L) * 1000L)
            
            val albumMid = item.optString("albummid").takeIf { it.isNotBlank() } ?: item.optString("album_mid")
            if (score > 0) validItems.add(MatchResult(3, score, albumMid = albumMid, songMid = item.optString("songmid")))
        }
        return validItems.maxByOrNull { it.score }
    }

    // ================== 获取具体数据 ==================

    // 🌟 阶梯降级获取高质量封面
    private suspend fun getQQCover(albumMid: String): ByteArray? {
        val resolutions = listOf(800, 600, 500, 300) // 最低不低于300
        for (res in resolutions) {
            val url = "https://y.qq.com/music/photo_new/T002R${res}x${res}M000${albumMid}.jpg"
            val bytes = httpGetBytes(url)
            if (bytes != null && bytes.size > 5000) return bytes // 小于5KB的通常是默认裂图
        }
        return null
    }

    private suspend fun getNeteaseCover(songId: Long): ByteArray? {
        val detailUrl = "https://music.163.com/api/song/detail?ids=[$songId]"
        val res = httpGet(detailUrl) ?: return null
        val picUrl = runCatching { JSONObject(res).optJSONArray("songs")?.getJSONObject(0)?.optJSONObject("al")?.optString("picUrl") }.getOrNull()
        if (!picUrl.isNullOrBlank()) {
            return httpGetBytes(picUrl)
        }
        return null
    }

    // 🌟 获取并交错合并翻译 LRC (Python merge_lrc_interleave 的 Kotlin 翻译版)
    private suspend fun getNeteaseLrc(songId: Long): String? {
        val url = "https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1"
        val res = httpGet(url) ?: return null
        val obj = runCatching { JSONObject(res) }.getOrNull() ?: return null
        val lrc = obj.optJSONObject("lrc")?.optString("lyric")
        val trans = obj.optJSONObject("tlyric")?.optString("lyric")
        return mergeLrcInterleave(lrc, trans)
    }

    private suspend fun getQQLrc(songMid: String): String? {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songMid&format=json&nobase64=1"
        val res = httpGet(url) ?: return null
        val obj = runCatching { JSONObject(res) }.getOrNull() ?: return null
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
            // 匹配误差在 1.5 秒内的翻译
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

    private suspend fun httpGet(urlString: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (urlString.contains("163.com")) conn.setRequestProperty("Referer", "https://music.163.com/")
            else if (urlString.contains("qq.com")) conn.setRequestProperty("Referer", "https://y.qq.com/")
            if (conn.responseCode == 200) return@withContext conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {}
        null
    }

    private suspend fun httpGetBytes(urlString: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) return@withContext conn.inputStream.readBytes()
        } catch (e: Exception) {}
        null
    }
}