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

    // 🌟 Znnu 终极密钥常量
    private const val ZNNU_HMAC_KEY = "a09d0f3700a279584e1515354fbe08a7ee1c617f919543142fa625b82f1b5ad0"

    // 🌟 恢复 year 字段，用于保存从 Znnu 提取的发行年份
    data class NetSongItem(
        val id: Long, val title: String, val artist: String, val album: String,
        val durationMs: Long, val format: String, val fileSizeStr: String,
        val year: String, val requestedLevel: String, val searchFallbackUrl: String
    )

    // ==========================================
    // 🚀 第 1 步：全盘接管 Znnu 搜索接口
    // ==========================================
    suspend fun searchOrParse(input: String, targetLevel: String): List<NetSongItem> = withContext(Dispatchers.IO) {
        try {
            val inputTrimmed = input.trim()
            val idMatch = Regex("""[?&]id=(\d+)""").find(inputTrimmed) ?: Regex("""/song/(\d+)""").find(inputTrimmed)
            val isLink = idMatch != null
            val keywordOrLink = if (isLink) inputTrimmed else inputTrimmed

            val auth = fetchZnnuAuth() ?: return@withContext emptyList()

            val timestamp = System.currentTimeMillis() / 1000
            val domain = "music.znnu.com"
            
            val params = mapOf(
                "act" to "search",
                "keyword" to keywordOrLink,
                "rawInput" to keywordOrLink,
                "ip" to auth.ip
            )

            val signature = generateSignature(params, timestamp, domain)

            val formBody = StringBuilder()
            params.forEach { (k, v) ->
                if (formBody.isNotEmpty()) formBody.append("&")
                formBody.append(k).append("=").append(URLEncoder.encode(v, "UTF-8").replace("+", "%20"))
            }
            formBody.append("&signature=$signature&timestamp=$timestamp&domain=$domain")

            val searchRes = httpPostForm("https://music.znnu.com/api/search", formBody.toString(), auth.keyToken) ?: return@withContext emptyList()
            val decryptedStr = decryptZnnuResponse(searchRes, auth.aesKey) ?: return@withContext emptyList()
            
            val responseObj = JSONObject(decryptedStr)
            val jsonArray = responseObj.optJSONArray("data") ?: responseObj.optJSONObject("data")?.optJSONArray("list") ?: return@withContext emptyList()

            val resultList = mutableListOf<NetSongItem>()
            val maxResults = minOf(jsonArray.length(), 80)
            
            for (i in 0 until maxResults) {
                val item = jsonArray.getJSONObject(i)
                
                val id = item.optLong("id")
                if (id == 0L) continue

                val title = item.optString("name", item.optString("songname", "未知歌曲"))
                val artist = item.optString("artist", item.optString("singer", "未知歌手"))
                val album = item.optString("album", item.optString("albumname", "未知专辑"))
                
                var sizeStr = item.optString("size")
                if (sizeStr.isNotBlank() && sizeStr.all { it.isDigit() }) {
                    val bytes = sizeStr.toLongOrNull() ?: 0L
                    sizeStr = if (bytes > 0) String.format("%.1f MB", bytes / 1048576.0f) else "未知大小"
                } else if (sizeStr.isBlank() || sizeStr == "null") {
                    sizeStr = "未知大小"
                }

                val durationMs = item.optLong("duration", item.optLong("dt", item.optLong("interval", 0L) * 1000L))
                
                // 🌟 提取年份：Znnu 的搜索结果通常带 publishTime（毫秒时间戳）
                val publishTime = item.optLong("publishTime", 0L)
                val yearStr = if (publishTime > 1000000000L) {
                    java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault()).format(java.util.Date(publishTime))
                } else ""

                val fallbackUrl = item.optString("url")
                val format = if (targetLevel == "lossless" || fallbackUrl.contains(".flac", ignoreCase = true)) "FLAC" else "MP3"

                resultList.add(NetSongItem(id, title, artist, album, durationMs, format, sizeStr, yearStr, targetLevel, fallbackUrl))
            }
            
            return@withContext resultList

        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            return@withContext emptyList()
        }
    }

    // ==========================================
    // ⚔️ 第 2 步：纯净下载与安全元数据注入
    // ==========================================
    suspend fun downloadSong(context: Context, song: NetSongItem, targetDirectory: File, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        var targetFile: File? = null
        var conn: HttpURLConnection? = null
        try {
            var audioUrl = extractZnnuSongUrl(song.id.toString(), song.requestedLevel)
            
            if (audioUrl.isNullOrBlank()) {
                Log.w(TAG, "api/song 提取失败，启动 api/search 劫持兜底直链！")
                audioUrl = song.searchFallbackUrl
            }
            
            if (audioUrl.isBlank()) return@withContext null
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

            // 🚀 1. 首先写入 Znnu 自带的基础标签（歌名、歌手、专辑、年份）确保文件 100% 成功落地
            injectBasicMetadata(targetFile, song)

            // 🚀 2. 文件存入后再尝试自动调用 MetadataFetcher 刮削封面和歌词（加一层安全隔离，防网络波动导致下载失败）
            try {
                injectFullMetadata(targetFile, song)
            } catch (e: Exception) {
                Log.w(TAG, "后台自动刮削封面/歌词失败，但不影响已下载的音频: ${e.message}")
            }

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

    // ==========================================
    // 📝 基础元数据写入 (歌名、歌手、专辑、年份)
    // ==========================================
    private fun injectBasicMetadata(audioFile: File, song: NetSongItem) {
        try {
            TagOptionSingleton.getInstance().isAndroid = true
            val f = AudioFileIO.read(audioFile)
            val tag = f.tagOrCreateAndSetDefault
            
            tag.setField(FieldKey.TITLE, song.title)
            tag.setField(FieldKey.ARTIST, song.artist)
            tag.setField(FieldKey.ALBUM, song.album)
            if (song.year.isNotBlank()) tag.setField(FieldKey.YEAR, song.year)

            f.commit()
            Log.d(TAG, "基础元数据写入成功: ${song.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Basic tag injection failed", e)
        }
    }

    // ==========================================
    // 📝 自动调用 MetadataFetcher 注入封面与歌词
    // ==========================================
    private suspend fun injectFullMetadata(audioFile: File, song: NetSongItem) {
        val metaResult = MetadataFetcher.fetchMetadataRaw(
            title = song.title,
            artist = song.artist,
            album = song.album,
            duration = song.durationMs,
            needLrc = true,
            needCover = true
        )

        val f = AudioFileIO.read(audioFile)
        val tag = f.tagOrCreateAndSetDefault
        var modified = false

        if (!metaResult.lrcWithTrans.isNullOrBlank()) {
            tag.setField(FieldKey.LYRICS, metaResult.lrcWithTrans)
            if (audioFile.parentFile != null && audioFile.parentFile!!.exists()) {
                File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc").writeText(metaResult.lrcWithTrans)
            }
            modified = true
        }

        if (metaResult.coverBytes != null && metaResult.coverBytes.size > 5000) {
            val artwork = AndroidArtwork().apply { 
                binaryData = metaResult.coverBytes 
                mimeType = "image/jpeg" 
            }
            tag.deleteArtworkField()
            tag.setField(artwork)
            modified = true
        }

        if (modified) {
            f.commit()
            Log.d(TAG, "自动刮削并注入封面歌词成功: ${song.title}")
        }
    }

    // ==========================================
    // 🔐 Znnu 安全认证与解密核心库
    // ==========================================
    private class ZnnuAuth(val ip: String, val keyToken: String, val aesKey: String)

    private suspend fun fetchZnnuAuth(): ZnnuAuth? {
        val ipRes = httpGet("https://music.znnu.com/api/ip")
        val ipObj = runCatching { JSONObject(ipRes ?: "") }.getOrNull() ?: return null
        val ip = ipObj.optString("ip").takeIf { it.isNotEmpty() } ?: ipObj.optJSONObject("data")?.optString("ip") ?: ""

        val keyRes = httpGet("https://music.znnu.com/api/key")
        val keyData = runCatching { JSONObject(keyRes ?: "").optJSONObject("data") }.getOrNull() ?: return null
        val keyToken = keyData.optString("keyToken")
        val aesKey = keyData.optString("key")

        if (ip.isBlank() || keyToken.isBlank() || aesKey.isBlank()) return null
        return ZnnuAuth(ip, keyToken, aesKey)
    }

    private suspend fun extractZnnuSongUrl(songId: String, level: String): String? {
        val auth = fetchZnnuAuth() ?: return null
        val timestamp = System.currentTimeMillis() / 1000
        val domain = "music.znnu.com"
        val rawInput = "https://music.163.com/song?id=$songId"

        val params = mapOf(
            "act" to "song",
            "id" to songId,
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

        val songRes = httpPostForm("https://music.znnu.com/api/song", formBody.toString(), auth.keyToken) ?: return null
        val decryptedStr = decryptZnnuResponse(songRes, auth.aesKey) ?: return null
        
        return JSONObject(decryptedStr).optString("url")
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

    // ==========================================
    // 🌐 底层网络工具
    // ==========================================
    private suspend fun httpGet(urlString: String, extraHeaders: Map<String, String> = emptyMap()): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("X-Referer", "musicParser")
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode == 200) return@withContext conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {} finally { conn?.disconnect() }
        null
    }

    private suspend fun httpPostForm(urlString: String, formBody: String, keyToken: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("X-Referer", "musicParser")
            if (keyToken.isNotBlank()) conn.setRequestProperty("X-Key-Token", keyToken)
            conn.doOutput = true
            
            conn.outputStream.use { os ->
                val input = formBody.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            if (conn.responseCode == 200) return@withContext conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {} finally { conn?.disconnect() }
        null
    }
}