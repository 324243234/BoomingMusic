package com.mardous.booming.data.local.lyrics.ttml

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UniversalDownloadEngine {
    private const val TAG = "UniversalDownloadEngine"
    private const val API_DOMAIN = "https://netease-cloud-music-api-rho-hazel-12.vercel.app"

    data class NetSongItem(
        val id: Long, val title: String, val artist: String, val album: String,
        val durationMs: Long, val picUrl: String, val fileSizeStr: String, val format: String,
        val year: String 
    )

    suspend fun searchOrParse(input: String): List<NetSongItem> = withContext(Dispatchers.IO) {
        try {
            val idMatch = Regex("""[?&]id=(\d+)""").find(input)
            if (idMatch != null) {
                val songId = idMatch.groupValues[1].toLong()
                val detail = fetchSongDetailWithQuality(songId)
                return@withContext if (detail != null) listOf(detail) else emptyList()
            }

            val searchUrl = "$API_DOMAIN/search?keywords=${Uri.encode(input.trim())}&type=1&limit=5"
            val res = httpGet(searchUrl) ?: return@withContext emptyList()
            val songs = JSONObject(res).optJSONObject("result")?.optJSONArray("songs") ?: return@withContext emptyList()

            val resultList = mutableListOf<NetSongItem>()
            for (i in 0 until songs.length()) {
                val id = songs.getJSONObject(i).optLong("id")
                fetchSongDetailWithQuality(id)?.let { resultList.add(it) }
            }
            return@withContext resultList
        } catch (e: Exception) {
            Log.e(TAG, "Search/Parse failed", e)
            return@withContext emptyList()
        }
    }

    private suspend fun fetchSongDetailWithQuality(songId: Long): NetSongItem? {
        val detailUrl = "$API_DOMAIN/song/detail?ids=[$songId]"
        val res = httpGet(detailUrl) ?: return null
        val songObj = runCatching { JSONObject(res).optJSONArray("songs")?.getJSONObject(0) }.getOrNull() ?: return null
        
        val title = songObj.optString("name")
        val artist = (0 until (songObj.optJSONArray("ar")?.length() ?: 0)).joinToString(" / ") { 
            songObj.optJSONArray("ar")?.getJSONObject(it)?.optString("name") ?: "" 
        }
        val album = songObj.optJSONObject("al")?.optString("name") ?: ""
        val picUrl = songObj.optJSONObject("al")?.optString("picUrl") ?: ""
        val duration = songObj.optLong("dt", 0L)
        
        val publishTimeMs = songObj.optLong("publishTime", 0L)
        val yearStr = if (publishTimeMs > 1000000000L) {
            java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault()).format(java.util.Date(publishTimeMs))
        } else ""

        val urlReq = "$API_DOMAIN/song/url/v1?id=$songId&level=lossless"
        val urlRes = httpGet(urlReq) ?: return null
        val dataObj = runCatching { JSONObject(urlRes).optJSONArray("data")?.getJSONObject(0) }.getOrNull()
        
        val sizeBytes = dataObj?.optLong("size", 0L) ?: 0L
        val sizeStr = if (sizeBytes > 0) String.format("%.1f MB", sizeBytes / 1048576.0f) else "未知大小"
        val fallbackType = if (dataObj?.optString("url")?.contains(".flac") == true) "FLAC" else "MP3"
        val format = dataObj?.optString("type")?.takeIf { it.isNotBlank() } ?: fallbackType

        return NetSongItem(songId, title, artist, album, duration, picUrl, sizeStr, format.uppercase(), yearStr)
    }

    // 🌟 内存防护大升级：原子化下载事务，失败立刻粉碎残缺文件
    suspend fun downloadSong(song: NetSongItem, targetDirectory: File, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        var targetFile: File? = null
        var conn: HttpURLConnection? = null
        try {
            val urlReq = "$API_DOMAIN/song/url/v1?id=${song.id}&level=lossless"
            val urlRes = httpGet(urlReq) ?: return@withContext null
            val dataObj = runCatching { JSONObject(urlRes).optJSONArray("data")?.getJSONObject(0) }.getOrNull() ?: return@withContext null
            
            val audioUrl = dataObj.optString("url")
            if (audioUrl.isNullOrBlank()) return@withContext null
            
            val safeTitle = song.title.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val safeArtist = song.artist.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val fileName = "$safeArtist - $safeTitle.${song.format.lowercase()}"
            targetFile = File(targetDirectory, fileName)

            conn = URL(audioUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 30000 // 延长下载流读取超时，防大文件弱网断流
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            val fileSize = conn.contentLength
            if (conn.responseCode != 200 && conn.responseCode != 206) return@withContext null

            // 🌟 强效双重 IO 关闭：使用 .use {} 确保底层流必定释放
            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var lastProgress = 0
                    var bytesRead: Int
                    // 🌟 协程存活检测 (isActive)：如果用户强退界面，立马中断循环！
                    while (isActive && input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (fileSize > 0) {
                            val progress = ((downloaded.toFloat() / fileSize) * 100).toInt()
                            if (progress > lastProgress) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) { onProgress(progress) }
                            }
                        }
                    }
                }
            }

            // 如果是被系统或用户强行取消了下载任务，立即销毁没下载完的残缺文件
            if (!isActive) {
                targetFile.delete()
                return@withContext null
            }

            injectMetadata(targetFile, song)
            return@withContext targetFile

        } catch (e: Exception) {
            // 🌟 任何网络闪断、抛出异常，都会执行这个“自毁程序”，绝不留下垃圾文件！
            Log.e(TAG, "Download crashed, destroying partial file...", e)
            targetFile?.takeIf { it.exists() }?.delete()
            return@withContext null
        } finally {
            // 断开 Socket 连接，归还系统连接池
            conn?.disconnect()
        }
    }

    private suspend fun injectMetadata(audioFile: File, song: NetSongItem) {
        try {
            TagOptionSingleton.getInstance().isAndroid = true
            val f = AudioFileIO.read(audioFile)
            val tag = f.tagOrCreateAndSetDefault
            
            tag.setField(FieldKey.TITLE, song.title)
            tag.setField(FieldKey.ARTIST, song.artist)
            tag.setField(FieldKey.ALBUM, song.album)
            if (song.year.isNotBlank()) tag.setField(FieldKey.YEAR, song.year)

            val metaResult = MetadataFetcher.fetchMetadataRaw(
                title = song.title,
                artist = song.artist,
                album = song.album,
                duration = song.durationMs,
                needLrc = true,
                needCover = true
            )

            if (!metaResult.lrcWithTrans.isNullOrBlank()) {
                tag.setField(FieldKey.LYRICS, metaResult.lrcWithTrans)
                File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc").writeText(metaResult.lrcWithTrans)
            }

            val finalCoverBytes = metaResult.coverBytes ?: run {
                if (song.picUrl.isNotBlank()) httpGetBytes(song.picUrl) else null
            }

            if (finalCoverBytes != null && finalCoverBytes.size > 5000) {
                val artwork = AndroidArtwork()
                artwork.binaryData = finalCoverBytes
                artwork.mimeType = "image/jpeg"
                tag.deleteArtworkField()
                tag.setField(artwork)
            }

            f.commit()
            Log.d(TAG, "满血元数据注入成功: ${song.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Tag injection failed", e)
        }
    }

    // 🌟 API 请求内存优化：强制使用 .use{} 读完立即关闭，不留僵尸流
    private suspend fun httpGet(urlString: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
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
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
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