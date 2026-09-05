package com.mardous.booming.util

import android.content.Context
import android.os.Environment
import android.widget.Toast
import com.mardous.booming.data.local.room.PlaylistWithSongs
import com.mardous.booming.data.local.room.SongEntity
import com.mardous.booming.data.local.room.PlaylistEntity
import com.mardous.booming.data.repository.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object RadioBackupManager {

    suspend fun exportAllRadios(context: Context, radioPlaylists: List<PlaylistWithSongs>) = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            val resolver = appContext.contentResolver
            val collection = android.provider.MediaStore.Files.getContentUri("external")
            
            radioPlaylists.forEach { playlist ->
                if (playlist.songs.isEmpty()) return@forEach
                val safeName = playlist.playlistEntity.playlistName.removePrefix("[Radio]").replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".m3u"
                val folderName = "Music/RadioBackups"
                
                val m3uContent = java.lang.StringBuilder().apply {
                    append("#EXTM3U\r\n")
                    playlist.songs.forEach { song ->
                        append("#EXTINF:0,${song.title}\r\n${song.data}\r\n")
                    }
                }
                
                // 🌟 查询现有文件以覆盖
                val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf(safeName, "$folderName%")
                var existingUri: android.net.Uri? = null
                
                resolver.query(collection, arrayOf(android.provider.MediaStore.MediaColumns._ID), selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                        existingUri = android.content.ContentUris.withAppendedId(collection, id)
                    }
                }
                
                if (existingUri != null) {
                    resolver.openOutputStream(existingUri!!, "wt")?.use { fos ->
                        fos.write(m3uContent.toString().toByteArray(Charsets.UTF_8))
                    }
                } else {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, folderName)
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val newUri = resolver.insert(collection, contentValues)
                    if (newUri != null) {
                        resolver.openOutputStream(newUri)?.use { fos ->
                            fos.write(m3uContent.toString().toByteArray(Charsets.UTF_8))
                        }
                        contentValues.clear()
                        contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(newUri, contentValues, null, null)
                    }
                }
            }
            withContext(Dispatchers.Main) { Toast.makeText(appContext, "电台已备份至 Music/RadioBackups", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) { Toast.makeText(appContext, "备份失败: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    suspend fun importRadioFromM3u(context: Context, repository: Repository, m3uFile: File) = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext // 获取全局上下文防泄漏
    try {
        val playlistName = "[Radio]${m3uFile.nameWithoutExtension}"
        var playlistId = repository.checkPlaylistExists(playlistName).firstOrNull()?.playListId
        if (playlistId == null) playlistId = repository.createPlaylist(PlaylistEntity(playlistName = playlistName))

        val songsToInsert = mutableListOf<SongEntity>()
        var currentTitle = "未知电台"
        var idOffset = 0L // 🌟 引入唯一序列控制

        m3uFile.inputStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim().replace("\uFEFF", "")
                if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                    currentTitle = trimmed.substringAfter(",", "未知电台").trim()
                } else if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                    val uniqueId = (System.currentTimeMillis() * 1000) + idOffset++
                    songsToInsert.add(
                        SongEntity(
                            id = uniqueId, // 🌟 解决防碰撞
                            title = currentTitle,
                            artistName = "网络电台",
                            albumName = "直播流",
                            duration = 0L, 
                            data = trimmed,
                            playlistCreatorId = playlistId,
                            trackNumber = 0, year = 0, size = 0L,
                            dateAdded = System.currentTimeMillis(), dateModified = System.currentTimeMillis(),
                            albumId = -1L, artistId = -1L, albumArtist = "网络电台", genreName = "直播"
                        )
                    )
                    currentTitle = "未知电台" 
                }
            }
        }
        
        if (songsToInsert.isNotEmpty()) {
            // 🌟 同样必须切片 500 条一组进行落库
            songsToInsert.chunked(500).forEach { chunk ->
                repository.insertSongsInPlaylist(chunk)
            }
            withContext(Dispatchers.Main) { Toast.makeText(appContext, "成功导入 ${songsToInsert.size} 个电台！", Toast.LENGTH_SHORT).show() }
        } else {
            withContext(Dispatchers.Main) { Toast.makeText(appContext, "解析失败，未找到有效链接", Toast.LENGTH_SHORT).show() }
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { Toast.makeText(appContext, "解析错误: ${e.message}", Toast.LENGTH_SHORT).show() }
    }
}
}