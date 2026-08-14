package com.mardous.booming.data.local.lyrics.ttml

import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.media.isArtistNameUnknown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 动态专辑画布引擎 (严格计分防假匹配版 - 优中取优，宁缺毋滥)
 * 优先级排序: Apple Music -> 网易云音乐 -> QQ音乐
 */
object AnimatedCanvasFetcher {

    private const val TAG = "AnimatedCanvasFetcher"
    
    private const val APPLE_TOKEN = "eyJhbGciOiJFUzI1NiIsImtpZCI6MldVTUZPQjA2MyJ9.eyJpc3MiOiJBNTZEUjg1TTRTIiwiaWF0IjoxNTc4NTI2NzI2LCJleHAiOjE3NzA0MzYzMjZ9.S6x2XGf7OqS6cZJ_3eG0W8gA4vN4aT3q9Z1aW3bX5cY"
    
    private const val NETEASE_API_DOMAIN = "https://my-wangyi-api.onrender.com"
    private const val QQ_MUSIC_API_DOMAIN = "https://my-qqmusic-api.onrender.com"

    private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm")
    private val uriCache = LruCache<String, String>(30)

    private data class CoverMatch(val platform: Int, val score: Int, val url: String)

    private fun normalizeStr(input: String?): String {
        if (input == null) return ""
        return input.lowercase().replace(Regex("""[^\w\u4e00-\u9fa5]"""), "")
    }

    // ==========================================
    // 🌟 核心引擎：高精深度评分（严禁串扰乱入）
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

        var score = 0
        var durationMatched = false
        val lDur = localSong.duration
        
        if (lDur > 0L && rDurMs > 0L) {
            val diff = Math.abs(lDur - rDurMs)
            if (diff <= 3500L) {
                score += (1000L - diff).toInt()
                durationMatched = true
            } else {
                return -1 // 时长差距大，强制拒绝！
            }
        }

        var albumMatched = false
        val normLaAlb = normalizeStr(localSong.albumName ?: "")
        val normRaAlb = normalizeStr(rAlbum)
        if (normLaAlb.isNotEmpty() && normRaAlb.isNotEmpty()) {
            if (normLaAlb == normRaAlb) {
                score += 500
                albumMatched = true
            } else if (normLaAlb.contains(normRaAlb) || normRaAlb.contains(normLaAlb)) {
                score += 200
                albumMatched = true
            } else {
                if (!durationMatched) return -1 
            }
        } else {
            if (!durationMatched) return -1 
        }

