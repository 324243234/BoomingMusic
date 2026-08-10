package com.mardous.booming.data.local.lyrics.ttml

import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.media.isArtistNameUnknown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 动态专辑画布引擎 (高可用线性防线 + 下载缓存版)
 * 优先级: 本地资产 -> Apple Music -> 网易云官方 -> QQ 音乐
 * 特性: LRU 内存缓存、协作式协程取消防抖、MP4 自动下载到歌曲同级目录
 */
object AnimatedCanvasFetcher {

    private const val TAG = "AnimatedCanvasFetcher"
    
    private const val APPLE_TOKEN = "eyJhbGciOiJFUzI1NiIsImtpZCI6MldVTUZPQjA2MyJ9.eyJpc3MiOiJBNTZEUjg1TTRTIiwiaWF0IjoxNTc4NTI2NzI2LCJleHAiOjE3NzA0MzYzMjZ9.S6x2XGf7OqS6cZJ_3eG0W8gA4vN4aT3q9Z1aW3bX5cY"
    
    // 你部署的专属 API
    private const val NETEASE_API_DOMAIN = "https://my-wangyi-api.onrender.com"
    private const val QQ_MUSIC_API_DOMAIN = "https://my-qqmusic-api.onrender.com"

    private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm")
    private val uriCache = LruCache<String, String>(30)

    suspend fun fetchCanvasUri(song: Song): String? = withContext(Dispatchers.IO) {
        val cacheKey = "${song.artistName}_${song.title}"
        uriCache.get(cacheKey)?.let { 
            Log.d(TAG, "🎯 命中内存缓存直接返回: $cacheKey")
            return@withContext it 
        }

        yield()

        // 1. 本地资产检索 (检查是否已经下载过同名 mp4)
        val parentDir = File(song.data).parentFile
        if (parentDir != null && parentDir.exists()) {
            val safeTitle = getSafeFilename(song.title)
            val songVideo = checkLocalVideo(parentDir, safeTitle) 
                ?: checkLocalVideo(parentDir, File(song.data).nameWithoutExtension)
                ?: checkLocalVideo(parentDir, "${song.artistName} - ${song.title}")
            
            if (songVideo != null) return@withContext cacheAndReturn(cacheKey, songVideo)

            val albumName = song.albumName
            if (!albumName.isNullOrBlank()) {
                val albumVideo = checkLocalVideo(parentDir, getSafeFilename(albumName))
                if (albumVideo != null) return@withContext cacheAndReturn(cacheKey, albumVideo)
            }
        }

        yield()

        // 清洗检索词
        val rawTitle = song.title.replace(Regex("""^\s*\d{1,4}\s*[-_.]?\s*"""), "")
            .replace(Regex("""\(.*?(Remaster|Live|翻唱|伴奏|现场|DJ).*?\)"""), "")
            .replace(Regex("""\[.*?\]|\【.*?\】"""), "").trim()
            
        val rawArtist = if (song.isArtistNameUnknown()) "" else song.artistName
        
        val rawAlbum = (song.albumName ?: "")
            .replace(Regex("""\(.*?\)"""), "")
            .replace(Regex("""\[.*?\]|\【.*?\】"""), "")
            .trim()

        // ==========================================
        // 核心检索流开始
        // ==========================================
        val query1 = (if (rawArtist.isBlank()) rawTitle else "$rawArtist $rawTitle")
            .replace(Regex("""[-_／/]"""), " ").trim()
            
        val cover1 = fetchAndDownloadFromNetwork(query1, song, parentDir)
        if (cover1 != null) {
            Log.d(TAG, "🎯 策略1命中: $query1")
            return@withContext cacheAndReturn(cacheKey, cover1)
        }

        if (rawAlbum.isNotBlank() && rawAlbum != rawTitle) {
            val query2 = "$rawTitle $rawAlbum".replace(Regex("""[-_／/]"""), " ").trim()
            val cover2 = fetchAndDownloadFromNetwork(query2, song, parentDir)
            if (cover2 != null) {
                Log.d(TAG, "🎯 策略2降级命中: $query2")
                return@withContext cacheAndReturn(cacheKey, cover2)
            }
        }

        Log.d(TAG, "💔 彻底放弃，没有动态封面: ${song.title}")
        return@withContext null
    }

