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
 * 动态专辑画布引擎 (严格计分版 + 冷热双轨 LRU 缓存系统 + 隐藏文件夹防相册污染)
 * <= 3.5MB：永久落盘至歌曲所在目录的 .MP4 隐藏文件夹
 * >  3.5MB：存入临时 LRU 缓存池，最多保留 5 首，避免大文件撑爆车机空间
 */
object AnimatedCanvasFetcher {

    private const val TAG = "AnimatedCanvasFetcher"
    
    private const val APPLE_TOKEN = "eyJhbGciOiJFUzI1NiIsImtpZCI6MldVTUZPQjA2MyJ9.eyJpc3MiOiJBNTZEUjg1TTRTIiwiaWF0IjoxNTc4NTI2NzI2LCJleHAiOjE3NzA0MzYzMjZ9.S6x2XGf7OqS6cZJ_3eG0W8gA4vN4aT3q9Z1aW3bX5cY"
    
    //private const val NETEASE_API_DOMAIN = "https://my-wangyi-api.onrender.com"
    //private const val QQ_MUSIC_API_DOMAIN = "https://my-qqmusic-api.onrender.com"

    private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm")
    private val uriCache = LruCache<String, String>(30)

    // 🌟 空间保护核心常量
    private const val MAX_PERMANENT_BYTES = 3_500_000L // 3.5MB
    private const val MAX_TEMP_CACHE_FILES = 5         // 临时大文件最多缓存 5 首

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

    // 🌟 新增 Context 参数，用于获取 Android CacheDir
    // 🌟 2. 在主流程中加入对 BLOCKED 的秒级熔断拦截
    suspend fun fetchCanvasUri(context: Context, song: Song): String? = withContext(Dispatchers.IO) {
        val cacheKey = "${song.artistName}_${song.title}"
        uriCache.get(cacheKey)?.let { 
            Log.d(TAG, "🎯 命中内存缓存直接返回: $cacheKey")
            return@withContext it 
        }

        yield()
        val audioFileName = File(song.data).nameWithoutExtension

        // 1. 检查永久冷端本地缓存 (Permanent)
        val parentDir = File(song.data).parentFile
        if (parentDir != null && parentDir.exists()) {
            val hiddenVideoDir = File(parentDir, ".MP4")

            // 优先去 .MP4 隐藏文件夹里找
            var songVideo = if (hiddenVideoDir.exists()) {
                checkLocalVideo(hiddenVideoDir, audioFileName)
                    ?: checkLocalVideo(hiddenVideoDir, getSafeFilename(song.title))
                    ?: checkLocalVideo(hiddenVideoDir, "${song.artistName} - ${song.title}")
            } else null

            // 兜底：兼容以前下载在父目录的旧视频
            if (songVideo == null) {
                songVideo = checkLocalVideo(parentDir, audioFileName)
                    ?: checkLocalVideo(parentDir, getSafeFilename(song.title))
                    ?: checkLocalVideo(parentDir, "${song.artistName} - ${song.title}")
            }
            
            // 🛑 拦截生效：遇到占位黑名单，直接返回 null，不播放也不走网络下载！
            if (songVideo == "BLOCKED") {
                Log.d(TAG, "⛔ 检测到黑名单空文件/文件夹占位，已永远跳过该歌曲的视频加载: $audioFileName")
                return@withContext null
            }
            if (songVideo != null) return@withContext cacheAndReturn(cacheKey, songVideo)

            // 同样拦截 Album 级别的黑名单
            val albumName = song.albumName
            if (!albumName.isNullOrBlank()) {
                var albumVideo = if (hiddenVideoDir.exists()) checkLocalVideo(hiddenVideoDir, getSafeFilename(albumName)) else null
                if (albumVideo == null) albumVideo = checkLocalVideo(parentDir, getSafeFilename(albumName))

                if (albumVideo == "BLOCKED") {
                    Log.d(TAG, "⛔ 检测到专辑黑名单占位，跳过视频加载")
                    return@withContext null
                }
                if (albumVideo != null) return@withContext cacheAndReturn(cacheKey, albumVideo)
            }
        }

        // 2. 检查临时热端 LRU 缓存 (Temporary)
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
            
        // 🌟 3. 并发全局大拉取：全网优中取优
        val candidates = coroutineScope {
            val aTask = async { fetchAppleMusicCover(strictQuery, song) }
            val nTask = async { fetchNeteaseCover(context, strictQuery, song) }
            val qTask = async { fetchQQMusicCover(context, strictQuery, song) } // 🌟 加入 context
            listOfNotNull(aTask.await(), nTask.await(), qTask.await())
        }

        if (candidates.isEmpty()) {
            Log.d(TAG, "💔 宁缺毋滥，未找到严格匹配的动态封面: ${song.title}")
            return@withContext null
        }

        val bestMatch = candidates.maxWithOrNull(Comparator { a, b ->
            if (a.score != b.score) a.score.compareTo(b.score)
            else b.platform.compareTo(a.platform)
        })

        val networkUrl = bestMatch?.url
        if (networkUrl != null) {
            // 🌟 4. 执行双轨下载策略
            return@withContext downloadToDoubleTrackCache(context, networkUrl, parentDir, audioFileName)
        }

        return@withContext networkUrl
    }