        return score
    }

    suspend fun fetchCanvasUri(song: Song): String? = withContext(Dispatchers.IO) {
        val cacheKey = "${song.artistName}_${song.title}"
        uriCache.get(cacheKey)?.let { 
            Log.d(TAG, "🎯 命中内存缓存直接返回: $cacheKey")
            return@withContext it 
        }

        yield()

        // 🌟 1. 物理检查本地封面
        val parentDir = File(song.data).parentFile
        if (parentDir != null && parentDir.exists()) {
            val audioFileName = File(song.data).nameWithoutExtension
            val songVideo = checkLocalVideo(parentDir, audioFileName) 
                ?: checkLocalVideo(parentDir, getSafeFilename(song.title)) 
                ?: checkLocalVideo(parentDir, "${song.artistName} - ${song.title}") 
            
            if (songVideo != null) return@withContext cacheAndReturn(cacheKey, songVideo)

            val albumName = song.albumName
            if (!albumName.isNullOrBlank()) {
                val albumVideo = checkLocalVideo(parentDir, getSafeFilename(albumName))
                if (albumVideo != null) return@withContext cacheAndReturn(cacheKey, albumVideo)
            }
        }

        yield()

        val rawTitle = song.title.replace(Regex("""^\s*\d{1,4}\s*[-_.]?\s*"""), "")
            .replace(Regex("""\(.*?(Remaster|Live|翻唱|伴奏|现场|DJ).*?\)"""), "")
            .replace(Regex("""\[.*?\]|\【.*?\】"""), "").trim()
            
        val rawArtist = if (song.isArtistNameUnknown()) "" else song.artistName
        val primaryArtist = rawArtist.split(Regex("[/&,、]| and ")).firstOrNull()?.trim() ?: ""

        val strictQueryParts = mutableListOf<String>()
        if (primaryArtist.isNotBlank()) strictQueryParts.add(primaryArtist)
        if (rawTitle.isNotBlank()) strictQueryParts.add(rawTitle)
        
        val strictQuery = strictQueryParts.joinToString(" ").replace(Regex("""[-_／/]"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (strictQuery.isBlank()) return@withContext null
            
        // 🌟 2. 并发全局大拉取：全网优中取优
        val candidates = coroutineScope {
            val aTask = async { fetchAppleMusicCover(strictQuery, song) }
            val nTask = async { fetchNeteaseCover(strictQuery, song) }
            val qTask = async { fetchQQMusicCover(strictQuery, song) }
            listOfNotNull(aTask.await(), nTask.await(), qTask.await())
        }

        if (candidates.isEmpty()) {
            Log.d(TAG, "💔 宁缺毋滥，全网未找到严格匹配 [专辑+时长] 的动态封面: ${song.title}")
            return@withContext null
        }

        // 🌟 3. 排序决出王者：分数最高者优先；分数相同，按 Apple > Netease > QQ
        val bestMatch = candidates.maxWithOrNull(Comparator { a, b ->
            if (a.score != b.score) a.score.compareTo(b.score)
            else b.platform.compareTo(a.platform)
        })

        val networkUrl = bestMatch?.url
        if (networkUrl != null && parentDir != null) {
            val audioFileName = File(song.data).nameWithoutExtension
            return@withContext downloadToLocal(networkUrl, parentDir, audioFileName)
        }

        return@withContext networkUrl
    }

    private suspend fun fetchAppleMusicCover(query: String, song: Song): CoverMatch? {
        try {
            data class AMatch(val score: Int, val id: String, val country: String)
            val validItems = mutableListOf<AMatch>()

            for (country in listOf("cn", "us")) {
                yield()
                val searchUrl = "https://itunes.apple.com/search?term=${Uri.encode(query)}&entity=song&limit=10&country=$country"
                val searchRes = httpGet(searchUrl) ?: continue
                val results = runCatching { JSONObject(searchRes).optJSONArray("results") }.getOrNull() ?: continue
                
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val score = calculateMatchScore(song, item.optString("trackName"), item.optString("artistName"), item.optString("collectionName"), item.optLong("trackTimeMillis", 0L))
                    if (score > 0) validItems.add(AMatch(score, item.optString("collectionId"), country))
                }
            }

            val bestMatch = validItems.maxByOrNull { it.score }
            if (bestMatch != null && bestMatch.id.isNotBlank()) {
                val ampUrl = "https://amp-api.music.apple.com/v1/catalog/${bestMatch.country}/albums/${bestMatch.id}"
                val ampRes = httpGet(ampUrl, useAuth = true) ?: return null
                val videoUrl = JSONObject(ampRes).optJSONArray("data")
                    ?.optJSONObject(0)?.optJSONObject("attributes")?.optJSONObject("editorialVideo")
                    ?.optJSONObject("motionDetailSquare")?.optString("video")

                if (!videoUrl.isNullOrBlank() && videoUrl.endsWith(".m3u8")) {
                    return CoverMatch(1, bestMatch.score, videoUrl)
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) Log.e(TAG, "Apple Music fetch failed", e)
        }
        return null
    }

    private suspend fun fetchNeteaseCover(query: String, song: Song): CoverMatch? {
        try {
            val searchUrl = "$NETEASE_API_DOMAIN/search?keywords=${Uri.encode(query)}&limit=10"
            val searchRes = httpGet(searchUrl) ?: return null
            val songs = runCatching { JSONObject(searchRes).optJSONObject("result")?.optJSONArray("songs") }.getOrNull() ?: return null
            
            data class NMatch(val score: Int, val id: Long)
            val validItems = mutableListOf<NMatch>()

            for (i in 0 until songs.length()) {
                val item = songs.getJSONObject(i)
                val rArtist = (0 until (item.optJSONArray("artists")?.length() ?: 0)).joinToString("") { item.optJSONArray("artists")?.getJSONObject(it)?.optString("name") ?: "" }
                val score = calculateMatchScore(song, item.optString("name"), rArtist, item.optJSONObject("album")?.optString("name") ?: "", item.optLong("duration", 0L))
                if (score > 0) validItems.add(NMatch(score, item.optLong("id", 0L)))
            }

            val bestMatch = validItems.maxByOrNull { it.score }
            if (bestMatch != null && bestMatch.id != 0L) {
                yield()
                val dynamicCoverUrl = "$NETEASE_API_DOMAIN/song/dynamic/cover?id=${bestMatch.id}"
                val dynamicRes = httpGet(dynamicCoverUrl) ?: return null
                val dataObj = JSONObject(dynamicRes).optJSONObject("data")
                if (dataObj != null) {
                    var coverUrl = dataObj.optString("videoPlayUrl")
                    if (coverUrl.isNullOrBlank()) coverUrl = dataObj.optString("url")
                    if (!coverUrl.isNullOrBlank()) {
                        return CoverMatch(2, bestMatch.score, coverUrl.replace("http://", "https://"))
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) Log.e(TAG, "Netease fetch error", e)
        }
        return null
    }

    private suspend fun fetchQQMusicCover(query: String, song: Song): CoverMatch? {
        try {
            val searchUrl = "$QQ_MUSIC_API_DOMAIN/api/search?key=${Uri.encode(query)}"
            val searchRes = httpGet(searchUrl) ?: return null
            val list = runCatching { JSONObject(searchRes).optJSONObject("data")?.optJSONArray("list") }.getOrNull() ?: return null

            data class QMatch(val score: Int, val vid: String)
            val validItems = mutableListOf<QMatch>()

            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val rArtist = (0 until (item.optJSONArray("singer")?.length() ?: 0)).joinToString("") { item.optJSONArray("singer")?.getJSONObject(it)?.optString("name") ?: "" }
                val score = calculateMatchScore(song, item.optString("songname"), rArtist, item.optString("albumname"), item.optLong("interval", 0L) * 1000L)
                val vid = item.optString("vid", "")
                if (score > 0 && vid.isNotBlank()) validItems.add(QMatch(score, vid))
            }

            val bestMatch = validItems.maxByOrNull { it.score }
            if (bestMatch != null) {
                yield()
                val mvUrl = "$QQ_MUSIC_API_DOMAIN/api/mv?id=${bestMatch.vid}"
                val mvRes = httpGet(mvUrl) ?: return null
                val urlsObj = JSONObject(mvRes).optJSONObject("data") ?: return null
                
                val preferredKeys = listOf("480", "720", "mp4", "360", "240", "1080")
                var selectedResolutionKey: String? = null
                
                for (pref in preferredKeys) {
                    if (urlsObj.has(pref)) {
                        selectedResolutionKey = pref
                        break
                    }
                }
                
                if (selectedResolutionKey == null && urlsObj.keys().hasNext()) {
                    selectedResolutionKey = urlsObj.keys().next()
                }

                if (selectedResolutionKey != null) {
                    val urlList = urlsObj.optJSONArray(selectedResolutionKey)
                    if (urlList != null && urlList.length() > 0) {
                        val finalUrl = urlList.optString(0)
                        if (finalUrl.isNotBlank()) {
                            return CoverMatch(3, bestMatch.score, finalUrl)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) Log.e(TAG, "QQ Music fetch error", e)
        }
        return null
    }

    // ==========================================
    // 缓存与底层物理下载
    // ==========================================
    private fun cacheAndReturn(key: String, uri: String): String {
        uriCache.put(key, uri)
        return uri
    }

    private fun getSafeFilename(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private fun checkLocalVideo(dir: File, targetName: String): String? {
        val safeName = getSafeFilename(targetName)
        for (ext in VIDEO_EXTENSIONS) {
            val file = File(dir, "$safeName$ext")
            if (file.exists() && file.isFile && file.length() > 0) return file.absolutePath
        }
        return null
    }

    private suspend fun downloadToLocal(urlStr: String, parentDir: File, audioFileName: String): String? {
        if (urlStr.endsWith(".m3u8") || urlStr.contains(".m3u8")) {
            return urlStr
        }

        return withContext(Dispatchers.IO) {
            val targetFile = File(parentDir, "$audioFileName.mp4")

            try {
                if (targetFile.exists() && targetFile.length() > 0) {
                    return@withContext targetFile.absolutePath
                }

                var currentUrl = urlStr
                var redirectCount = 0
                var conn: HttpURLConnection

                while (redirectCount < 5) {
                    conn = URL(currentUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.instanceFollowRedirects = false 

                    val responseCode = conn.responseCode
                    if (responseCode in 300..399) {
                        currentUrl = conn.getHeaderField("Location") ?: break
                        redirectCount++
                        conn.disconnect()
                        continue
                    }

                    if (responseCode == 200 || responseCode == 206) {
                        Log.d(TAG, "⬇️ 开始下载动态封面至本地: ${targetFile.absolutePath}")
                        conn.inputStream.use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.d(TAG, "✅ 动态封面下载完成！")
                        return@withContext targetFile.absolutePath
                    } else {
                        break 
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "视频下载失败，回退使用网络 URL", e)
                    if (targetFile.exists()) targetFile.delete() 
                }
            }
            return@withContext urlStr
        }
    }

    @Throws(Exception::class)
    private suspend fun httpGet(urlString: String, useAuth: Boolean = false): String? {
        yield()
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000 
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            if (useAuth) {
                conn.setRequestProperty("Authorization", "Bearer $APPLE_TOKEN")
                conn.setRequestProperty("Origin", "https://music.apple.com")
            }
            
            if (conn.responseCode == 200) {
                return conn.inputStream.bufferedReader().readText()
            }
        } finally {
            conn?.disconnect()
        }
        return null
    }
}