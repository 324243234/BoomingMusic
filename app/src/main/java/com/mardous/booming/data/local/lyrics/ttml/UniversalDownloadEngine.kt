package com.mardous.booming.data.local.lyrics.ttml

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Base64
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
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object UniversalDownloadEngine {
    private const val TAG = "UniversalDownloadEngine"
    private const val RENDER_API = "https://my-wangyi-api.onrender.com"

    // 🌟 Znnu 终极密钥常量
    private const val ZNNU_HMAC_KEY = "a09d0f3700a279584e1515354fbe08a7ee1c617f919543142fa625b82f1b5ad0"

    data class NetSongItem(
        val id: Long, val title: String, val artist: String, val album: String,
        val durationMs: Long, val picUrl: String, val fileSizeStr: String, val format: String,
        val year: String, val requestedLevel: String
    )

    // ==========================================
    // 🌟 1. 用极其稳定的 Render 节点进行搜索（绝不出现搜不到结果的问题）
    // ==========================================
    suspend fun searchOrParse(input: String, targetLevel: String): List<NetSongItem> = withContext(Dispatchers.IO) {
        try {
            val inputTrimmed = input.trim()
            val idMatch = Regex("""[?&]id=(\d+)""").find(inputTrimmed) ?: Regex("""/song/(\d+)""").find(inputTrimmed)
            val idsToFetch = mutableListOf<Long>()
            
            if (idMatch != null) {
                idsToFetch.add(idMatch.groupValues[1].toLong())
            } else {
                // 🌟 智能动态限流策略：
                // 如果包含空格或连字符（例如 "周杰伦 晴天" 或 "artist-title"），说明是精确搜索，限制为 30 首以追求极致速度；
                // 如果只是单纯的单关键词（例如 "晴天"），则放开至 80 首以提供更丰富的候选。
                val limit = if (inputTrimmed.contains(" ") || inputTrimmed.contains("-")) 30 else 80
                val encodedQuery = URLEncoder.encode(inputTrimmed, "UTF-8").replace("+", "%20")
                val searchUrl = "$RENDER_API/search?keywords=$encodedQuery&type=1&limit=$limit"
                
                val res = httpGet(searchUrl) ?: return@withContext emptyList()
                val songs = JSONObject(res).optJSONObject("result")?.optJSONArray("songs") ?: return@withContext emptyList()
                
                for (i in 0 until songs.length()) {
                    idsToFetch.add(songs.getJSONObject(i).optLong("id"))
                }
            }

            if (idsToFetch.isEmpty()) return@withContext emptyList()

            val idsParam = idsToFetch.joinToString(",")
            val detailUrl = "$RENDER_API/song/detail?ids=$idsParam"
            val detailRes = httpGet(detailUrl) ?: return@withContext emptyList()
            val songArray = runCatching { JSONObject(detailRes).optJSONArray("songs") }.getOrNull() ?: return@withContext emptyList()

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

                val format = if (targetLevel == "lossless") "FLAC" else "MP3"

                resultList.add(NetSongItem(id, title, artist, album, duration, picUrl, "点击下载", format, yearStr, targetLevel))
            }
            
            return@withContext idsToFetch.mapNotNull { targetId -> resultList.find { it.id == targetId } }

        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            return@withContext emptyList()
        }
    }

    // ==========================================
    // 🌟 2. 彻底抛弃网易云下载，全盘走 Znnu 破盾下载！
    // ==========================================
    suspend fun downloadSong(context: Context, song: NetSongItem, targetDirectory: File, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        var targetFile: File? = null
        var conn: HttpURLConnection? = null
        try {
            // 🚀 核心战区：启动黑客引擎劫持真实下载直链！
            var audioUrl = extractZnnuVipUrl(song.id, song.requestedLevel)
            
            if (audioUrl.isNullOrBlank()) {
                Log.e(TAG, "Znnu 解析失败，启动 Render 降级兜底...")
                val fallbackRes = httpGet("$RENDER_API/song/url/v1?id=${song.id}&level=${song.requestedLevel}")
                audioUrl = runCatching { JSONObject(fallbackRes ?: "").optJSONArray("data")?.getJSONObject(0)?.optString("url") }.getOrNull()
            }
            
            if (audioUrl.isNullOrBlank()) return@withContext null
            audioUrl = audioUrl.replace("http://", "https://") 
            
            val actualFormat = if (audioUrl.contains(".flac", ignoreCase = true)) "flac" else "mp3"
            
            val safeTitle = song.title.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val safeArtist = song.artist.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val fileName = "$safeArtist - $safeTitle.$actualFormat"
            targetFile = File(targetDirectory, fileName)

            conn = URL(audioUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000 
            conn.readTimeout = 60000 
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Referer", "https://music.znnu.com/")
            
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
            Log.e(TAG, "Download crashed", e)
            targetFile?.takeIf { it.exists() }?.delete()
            return@withContext null
        } finally {
            conn?.disconnect()
        }
    }

    // ==========================================
    // ⚔️ 终极 Znnu 破盾引擎 (完美还原 JS 算法)
    // ==========================================
    private suspend fun extractZnnuVipUrl(songId: Long, level: String): String? {
        try {
            val ipRes = httpGet("https://music.znnu.com/api/ip", mapOf("X-Referer" to "musicParser"))
            val ipObj = JSONObject(ipRes ?: "")
            val ip = ipObj.optString("ip").takeIf { it.isNotEmpty() } ?: ipObj.optJSONObject("data")?.optString("ip") ?: ""
            if (ip.isBlank()) return null

            val keyRes = httpGet("https://music.znnu.com/api/key", mapOf("X-Referer" to "musicParser"))
            val keyData = JSONObject(keyRes ?: "").optJSONObject("data") ?: return null
            val aesKeyB64 = keyData.optString("key")
            val keyToken = keyData.optString("keyToken")

            val timestamp = System.currentTimeMillis() / 1000
            val domain = "music.znnu.com"
            val rawInput = "https://music.163.com/song?id=$songId"

            val params = mapOf(
                "act" to "song",
                "id" to songId.toString(),
                "ip" to ip,
                "level" to level,
                "rawInput" to rawInput
            )

            val sortedKeys = params.keys.sorted()
            var signString = "${timestamp}${domain}"
            for (k in sortedKeys) {
                signString += "${k}=${params[k]}"
            }
            
            val mac = Mac.getInstance("HmacSHA256")
            val secretKeySpec = SecretKeySpec(ZNNU_HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256")
            mac.init(secretKeySpec)
            val hashBytes = mac.doFinal(signString.toByteArray(Charsets.UTF_8))
            val signature = hashBytes.joinToString("") { "%02x".format(it) }

            val formBody = StringBuilder()
            params.forEach { (k, v) ->
                if (formBody.isNotEmpty()) formBody.append("&")
                formBody.append(k).append("=").append(URLEncoder.encode(v, "UTF-8"))
            }
            formBody.append("&signature=$signature")
            formBody.append("&timestamp=$timestamp")
            formBody.append("&domain=$domain")

            val songRes = httpPostForm(
                "https://music.znnu.com/api/song",
                formBody.toString(),
                mapOf("X-Key-Token" to keyToken, "X-Referer" to "musicParser")
            ) ?: return null

            val songObj = JSONObject(songRes)
            if (songObj.optInt("code") == 200) {
                val dataObj = songObj.optJSONObject("data") ?: return null
                if (dataObj.optInt("enc") == 1) {
                    val iv = dataObj.optString("iv")
                    val ciphertext = dataObj.optString("ciphertext")
                    val tag = dataObj.optString("tag")
                    
                    val decryptedJson = decryptAESGCM(aesKeyB64, iv, ciphertext, tag)
                    if (decryptedJson != null) {
                        return JSONObject(decryptedJson).optString("url")
                    }
                } else {
                    return dataObj.optString("url")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Znnu crack failed", e)
        }
        return null
    }

    private fun decryptAESGCM(aesKeyB64: String, ivB64: String, ciphertextB64: String, tagB64: String): String? {
        return try {
            val keyBytes = Base64.decode(aesKeyB64, Base64.DEFAULT)
            val ivBytes = Base64.decode(ivB64, Base64.DEFAULT) 
            val cipherBytes = Base64.decode(ciphertextB64, Base64.DEFAULT)
            val tagBytes = Base64.decode(tagB64, Base64.DEFAULT)

            val combined = ByteArray(cipherBytes.size + tagBytes.size)
            System.arraycopy(cipherBytes, 0, combined, 0, cipherBytes.size)
            System.arraycopy(tagBytes, 0, combined, cipherBytes.size, tagBytes.size)

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, ivBytes)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            
            String(cipher.doFinal(combined), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // ==========================================
    // 🎵 元数据注入模块 (封面/歌词/属性)
    // ==========================================
    private suspend fun injectMetadata(audioFile: File, song: NetSongItem) {
        try {
            TagOptionSingleton.getInstance().isAndroid = true
            val f = AudioFileIO.read(audioFile)
            val tag = f.tagOrCreateAndSetDefault
            
            tag.setField(FieldKey.TITLE, song.title)
            tag.setField(FieldKey.ARTIST, song.artist)
            tag.setField(FieldKey.ALBUM, song.album)
            if (song.year.isNotBlank()) tag.setField(FieldKey.YEAR, song.year)

            val metaResult = MetadataFetcher.fetchMetadataRaw(song.title, song.artist, song.album, song.durationMs, needLrc = true, needCover = true)

            if (!metaResult.lrcWithTrans.isNullOrBlank()) {
                tag.setField(FieldKey.LYRICS, metaResult.lrcWithTrans)
                File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc").writeText(metaResult.lrcWithTrans)
            }

            val finalCoverBytes = metaResult.coverBytes ?: if (song.picUrl.isNotBlank()) httpGetBytes(song.picUrl) else null

            if (finalCoverBytes != null && finalCoverBytes.size > 5000) {
                val artwork = AndroidArtwork().apply { binaryData = finalCoverBytes; mimeType = "image/jpeg" }
                tag.deleteArtworkField()
                tag.setField(artwork)
            }

            f.commit()
        } catch (e: Exception) {}
    }

    // ==========================================
    // 🌐 底层网络通讯库
    // ==========================================
    private suspend fun httpGet(urlString: String, headers: Map<String, String> = emptyMap()): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode == 200) return@withContext conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {} finally { conn?.disconnect() }
        null
    }

    private suspend fun httpPostForm(urlString: String, formBody: String, headers: Map<String, String> = emptyMap()): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.doOutput = true
            
            conn.outputStream.use { os ->
                val input = formBody.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            if (conn.responseCode == 200) return@withContext conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {} finally { conn?.disconnect() }
        null
    }

    private suspend fun httpGetBytes(urlString: String): ByteArray? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) return@withContext conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {} finally { conn?.disconnect() }
        null
    }
}