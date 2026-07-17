package com.mardous.booming.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.mardous.booming.data.local.repository.LyricsRepository
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 车载蓝牙歌词核心引擎 (深度优化版)
 * 特性：纯音符间奏、独立暗色翻译、双行未唱歌词、毫秒级防抖、超低 CPU 占用
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
    
    // 【性能核心】：记录上次推送的文本，严格拦截无效的跨进程蓝牙刷新，彻底解决发热问题
    private var lastPushedTitle: String = ""
    private var lastPushedArtist: String = ""

    private var fetchJob: Job? = null

    // 【能耗核心】：400ms 轮询间隔，既能保证秒级视觉同步，又不会给 CPU 造成负担
    private val progressObserver = ProgressObserver(400L)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && currentLyricsList.isNotEmpty()) {
                    // 秒响应：刚点下播放键，立即同步一次，不等待定时器
                    syncLyrics()
                    progressObserver.start { syncLyrics() }
                } else {
                    // 暂停：立刻停掉轮询，并恢复原歌名，防止屏幕卡在某句歌词上
                    progressObserver.stop()
                    restoreOriginalMetadata()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 切歌：立即停止老任务，防止数据错乱
                progressObserver.stop()
                fetchJob?.cancel()

                // 缓存新歌的原始元数据
                originalTitle = mediaItem?.mediaMetadata?.title?.toString()
                originalArtist = mediaItem?.mediaMetadata?.artist?.toString()
                originalAlbum = mediaItem?.mediaMetadata?.albumTitle?.toString()
                
                // 重置状态
                currentLyricsList = emptyList()
                lastPushedTitle = ""
                lastPushedArtist = ""
                
                // 秒响应：切歌瞬间立刻显示新歌名，掩盖网络拉取歌词的延迟
                restoreOriginalMetadata()
            }
        })
    }

    /**
     * 外部调用：当切歌完成时，传入新歌信息拉取流
     */
    fun loadLyricsForSong(song: Song) {
        fetchJob?.cancel()
        fetchJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                lyricsRepository.getLyricsFlow(song.id).collectLatest { lyrics ->
                    if (lyrics is SyncedLyrics && lyrics.lines.isNotEmpty()) {
                        currentLyricsList = lyrics.lines
                        if (player.isPlaying) {
                            // 获取到歌词，立马显示
                            syncLyrics()
                            progressObserver.start { syncLyrics() }
                        }
                    } else {
                        // 无歌词或拉取失败：退回普通蓝牙模式
                        currentLyricsList = emptyList()
                        progressObserver.stop()
                        restoreOriginalMetadata()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                progressObserver.stop()
                restoreOriginalMetadata()
            }
        }
    }

    /**
     * 核心同步算法：排版与状态控制
     */
    private fun syncLyrics() {
        if (!player.isPlaying || currentLyricsList.isEmpty()) return
        
        val currentPosition = player.currentPosition
        val currentIndex = currentLyricsList.indexOfLast { it.start <= currentPosition }

        if (currentIndex == -1) {
            // 状态 1：前奏（还未到第一句）
            val firstLineStart = currentLyricsList.firstOrNull()?.start ?: 0
            if (firstLineStart - currentPosition > 3000L) {
                pushToBluetooth(
                    titleText = originalTitle ?: "未知歌曲",
                    artistText = "🎵 🎵 🎵" // 纯音符展示，无多余文字
                )
            }
            return
        }

        val currentLineObj = currentLyricsList[currentIndex]
        
        // 状态 2：间奏（检查当前这句是否唱完很久，且下一句还很远）
        val nextLineStart = currentLyricsList.getOrNull(currentIndex + 1)?.start ?: Long.MAX_VALUE
        val timeToNextLine = nextLineStart - currentPosition
        val timeSinceCurrentLineStart = currentPosition - currentLineObj.start

        if (timeSinceCurrentLineStart > 3000L && timeToNextLine > 5000L) {
            pushToBluetooth(
                titleText = originalTitle ?: "未知歌曲",
                artistText = "🎵 🎵 🎵" // 纯音符展示，无多余文字
            )
            return
        }

        // 状态 3：正常演唱
        // 最亮的主标题：只保留当前唱的原文
        val currentText = currentLineObj.content.content

        // 变暗的副标题：组合【翻译】+【未唱的第一句】+【未唱的第二句】
        val displayArtistText = buildString {
            // 1. 如果有翻译，先塞入翻译（它会被车机当作歌手名变暗显示）
            val translation = currentLineObj.translation?.content
            if (!translation.isNullOrBlank()) {
                append(translation)
                append("\n")
            }
            // 2. 追加未唱的第一句
            val nextLine1 = currentLyricsList.getOrNull(currentIndex + 1)?.content?.content
            if (!nextLine1.isNullOrBlank()) {
                append(nextLine1)
            }
            // 3. 追加未唱的第二句
            val nextLine2 = currentLyricsList.getOrNull(currentIndex + 2)?.content?.content
            if (!nextLine2.isNullOrBlank()) {
                append("\n")
                append(nextLine2)
            }
        }.trimEnd()

        pushToBluetooth(titleText = currentText, artistText = displayArtistText)
    }

    /**
     * 发送指令：包含严格的防抖校验
     */
    private fun pushToBluetooth(titleText: String, artistText: String) {
        // 如果文字没变化，直接 return，节省大量 CPU 资源，防止手机发烫
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
            .setTitle(titleText)         // 唯一高亮
            .setArtist(artistText)       // 包含：翻译（暗） + 未唱的两句（暗）
            .setAlbumTitle(" ")          // 清空专辑避免捣乱
            .build()

        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(updatedMetadata)
            .build()

        isHooked = true
        lastPushedTitle = titleText
        lastPushedArtist = artistText

        player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
    }

    /**
     * 还原车机原始音乐信息
     */
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