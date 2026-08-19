package com.mardous.booming.data.local.lyrics.ttml

import android.content.Context
import android.media.MediaScannerConnection
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
import java.net.URLEncoder

object UniversalDownloadEngine {
    private const val TAG = "UniversalDownloadEngine"
    private const val API_DOMAIN = "https://my-wangyi-api.onrender.com"

    // 🌟 增加 requestedLevel 字段，记忆用户此时选择的音质
    data class NetSongItem(
        val id: Long, val title: String, val artist: String, val album: String,
        val durationMs: Long, val picUrl: String, val fileSizeStr: String, val format: String,
        val year: String, val requestedLevel: String
    )

    // 🌟 增加 targetLevel 参数：可选 "lossless"(FLAC) 或 "exhigh"(MP3)
    suspend fun searchOrParse(input: String, targetLevel: String): List<NetSongItem> = withContext(Dispatchers.IO) {
        try {
            val inputTrimmed = input.trim()
            val idMatch = Regex("""[?&]id=(\d+)""").find(inputTrimmed)
            
            val idsToFetch = mutableListOf<Long>()
            
            if (idMatch != null) {
                idsToFetch.add(idMatch.groupValues[1].toLong())
            } else {
                val limit = if (!inputTrimmed.contains(" ") && !inputTrimmed.contains("-")) 30 else 8
                val encodedQuery = URLEncoder.encode(inputTrimmed, "UTF-8")
                val searchUrl = "$API_DOMAIN/search?keywords=$encodedQuery&type=1&limit=$limit"
                
                val res = httpGet(searchUrl) ?: return@withContext emptyList()
                val songs = JSONObject(res).optJSONObject("result")?.optJSONArray("songs") ?: return@withContext emptyList()
                
                for (i in 0 until songs.length()) {
                    idsToFetch.add(songs.getJSONObject(i).optLong("id"))
                }
            }

            if (idsToFetch.isEmpty()) return@withContext emptyList()

            val idsParam = idsToFetch.joinToString(",")
            val detailUrl = "$API_DOMAIN/song/detail?ids=$idsParam"
            val detailRes = httpGet(detailUrl) ?: return@withContext emptyList()
            val songArray = runCatching { JSONObject(detailRes).optJSONArray("songs") }.getOrNull() ?: return@withContext emptyList()

            // 🌟 批量探针：携带用户指定的音质级别去查询大小
            val urlReq = "$API_DOMAIN/song/url/v1?id=$idsParam&level=$targetLevel"
            val urlRes = httpGet(urlReq)
            val urlArray = if (urlRes != null) runCatching { JSONObject(urlRes).optJSONArray("data") }.getOrNull() else null
            
            val urlMap = mutableMapOf<Long, JSONObject>()
            if (urlArray != null) {
                for (i in 0 until urlArray.length()) {
                    val item = urlArray.getJSONObject(i)
                    urlMap[item.optLong("id")] = item
                }
            }

            val resultList = mutableListOf<NetSongItem>()
            for (i in 0 until songArray.length()) {
                val songObj = songArray.getJSONObject(i)
                val id = songObj.optLong("id")
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

                val dataObj = urlMap[id]
                val sizeBytes = dataObj?.optLong("size", 0L) ?: 0L
                val sizeStr = if (sizeBytes > 0) String.format("%.1f MB", sizeBytes / 1048576.0f) else "大小未知"
                val fallbackType = if (dataObj?.optString("url")?.contains(".flac") == true) "FLAC" else "MP3"
                val format = dataObj?.optString("type")?.takeIf { it.isNotBlank() } ?: fallbackType

                // 🌟 将 targetLevel 一并存入歌曲信息，供下载使用
                resultList.add(NetSongItem(id, title, artist, album, duration, picUrl, sizeStr, format.uppercase(), yearStr, targetLevel))
            }
            
            val orderedList = idsToFetch.mapNotNull { targetId -> resultList.find { it.id == targetId } }
            return@withContext orderedList

        } catch (e: Exception) {
            Log.e(TAG, "Search/Parse failed", e)
            return@withContext emptyList()
        }
    }

    suspend fun downloadSong(context: Context, song: NetSongItem, targetDirectory: File, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        var targetFile: File? = null
        var conn: HttpURLConnection? = null
        try {
            // 🌟 真正下载时，再次根据当初选择的级别进行拉取
            val urlReq = "$API_DOMAIN/song/url/v1?id=${song.id}&level=${song.requestedLevel}"
            val urlRes = httpGet(urlReq) ?: return@withContext null
            val dataObj = runCatching { JSONObject(urlRes).optJSONArray("data")?.getJSONObject(0) }.getOrNull() ?: return@withContext null
            
            val audioUrl = dataObj.optString("url")
            if (audioUrl.isNullOrBlank()) return@withContext null
            
            val safeTitle = song.title.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val safeArtist = song.artist.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val fileName = "$safeArtist - $safeTitle.${song.format.lowercase()}"
            targetFile = File(targetDirectory, fileName)

            conn = URL(audioUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000 
            conn.readTimeout = 30000 
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            val fileSize = conn.contentLength
            if (conn.responseCode != 200 && conn.responseCode != 206) return@withContext null

            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var lastProgress = 0
                    var bytesRead = 0
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

            if (!isActive) {
                targetFile.delete()
                return@withContext null
            }

            injectMetadata(targetFile, song)
            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
            return@withContext targetFile

        } catch (e: Exception) {
            Log.e(TAG, "Download crashed, destroying partial file...", e)
            targetFile?.takeIf { it.exists() }?.delete()
            return@withContext null
        } finally {
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

    private suspend fun httpGet(urlString: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000 
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                return@withContext conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "httpGet failed: $urlString", e)
        } finally {
            conn?.disconnect()
        }
        null
    }

    private suspend fun httpGetBytes(urlString: String): ByteArray? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                return@withContext conn.inputStream.use { it.readBytes() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "httpGetBytes failed: $urlString", e)
        } finally {
            conn?.disconnect()
        }
        null
    }
}