package com.mardous.booming.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mardous.booming.data.local.repository.LyricsRepository
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 车载蓝牙歌词核心引擎 (GS3 极致优化版)
 * 特性：纯音符间奏、暗色独立翻译、双行未唱歌词预测、毫秒级防抖、超低 CPU 占用
 */
class BluetoothLyricManager(
    private val player: Player,
    private val coroutineScope: CoroutineScope,
    private val lyricsRepository: LyricsRepository
) {
    private var isHooked = false
    private var originalTitle: String? = null
    private var originalArtist: String? = null
    private var originalAlbum: String? = null

    private var currentLyricsList: List<SyncedLyrics.Line> = emptyList()
    
    // 【性能防线】：记录上次推送到车机的文本，严格拦截无效跨进程通信，避免手机发热
    private var lastPushedTitle: String = ""
    private var lastPushedArtist: String = ""

    private var fetchJob: Job? = null

    // 【能耗控制】：400ms 轮询间隔，保证秒级视觉同步且极其省电
    private val progressObserver = ProgressObserver(400L)

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

                originalTitle = mediaItem?.mediaMetadata?.title?.toString()
                originalArtist = mediaItem?.mediaMetadata?.artist?.toString()
                originalAlbum = mediaItem?.mediaMetadata?.albumTitle?.toString()
                
                currentLyricsList = emptyList()
                lastPushedTitle = ""
                lastPushedArtist = ""
                
                // 切歌瞬间立刻恢复原歌名，掩盖解析歌词的微小延迟
                restoreOriginalMetadata()
            }
        })
    }

    /**
     * 切歌时拉取并解析歌词
     */
    fun loadLyricsForSong(song: Song) {
        fetchJob?.cancel()
        fetchJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                // 1. 严格按照 BoomingMusic 的底层逻辑，依次尝试获取本地/内嵌/数据库歌词
                val rawLyrics = lyricsRepository.fileLyrics(song)
                    ?: lyricsRepository.embeddedLyrics(song)
                    ?: lyricsRepository.storedLyrics(song, allowDownload = true)

                // 2. 解析原始歌词为时间轴歌词对象
                val parsedLyrics = rawLyrics?.let { 
                    lyricsRepository.parseRawLyrics(song, it) 
                }

                if (parsedLyrics != null && parsedLyrics.lines.isNotEmpty()) {
                    currentLyricsList = parsedLyrics.lines
                    if (player.isPlaying) {
                        syncLyrics()
                        progressObserver.start { syncLyrics() }
                    }
                } else {
                    currentLyricsList = emptyList()
                    progressObserver.stop()
                    restoreOriginalMetadata()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                progressObserver.stop()
                restoreOriginalMetadata()
            }
        }
    }

    /**
     * 核心排版引擎
     */
    private fun syncLyrics() {
        if (!player.isPlaying || currentLyricsList.isEmpty()) return
        
        val currentPosition = player.currentPosition
        val currentIndex = currentLyricsList.indexOfLast { it.start <= currentPosition }

        // 状态 1：前奏（未到第一句）
        if (currentIndex == -1) {
            val firstLineStart = currentLyricsList.firstOrNull()?.start ?: 0
            if (firstLineStart - currentPosition > 3000L) {
                pushToBluetooth(
                    titleText = originalTitle ?: "未知歌曲",
                    artistText = "🎵 🎵 🎵" // 极简纯音符前奏
                )
            }
            return
        }

        val currentLineObj = currentLyricsList[currentIndex]
        
        // 状态 2：间奏（当前句唱完很久，且下一句还很远）
        val nextLineStart = currentLyricsList.getOrNull(currentIndex + 1)?.start ?: Long.MAX_VALUE
        val timeToNextLine = nextLineStart - currentPosition
        val timeSinceCurrentLineStart = currentPosition - currentLineObj.start

        if (timeSinceCurrentLineStart > 3000L && timeToNextLine > 5000L) {
            pushToBluetooth(
                titleText = originalTitle ?: "未知歌曲",
                artistText = "🎵 🎵 🎵" // 极简纯音符间奏
            )
            return
        }

        // 状态 3：正常演唱
        // 最亮的 Title 区域：只放原文主歌词
        val currentText = currentLineObj.content.content

        // 变暗的 Artist 区域：【翻译】+【未唱第一句】+【未唱第二句】
        val parts = mutableListOf<String>()
        
        // (1) 翻译不用高亮，直接塞入暗色区域的第一行
        currentLineObj.translation?.content?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        
        // (2) 未唱的第一句
        currentLyricsList.getOrNull(currentIndex + 1)?.content?.content?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        
        // (3) 未唱的第二句
        currentLyricsList.getOrNull(currentIndex + 2)?.content?.content?.takeIf { it.isNotBlank() }?.let { parts.add(it) }

        // 用换行符拼接成完整的暗色块
        val displayArtistText = parts.joinToString("\n")

        pushToBluetooth(titleText = currentText, artistText = displayArtistText)
    }

    /**
     * 发送指令（带防抖拦截）
     */
    private fun pushToBluetooth(titleText: String, artistText: String) {
        // 核心拦截：文字毫无变化时绝对不执行后续跨进程代码，这是解决发热的关键
        if (titleText == lastPushedTitle && artistText == lastPushedArtist) {
            return
        }

        val currentItem = player.currentMediaItem ?: return

        if (originalTitle == null) {
            originalTitle = currentItem.mediaMetadata.title?.toString() ?: "未知歌曲"
            originalArtist = currentItem.mediaMetadata.artist?.toString() ?: "未知歌手"
            originalAlbum = currentItem.mediaMetadata.albumTitle?.toString() ?: "未知专辑"
        }

        val updatedMetadata = currentItem.mediaMetadata.buildUpon()
            .setTitle(titleText)         
            .setArtist(artistText)       
            .setAlbumTitle(" ")          
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
        if (!isHooked) return
        
        lastPushedTitle = ""
        lastPushedArtist = ""
        
        val currentItem = player.currentMediaItem ?: return

        val restoredMetadata = currentItem.mediaMetadata.buildUpon()
            .setTitle(originalTitle ?: "未知歌曲")
            .setArtist(originalArtist ?: "未知歌手")
            .setAlbumTitle(originalAlbum ?: "未知专辑")
            .build()

        val restoredItem = currentItem.buildUpon()
            .setMediaMetadata(restoredMetadata)
            .build()

        isHooked = false
        player.replaceMediaItem(player.currentMediaItemIndex, restoredItem)
    }
}