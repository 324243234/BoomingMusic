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
 * 车载蓝牙歌词核心引擎 (GS3 终极零功耗 + 延迟补偿版)
 * 修复：精准调用真实的 Repo 解析方法
 * 优化：零内存分配拦截 + 400ms前置补偿 + 纯音符间奏
 */
class BluetoothLyricManager(
    private val player: Player,
    private val coroutineScope: CoroutineScope,
    private val lyricsRepository: LyricsRepository
) {
    private var isHooked = false
    private var currentLyricsList: List<SyncedLyrics.Line> = emptyList()
    
    // 【发热终结者】：状态机缓存，阻止无效的内存分配
    private enum class DisplayState { UNKNOWN, PRELUDE, INTERLUDE, LYRIC }
    private var currentDisplayState = DisplayState.UNKNOWN
    private var currentDisplayIndex = -1

    private var lastPushedTitle: String = ""
    private var lastPushedArtist: String = ""

    private var fetchJob: Job? = null

    // 250ms 轮询，配合零分配状态机，极速又极其省电
    private val progressObserver = ProgressObserver(250L)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && currentLyricsList.isNotEmpty()) {
                    syncLyrics()
                    progressObserver.start { syncLyrics() }
                } else {
                    progressObserver.stop()
                    restoreOriginalMetadata()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                progressObserver.stop()
                fetchJob?.cancel()
                
                currentLyricsList = emptyList()
                resetStateCache()
                
                restoreOriginalMetadata()
            }
        })
    }

    private fun resetStateCache() {
        currentDisplayState = DisplayState.UNKNOWN
        currentDisplayIndex = -1
        lastPushedTitle = ""
        lastPushedArtist = ""
    }

    /**
     * 切歌时拉取并解析歌词 (真实 Repo 逻辑)
     */
    fun loadLyricsForSong(song: Song) {
        fetchJob?.cancel()
        fetchJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                // 1. 依次尝试获取本地/内嵌/数据库的原始歌词
                val rawLyrics = lyricsRepository.fileLyrics(song)
                    ?: lyricsRepository.embeddedLyrics(song)
                    ?: lyricsRepository.storedLyrics(song, allowDownload = true)

                // 2. 解析为带有时间轴的实体类
                val parsedLyrics = rawLyrics?.let { 
                    lyricsRepository.parseRawLyrics(song, it) 
                }

                // 3. 传给主线程处理
                handleLyricsResult(parsedLyrics)
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    currentLyricsList = emptyList()
                    progressObserver.stop()
                    restoreOriginalMetadata()
                }
            }
        }
    }

    private suspend fun handleLyricsResult(lyrics: SyncedLyrics?) {
        withContext(Dispatchers.Main) {
            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                currentLyricsList = lyrics.lines
                if (player.isPlaying) {
                    syncLyrics()
                    progressObserver.start { syncLyrics() }
                }
            } else {
                currentLyricsList = emptyList()
                progressObserver.stop()
                restoreOriginalMetadata()
            }
        }
    }

    /**
     * 核心排版引擎 (时间补偿 + 零内存分配)
     */
    private fun syncLyrics() {
        if (!player.isPlaying || currentLyricsList.isEmpty()) return
        
        // 400ms 前置补偿，抵消蓝牙和车机渲染的物理延迟
        val compensatedPosition = player.currentPosition + 400L
        val currentIndex = currentLyricsList.indexOfLast { it.start <= compensatedPosition }

        // --- 第一步：零分配状态计算 ---
        val targetState: DisplayState
        
        if (currentIndex == -1) {
            val firstLineStart = currentLyricsList.firstOrNull()?.start ?: 0
            targetState = if (firstLineStart - compensatedPosition > 3000L) DisplayState.PRELUDE else DisplayState.LYRIC
        } else {
            val currentLineObj = currentLyricsList[currentIndex]
            val nextLineStart = currentLyricsList.getOrNull(currentIndex + 1)?.start ?: Long.MAX_VALUE
            val timeSinceCurrent = compensatedPosition - currentLineObj.start
            val timeToNext = nextLineStart - compensatedPosition
            
            // 判断间奏：空行 或 唱完超3秒且下句超5秒
            val isInterlude = currentLineObj.content.content.isBlank() || (timeSinceCurrent > 3000L && timeToNext > 5000L)
            targetState = if (isInterlude) DisplayState.INTERLUDE else DisplayState.LYRIC
        }

        // ==========================================
        // 发热终结核心：如果行号和间奏状态都没变，立刻阻断！
        // ==========================================
        if (targetState == currentDisplayState && currentIndex == currentDisplayIndex) {
            return
        }

        // --- 第二步：只有状态改变才去拼接字符串 ---
        currentDisplayState = targetState
        currentDisplayIndex = currentIndex

        var titleText = "🎵 🎵 🎵"
        val artistParts = mutableListOf<String>()

        if (targetState == DisplayState.PRELUDE || targetState == DisplayState.INTERLUDE) {
            // 空档期：只放音符，下方预告后两句
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
            // 演唱期：主区放原词，暗区放翻译和预告
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
        lastPushedTitle = titleText
        lastPushedArtist = artistText

        player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
    }

    private fun restoreOriginalMetadata() {
        val currentItem = player.currentMediaItem ?: return
        val extras = currentItem.mediaMetadata.extras ?: return
        
        val cleanTitle = extras.getString("BT_ORIGINAL_TITLE") ?: return 
        val cleanArtist = extras.getString("BT_ORIGINAL_ARTIST") ?: "未知歌手"
        val cleanAlbum = extras.getString("BT_ORIGINAL_ALBUM") ?: "未知专辑"

        val cleanExtras = Bundle(extras).apply {
            remove("BT_ORIGINAL_TITLE")
            remove("BT_ORIGINAL_ARTIST")
            remove("BT_ORIGINAL_ALBUM")
        }

        val restoredMetadata = currentItem.mediaMetadata.buildUpon()
            .setTitle(cleanTitle)
            .setArtist(cleanArtist)
            .setAlbumTitle(cleanAlbum)
            .setExtras(cleanExtras)
            .build()

        val restoredItem = currentItem.buildUpon()
            .setMediaMetadata(restoredMetadata)
            .build()

        resetStateCache()
        isHooked = false
        
        player.replaceMediaItem(player.currentMediaItemIndex, restoredItem)
    }
}