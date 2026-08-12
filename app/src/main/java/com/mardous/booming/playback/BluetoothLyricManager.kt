package com.mardous.booming.playback

import android.content.SharedPreferences
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class BluetoothLyricManager(
    private val player: Player,
    private val coroutineScope: CoroutineScope,
    private val lyricsRepository: LyricsRepository,
    private val preferences: SharedPreferences // 🌟 注入设置，实时感知翻译开关
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
    private var isSeeking = false
    private var seekTimeoutJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && currentLyricsList.isNotEmpty()) {
                syncLyrics()
                progressObserver.start { syncLyrics() }
            } else {
                progressObserver.stop()
                if (currentLyricsList.isNotEmpty()) syncLyrics()
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                isSeeking = true
                seekTimeoutJob?.cancel()
                seekTimeoutJob = coroutineScope.launch(Dispatchers.Main) {
                    delay(800)
                    isSeeking = false
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

    init { player.addListener(playerListener) }

    private fun resetStateCache() {
        currentDisplayState = DisplayState.UNKNOWN
        currentDisplayIndex = -1
        lastPushedTitle = ""
        lastPushedArtist = ""
    }

    fun loadLyricsForSong(song: Song) {
        coroutineScope.launch(Dispatchers.Main) {
            val uniqueSongKey = "${song.id}_${song.title}"
            if (uniqueSongKey == currentPlayingSongKey) return@launch

            currentPlayingSongKey = uniqueSongKey
            restoreOriginalMetadata()
            progressObserver.stop()
            fetchJob?.cancel()
            currentLyricsList = emptyList()
            resetStateCache()
            hookedMediaId = song.id.toString()
            isHooked = true

            fetchJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    val rawLyrics = lyricsRepository.fileLyrics(song) ?: lyricsRepository.embeddedLyrics(song) ?: lyricsRepository.storedLyrics(song, allowDownload = true)
                    val parsedLyrics = rawLyrics?.let { lyricsRepository.parseRawLyrics(song, it) }
                    withContext(Dispatchers.Main) { handleLyricsResult(parsedLyrics) }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { handleLyricsResult(null) }
                }
            }
        }
    }
	
	// 🌟 【核心修复 2：强行重载引擎】
    // 暴露此接口用于格式切换：无视“同一首歌不重复加载”的性能拦截，强行重刷最新格式
    fun forceReloadLyricsForSong(song: Song) {
        currentPlayingSongKey = "" // 强行清空校验锁，打破防抖拦截
        loadLyricsForSong(song)
    }

    private fun handleLyricsResult(lyrics: SyncedLyrics?) {
        if (lyrics != null && lyrics.lines.isNotEmpty()) {
            currentLyricsList = lyrics.lines
            syncLyrics()
            if (player.isPlaying) progressObserver.start { syncLyrics() }
        } else {
            currentLyricsList = emptyList()
            progressObserver.stop()
            restoreOriginalMetadata()
        }
    }

    // 🌟 核心：暴露强制刷新接口。当翻译开关变化时，无视时间轴变化，强行重绘蓝牙歌词
    fun forceInstantUpdate() {
        // 清除上一次的推送记录，打破防抖，强制重新构建发送
        lastPushedTitle = ""
        lastPushedArtist = ""
        syncLyrics()
    }

    private fun syncLyrics() {
        if (currentLyricsList.isEmpty() || isSeeking || player.playbackState != Player.STATE_READY) return

        val latencyCompensationMs = if (player.isPlaying) 400L else 0L
        val compensatedPosition = player.currentPosition + latencyCompensationMs
        val currentIndex = currentLyricsList.indexOfLast { it.start <= compensatedPosition }
        
        val targetState = if (currentIndex == -1) DisplayState.PRELUDE else {
            if (currentLyricsList[currentIndex].content.content.isBlank()) DisplayState.INTERLUDE else DisplayState.LYRIC
        }

        // 🌟 实时读取翻译开关设置
        val showTranslation = preferences.getBoolean("lyrics_show_translation", false)

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

            // 🌟 关键拦截：只有当 showTranslation 为 true 时，才往蓝牙发翻译
            if (showTranslation) {
                currentLineObj.translation?.content?.takeIf { it.isNotBlank() }?.let { artistParts.add(it) }
            }

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

        // 记录状态，防止重复推送
        currentDisplayState = targetState
        currentDisplayIndex = currentIndex

        val artistText = if (artistParts.isNotEmpty()) artistParts.joinToString("\n") else " "
        pushToBluetooth(titleText, artistText)
    }

    private fun pushToBluetooth(titleText: String, artistText: String) {
        if (titleText == lastPushedTitle && artistText == lastPushedArtist) return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex >= player.mediaItemCount) return
        
        val currentItem = player.getMediaItemAt(currentIndex)
        if (currentItem.mediaId != hookedMediaId) return

        val extras = Bundle(currentItem.mediaMetadata.extras ?: Bundle.EMPTY)
        val cleanTitle = extras.getString("BT_ORIGINAL_TITLE") ?: currentItem.mediaMetadata.title?.toString() ?: "未知歌曲"
        val cleanArtist = extras.getString("BT_ORIGINAL_ARTIST") ?: currentItem.mediaMetadata.artist?.toString() ?: "未知歌手"
        val cleanAlbum = extras.getString("BT_ORIGINAL_ALBUM") ?: currentItem.mediaMetadata.albumTitle?.toString() ?: "未知专辑"

        if (!extras.containsKey("BT_ORIGINAL_TITLE")) {
            extras.putString("BT_ORIGINAL_TITLE", cleanTitle)
            extras.putString("BT_ORIGINAL_ARTIST", cleanArtist)
            extras.putString("BT_ORIGINAL_ALBUM", cleanAlbum)
        }

        val updatedMetadata = currentItem.mediaMetadata.buildUpon()
            .setTitle(titleText).setArtist(artistText).setAlbumTitle(" ").setExtras(extras).build()
        val updatedItem = currentItem.buildUpon().setMediaMetadata(updatedMetadata).build()

        lastPushedTitle = titleText
        lastPushedArtist = artistText

        val realPlayer = (player as? AdvancedForwardingPlayer)?.exoPlayer ?: player
        realPlayer.replaceMediaItem(currentIndex, updatedItem)
    }

    private fun restoreOriginalMetadata() {
        if (!isHooked || hookedMediaId.isEmpty()) {
            isHooked = false; hookedMediaId = ""; resetStateCache(); return
        }

        var targetIndex = -1
        for (i in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(i).mediaId == hookedMediaId) { targetIndex = i; break }
        }

        if (targetIndex != -1) {
            val itemToRestore = player.getMediaItemAt(targetIndex)
            val extras = itemToRestore.mediaMetadata.extras
            if (extras != null && extras.containsKey("BT_ORIGINAL_TITLE")) {
                val cleanTitle = extras.getString("BT_ORIGINAL_TITLE") ?: "未知歌曲"
                val cleanArtist = extras.getString("BT_ORIGINAL_ARTIST") ?: "未知歌手"
                val cleanAlbum = extras.getString("BT_ORIGINAL_ALBUM") ?: "未知专辑"

                val cleanExtras = Bundle(extras).apply {
                    remove("BT_ORIGINAL_TITLE"); remove("BT_ORIGINAL_ARTIST"); remove("BT_ORIGINAL_ALBUM")
                }

                val restoredMetadata = itemToRestore.mediaMetadata.buildUpon()
                    .setTitle(cleanTitle).setArtist(cleanArtist).setAlbumTitle(cleanAlbum).setExtras(cleanExtras).build()
                val restoredItem = itemToRestore.buildUpon().setMediaMetadata(restoredMetadata).build()

                val realPlayer = (player as? AdvancedForwardingPlayer)?.exoPlayer ?: player
                realPlayer.replaceMediaItem(targetIndex, restoredItem)
            }
        }
        isHooked = false; hookedMediaId = ""; resetStateCache()
    }

    fun stopLyrics() {
        coroutineScope.launch(Dispatchers.Main) {
            seekTimeoutJob?.cancel(); progressObserver.stop(); fetchJob?.cancel()
            currentLyricsList = emptyList(); currentPlayingSongKey = "" 
            restoreOriginalMetadata()
        }
    }
    fun release() { stopLyrics(); player.removeListener(playerListener) }
}