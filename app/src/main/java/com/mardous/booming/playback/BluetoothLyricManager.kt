package com.mardous.booming.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.mardous.booming.data.local.repository.LyricsRepository
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 车载蓝牙歌词核心引擎 (针对 BoomingMusic 框架深度适配版)
 */
@UnstableApi
class BluetoothLyricManager(
    private val player: Player,
    private val coroutineScope: CoroutineScope,
    private val lyricsRepository: LyricsRepository
) {
    private var isHooked = false
    private var hookedMediaId: String = ""
    private var currentLyricsList: List<SyncedLyrics.Line> = emptyList()
    
    private var currentPlayingSongKey: String = ""

    private enum class DisplayState { UNKNOWN, PRELUDE, INTERLUDE, LYRIC }
    private var currentDisplayState = DisplayState.UNKNOWN
    private var currentDisplayIndex = -1

    private var lastPushedTitle: String = ""
    private var lastPushedArtist: String = ""

    private var fetchJob: Job? = null
    private val progressObserver = ProgressObserver(250L)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && currentLyricsList.isNotEmpty()) {
                syncLyrics()
                progressObserver.start { syncLyrics() }
            } else {
                progressObserver.stop()
                if (currentLyricsList.isNotEmpty()) {
                    syncLyrics()
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) {
                currentPlayingSongKey = ""
                progressObserver.stop()
                fetchJob?.cancel()
                currentLyricsList = emptyList()
                restoreOriginalMetadata()
            }
        }
    }

    init {
        player.addListener(playerListener)
    }

    private fun resetStateCache() {
        currentDisplayState = DisplayState.UNKNOWN
        currentDisplayIndex = -1
        lastPushedTitle = ""
        lastPushedArtist = ""
    }

    fun loadLyricsForSong(song: Song) {
        coroutineScope.launch(Dispatchers.Main) {
            val uniqueSongKey = "${song.id}_${song.title}"
            if (uniqueSongKey == currentPlayingSongKey) {
                return@launch
            }

            currentPlayingSongKey = uniqueSongKey
            restoreOriginalMetadata()

            progressObserver.stop()
            fetchJob?.cancel()
            currentLyricsList = emptyList()
            resetStateCache()

            fetchJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    val rawLyrics = lyricsRepository.fileLyrics(song)
                        ?: lyricsRepository.embeddedLyrics(song)
                        ?: lyricsRepository.storedLyrics(song, allowDownload = true)

                    val parsedLyrics = rawLyrics?.let {
                        lyricsRepository.parseRawLyrics(song, it)
                    }

                    withContext(Dispatchers.Main) {
                        handleLyricsResult(parsedLyrics)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        handleLyricsResult(null)
                    }
                }
            }
        }
    }

    private fun handleLyricsResult(lyrics: SyncedLyrics?) {
        if (lyrics != null && lyrics.lines.isNotEmpty()) {
            currentLyricsList = lyrics.lines
            syncLyrics()
            if (player.isPlaying) {
                progressObserver.start { syncLyrics() }
            }
        } else {
            currentLyricsList = emptyList()
            progressObserver.stop()
            restoreOriginalMetadata()
        }
    }

    private fun syncLyrics() {
        if (currentLyricsList.isEmpty()) return

        val latencyCompensationMs = if (player.isPlaying) 400L else 0L
        val compensatedPosition = player.currentPosition + latencyCompensationMs
        
        val currentIndex = currentLyricsList.indexOfLast { it.start <= compensatedPosition }
        val targetState: DisplayState

        if (currentIndex == -1) {
            targetState = DisplayState.PRELUDE
        } else {
            val currentLineObj = currentLyricsList[currentIndex]
            val isInterlude = currentLineObj.content.content.isBlank()
            targetState = if (isInterlude) DisplayState.INTERLUDE else DisplayState.LYRIC
        }

        if (targetState == currentDisplayState && currentIndex == currentDisplayIndex) {
            return
        }

        currentDisplayState = targetState
        currentDisplayIndex = currentIndex

        var titleText = "🎵 🎵 🎵"
        val artistParts = mutableListOf<String>()

        if (targetState == DisplayState.PRELUDE || targetState == DisplayState.INTERLUDE) {
            var nextIdx = currentIndex + 1
            var found = 0
            while (nextIdx < currentLyricsList.size && found < 2) {
                val nextText = currentLyricsList[nextIdx].content.content
                if (nextText.isNotBlank()) {
                    artistParts.add(nextText)
                    found++
                }
                nextIdx++
            }
        } else {
            val currentLineObj = currentLyricsList[currentIndex]
            titleText = currentLineObj.content.content

            currentLineObj.translation?.content?.takeIf { it.isNotBlank() }?.let { artistParts.add(it) }

            var nextIdx = currentIndex + 1
            var found = 0
            while (nextIdx < currentLyricsList.size && found < 2) {
                val nextText = currentLyricsList[nextIdx].content.content
                if (nextText.isNotBlank()) {
                    artistParts.add(nextText)
                    found++
                }
                nextIdx++
            }
        }

        val artistText = if (artistParts.isNotEmpty()) artistParts.joinToString("\n") else " "
        pushToBluetooth(titleText, artistText)
    }

    private fun pushToBluetooth(titleText: String, artistText: String) {
        if (titleText == lastPushedTitle && artistText == lastPushedArtist) {
            return
        }

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex >= player.mediaItemCount) return
        
        val currentItem = player.getMediaItemAt(currentIndex)
        val extras = Bundle(currentItem.mediaMetadata.extras ?: Bundle.EMPTY)

        // 备份原始歌曲信息
        val cleanTitle = extras.getString("BT_ORIGINAL_TITLE") ?: currentItem.mediaMetadata.title?.toString() ?: "未知歌曲"
        val cleanArtist = extras.getString("BT_ORIGINAL_ARTIST") ?: currentItem.mediaMetadata.artist?.toString() ?: "未知歌手"
        val cleanAlbum = extras.getString("BT_ORIGINAL_ALBUM") ?: currentItem.mediaMetadata.albumTitle?.toString() ?: "未知专辑"

        if (!extras.containsKey("BT_ORIGINAL_TITLE")) {
            extras.putString("BT_ORIGINAL_TITLE", cleanTitle)
            extras.putString("BT_ORIGINAL_ARTIST", cleanArtist)
            extras.putString("BT_ORIGINAL_ALBUM", cleanAlbum)
        }

        val updatedMetadata = currentItem.mediaMetadata.buildUpon()
            .setTitle(titleText)
            .setArtist(artistText)
            .setAlbumTitle(" ")
            .setExtras(extras)
            .build()

        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(updatedMetadata)
            .build()

        isHooked = true
        hookedMediaId = currentItem.mediaId
        lastPushedTitle = titleText
        lastPushedArtist = artistText

        // 【关键修复】：如果使用的 player 是 AdvancedForwardingPlayer，直接操作其代理的原生 exoPlayer 替换，
        // 避开 AdvancedForwardingPlayer 内部对 replaceMediaItem 的拦截与二次覆盖
        val realPlayer = (player as? AdvancedForwardingPlayer)?.exoPlayer ?: player
        realPlayer.replaceMediaItem(currentIndex, updatedItem)
    }

    private fun restoreOriginalMetadata() {
        if (!isHooked || hookedMediaId.isEmpty()) {
            isHooked = false
            hookedMediaId = ""
            resetStateCache()
            return
        }

        // 通过 MediaId 精准查找要还原的项，解决索引错位问题
        var targetIndex = -1
        for (i in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(i).mediaId == hookedMediaId) {
                targetIndex = i
                break
            }
        }

        if (targetIndex != -1) {
            val itemToRestore = player.getMediaItemAt(targetIndex)
            val extras = itemToRestore.mediaMetadata.extras

            if (extras != null && extras.containsKey("BT_ORIGINAL_TITLE")) {
                val cleanTitle = extras.getString("BT_ORIGINAL_TITLE") ?: "未知歌曲"
                val cleanArtist = extras.getString("BT_ORIGINAL_ARTIST") ?: "未知歌手"
                val cleanAlbum = extras.getString("BT_ORIGINAL_ALBUM") ?: "未知专辑"

                val cleanExtras = Bundle(extras).apply {
                    remove("BT_ORIGINAL_TITLE")
                    remove("BT_ORIGINAL_ARTIST")
                    remove("BT_ORIGINAL_ALBUM")
                }

                val restoredMetadata = itemToRestore.mediaMetadata.buildUpon()
                    .setTitle(cleanTitle)
                    .setArtist(cleanArtist)
                    .setAlbumTitle(cleanAlbum)
                    .setExtras(cleanExtras)
                    .build()

                val restoredItem = itemToRestore.buildUpon()
                    .setMediaMetadata(restoredMetadata)
                    .build()

                val realPlayer = (player as? AdvancedForwardingPlayer)?.exoPlayer ?: player
                realPlayer.replaceMediaItem(targetIndex, restoredItem)
            }
        }

        isHooked = false
        hookedMediaId = ""
        resetStateCache()
    }

    fun stopLyrics() {
        coroutineScope.launch(Dispatchers.Main) {
            progressObserver.stop()
            fetchJob?.cancel()
            currentLyricsList = emptyList()
            currentPlayingSongKey = "" 
            restoreOriginalMetadata()
        }
    }
    
    fun release() {
        stopLyrics()
        player.removeListener(playerListener)
    }
}