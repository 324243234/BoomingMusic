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
    // 🚀 第 1 级火箭：稳定搜索与链接解析 (纯净 Render 方案)
    // ==========================================
    suspend fun searchOrParse(input: String, targetLevel: String): List<NetSongItem> = withContext(Dispatchers.IO) {
        try {
            val inputTrimmed = input.trim()
            val idMatch = Regex("""[?&]id=(\d+)""").find(inputTrimmed) ?: Regex("""/song/(\d+)""").find(inputTrimmed)
            val idsToFetch = mutableListOf<Long>()
            
            if (idMatch != null) {
                idsToFetch.add(idMatch.groupValues[1].toLong())
            } else {
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

            // 🌟 独家优化：支线任务！如果是单曲解析，偷偷去 Znnu 接口把真实的满血大小拿过来！
            var singleSongSizeStr = "点击破盾下载"
            if (idsToFetch.size == 1) {
                val realSize = fetchZnnuSingleSongSize(idsToFetch[0].toString())
                if (!realSize.isNullOrBlank()) {
                    singleSongSizeStr = realSize
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

                val format = if (targetLevel == "lossless") "FLAC" else "MP3"
                val finalSizeStr = if (idsToFetch.size == 1) singleSongSizeStr else "点击破盾下载"

                resultList.add(NetSongItem(id, title, artist, album, duration, picUrl, finalSizeStr, format, yearStr, targetLevel))
            }
            
            return@withContext idsToFetch.mapNotNull { targetId -> resultList.find { it.id == targetId } }

        } catch (e: Exception) {
            Log.e(TAG, "Search/Parse failed", e)
            return@withContext emptyList()
        }
    }

    // ==========================================
    // ⚔️ 第 2 级火箭：破盾下载流 (100% 走 Znnu 引擎)
    // ==========================================
    suspend fun downloadSong(context: Context, song: NetSongItem, targetDirectory: File, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        var targetFile: File? = null
        var conn: HttpURLConnection? = null
        try {
            var audioUrl = extractZnnuVipUrl(song.id, song.requestedLevel)
            
            if (audioUrl.isNullOrBlank()) {
                Log.w(TAG, "Znnu 解析失败，启动 Render 降级兜底...")
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

            injectMetadataSafely(targetFile, song)
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
    // 🎨 第 3 级火箭：安全隔离的元数据注入
    // ==========================================
    private suspend fun injectMetadataSafely(audioFile: File, song: NetSongItem) {
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

                if (isModified) {
                    metaFile.commit()
                }
            } catch (e: Exception) {
                Log.w(TAG, "满血刮削失败，但音频已安全保存: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Metadata injection critical failure", e)
        }
    }

    // ==========================================
    // 🔐 Znnu 核心破解黑客算法 (AES-GCM & HMAC)
    // ==========================================
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
        for (k in sortedKeys) {
            signString += "${k}=${params[k]}"
        }
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
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun extractZnnuVipUrl(songId: Long, level: String): String? {
        try {
            val auth = fetchZnnuAuth() ?: return null
            val timestamp = System.currentTimeMillis() / 1000
            val domain = "music.znnu.com"
            val rawInput = "https://music.163.com/song?id=$songId"

            val params = mapOf(
                "act" to "song",
                "id" to songId.toString(),
                "ip" to auth.ip,
                "level" to level,
                "rawInput" to rawInput
            )

            val signature = generateSignature(params, timestamp, domain)

            val formBody = StringBuilder()
            params.forEach { (k, v) ->
                if (formBody.isNotEmpty()) formBody.append("&")
                formBody.append(k).append("=").append(URLEncoder.encode(v, "UTF-8").replace("+", "%20"))
            }
            formBody.append("&signature=$signature&timestamp=$timestamp&domain=$domain")

            val songRes = httpPostForm(
                "https://music.znnu.com/api/song",
                formBody.toString(),
                mapOf("X-Key-Token" to auth.keyToken, "X-Referer" to "musicParser")
            ) ?: return null

            // 🌟 大一统：复用通用的 decryptZnnuResponse，一行代码搞定解密！
            val decryptedJson = decryptZnnuResponse(songRes, auth.aesKey) ?: return null
            return JSONObject(decryptedJson).optString("url")

        } catch (e: Exception) {
            Log.e(TAG, "Znnu crack failed", e)
        }
        return null
    }

    // ==========================================
    // 🕵️ 支线任务：Znnu 单曲真实大小安全探测器
    // ==========================================
    private suspend fun fetchZnnuSingleSongSize(songId: String): String? {
        try {
            val auth = fetchZnnuAuth() ?: return null
            val timestamp = System.currentTimeMillis() / 1000
            val domain = "music.znnu.com"
            val rawInput = "https://music.163.com/song?id=$songId"

            val params = mapOf(
                "act" to "search",
                "keyword" to rawInput,
                "rawInput" to rawInput,
                "ip" to auth.ip
            )

            val signature = generateSignature(params, timestamp, domain)

            val formBody = StringBuilder()
            params.forEach { (k, v) ->
                if (formBody.isNotEmpty()) formBody.append("&")
                formBody.append(k).append("=").append(URLEncoder.encode(v, "UTF-8").replace("+", "%20"))
            }
            formBody.append("&signature=$signature&timestamp=$timestamp&domain=$domain")

            val searchRes = httpPostForm(
                "https://music.znnu.com/api/search", 
                formBody.toString(), 
                mapOf("X-Key-Token" to auth.keyToken, "X-Referer" to "musicParser")
            ) ?: return null
            
            val decryptedStr = decryptZnnuResponse(searchRes, auth.aesKey) ?: return null
            
            val responseObj = JSONObject(decryptedStr)
            val jsonArray = responseObj.optJSONArray("data") 
                ?: responseObj.optJSONObject("data")?.optJSONArray("list") 
                ?: responseObj.optJSONArray("list") 
                ?: return null

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                if (item.optLong("id").toString() == songId) {
                    var sizeStr = item.optString("size")
                    if (sizeStr.isNotBlank() && sizeStr.all { it.isDigit() }) {
                        val bytes = sizeStr.toLongOrNull() ?: 0L
                        return if (bytes > 0) String.format("%.1f MB", bytes / 1048576.0f) else null
                    }
                    return sizeStr.takeIf { it.isNotBlank() && it != "null" }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Znnu 支线探测大小失败: ${e.message}")
        }
        return null
    }

    // ==========================================
    // 🌐 底层基础网络通讯库
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