    // ==========================================
    // 高精网络抓取 API 逻辑保持原样...
    // ==========================================
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

    // 🌟 修复：签名加上 context 参数，并动态获取 baseUrl
    private suspend fun fetchNeteaseCover(context: Context, query: String, song: Song): CoverMatch? {
        try {
            // 动态读取网易云域名
            val baseUrl = com.mardous.booming.data.network.NeteaseDailyApi.getBaseUrl(context)
            val searchUrl = "$baseUrl/search?keywords=${Uri.encode(query)}&limit=10"
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
                // 🌟 使用动态 baseUrl
                val dynamicCoverUrl = "$baseUrl/song/dynamic/cover?id=${bestMatch.id}"
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

    // 🌟 签名加上 context，URL 动态获取
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
                // 🌟 使用动态 baseUrl
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

    // ==========================================
    // 🌟 热端 LRU 缓存管理模块
    // ==========================================
    private fun getTempCacheDir(context: Context): File {
        val dir = File(context.externalCacheDir ?: context.cacheDir, "canvas_temp_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun manageTempCacheLRU(tempDir: File) {
        val files = tempDir.listFiles()?.filter { it.isFile && it.extension in listOf("mp4", "webm") } ?: return
        if (files.size >= MAX_TEMP_CACHE_FILES) {
            // 按最后修改时间升序排列（最旧的在前面），清理出空间
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_TEMP_CACHE_FILES + 1)
                .forEach { 
                    it.delete()
                    Log.d(TAG, "🧹 LRU 清理大体积临时视频: ${it.name}")
                }
        }
    }

    /**
     * 对外暴露的方法：用于在关闭 APP 时 (如 MainActivity onDestroy 或 Service 中) 调用清空临时库
     */
    fun clearAllTempCache(context: Context) {
        runCatching {
            getTempCacheDir(context).deleteRecursively()
            Log.d(TAG, "💥 App 关闭，已抹除所有临时大视频缓存！")
        }
    }

    // ==========================================
    // 🌟 核心分发：双轨下载体系
    // ==========================================
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

                        // 🌟 根据体积决定它是进入冷端（永久隐藏）还是热端（临时LRU）
                        if (contentLength <= MAX_PERMANENT_BYTES && parentDir != null) {
                            val hiddenVideoDir = File(parentDir, ".MP4")
                            if (!hiddenVideoDir.exists()) hiddenVideoDir.mkdirs() // 自动创建隐藏文件夹
                            targetFile = File(hiddenVideoDir, "$audioFileName.mp4")
                            Log.d(TAG, "⬇️ 文件较小 (${contentLength/1024}KB) -> 转入永久存储: ${targetFile.absolutePath}")
                        } else {
                            val tempDir = getTempCacheDir(context)
                            manageTempCacheLRU(tempDir) // 存放前先检查并淘汰旧缓存
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

    // 🌟 1. 修改检查逻辑，支持 0字节/文件夹 占位熔断
    private fun checkLocalVideo(dir: File, targetName: String): String? {
        val safeName = getSafeFilename(targetName)
        for (ext in VIDEO_EXTENSIONS) {
            val file = File(dir, "$safeName$ext")
            // 只要同名对象存在
            if (file.exists()) {
                // 如果是正常的非空文件，正常返回去播放
                if (file.isFile && file.length() > 0) {
                    return file.absolutePath
                } else {
                    // 🛑 核心：如果是 0 字节的空文件，或者是用户新建的同名文件夹，视为“黑名单拦截标记”！
                    return "BLOCKED"
                }
            }
        }
        return null
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