    /**
     * 依次从 Apple -> 网易云 -> QQ 音乐 检索。
     * 如果检索到的是 mp4，直接下载到与歌曲相同目录并返回本地路径。
     */
    private suspend fun fetchAndDownloadFromNetwork(query: String, song: Song, parentDir: File?): String? {
        // 1. Apple Music (返回多为 .m3u8 串流，不下载直接播)
        var networkUrl = fetchAppleMusicCover(query)
        
        // 2. 网易云
        if (networkUrl == null) {
            yield()
            networkUrl = fetchNeteaseCover(query)
        }

        // 3. QQ 音乐
        if (networkUrl == null) {
            yield()
            networkUrl = fetchQQMusicCover(query)
        }

        // 如果找到了封面，且有本地歌曲目录，则尝试下载（过滤 HLS）
        if (networkUrl != null && parentDir != null) {
            return downloadToLocal(networkUrl, parentDir, song.title)
        }

        return networkUrl
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
            if (file.exists() && file.isFile && file.length() > 0) return file.absolutePath
        }
        return null
    }

    /**
     * 将 MP4 下载到歌曲同级目录，实现完美本地化
     */
    private suspend fun downloadToLocal(urlStr: String, parentDir: File, songTitle: String): String? {
        // Apple Music 等使用的 m3u8 是切片流，无法简单作为单文件下载，直接返回原链接交由 ExoPlayer 缓存
        if (urlStr.endsWith(".m3u8") || urlStr.contains(".m3u8")) {
            return urlStr 
        }

        return withContext(Dispatchers.IO) {
            try {
                val safeTitle = getSafeFilename(songTitle)
                val targetFile = File(parentDir, "$safeTitle.mp4")
                
                // 如果其他任务已经下载完毕，直接返回
                if (targetFile.exists() && targetFile.length() > 0) {
                    return@withContext targetFile.absolutePath
                }

                Log.d(TAG, "⬇️ 开始下载动态封面至本地: ${targetFile.absolutePath}")
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000 
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                
                if (conn.responseCode == 200 || conn.responseCode == 206) {
                    conn.inputStream.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "✅ 动态封面下载完成！")
                    return@withContext targetFile.absolutePath
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "视频下载失败，回退使用网络 URL", e)
                }
            }
            // 下载失败则退化为在线播放
            return@withContext urlStr
        }
    }

    private suspend fun fetchAppleMusicCover(query: String): String? {
        try {
            for (country in listOf("cn", "us")) {
                yield()
                val searchUrl = "https://itunes.apple.com/search?term=${Uri.encode(query)}&entity=song&limit=2&country=$country"
                val searchRes = httpGet(searchUrl) ?: continue
                
                val results = JSONObject(searchRes).optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val collectionId = results.getJSONObject(0).optString("collectionId")
                    if (collectionId.isBlank()) continue

                    val ampUrl = "https://amp-api.music.apple.com/v1/catalog/$country/albums/$collectionId"
                    val ampRes = httpGet(ampUrl, useAuth = true) ?: continue
                    
                    val videoUrl = JSONObject(ampRes).optJSONArray("data")
                        ?.optJSONObject(0)?.optJSONObject("attributes")?.optJSONObject("editorialVideo")
                        ?.optJSONObject("motionDetailSquare")?.optString("video")

                    if (!videoUrl.isNullOrBlank() && videoUrl.endsWith(".m3u8")) {
                        return videoUrl
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) Log.e(TAG, "Apple Music fetch failed", e)
        }
        return null
    }

    private suspend fun fetchNeteaseCover(query: String): String? {
        try {
            val searchUrl = "$NETEASE_API_DOMAIN/search?keywords=${Uri.encode(query)}&limit=1"
            val searchRes = httpGet(searchUrl) ?: return null
            
            val songs = JSONObject(searchRes).optJSONObject("result")?.optJSONArray("songs")
            if (songs == null || songs.length() == 0) return null
            
            val songId = songs.getJSONObject(0).optLong("id", 0L)
            if (songId == 0L) return null

            yield() 

            val dynamicCoverUrl = "$NETEASE_API_DOMAIN/song/dynamic/cover?id=$songId"
            val dynamicRes = httpGet(dynamicCoverUrl)
            
            if (dynamicRes != null) {
                val dataObj = JSONObject(dynamicRes).optJSONObject("data")
                if (dataObj != null) {
                    var coverUrl = dataObj.optString("videoPlayUrl")
                    if (coverUrl.isNullOrBlank()) {
                        coverUrl = dataObj.optString("url")
                    }
                    if (!coverUrl.isNullOrBlank()) {
                        return coverUrl.replace("http://", "https://")
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) Log.e(TAG, "Netease fetch error", e)
        }
        return null
    }

    private suspend fun fetchQQMusicCover(query: String): String? {
        try {
            // 1. 搜索拿 QQ 音乐的 mid 或 vid
            val searchUrl = "$QQ_MUSIC_API_DOMAIN/api/search?key=${Uri.encode(query)}"
            val searchRes = httpGet(searchUrl) ?: return null
            
            val list = JSONObject(searchRes).optJSONObject("data")?.optJSONArray("list")
            if (list == null || list.length() == 0) return null

            // 获取第一首搜索结果中的 MV ID
            val songObj = list.getJSONObject(0)
            val vid = songObj.optString("vid", "")
            if (vid.isBlank()) return null

            yield()

            // 2. 获取 MV 链接作为动态封面
            val mvUrl = "$QQ_MUSIC_API_DOMAIN/api/mv?id=$vid"
            val mvRes = httpGet(mvUrl) ?: return null
            
            // 解析 QQ API 返回的 MP4 地址 (大部分 Node API 返回 mp4 URL)
            val urlsObj = JSONObject(mvRes).optJSONObject("data") ?: return null
            // 优先拿清晰度较高的 720p 或 480p 等，没有则遍历拿第一个
            val keys = urlsObj.keys()
            if (keys.hasNext()) {
                val firstKey = keys.next()
                val urlList = urlsObj.optJSONArray(firstKey)
                if (urlList != null && urlList.length() > 0) {
                    val finalUrl = urlList.optString(0)
                    if (finalUrl.isNotBlank()) {
                        Log.d(TAG, "🎬 成功解析QQ音乐动态封面: $finalUrl")
                        return finalUrl
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) Log.e(TAG, "QQ Music fetch error", e)
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