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
 * 优先级: 本地资产 -> Apple Music -> 网易云官方动态封面
 * 检索降级策略: 优先 [歌手+歌名] -> 查不到则降级 [歌名+专辑名]
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

    // LRU 内存缓存
    private val uriCache = LruCache<String, String>(30)

    suspend fun fetchCanvasUri(song: Song): String? = withContext(Dispatchers.IO) {
        // 1. 检查缓存
        val cacheKey = "${song.artistName}_${song.title}"
        uriCache.get(cacheKey)?.let { 
            Log.d(TAG, "🎯 命中缓存直接返回: $cacheKey")
            return@withContext it 
        }

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

        yield()

        // 3. 提取并清洗检索词
        val rawTitle = song.title.replace(Regex("""^\s*\d{1,4}\s*[-_.]?\s*"""), "")
            .replace(Regex("""\(.*?(Remaster|Live|翻唱|伴奏|现场|DJ).*?\)"""), "")
            .replace(Regex("""\[.*?\]|\【.*?\】"""), "").trim()
            
        val rawArtist = if (song.isArtistNameUnknown()) "" else song.artistName
        
        // 🌟 清洗专辑名（去除类似 "(Deluxe Edition)" 等后缀干扰，提高命中率）
        val rawAlbum = (song.albumName ?: "")
            .replace(Regex("""\(.*?\)"""), "")
            .replace(Regex("""\[.*?\]|\【.*?\】"""), "")
            .trim()

        // ==========================================
        // 🌟 检索策略 1：[歌手 + 歌名]
        // ==========================================
        val query1 = (if (rawArtist.isBlank()) rawTitle else "$rawArtist $rawTitle")
            .replace(Regex("""[-_／/]"""), " ").trim()
            
        val cover1 = fetchFromNetwork(query1)
        if (cover1 != null) {
            Log.d(TAG, "🎯 策略1 [歌手+歌名] 命中: $query1")
            return@withContext cacheAndReturn(cacheKey, cover1)
        }

        // ==========================================
        // 🌟 检索策略 2：降级使用 [歌名 + 专辑名]
        // ==========================================
        if (rawAlbum.isNotBlank() && rawAlbum != rawTitle) {
            val query2 = "$rawTitle $rawAlbum".replace(Regex("""[-_／/]"""), " ").trim()
            val cover2 = fetchFromNetwork(query2)
            if (cover2 != null) {
                Log.d(TAG, "🎯 策略2 [歌名+专辑名] 降级命中: $query2")
                return@withContext cacheAndReturn(cacheKey, cover2)
            }
        }

        Log.d(TAG, "💔 彻底放弃，没有动态封面: ${song.title}")
        return@withContext null
    }

    /**
     * 核心网络轮询逻辑（被不同的检索策略复用）
     */
    private suspend fun fetchFromNetwork(query: String): String? {
        // 优先去 Apple Music 找高质量无损视频
        val appleCover = fetchAppleMusicCover(query)
        if (appleCover != null) return appleCover

        yield()

        // Apple 找不到，再去网易云找官方动态封面
        val neteaseCover = fetchNeteaseCover(query)
        if (neteaseCover != null) return neteaseCover

        return null
    }

    private fun cacheAndReturn(key: String, uri: String): String {
        uriCache.put(key, uri)
        return uri
    }

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

    // 把你的网易云 Cookie 字符串贴在这里 (记得保留 MUSIC_U 等核心字段)
// 修复：使用三引号 """ 包裹，允许长字符串跨行，避免编译报错
    private const val NETEASE_COOKIE = """MUSIC_A_T=1429951568000; MUSIC_R_T=1429951651174; WEVNSM=1.0.0; NTES_CMT_USER_INFO=1229549712%7C%E6%9C%89%E6%80%81%E5%BA%A6%E7%BD%91%E5%8F%8B19in2g%7Chttp%3A%2F%2Fcms-bucket.nosdn.127.net%2F2018%2F08%2F13%2F078ea9f65d954410b62a52ac773875a1.jpeg%7Cfalse%7Cemh1Mjd4aUAxNjMuY29t; NMTID=00OKTuanndT_EArNErqtNlv_WY571UAAAGeSVNWZA; WNMCID=cszhnf.1779346662045.01.0; NTES_P_UTID=u1uIQwroupzYk8OJRleeYMa2eY6rMplb|1782538308; nts_mail_user=huatesport@163.com:-1:1; MUSIC_U=00C53D9FC111B0B0850A763AC0BDEC1A694A4BDFAB85DF5C9700BA9AD92F45095EFA33B95EE6B638CA9BDBF28284C325BA9CE63825941433CA5BF8ACCC6F866616B1DF60C317C814F12A3402B06B9F3B2A9E88E43C268E5E90009E6E1DB4FB4B540946E57FE0A0B04BC2B7E4AEA6C6695665645A694228235F9CAE765B54E9F92E764415C0236D5CD6BAA477493E8EB9EDADB6372C77F9C2177796764BCCECD3D52D9C36973801A7CDF07BE3E19592F90624F9F606E6A7B2F13766485F30074DB4B414E05CED58EDE167825EFDC9C1804EA710FEF43A37FB6DA92747BFF2F8D04576CDA3B73DBDB375865152A35048DE5C7DC3187518073F05563E94E40AB7668B4C9CE6A3748240447F8EDBBB8E4F14177627E6642C43BF9721D209F0CC56209C4FCF78F58015CE94632F1C9315E92E735466F891C086F10785E2197DA16082275CAF1A8BE5AF299CFB748744423A5F601AA79A37CFA0B33BE6F57353476E7BF41B3468551E1B059449BE450BED666E657013C373DE67C160DDEFE9504199C749; __csrf=20a15fb97b798f8dc423db64a192de2f; ntes_kaola_ad=1; JSESSIONID-WYYY=k4%2BKzOqp1NJ0i8tYHXb1s0wClMOwXlrzUVCfGBQ845DSK64TU%2Bc0SeiuuxoQDrhGFQHtMlgZSSkwgUYOzrHJksuY%5CO2HpPAI4eeO8zx%2BYITqvJVGERxkY%2B7O9h0wyawFdMm%2BW4iOz1s%2B4drJxVUwp8NQoF9wSqDe%5CuHl%5CWd1HylDlTs%2F%3A1786308980905; _iuqxldmzr_=33"""

private suspend fun fetchNeteaseCover(query: String): String? {
    try {
        // 1. 搜歌
        val searchUrl = "$NETEASE_API_DOMAIN/search?keywords=${Uri.encode(query)}&limit=1"
        val searchRes = httpGet(searchUrl) ?: return null
        
        val songs = JSONObject(searchRes).optJSONObject("result")?.optJSONArray("songs")
        if (songs == null || songs.length() == 0) return null
        
        val songId = songs.getJSONObject(0).optLong("id", 0L)
        if (songId == 0L) return null

        yield()

        // 2. 请求动态封面，把 cookie 参数直接拼在 URL 末尾
        val dynamicCoverUrl = "$NETEASE_API_DOMAIN/song/dynamic/cover?id=$songId&cookie=${Uri.encode(NETEASE_COOKIE)}"
        val dynamicRes = httpGet(dynamicCoverUrl)
        
        if (dynamicRes != null) {
            val coverUrl = JSONObject(dynamicRes).optJSONObject("data")?.optString("url")
            if (!coverUrl.isNullOrBlank()) {
                return coverUrl.replace("http://", "https://")
            }
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
            conn.connectTimeout = 2000 
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
            conn?.disconnect()
        }
        return null
    }
}