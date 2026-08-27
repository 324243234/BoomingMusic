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
            val backupDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "RadioBackups")
            if (!backupDir.exists()) backupDir.mkdirs()

            radioPlaylists.forEach { playlist ->
                if (playlist.songs.isEmpty()) return@forEach
                val safeName = playlist.playlistEntity.playlistName.removePrefix("[Radio]").replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val m3uFile = File(backupDir, "${safeName}.m3u")
                
                val m3uContent = StringBuilder().apply {
                    append("#EXTM3U\r\n")
                    playlist.songs.forEach { song ->
                        append("#EXTINF:0,${song.title}\r\n${song.data}\r\n") // 0 代表直播流
                    }
                }
                FileOutputStream(m3uFile, false).use { fos ->
                    fos.write(m3uContent.toString().toByteArray(Charsets.UTF_8))
                    fos.flush(); fos.fd.sync()
                }
            }
            withContext(Dispatchers.Main) { Toast.makeText(context, "电台已备份至 Music/RadioBackups", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {}
    }

    suspend fun importRadioFromM3u(context: Context, repository: Repository, m3uFile: File) = withContext(Dispatchers.IO) {
        try {
            val playlistName = "[Radio]${m3uFile.nameWithoutExtension}" // 完美适配刚才用户自定义的文件名
            var playlistId = repository.checkPlaylistExists(playlistName).firstOrNull()?.playListId
            if (playlistId == null) playlistId = repository.createPlaylist(PlaylistEntity(playlistName = playlistName))

            val songsToInsert = mutableListOf<SongEntity>()
            var currentTitle = "未知电台"

            // 🌟 智能过滤不可见乱码（UTF-8 BOM 头），防止提取失败
            val rawContent = String(m3uFile.readBytes(), Charsets.UTF_8).replace("\uFEFF", "")

            rawContent.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                    currentTitle = trimmed.substringAfter(",", "未知电台").trim()
                }
                else if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                    songsToInsert.add(
                        SongEntity(
                            id = System.currentTimeMillis() + kotlin.random.Random.nextInt(10000), // 🌟 换成绝对正数时间戳
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
                    currentTitle = "未知电台" // 提取完成立即重置
                }
            }
            if (songsToInsert.isNotEmpty()) {
                repository.insertSongsInPlaylist(songsToInsert)
                withContext(Dispatchers.Main) { Toast.makeText(context, "成功导入 ${songsToInsert.size} 个电台！", Toast.LENGTH_SHORT).show() }
            } else {
                withContext(Dispatchers.Main) { Toast.makeText(context, "解析失败，未找到有效链接", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { Toast.makeText(context, "解析错误: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }
}