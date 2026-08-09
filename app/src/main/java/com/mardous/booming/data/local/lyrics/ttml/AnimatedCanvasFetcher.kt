package com.mardous.booming.data.local.lyrics.ttml

import android.net.Uri
import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.media.isArtistNameUnknown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AnimatedCanvasFetcher {
    private const val TAG = "AnimatedCanvasFetcher"
    private const val APPLE_TOKEN = "eyJhbGciOiJFUzI1NiIsImtpZCI6MldVTUZPQjA2MyJ9.eyJpc3MiOiJBNTZEUjg1TTRTIiwiaWF0IjoxNTc4NTI2NzI2LCJleHAiOjE3NzA0MzYzMjZ9.S6x2XGf7OqS6cZJ_3eG0W8gA4vN4aT3q9Z1aW3bX5cY"

    suspend fun fetchCanvasUri(song: Song): String? = withContext(Dispatchers.IO) {
        // 1. 本地优先：零网络消耗
        try {
            val songFile = File(song.data)
            val parentDir = songFile.parentFile
            if (parentDir != null && parentDir.exists()) {
                val possibleNames = listOf(songFile.nameWithoutExtension, "${song.artistName} - ${song.title}")
                for (name in possibleNames) {
                    val localVideo = File(parentDir, "$name.mp4")
                    if (localVideo.exists() && localVideo.isFile) return@withContext localVideo.absolutePath
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Local check failed", e) }

        // 2. 远端静默刮削：1:1 正方形画幅
        val rawTitle = song.title.replace(Regex("""^\s*\d{1,4}\s*[-_.]?\s*"""), "")
            .replace(Regex("""\(.*?(Remaster|Live|翻唱|伴奏|现场|DJ).*?\)"""), "").replace(Regex("""\[.*?\]|\【.*?\】"""), "").trim()
        val rawArtist = if (song.isArtistNameUnknown()) "" else song.artistName
        val cleanQuery = (if (rawArtist.isBlank()) rawTitle else "$rawArtist $rawTitle").replace(Regex("""[-_／/]"""), " ").trim()

        try {
            for (country in listOf("cn", "us")) {
                val searchUrl = "https://itunes.apple.com/search?term=${Uri.encode(cleanQuery)}&entity=song&limit=2&country=$country"
                val searchRes = httpGet(searchUrl) ?: continue
                val results = JSONObject(searchRes).optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val collectionId = results.getJSONObject(0).optString("collectionId")
                    if (collectionId.isBlank()) continue

                    val ampUrl = "https://amp-api.music.apple.com/v1/catalog/$country/albums/$collectionId"
                    val ampRes = httpGet(ampUrl, useAuth = true) ?: continue
                    
                    val editorialVideo = JSONObject(ampRes).optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")?.optJSONObject("editorialVideo")
                    val videoUrl = editorialVideo?.optJSONObject("motionDetailSquare")?.optString("video")

                    if (!videoUrl.isNullOrBlank() && videoUrl.endsWith(".m3u8")) return@withContext videoUrl
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Network fetch failed", e) }
        return@withContext null
    }

    private fun httpGet(urlString: String, useAuth: Boolean = false): String? {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.readTimeout = 3000 // 极短超时，防止阻塞
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (useAuth) {
                conn.setRequestProperty("Authorization", "Bearer $APPLE_TOKEN")
                conn.setRequestProperty("Origin", "https://music.apple.com")
            }
            if (conn.responseCode == 200) return conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {}
        return null
    }
}