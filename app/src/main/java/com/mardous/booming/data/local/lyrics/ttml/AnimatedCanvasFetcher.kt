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
 * 动态专辑画布引擎 (高可用线性防线版)
 * 优先级: 本地歌曲视频 -> 本地专辑视频 -> Apple Music (画质最优) -> 网易云 MV (华语兜底)
 * 特性: LRU 内存缓存、协作式协程取消防抖、严格连接池释放
 */
object AnimatedCanvasFetcher {

    private const val TAG = "AnimatedCanvasFetcher"
    
    // Apple Music Auth Token
    private const val APPLE_TOKEN = "eyJhbGciOiJFUzI1NiIsImtpZCI6MldVTUZPQjA2MyJ9.eyJpc3MiOiJBNTZEUjg1TTRTIiwiaWF0IjoxNTc4NTI2NzI2LCJleHAiOjE3NzA0MzYzMjZ9.S6x2XGf7OqS6cZJ_3eG0W8gA4vN4aT3q9Z1aW3bX5cY"
    
    // 网易云 API 域名
    private const val NETEASE_API_DOMAIN = "https://my-wangymusic-api.vercel.app"

    // 支持的本地视频格式
    private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm")

    // LRU 内存缓存 (最多缓存 30 首歌的动态背景 URI，防来回切歌)
    private val uriCache = LruCache<String, String>(30)

    suspend fun fetchCanvasUri(song: Song): String? = withContext(Dispatchers.IO) {
        // 1. 检查缓存 (使用 歌手+歌名 作为唯一标识)
        val cacheKey = "${song.artistName}_${song.title}"
        uriCache.get(cacheKey)?.let { 
            Log.d(TAG, "🎯 命中缓存直接返回: $cacheKey")
            return@withContext it 
        }

        // 检查 1：如果此时用户已经切歌，立刻中断，不碰本地 IO
        yield()

        // 2. 本地资产检索
        val parentDir = File(song.data).parentFile
        if (parentDir != null && parentDir.exists()) {
            val songVideo = checkLocalVideo(parentDir, File(song.data).nameWithoutExtension) 
                ?: checkLocalVideo(parentDir, "${song.artistName} - ${song.title}")
            if (songVideo != null) return@withContext cacheAndReturn(cacheKey, songVideo)

            val albumName = song.albumName
            if (!albumName.isNullOrBlank()) {
                val albumVideo = checkLocalVideo(parentDir, albumName)
                if (albumVideo != null) return@withContext cacheAndReturn(cacheKey, albumVideo)
            }
        }

        // 检查 2：进入耗时网络请求前，再次确认未被切歌打断
        yield()

        // 3. 提取并清洗检索词，提高命中率
        val rawTitle = song.title.replace(Regex("""^\s*\d{1,4}\s*[-_.]?\s*"""), "")
            .replace(Regex("""\(.*?(Remaster|Live|翻唱|伴奏|现场|DJ).*?\)"""), "")
            .replace(Regex("""\[.*?\]|\【.*?\】"""), "").trim()
        val rawArtist = if (song.isArtistNameUnknown()) "" else song.artistName
        
        val cleanQuery = (if (rawArtist.isBlank()) rawTitle else "$rawArtist $rawTitle")
            .replace(Regex("""[-_／/]"""), " ").trim()

        // 4. Apple Music AMP API (画质最优，正方形裁切完美适配车机横屏)
        val appleCover = fetchAppleMusicCover(cleanQuery)
        if (appleCover != null) return@withContext cacheAndReturn(cacheKey, appleCover)

        // 检查 3：Apple 没查到，准备请求网易云之前，确认未被切歌打断
        yield()

        // 5. 网易云 API 兜底 (通过获取歌曲关联的 MV 直链)
        val neteaseCover = fetchNeteaseCover(cleanQuery)
        if (neteaseCover != null) return@withContext cacheAndReturn(cacheKey, neteaseCover)

        return@withContext null
    }

    /**
     * 辅助方法：存入缓存并返回
     */
    private fun cacheAndReturn(key: String, uri: String): String {
        uriCache.put(key, uri)
        Log.d(TAG, "💾 存入缓存并返回: $key -> $uri")
        return uri
    }

    /**
     * 辅助方法：检查本地视频
     */
    private fun checkLocalVideo(dir: File, targetName: String): String? {
        val safeName = targetName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        for (ext in VIDEO_EXTENSIONS) {
            val file = File(dir, "$safeName$ext")
            if (file.exists() && file.isFile) return file.absolutePath
        }
        return null
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
            if (e is CancellationException) throw e
            Log.e(TAG, "Apple Music fetch failed", e)
        }
        return null
    }

    private suspend fun fetchNeteaseCover(query: String): String? {
        try {
            // 第一步：搜歌拿 MVID
            val searchUrl = "$NETEASE_API_DOMAIN/search?keywords=${Uri.encode(query)}&limit=1"
            val searchRes = httpGet(searchUrl) ?: return null
            
            val songs = JSONObject(searchRes).optJSONObject("result")?.optJSONArray("songs")
            if (songs == null || songs.length() == 0) return null
            
            val mvid = songs.getJSONObject(0).optInt("mvid", 0)
            if (mvid == 0) return null

            yield() // 发起第二次请求前检查

            // 第二步：通过 MVID 拿视频直链
            val mvUrl = "$NETEASE_API_DOMAIN/mv/url?id=$mvid"
            val mvRes = httpGet(mvUrl) ?: return null
            
            val videoUrl = JSONObject(mvRes).optJSONObject("data")?.optString("url")

            if (!videoUrl.isNullOrBlank()) {
                return videoUrl.replace("http://", "https://")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Netease fetch error", e)
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
            conn.connectTimeout = 2000 // 严格控制超时
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            if (useAuth) {
                conn.setRequestProperty("Authorization", "Bearer $APPLE_TOKEN")
                conn.setRequestProperty("Origin", "https://music.apple.com")
            }
            
            if (conn.responseCode == 200) {
                return conn.inputStream.bufferedReader().readText()
            }
        } finally {
            // 核心防泄漏：无论成功、失败还是协程被取消，都强制释放 Socket 连接
            conn?.disconnect()
        }
        return null
    }
}