package com.mardous.booming.data.local.lyrics.ttml

import android.content.Context
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
 * 动态专辑画布引擎 (双轨智能超时降级 + 冷热双轨 LRU 缓存)
 */
object AnimatedCanvasFetcher {

    private const val TAG = "AnimatedCanvasFetcher"
    
    private const val APPLE_TOKEN = "eyJhbGciOiJFUzI1NiIsImtpZCI6MldVTUZPQjA2MyJ9.eyJpc3MiOiJBNTZEUjg1TTRTIiwiaWF0IjoxNTc4NTI2NzI2LCJleHAiOjE3NzA0MzYzMjZ9.S6x2XGf7OqS6cZJ_3eG0W8gA4vN4aT3q9Z1aW3bX5cY"
    
    private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm")
    private val uriCache = LruCache<String, String>(30)

    private const val MAX_PERMANENT_BYTES = 3_500_000L // 3.5MB
    private const val MAX_TEMP_CACHE_FILES = 5

    private data class CoverMatch(val platform: Int, val score: Int, val url: String)

    private fun normalizeStr(input: String?): String {
        if (input == null) return ""
        return input.lowercase().replace(Regex("""[^\w\u4e00-\u9fa5]"""), "")
    }

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
                return -1 
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

    suspend fun fetchCanvasUri(context: Context, song: Song): String? = withContext(Dispatchers.IO) {
        val cacheKey = "${song.artistName}_${song.title}"
        uriCache.get(cacheKey)?.let { 
            Log.d(TAG, "🎯 命中内存缓存直接返回: $cacheKey")
            return@withContext it 
        }

        yield()
        val audioFileName = File(song.data).nameWithoutExtension

        val parentDir = File(song.data).parentFile
        if (parentDir != null && parentDir.exists()) {
            val hiddenVideoDir = File(parentDir, ".MP4")

            var songVideo = if (hiddenVideoDir.exists()) {
                checkLocalVideo(hiddenVideoDir, audioFileName)
                    ?: checkLocalVideo(hiddenVideoDir, getSafeFilename(song.title))
                    ?: checkLocalVideo(hiddenVideoDir, "${song.artistName} - ${song.title}")
            } else null

            if (songVideo == null) {
                songVideo = checkLocalVideo(parentDir, audioFileName)
                    ?: checkLocalVideo(parentDir, getSafeFilename(song.title))
                    ?: checkLocalVideo(parentDir, "${song.artistName} - ${song.title}")
            }
            
            if (songVideo == "BLOCKED") {
                Log.d(TAG, "⛔ 检测到黑名单空文件/文件夹占位，已永远跳过该歌曲的视频加载: $audioFileName")
                return@withContext null
            }
            if (songVideo != null) return@withContext cacheAndReturn(cacheKey, songVideo)

            val albumName = song.albumName
            if (!albumName.isNullOrBlank()) {
                var albumVideo = if (hiddenVideoDir.exists()) checkLocalVideo(hiddenVideoDir, getSafeFilename(albumName)) else null
                if (albumVideo == null) albumVideo = checkLocalVideo(parentDir, getSafeFilename(albumName))

                if (albumVideo == "BLOCKED") return@withContext null
                if (albumVideo != null) return@withContext cacheAndReturn(cacheKey, albumVideo)
            }
        }

        val tempDir = getTempCacheDir(context)
        val tempVideoPath = checkLocalVideo(tempDir, audioFileName)
        if (tempVideoPath == "BLOCKED") return@withContext null
        if (tempVideoPath != null) {
            File(tempVideoPath).setLastModified(System.currentTimeMillis())
            return@withContext cacheAndReturn(cacheKey, tempVideoPath)
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
            
        // 🌟 双段式拉取：第一段 (极速 3秒并发)
        val candidates = coroutineScope {
            val aTask = async { fetchAppleMusicCover(strictQuery, song) }
            val nTask = async { fetchNeteaseCover(context, strictQuery, song, timeoutMs = 3000) }
            val qTask = async { fetchQQMusicCover(context, strictQuery, song) }
            listOfNotNull(aTask.await(), nTask.await(), qTask.await())
        }

        var bestMatch = candidates.maxWithOrNull(Comparator { a, b ->
            if (a.score != b.score) a.score.compareTo(b.score)
            else b.platform.compareTo(a.platform)
        })

        // 🌟 双段式拉取：第二段 (如果全网都没找到，且刚开局网易云可能因为 Render 休眠失败了，放宽到 45 秒专等网易云！)
        if (bestMatch == null) {
            Log.d(TAG, "⏳ 极速模式未找到动封，进入 Render 唤醒等待模式 (最长耐心等待 45秒)...")
            bestMatch = fetchNeteaseCover(context, strictQuery, song, timeoutMs = 45000)
        }

        if (bestMatch == null) {
            Log.d(TAG, "💔 宁缺毋滥，极限等待后仍未找到匹配的动态封面: ${song.title}")
            return@withContext null
        }

        val networkUrl = bestMatch.url
        return@withContext downloadToDoubleTrackCache(context, networkUrl, parentDir, audioFileName)
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

    // 🌟 支持传入动态超时时间的网易云获取方法
    private suspend fun fetchNeteaseCover(context: Context, query: String, song: Song, timeoutMs: Int): CoverMatch? {
        try {
            val baseUrl = com.mardous.booming.data.network.NeteaseDailyApi.getBaseUrl(context)
            val searchUrl = "$baseUrl/search?keywords=${Uri.encode(query)}&limit=10"
            
            val searchRes = httpGet(searchUrl, timeoutMs = timeoutMs) ?: return null
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
                val dynamicCoverUrl = "$baseUrl/song/dynamic/cover?id=${bestMatch.id}"
                val dynamicRes = httpGet(dynamicCoverUrl, timeoutMs = timeoutMs) ?: return null
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

    private suspend fun fetchQQMusicCover(context: Context, query: String, song: Song): CoverMatch? {
        try {
            val baseUrl = com.mardous.booming.data.network.NeteaseDailyApi.getQqBaseUrl(context)
            val searchUrl = "$baseUrl/api/search?key=${Uri.encode(query)}"
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
                val mvUrl = "$baseUrl/api/mv?id=${bestMatch.vid}"
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
                        if (finalUrl.isNotBlank()) return CoverMatch(3, bestMatch.score, finalUrl)
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) Log.e(TAG, "QQ Music fetch error", e)
        }
        return null
    }

    private fun getTempCacheDir(context: Context): File {
        val dir = File(context.externalCacheDir ?: context.cacheDir, "canvas_temp_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun manageTempCacheLRU(tempDir: File) {
        val files = tempDir.listFiles()?.filter { it.isFile && it.extension in listOf("mp4", "webm") } ?: return
        if (files.size >= MAX_TEMP_CACHE_FILES) {
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_TEMP_CACHE_FILES + 1)
                .forEach { 
                    it.delete()
                    Log.d(TAG, "🧹 LRU 清理大体积临时视频: ${it.name}")
                }
        }
    }

    fun clearAllTempCache(context: Context) {
        runCatching {
            getTempCacheDir(context).deleteRecursively()
            Log.d(TAG, "💥 App 关闭，已抹除所有临时大视频缓存！")
        }
    }

    private suspend fun downloadToDoubleTrackCache(context: Context, urlStr: String, parentDir: File?, audioFileName: String): String? {
        if (urlStr.endsWith(".m3u8") || urlStr.contains(".m3u8")) {
            return urlStr
        }

        return withContext(Dispatchers.IO) {
            try {
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
                        val contentLength = conn.contentLengthLong
                        val targetFile: File

                        if (contentLength <= MAX_PERMANENT_BYTES && parentDir != null) {
                            val hiddenVideoDir = File(parentDir, ".MP4")
                            if (!hiddenVideoDir.exists()) hiddenVideoDir.mkdirs()
                            targetFile = File(hiddenVideoDir, "$audioFileName.mp4")
                            Log.d(TAG, "⬇️ 文件较小 (${contentLength/1024}KB) -> 转入永久存储: ${targetFile.absolutePath}")
                        } else {
                            val tempDir = getTempCacheDir(context)
                            manageTempCacheLRU(tempDir)
                            targetFile = File(tempDir, "$audioFileName.mp4")
                            Log.w(TAG, "⚠️ 发现巨型 MV (${contentLength/1024/1024}MB) -> 转入 LRU 临时热缓存: ${targetFile.absolutePath}")
                        }

                        if (targetFile.exists() && targetFile.length() == contentLength && contentLength > 0) {
                            return@withContext targetFile.absolutePath
                        }

                        conn.inputStream.use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        return@withContext targetFile.absolutePath
                    } else {
                        break 
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) Log.e(TAG, "视频下载失败", e)
            }
            return@withContext urlStr
        }
    }

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
            if (file.exists()) {
                if (file.isFile && file.length() > 0) {
                    return file.absolutePath
                } else {
                    return "BLOCKED"
                }
            }
        }
        return null
    }

    // 🌟 支持动态传入 timeout 阈值
    @Throws(Exception::class)
    private suspend fun httpGet(urlString: String, useAuth: Boolean = false, timeoutMs: Int = 3000): String? {
        yield()
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs 
            // readTimeout 放宽，容忍大数据包读取时间
            conn.readTimeout = timeoutMs + 2000
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