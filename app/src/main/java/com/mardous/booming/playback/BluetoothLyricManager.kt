package com.mardous.booming.playback

import android.content.SharedPreferences
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.mardous.booming.data.repository.LyricsRepository
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
    private val preferences: SharedPreferences 
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
    
    fun forceReloadLyricsForSong(song: Song) {
        currentPlayingSongKey = "" 
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

    fun forceInstantUpdate() {
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

        val showTranslation = preferences.getBoolean("lyrics_show_translation", false)

        var titleText = "?? ?? ??"
        val artistParts = mutableListOf<String>()

        if (targetState == DisplayState.PRELUDE || targetState == DisplayState.INTERLUDE) {
            var nextIdx = currentIndex + 1
            while (nextIdx < currentLyricsList.size) {
                val nextText = currentLyricsList[nextIdx].content.content
                if (nextText.isNotBlank()) {
                    artistParts.add(nextText)
                    break
                }
                nextIdx++
            }
        } else {
            val currentLineObj = currentLyricsList[currentIndex]
            titleText = currentLineObj.content.content

            val transText = currentLineObj.translation?.content
            if (showTranslation && !transText.isNullOrBlank()) {
                artistParts.add(transText)
            } else {
                var nextIdx = currentIndex + 1
                while (nextIdx < currentLyricsList.size) {
                    val nextText = currentLyricsList[nextIdx].content.content
                    if (nextText.isNotBlank()) {
                        artistParts.add(nextText)
                        break
                    }
                    nextIdx++
                }
            }
        }

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

        // ?? 深度保护：继承所有已有的 CarWith 装甲 Extras，绝不抹除
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
            .setTitle(titleText)
            .setArtist(artistText)
            .setAlbumTitle(" ")
            .setExtras(extras)
            .build()
        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(updatedMetadata)
            .build()

        lastPushedTitle = titleText
        lastPushedArtist = artistText

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
            seekTimeoutJob?.cancel()
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