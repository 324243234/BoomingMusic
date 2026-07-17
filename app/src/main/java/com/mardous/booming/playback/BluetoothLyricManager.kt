package com.mardous.booming.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mardous.booming.data.local.repository.LyricsRepository
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 车载蓝牙歌词核心引擎 (GS3 完美体验版)
 * 修复：暂停时歌词退化为歌名的混乱问题
 * 优化：智能时间补偿（播放时预测，暂停时精准静止）
 */
class BluetoothLyricManager(
    private val player: Player,
    private val coroutineScope: CoroutineScope,
    private val lyricsRepository: LyricsRepository
) {
    private var isHooked = false
    private var hookedIndex = -1 
    private var currentLyricsList: List<SyncedLyrics.Line> = emptyList()
    
    private var currentPlayingSongId: Long = -1L

    private enum class DisplayState { UNKNOWN, PRELUDE, INTERLUDE, LYRIC }
    private var currentDisplayState = DisplayState.UNKNOWN
    private var currentDisplayIndex = -1

    private var lastPushedTitle: String = ""
    private var lastPushedArtist: String = ""

    private var fetchJob: Job? = null
    private val progressObserver = ProgressObserver(250L)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && currentLyricsList.isNotEmpty()) {
                    syncLyrics()
                    progressObserver.start { syncLyrics() }
                } else {
                    // 【修复核心】：暂停时只停止轮询，绝对不擦除歌词！
                    progressObserver.stop()
                    if (currentLyricsList.isNotEmpty()) {
                        // 强制再刷一次，将暂停瞬间的精确歌词钉在屏幕上
                        syncLyrics()
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) {
                    currentPlayingSongId = -1L
                    progressObserver.stop()
                    fetchJob?.cancel()
                    currentLyricsList = emptyList()
                    restoreOriginalMetadata()
                }
            }
        })
    }

    private fun resetStateCache() {
        currentDisplayState = DisplayState.UNKNOWN
        currentDisplayIndex = -1
        lastPushedTitle = ""
        lastPushedArtist = ""
    }

    fun loadLyricsForSong(song: Song) {
        if (song.id == currentPlayingSongId) {
            return
        }

        currentPlayingSongId = song.id
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

    private fun handleLyricsResult(lyrics: SyncedLyrics?) {
        if (lyrics != null && lyrics.lines.isNotEmpty()) {
            currentLyricsList = lyrics.lines
            // 无论是在播放还是暂停状态下切歌，只要解析完，立刻铺上屏幕
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

        // 【细节优化】：播放时预测未来 400ms（抵消延迟）；暂停时精准静止（0ms）
        val latencyCompensationMs = if (player.isPlaying) 400L else 0L
        val compensatedPosition = player.currentPosition + latencyCompensationMs
        
        val currentIndex = currentLyricsList.indexOfLast { it.start <= compensatedPosition }
        val targetState: DisplayState

        if (currentIndex == -1) {
            val firstLineStart = currentLyricsList.firstOrNull()?.start ?: 0
            targetState = if (firstLineStart - compensatedPosition > 3000L) DisplayState.PRELUDE else DisplayState.LYRIC
        } else {
            val currentLineObj = currentLyricsList[currentIndex]
            val nextLineStart = currentLyricsList.getOrNull(currentIndex + 1)?.start ?: Long.MAX_VALUE
            val timeSinceCurrent = compensatedPosition - currentLineObj.start
            val timeToNext = nextLineStart - compensatedPosition

            val isInterlude = currentLineObj.content.content.isBlank() || (timeSinceCurrent > 3000L && timeToNext > 5000L)
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
        val currentItem = player.currentMediaItem ?: return
        val extras = currentItem.mediaMetadata.extras ?: Bundle()

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
        hookedIndex = currentIndex
        lastPushedTitle = titleText
        lastPushedArtist = artistText

        player.replaceMediaItem(currentIndex, updatedItem)
    }

    private fun restoreOriginalMetadata() {
        if (!isHooked || hookedIndex < 0 || hookedIndex >= player.mediaItemCount) {
            isHooked = false
            hookedIndex = -1
            resetStateCache()
            return
        }

        val itemToRestore = player.getMediaItemAt(hookedIndex)
        val extras = itemToRestore.mediaMetadata.extras

        if (extras == null || !extras.containsKey("BT_ORIGINAL_TITLE")) {
            isHooked = false
            hookedIndex = -1
            resetStateCache()
            return
        }

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

        player.replaceMediaItem(hookedIndex, restoredItem)

        isHooked = false
        hookedIndex = -1
        resetStateCache()
    }
}