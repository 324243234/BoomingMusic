package com.mardous.booming.data.local.lyrics.ttml

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Base64
import android.util.Log
import com.mardous.booming.data.network.ApiConfigManager
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
    private const val ZNNU_HMAC_KEY = "a09d0f3700a279584e1515354fbe08a7ee1c617f919543142fa625b82f1b5ad0"

    data class NetSongItem(
        val id: Long, val title: String, val artist: String, val album: String,
        val durationMs: Long, val picUrl: String, val fileSizeStr: String, val format: String,
        val year: String, val requestedLevel: String
    )

    suspend fun searchOrParse(context: Context, input: String, targetLevel: String): List<NetSongItem> = withContext(Dispatchers.IO) {
        try {
            val inputTrimmed = input.trim()
            val idMatch = Regex("""[?&]id=(\d+)""").find(inputTrimmed) ?: Regex("""/song/(\d+)""").find(inputTrimmed)
            val idsToFetch = mutableListOf<Long>()
            
            if (idMatch != null) {
                idsToFetch.add(idMatch.groupValues[1].toLong())
            } else {
                val limit = if (inputTrimmed.contains(" ") || inputTrimmed.contains("-")) 30 else 80
                val encodedQuery = URLEncoder.encode(inputTrimmed, "UTF-8").replace("+", "%20")
                
                // 极速主通道
                val publicSearchUrl = "https://music.163.com/api/search/get/web?s=$encodedQuery&type=1&limit=$limit"
                var res = httpGet(publicSearchUrl, timeoutMs = 4000)
                var songs = runCatching { JSONObject(res ?: "").optJSONObject("result")?.optJSONArray("songs") }.getOrNull()
                
                // 熔断降级兜底
                if (songs == null || songs.length() == 0) {
                    val baseUrl = ApiConfigManager.getNeteaseBaseUrl(context)
                    val fallbackUrl = "$baseUrl/search?keywords=$encodedQuery&type=1&limit=$limit"
                    res = httpGet(fallbackUrl, timeoutMs = 45000)
                    songs = runCatching { JSONObject(res ?: "").optJSONObject("result")?.optJSONArray("songs") }.getOrNull() ?: return@withContext emptyList()
                }
                
                for (i in 0 until songs.length()) {
                    idsToFetch.add(songs.getJSONObject(i).optLong("id"))
                }
            }

            if (idsToFetch.isEmpty()) return@withContext emptyList()

            val idsParam = idsToFetch.joinToString(",")
            
            val publicDetailUrl = "https://music.163.com/api/song/detail?ids=[$idsParam]"
            var detailRes = httpGet(publicDetailUrl, timeoutMs = 4000)
            var songArray = runCatching { JSONObject(detailRes ?: "").optJSONArray("songs") }.getOrNull()
            
            if (songArray == null || songArray.length() == 0) {
                val baseUrl = ApiConfigManager.getNeteaseBaseUrl(context)
                val fallbackDetailUrl = "$baseUrl/song/detail?ids=$idsParam"
                detailRes = httpGet(fallbackDetailUrl, timeoutMs = 45000)
                songArray = runCatching { JSONObject(detailRes ?: "").optJSONArray("songs") }.getOrNull() ?: return@withContext emptyList()
            }

            var singleSongSizeStr = "点击破盾下载"
            if (idsToFetch.size == 1) {
                val realSize = fetchZnnuSingleSongSize(idsToFetch[0].toString(), targetLevel)
                if (!realSize.isNullOrBlank()) {
                    singleSongSizeStr = realSize
                }
            }
            
            val resultList = mutableListOf<NetSongItem>()
            for (i in 0 until songArray.length()) {
                val songObj = songArray.getJSONObject(i)
                val id = songObj.optLong("id")
                val title = songObj.optString("name")
                
                // 🌟 双向兼容：同时支持 Web API 的 'artists' 和 私有 API 的 'ar'
                val arArray = songObj.optJSONArray("ar") ?: songObj.optJSONArray("artists")
                val artist = (0 until (arArray?.length() ?: 0)).joinToString(" / ") { 
                    arArray?.getJSONObject(it)?.optString("name") ?: "" 
                }
                
                // 🌟 双向兼容：同时支持 Web API 的 'album' 和 私有 API 的 'al'
                val alObj = songObj.optJSONObject("al") ?: songObj.optJSONObject("album")
                val album = alObj?.optString("name") ?: ""
                val picUrl = alObj?.optString("picUrl") ?: ""
                
                // 🌟 双向兼容：同时支持 Web API 的 'duration' 和 私有 API 的 'dt'
                val duration = songObj.optLong("dt").takeIf { it > 0 } ?: songObj.optLong("duration", 0L)
                
                val publishTimeMs = songObj.optLong("publishTime").takeIf { it > 0 } ?: alObj?.optLong("publishTime", 0L) ?: 0L
                val yearStr = if (publishTimeMs > 1000000000L) {
                    java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault()).format(java.util.Date(publishTimeMs))
                } else ""
                
                val format = if (targetLevel == "lossless") "FLAC" else "MP3"
                
                // 🌟 新增：提取官方预估的文件大小（支持无损sq、高解析hr、高音质hMusic）
                val sqBytes = (songObj.optJSONObject("sq") ?: songObj.optJSONObject("hr"))?.optLong("size", 0L) ?: 0L
                val hqBytes = (songObj.optJSONObject("hMusic") ?: songObj.optJSONObject("h"))?.optLong("size", 0L) ?: 0L
                val lqBytes = (songObj.optJSONObject("mMusic") ?: songObj.optJSONObject("m") ?: songObj.optJSONObject("lMusic") ?: songObj.optJSONObject("l"))?.optLong("size", 0L) ?: 0L

                var estSizeStr = "点击下载"
                if (targetLevel == "lossless") {
                    if (sqBytes > 0) {
                        estSizeStr = String.format("~%.1f MB", sqBytes / 1048576.0f)
                    } else if (duration > 0) {
                        // 🌟 核心修复：当 API 隐藏真实 FLAC 大小时，根据歌曲毫秒数预估体积
                        // FLAC 的平均码率约 1000kbps (约 125 KB/s)。
                        estSizeStr = String.format("~%.1f MB (预估)", (duration / 1000) * 125 / 1024.0f)
                    }
                } else {
                    if (hqBytes > 0) {
                        estSizeStr = String.format("~%.1f MB", hqBytes / 1048576.0f)
                    } else if (lqBytes > 0) {
                        estSizeStr = String.format("~%.1f MB", lqBytes / 1048576.0f)
                    }
                }

                val finalSizeStr = if (idsToFetch.size == 1) singleSongSizeStr else estSizeStr

                resultList.add(NetSongItem(id, title, artist, album, duration, picUrl, finalSizeStr, format, yearStr, targetLevel))
            }
            return@withContext idsToFetch.mapNotNull { targetId -> resultList.find { it.id == targetId } }
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }

    suspend fun downloadSong(context: Context, song: NetSongItem, targetDirectory: File, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        var targetFile: File? = null
        var conn: HttpURLConnection? = null
        try {
            var audioUrl = extractZnnuVipUrl(song.id, song.requestedLevel)
            
            if (audioUrl.isNullOrBlank()) {
                val baseUrl = ApiConfigManager.getNeteaseBaseUrl(context)
                val fallbackRes = httpGet("$baseUrl/song/url/v1?id=${song.id}&level=${song.requestedLevel}", timeoutMs = 45000)
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
            conn.connectTimeout = 20000 
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

            injectMetadataSafely(context, targetFile, song)
            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
            return@withContext targetFile
        } catch (e: Exception) {
            targetFile?.takeIf { it.exists() }?.delete()
            return@withContext null
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun injectMetadataSafely(context: Context, audioFile: File, song: NetSongItem) {
        try {
            TagOptionSingleton.getInstance().isAndroid = true
            val f = AudioFileIO.read(audioFile)
            val tag = f.tagOrCreateAndSetDefault
            
            tag.setField(FieldKey.TITLE, song.title)
            tag.setField(FieldKey.ARTIST, song.artist)
            tag.setField(FieldKey.ALBUM, song.album)
            if (song.year.isNotBlank()) tag.setField(FieldKey.YEAR, song.year)
            f.commit()

            try {
                val metaResult = MetadataFetcher.fetchMetadataRaw(
                    context = context,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    duration = song.durationMs,
                    needLrc = true,
                    needCover = true
                )

                val metaFile = AudioFileIO.read(audioFile)
                val metaTag = metaFile.tagOrCreateAndSetDefault
                var isModified = false

                if (!metaResult.lrcWithTrans.isNullOrBlank()) {
                    metaTag.setField(FieldKey.LYRICS, metaResult.lrcWithTrans)
                    File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc").writeText(metaResult.lrcWithTrans)
                    isModified = true
                }

                val finalCoverBytes = metaResult.coverBytes ?: if (song.picUrl.isNotBlank()) httpGetBytes(song.picUrl) else null

                if (finalCoverBytes != null && finalCoverBytes.size > 5000) {
                    val artwork = AndroidArtwork().apply { binaryData = finalCoverBytes; mimeType = "image/jpeg" }
                    metaTag.deleteArtworkField()
                    metaTag.setField(artwork)
                    isModified = true
                }

                if (isModified) metaFile.commit()
            } catch (e: Exception) { }
        } catch (e: Exception) { }
    }

    private class ZnnuAuth(val ip: String, val keyToken: String, val aesKey: String)

    private suspend fun fetchZnnuAuth(): ZnnuAuth? {
        val ipRes = httpGet("https://music.znnu.com/api/ip", mapOf("X-Referer" to "musicParser"))
        val ipObj = runCatching { JSONObject(ipRes ?: "") }.getOrNull() ?: return null
        val ip = ipObj.optString("ip").takeIf { it.isNotEmpty() } ?: ipObj.optJSONObject("data")?.optString("ip") ?: ""

        val keyRes = httpGet("https://music.znnu.com/api/key", mapOf("X-Referer" to "musicParser"))
        val keyData = runCatching { JSONObject(keyRes ?: "").optJSONObject("data") }.getOrNull() ?: return null
        val keyToken = keyData.optString("keyToken")
        val aesKey = keyData.optString("key")

        if (ip.isBlank() || keyToken.isBlank() || aesKey.isBlank()) return null
        return ZnnuAuth(ip, keyToken, aesKey)
    }

    private fun generateSignature(params: Map<String, String>, timestamp: Long, domain: String): String {
        val sortedKeys = params.keys.sorted()
        var signString = "${timestamp}${domain}"
        for (k in sortedKeys) signString += "${k}=${params[k]}"
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(ZNNU_HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)
        val hashBytes = mac.doFinal(signString.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun decryptZnnuResponse(responseJson: String, aesKeyB64: String): String? {
        return try {
            val responseObj = JSONObject(responseJson)
            if (responseObj.optInt("code") != 200) return null
            val dataObj = responseObj.optJSONObject("data") ?: return null
            if (dataObj.optInt("enc") != 1) return dataObj.toString()

            val ivB64 = dataObj.optString("iv")
            val ciphertextB64 = dataObj.optString("ciphertext")
            val tagB64 = dataObj.optString("tag")

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
        } catch (e: Exception) { null }
    }

    private suspend fun extractZnnuVipUrl(songId: Long, level: String): String? {
        try {
            val auth = fetchZnnuAuth() ?: return null
            val timestamp = System.currentTimeMillis() / 1000
            val domain = "music.znnu.com"
            val rawInput = "https://music.163.com/song?id=$songId"

            val params = mapOf("act" to "song", "id" to songId.toString(), "ip" to auth.ip, "level" to level, "rawInput" to rawInput)
            val signature = generateSignature(params, timestamp, domain)

            val formBody = StringBuilder()
            params.forEach { (k, v) ->
                if (formBody.isNotEmpty()) formBody.append("&")
                formBody.append(k).append("=").append(URLEncoder.encode(v, "UTF-8").replace("+", "%20"))
            }
            formBody.append("&signature=$signature&timestamp=$timestamp&domain=$domain")

            val songRes = httpPostForm("https://music.znnu.com/api/song", formBody.toString(), mapOf("X-Key-Token" to auth.keyToken, "X-Referer" to "musicParser")) ?: return null
            val decryptedJson = decryptZnnuResponse(songRes, auth.aesKey) ?: return null
            return JSONObject(decryptedJson).optString("url")
        } catch (e: Exception) { return null }
    }

    private suspend fun fetchZnnuSingleSongSize(songId: String, level: String): String? {
        try {
            val auth = fetchZnnuAuth() ?: return null
            val timestamp = System.currentTimeMillis() / 1000
            val domain = "music.znnu.com"
            val rawInput = "https://music.163.com/song?id=$songId"

            val params = mapOf("act" to "song", "id" to songId, "ip" to auth.ip, "level" to level, "rawInput" to rawInput)
            val signature = generateSignature(params, timestamp, domain)

            val formBody = StringBuilder()
            params.forEach { (k, v) ->
                if (formBody.isNotEmpty()) formBody.append("&")
                formBody.append(k).append("=").append(URLEncoder.encode(v, "UTF-8").replace("+", "%20"))
            }
            formBody.append("&signature=$signature&timestamp=$timestamp&domain=$domain")

            val songRes = httpPostForm("https://music.znnu.com/api/song", formBody.toString(), mapOf("X-Key-Token" to auth.keyToken, "X-Referer" to "musicParser")) ?: return null
            val decryptedStr = decryptZnnuResponse(songRes, auth.aesKey) ?: return null
            val responseObj = JSONObject(decryptedStr)
            
            var sizeStr = responseObj.optString("size")
            if (sizeStr.isNotBlank() && sizeStr.all { it.isDigit() }) {
                val bytes = sizeStr.toLongOrNull() ?: 0L
                return if (bytes > 0) String.format("%.1f MB", bytes / 1048576.0f) else null
            }
            return sizeStr.takeIf { it.isNotBlank() && it != "null" }
        } catch (e: Exception) { return null }
    }

    private suspend fun httpGet(urlString: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (urlString.contains("163.com")) {
                conn.setRequestProperty("Referer", "https://music.163.com/")
            }
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
            conn.connectTimeout = 15000; conn.readTimeout = 15000
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
            conn.connectTimeout = 15000; conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) return@withContext conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {} finally { conn?.disconnect() }
        null
    }
}