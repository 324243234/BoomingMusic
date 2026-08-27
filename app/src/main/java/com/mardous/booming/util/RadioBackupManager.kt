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
            val playlistName = "[Radio]${m3uFile.nameWithoutExtension}" // 强加前缀隔离
            var playlistId = repository.checkPlaylistExists(playlistName).firstOrNull()?.playListId
            if (playlistId == null) playlistId = repository.createPlaylist(PlaylistEntity(playlistName = playlistName))

            val songsToInsert = mutableListOf<SongEntity>()
            var currentTitle = "未知电台"

            m3uFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXTINF:")) currentTitle = trimmed.substringAfter(",", "未知电台")
                else if (trimmed.startsWith("http")) {
                    songsToInsert.add(
                        SongEntity(
                            id = System.nanoTime() + kotlin.random.Random.nextInt(10000),
                            title = currentTitle,
                            artistName = "网络电台",
                            albumName = "直播流",
                            duration = 0L, 
                            data = trimmed,
                            playlistCreatorId = playlistId,
                            // 🌟 补齐底层 Entity 强制要求的占位参数
                            trackNumber = 0,
                            year = 0,
                            size = 0L,
                            dateAdded = System.currentTimeMillis(),
                            dateModified = System.currentTimeMillis(),
                            albumId = -1L,
                            artistId = -1L,
                            albumArtist = "网络电台",
                            genreName = "直播"
                        )
                    )
                }
            }
            if (songsToInsert.isNotEmpty()) repository.insertSongsInPlaylist(songsToInsert)
            withContext(Dispatchers.Main) { Toast.makeText(context, "导入成功！", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {}
    }
}