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
 * 车载蓝牙歌词核心引擎 (GS3 终极修复与极低功耗版)
 * 修复：解决跨线程导致的秒显失效问题
 * 优化：利用 Bundle 存储原数据，彻底解决发热和列表污染问题
 */
class BluetoothLyricManager(
    private val player: Player,
    private val coroutineScope: CoroutineScope,
    private val lyricsRepository: LyricsRepository
) {
    private var currentLyricsList: List<SyncedLyrics.Line> = emptyList()
    
    // 【性能防线】：严格拦截重复文本，阻止无效的底层跨进程刷新
    private var lastPushedTitle: String = ""
    private var lastPushedArtist: String = ""

    private var fetchJob: Job? = null

    // 【能耗控制】：400ms 刷新间隔
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
                
                currentLyricsList = emptyList()
                lastPushedTitle = ""
                lastPushedArtist = ""
                
                // 切歌瞬间恢复原歌名
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
                // 1. 在后台线程（IO）拉取并解析歌词
                val rawLyrics = lyricsRepository.fileLyrics(song)
                    ?: lyricsRepository.embeddedLyrics(song)
                    ?: lyricsRepository.storedLyrics(song, allowDownload = true)

                val parsedLyrics = rawLyrics?.let { 
                    lyricsRepository.parseRawLyrics(song, it) 
                }

                // 2. 【核心修复】：必须切换回主线程（Main）才能操作播放器和启动 UI 轮询！
                withContext(Dispatchers.Main) {
                    if (parsedLyrics != null && parsedLyrics.lines.isNotEmpty()) {
                        currentLyricsList = parsedLyrics.lines
                        if (player.isPlaying) {
                            // 此时在主线程，歌词会瞬间顶替上去，不再需要快进触发
                            syncLyrics()
                            progressObserver.start { syncLyrics() }
                        }
                    } else {
                        currentLyricsList = emptyList()
                        progressObserver.stop()
                        restoreOriginalMetadata()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // 异常时也必须在主线程恢复
                withContext(Dispatchers.Main) {
                    currentLyricsList = emptyList()
                    progressObserver.stop()
                    restoreOriginalMetadata()
                }
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

        // 状态 1：前奏
        if (currentIndex == -1) {
            val firstLineStart = currentLyricsList.firstOrNull()?.start ?: 0
            if (firstLineStart - currentPosition > 3000L) {
                pushToBluetooth(
                    titleText = getOriginalTitle() ?: "未知歌曲",
                    artistText = "🎵 🎵 🎵"
                )
            }
            return
        }

        val currentLineObj = currentLyricsList[currentIndex]
        
        // 状态 2：间奏
        val nextLineStart = currentLyricsList.getOrNull(currentIndex + 1)?.start ?: Long.MAX_VALUE
        val timeToNextLine = nextLineStart - currentPosition
        val timeSinceCurrentLineStart = currentPosition - currentLineObj.start

        if (timeSinceCurrentLineStart > 3000L && timeToNextLine > 5000L) {
            pushToBluetooth(
                titleText = getOriginalTitle() ?: "未知歌曲",
                artistText = "🎵 🎵 🎵"
            )
            return
        }

        // 状态 3：正常演唱
        val currentText = currentLineObj.content.content

        val parts = mutableListOf<String>()
        currentLineObj.translation?.content?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        currentLyricsList.getOrNull(currentIndex + 1)?.content?.content?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        currentLyricsList.getOrNull(currentIndex + 2)?.content?.content?.takeIf { it.isNotBlank() }?.let { parts.add(it) }

        val displayArtistText = parts.joinToString("\n")

        pushToBluetooth(titleText = currentText, artistText = displayArtistText)
    }

    /**
     * 获取最纯净的原歌名
     */
    private fun getOriginalTitle(): String? {
        val currentItem = player.currentMediaItem ?: return null
        val extras = currentItem.mediaMetadata.extras ?: return currentItem.mediaMetadata.title?.toString()
        return extras.getString("BT_ORIGINAL_TITLE") ?: currentItem.mediaMetadata.title?.toString()
    }

    /**
     * 发送指令（Bundle 极致护航版）
     */
    private fun pushToBluetooth(titleText: String, artistText: String) {
        // CPU 优化核心：文本无变化时，阻断跨进程通信
        if (titleText == lastPushedTitle && artistText == lastPushedArtist) {
            return
        }

        val currentItem = player.currentMediaItem ?: return
        val extras = currentItem.mediaMetadata.extras ?: Bundle()

        // 【数据隔离优化】：把原始歌名备份到 Bundle 里。
        // 这样哪怕你疯狂快进、切歌，都不会污染播放器内部的播放列表数据
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
            .setExtras(extras) // 带着原数据走
            .build()

        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(updatedMetadata)
            .build()

        lastPushedTitle = titleText
        lastPushedArtist = artistText

        player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
    }

    /**
     * 完全无痕地恢复原状
     */
    private fun restoreOriginalMetadata() {
        val currentItem = player.currentMediaItem ?: return
        val extras = currentItem.mediaMetadata.extras ?: return
        
        // 没被劫持过，不需要恢复
        val cleanTitle = extras.getString("BT_ORIGINAL_TITLE") ?: return 
        val cleanArtist = extras.getString("BT_ORIGINAL_ARTIST") ?: "未知歌手"
        val cleanAlbum = extras.getString("BT_ORIGINAL_ALBUM") ?: "未知专辑"

        // 构造干净的 Extras
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

        lastPushedTitle = ""
        lastPushedArtist = ""
        
        player.replaceMediaItem(player.currentMediaItemIndex, restoredItem)
    }
}