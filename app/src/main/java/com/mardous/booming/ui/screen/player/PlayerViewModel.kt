/*
 * Copyright (c) 2024 Christians Martínez Alvarado
 */

package com.mardous.booming.ui.screen.player

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.mardous.booming.core.model.AudioSourceType
import com.mardous.booming.core.model.MediaEvent
import com.mardous.booming.core.model.PaletteColor
import com.mardous.booming.core.model.action.QueueClearingBehavior
import com.mardous.booming.core.model.action.SongClickBehavior
import com.mardous.booming.core.model.getAudioSourceType
import com.mardous.booming.core.model.player.MetadataField
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.player.PlayerColorSchemeMode
import com.mardous.booming.core.model.shuffle.GroupShuffleMode
import com.mardous.booming.core.model.shuffle.OpenShuffleMode
import com.mardous.booming.core.model.shuffle.ShuffleOperationState
import com.mardous.booming.core.model.shuffle.SpecialShuffleMode
import com.mardous.booming.core.sort.SongSortMode
import com.mardous.booming.data.SongProvider
import com.mardous.booming.data.local.room.PlaylistEntity
import com.mardous.booming.data.mapper.toSongs
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.repository.Repository
import com.mardous.booming.playback.Playback
import com.mardous.booming.playback.ProgressObserver
import com.mardous.booming.playback.QueueStateHolder
import com.mardous.booming.playback.shuffle.ShuffleManager
import com.mardous.booming.util.NOW_PLAYING_EXTRA_INFO
import com.mardous.booming.util.Preferences
import com.mardous.booming.util.REMEMBER_SHUFFLE_MODE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds

// 🌟 核心 1：开源备用源嗅探（LX Music 同款逻辑：当网易云为 VIP 30 秒试听时，无缝获取完整流）
private suspend fun fetchFallbackFullUrl(title: String, artist: String): String = withContext(Dispatchers.IO) {
    try {
        val query = java.net.URLEncoder.encode("$title $artist", "UTF-8")
        val searchUrl = "http://search.kuwo.cn/r.s?all=$query&ft=music&itemset=web_2013&client=kt&pn=0&rn=1&rformat=json&encoding=utf8"
        val conn = java.net.URL(searchUrl).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val rid = Regex("MUSIC_(\\d+)").find(text)?.groupValues?.get(1)

        if (!rid.isNullOrEmpty()) {
            val playUrlReq = "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=$rid&format=mp3&response=url"
            val playConn = java.net.URL(playUrlReq).openConnection() as java.net.HttpURLConnection
            playConn.connectTimeout = 3000
            playConn.readTimeout = 3000
            val res = playConn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (res.startsWith("http")) {
                return@withContext res.replace("http://", "https://")
            } else if (res.contains("\"url\"")) {
                val url = org.json.JSONObject(res).optString("url", "")
                if (url.startsWith("http")) return@withContext url.replace("http://", "https://")
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return@withContext ""
}

// 🌟 核心 2：优先获取网易云官方直链，遇到 30 秒试听文件（freeTrialInfo）立即拦截并替换为完整音频流
private suspend fun fetchNeteaseUrl(context: Context, song: Song): String = withContext(Dispatchers.IO) {
    var fullAudioUrl = ""
    try {
        val baseUrl = com.mardous.booming.data.network.ApiConfigManager.getNeteaseBaseUrl(context)
        val cookie = com.mardous.booming.data.network.ApiConfigManager.getCookie(context)
        val encodedCookie = java.net.URLEncoder.encode(cookie, "UTF-8")
        val separator = if (baseUrl.contains("?")) "&" else "?"
        val urlStr = "$baseUrl/song/url/v1?id=${song.size}&level=standard$separator" + "cookie=$encodedCookie"

        val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Cookie", cookie)
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

        if (conn.responseCode == 200) {
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val data = org.json.JSONObject(json).optJSONArray("data")
            if (data != null && data.length() > 0) {
                val item = data.getJSONObject(0)
                val rawUrl = item.optString("url", "")
                val freeTrialInfo = item.optJSONObject("freeTrialInfo")
                val isTrial = freeTrialInfo != null || (item.optJSONObject("freeTimeTrialPrivilege")?.optInt("remainTime", 0) ?: 0) > 0

                // 只有在非试听、且为完整音频流时才采用官方链接
                if (rawUrl.isNotEmpty() && rawUrl != "null" && !isTrial) {
                    fullAudioUrl = rawUrl.replace("http://", "https://")
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 若官方接口未返回有效直链或识别为 30 秒 VIP 截断，自动切入备用源获取整曲
    if (fullAudioUrl.isEmpty()) {
        fullAudioUrl = fetchFallbackFullUrl(song.title, song.artistName)
    }

    // 最终兜底
    return@withContext if (fullAudioUrl.isNotEmpty()) fullAudioUrl else "https://music.163.com/song/media/outer/url?id=${song.size}.mp3"
}

// 🌟 核心 3：拦截假本地路径并挂载完整流
private suspend fun List<Song>.toMediaItems(context: Context): List<MediaItem> = withContext(Dispatchers.IO) {
    mapNotNull { song ->
        if (song.genreName == "Netease" && song.size > 0L) {
            val finalUrl = fetchNeteaseUrl(context, song)
            if (finalUrl.isEmpty()) return@mapNotNull null

            MediaItem.Builder()
                .setMediaId(song.id.toString())
                .setUri(Uri.parse(finalUrl))
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artistName)
                        .setAlbumTitle(song.albumName)
                        .setAlbumArtist(song.artistName)
                        .setGenre("Netease")
                        .build()
                )
                .build()
        } else {
            song.toMediaItem().takeUnless { it == MediaItem.EMPTY }
        }
    }
}

@OptIn(FlowPreview::class, ExperimentalAtomicApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerViewModel(
    private val preferences: SharedPreferences,
    private val repository: Repository,
    queueStateHolder: QueueStateHolder
) : ViewModel(), Player.Listener, KoinComponent {

    private val progressObserver = ProgressObserver(intervalMs = 100)
    private val shuffleManager = ShuffleManager()
    private var mediaController: MediaController? = null

    private val _mediaEvent = MutableSharedFlow<MediaEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val mediaEvent = _mediaEvent.asSharedFlow()

    val queueFlow = queueStateHolder.queue
    val queue get() = queueFlow.value
    val positionFlow = queueStateHolder.position
    val position get() = positionFlow.value
    val shuffleModeFlow = queueStateHolder.shuffleMode
    val shuffleModeEnabled get() = shuffleModeFlow.value
    val repeatModeFlow = queueStateHolder.repeatMode
    val repeatMode get() = repeatModeFlow.value

    val currentSongFlow = queueStateHolder.currentSong.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = Song.emptySong
    )
    val currentSong get() = currentSongFlow.value

    val nextSongFlow = queueStateHolder.nextSong.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = Song.emptySong
    )
    val nextSong get() = nextSongFlow.value

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow = _isPlayingFlow.asStateFlow()
    val isPlaying get() = _isPlayingFlow.value

    private val _progressFlow = MutableStateFlow(C.TIME_UNSET)
    val progressFlow = _progressFlow.asStateFlow()
    val progress get() = progressFlow.value

    private val _durationFlow = MutableStateFlow(C.TIME_UNSET)
    val durationFlow = _durationFlow.asStateFlow()
    val duration get() = durationFlow.value

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val extraInfoFlow = currentSongFlow
        .debounce(500.milliseconds)
        .distinctUntilChangedBy { it.id }
        .mapLatest { song ->
            withContext(IO) {
                getExtraInfo(song)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _colorScheme = MutableStateFlow(PlayerColorScheme.Unspecified)
    val colorSchemeFlow = _colorScheme.asStateFlow()
    val colorScheme get() = colorSchemeFlow.value

    private val _shuffleOperationState = MutableStateFlow(ShuffleOperationState())
    val shuffleOperationState = _shuffleOperationState.asStateFlow()

    private val _stopAfterPosition = Channel<Pair<String?, Boolean>>(Channel.BUFFERED)
    val stopAfterPosition = _stopAfterPosition.receiveAsFlow()

    override fun onCleared() {
        progressObserver.stop()
    }

    fun setMediaController(mediaController: MediaController?) {
        if (this.mediaController == mediaController) return
        this.mediaController = mediaController
        if (mediaController != null) {
            setIsPlaying(mediaController.isPlaying)
            if (progress == C.TIME_UNSET || duration == C.TIME_UNSET) {
                _progressFlow.value = mediaController.contentPosition
                _durationFlow.value = mediaController.contentDuration
            }
        }
    }

    fun submitEvent(mediaEvent: MediaEvent) {
        _mediaEvent.tryEmit(mediaEvent)
    }

    private fun setIsPlaying(isPlaying: Boolean) {
        _isPlayingFlow.value = isPlaying
        if (isPlaying) {
            progressObserver.start {
                mediaController?.let { controller ->
                    _progressFlow.value = controller.contentPosition
                    _durationFlow.value = controller.contentDuration
                }
            }
        } else {
            progressObserver.stop()
        }
    }

    fun getExtraInfo(song: Song): String? {
        return if (Preferences.displayExtraInfo) {
            MetadataField.getMetadataValue(
                song = song,
                fields = Preferences.getExtraInfoContent(
                    key = NOW_PLAYING_EXTRA_INFO,
                    defaultContent = Preferences.getDefaultNowPlayingInfo()
                )
            )
        } else null
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val isPlayStateEvent = events.containsAny(
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_IS_PLAYING_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED
        )
        if (isPlayStateEvent) {
            setIsPlaying(player.playWhenReady && player.isPlaying)
            if (player.playbackState == Player.STATE_READY && !player.playWhenReady) {
                _progressFlow.value = player.contentPosition
                _durationFlow.value = player.contentDuration
            }
        }
        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
            if (!player.playWhenReady) {
                _progressFlow.value = player.contentPosition
                _durationFlow.value = player.contentDuration
            }
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        _playbackSpeed.value = playbackParameters.speed
    }

    fun toggleFavorite() {
        val song = currentSong
        if (song == Song.emptySong) return

        when (song.getAudioSourceType()) {
            AudioSourceType.LOCAL -> {
                mediaController?.sendCustomCommand(SessionCommand(Playback.TOGGLE_FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
            }
            AudioSourceType.RADIO -> {
                handleRadioFavorite(appContext, song)
            }
            AudioSourceType.NETEASE -> {
                handleNeteaseFavorite(appContext, song)
            }
            AudioSourceType.UNKNOWN -> {
                Toast.makeText(appContext, "未知音频源", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleNeteaseFavorite(context: Context, song: Song) {
        Toast.makeText(context, "❤️ 正在同步并极速下载...", Toast.LENGTH_SHORT).show()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val realNeteaseId = song.size 
                if (realNeteaseId > 0) {
                    com.mardous.booming.data.network.NeteaseDailyApi.likeSong(context, realNeteaseId)
                }
                
                val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "newdown")
                if (!targetDir.exists()) targetDir.mkdirs()

                val downloadItem = com.mardous.booming.data.local.lyrics.ttml.UniversalDownloadEngine.NetSongItem(
                    id = realNeteaseId, 
                    title = song.title,
                    artist = song.artistName,
                    album = song.albumName,
                    picUrl = "", 
                    durationMs = song.duration,
                    year = "",
                    format = "flac", 
                    fileSizeStr = "红心极速直连",
                    requestedLevel = "lossless" 
                )

                val downloadedFile = com.mardous.booming.data.local.lyrics.ttml.UniversalDownloadEngine.downloadSong(context, downloadItem, targetDir) { _ -> }

                if (downloadedFile != null && downloadedFile.exists()) {
                    kotlinx.coroutines.delay(1000)
                    val localSong = repository.songByFilePath(downloadedFile.absolutePath, ignoreBlacklist = false)
                    if (localSong != Song.emptySong) {
                        repository.toggleFavorite(localSong) 
                    }
                    
                    withContext(Dispatchers.Main) { 
                        Toast.makeText(context, "✅ 红心收藏并极速下载成功！", Toast.LENGTH_SHORT).show() 
                    }
                } else {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "❤️ 仅同步红心，下载受限", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleRadioFavorite(context: Context, song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val radioFavName = "[Radio]我的收藏"
                var playlistId = repository.checkPlaylistExists(radioFavName).firstOrNull()?.playListId
                if (playlistId == null) {
                    playlistId = repository.createPlaylist(PlaylistEntity(playlistName = radioFavName))
                }
                
                val existingSongs = repository.playlistSongs(playlistId)
                if (existingSongs.any { it.data == song.data }) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "已取消电台收藏", Toast.LENGTH_SHORT).show() }
                } else {
                    val radioEntity = com.mardous.booming.data.local.room.SongEntity(
                        id = (System.currentTimeMillis() * 1000), 
                        title = song.title, artistName = song.artistName, albumName = song.albumName,
                        duration = 0L, data = song.data, playlistCreatorId = playlistId,
                        trackNumber = 0, year = 0, size = 0L,
                        dateAdded = System.currentTimeMillis(), dateModified = System.currentTimeMillis(),
                        albumId = -1L, artistId = -1L, albumArtist = "网络电台", genreName = "直播"
                    )
                    repository.insertSongsInPlaylist(listOf(radioEntity))
                    withContext(Dispatchers.Main) { Toast.makeText(context, "❤️ 已收藏至电台列表", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun cycleRepeatMode() {
        mediaController?.sendCustomCommand(SessionCommand(Playback.CYCLE_REPEAT, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun toggleShuffleMode() {
        mediaController?.sendCustomCommand(SessionCommand(Playback.TOGGLE_SHUFFLE, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun togglePlayPause() {
        if (isPlaying) {
            mediaController?.pause()
        } else {
            mediaController?.play()
        }
    }

    fun play() {
        mediaController?.play()
    }

    fun seekToNext() {
        mediaController?.seekToNext()
    }

    fun seekToPrevious() {
        mediaController?.seekToPrevious()
    }

    fun seekForward() {
        mediaController?.seekForward()
    }

    fun seekBack() {
        mediaController?.seekBack()
    }

    fun seekTo(positionMillis: Long) {
        mediaController?.seekTo(positionMillis)
    }

    fun playSongAt(newPosition: Int) {
        mediaController?.let { controller ->
            if (controller.playbackState == Player.STATE_READY) {
                if (!controller.currentTimeline.isEmpty) {
                    controller.seekToDefaultPosition(position.getIndexForPosition(newPosition))
                }
            }
        }
    }

    fun playMediaItem(mediaItem: MediaItem, shuffleMode: Boolean = false) {
        mediaController?.let { controller ->
            controller.shuffleModeEnabled = shuffleMode
            controller.setMediaItem(mediaItem, true)
            controller.prepare()
            controller.play()
        }
    }

    fun playMediaId(mediaId: String, shuffleMode: Boolean = false) {
        playMediaItem(
            mediaItem = MediaItem.Builder()
                .setMediaId(mediaId)
                .build(),
            shuffleMode = shuffleMode
        )
    }

    fun openQueue(
        queue: List<Song>,
        position: Int = 0,
        startPlaying: Boolean = true,
        shuffleMode: OpenShuffleMode = OpenShuffleMode.Remember
    ) = viewModelScope.launch {
        mediaController?.let { controller ->
            var shuffleModeEnabled = controller.shuffleModeEnabled
            if (!preferences.getBoolean(REMEMBER_SHUFFLE_MODE, true)) {
                shuffleModeEnabled = false
            }
            val mediaItems = queue.toMediaItems(appContext)
            val finalShuffleMode = when (shuffleMode) {
                OpenShuffleMode.On -> true
                OpenShuffleMode.Off -> false
                OpenShuffleMode.Remember -> shuffleModeEnabled
            }
            if (mediaItems.isNotEmpty()) {
                controller.shuffleModeEnabled = finalShuffleMode
                controller.setMediaItems(mediaItems, position, C.TIME_UNSET)
                controller.playWhenReady = startPlaying
                controller.prepare()
            }
        }
    }

    fun openAndShuffleQueue(queue: List<Song>) = viewModelScope.launch {
        mediaController?.let { controller ->
            val mediaItems = queue.toMediaItems(appContext)
            if (mediaItems.isNotEmpty()) {
                controller.shuffleModeEnabled = true
                controller.setMediaItems(mediaItems, true)
                controller.prepare()
                controller.play()
            }
        }
    }

    fun openShuffle(
        providers: List<SongProvider>,
        mode: GroupShuffleMode,
        sortMode: SongSortMode
    ) = liveData {
        val mediaItems = withContext(IO) {
            shuffleManager.shuffleByProvider(providers, mode, sortMode).toMediaItems(appContext)
        }
        if (mediaItems.isNotEmpty()) {
            mediaController?.let { controller ->
                controller.shuffleModeEnabled = true
                val resultFuture = controller.sendCustomCommand(
                    SessionCommand(Playback.SET_UNSHUFFLED_ORDER, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                val result = runCatching { resultFuture.await() }
                    .getOrDefault(SessionResult(SessionError.ERROR_UNKNOWN))
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    controller.setMediaItems(mediaItems)
                    controller.prepare()
                    controller.play()
                }
            }
            emit(true)
        } else {
            emit(false)
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun openSpecialShuffle(songs: List<Song>, mode: SpecialShuffleMode) = viewModelScope.launch {
        if (shuffleOperationState.value.isIdle) {
            _shuffleOperationState.value = ShuffleOperationState(mode, ShuffleOperationState.Status.InProgress)
            val mediaItems = withContext(IO) {
                shuffleManager.applySmartShuffle(songs, mode).toMediaItems(appContext)
            }
            if (mediaItems.isNotEmpty()) {
                mediaController?.let { controller ->
                    controller.shuffleModeEnabled = true
                    val resultFuture = controller.sendCustomCommand(
                        SessionCommand(Playback.SET_UNSHUFFLED_ORDER, Bundle.EMPTY),
                        Bundle.EMPTY
                    )
                    val result = runCatching { resultFuture.await() }
                        .getOrDefault(SessionResult(SessionError.ERROR_UNKNOWN))
                    if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                        controller.setMediaItems(mediaItems, true)
                        controller.prepare()
                        controller.play()
                    }
                }
            }
            _shuffleOperationState.value = ShuffleOperationState()
        }
    }

    fun openPlaylist(
        playlist: PlaylistEntity,
        startPlaying: Boolean = true,
        shuffleMode: OpenShuffleMode = OpenShuffleMode.Off
    ) = viewModelScope.launch {
        val songs = withContext(IO) {
            repository.playlistSongs(playlist.playListId).toSongs()
        }
        openQueue(songs, startPlaying = startPlaying, shuffleMode = shuffleMode)
    }

    fun openSongs(
        position: Int,
        songs: List<Song>,
        behavior: SongClickBehavior
    ) = viewModelScope.launch {
        when (behavior) {
            SongClickBehavior.PlayWholeList -> openQueue(songs, position)

            SongClickBehavior.PlayOnlyThisSong -> {
                val selectedSong = songs.getOrNull(position)
                if (selectedSong != null) {
                    openQueue(listOf(selectedSong))
                }
            }

            SongClickBehavior.QueueNext -> {
                val selectedSong = songs.getOrNull(position)
                if (selectedSong != null) {
                    queueNext(selectedSong)
                }
            }

            SongClickBehavior.EnqueueAtEnd -> {
                val selectedSong = songs.getOrNull(position)
                if (selectedSong != null) {
                    enqueue(selectedSong)
                }
            }
        }
    }

    fun queueNext(song: Song) = viewModelScope.launch {
        mediaController?.let { controller ->
            if (controller.currentTimeline.isEmpty) {
                openQueue(listOf(song), startPlaying = false)
            } else {
                var nextIndex = position.getIndexForPosition(position.next)
                if (nextIndex == C.INDEX_UNSET) {
                    nextIndex = controller.mediaItemCount
                }
                val item = listOf(song).toMediaItems(appContext).firstOrNull()
                if (item != null) {
                    controller.addMediaItem(nextIndex, item)
                }
            }
        }
    }

    fun queueNext(songs: List<Song>) = viewModelScope.launch {
        mediaController?.let { controller ->
            if (controller.currentTimeline.isEmpty) {
                openQueue(songs, startPlaying = false)
            } else {
                var nextIndex = position.getIndexForPosition(position.next)
                if (nextIndex == C.INDEX_UNSET) {
                    nextIndex = controller.mediaItemCount
                }
                val items = songs.toMediaItems(appContext)
                controller.addMediaItems(nextIndex, items)
            }
        }
    }

    fun enqueue(song: Song, toPosition: Int = -1) = viewModelScope.launch {
        mediaController?.let { controller ->
            if (controller.currentTimeline.isEmpty) {
                openQueue(listOf(song), startPlaying = false)
            } else {
                val toIndex = position.getIndexForPosition(toPosition)
                val item = listOf(song).toMediaItems(appContext).firstOrNull()
                if (item != null) {
                    if (toPosition >= 0 && toIndex >= 0) {
                        controller.addMediaItem(toIndex, item)
                    } else {
                        controller.addMediaItem(item)
                    }
                }
            }
        }
    }

    fun enqueue(songs: List<Song>) = viewModelScope.launch {
        mediaController?.let { controller ->
            if (controller.currentTimeline.isEmpty) {
                openQueue(songs, startPlaying = false)
            } else {
                val items = songs.toMediaItems(appContext)
                controller.addMediaItems(items)
            }
        }
    }

    fun clearQueue(behavior: QueueClearingBehavior = Preferences.clearQueueAction) {
        when (behavior) {
            QueueClearingBehavior.RemoveAllSongs -> {
                mediaController?.clearMediaItems()
            }

            QueueClearingBehavior.RemoveAllSongsExceptCurrentlyPlaying -> {
                mediaController?.let { controller ->
                    if (controller.mediaItemCount > 1) {
                        val currentItem = controller.currentMediaItemIndex
                        if (currentItem == C.INDEX_UNSET) return
                        if (currentItem == 0) {
                            controller.removeMediaItems(1, controller.mediaItemCount)
                        } else {
                            controller.removeMediaItems(0, currentItem)
                            if (controller.mediaItemCount > 1) {
                                controller.removeMediaItems(1, controller.mediaItemCount)
                            }
                        }
                    }
                }
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun stopAt(stopPosition: Int) = viewModelScope.launch {
        mediaController?.let { controller ->
            if (stopPosition >= 0 && stopPosition < controller.mediaItemCount) {
                val stopIndex = position.getIndexForPosition(stopPosition)
                val mediaItem = controller.getMediaItemAt(stopIndex)
                val resultFuture = controller.sendCustomCommand(
                    SessionCommand(
                        Playback.SET_STOP_POSITION,
                        Bundle().apply {
                            putInt("index", stopIndex)
                        }
                    ),
                    Bundle.EMPTY
                )
                val result = runCatching { resultFuture.await() }
                    .getOrDefault(SessionResult(SessionError.ERROR_UNKNOWN))
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    val canceled = result.extras.getBoolean("canceled", false)
                    _stopAfterPosition.send(mediaItem.mediaMetadata.title?.toString() to canceled)
                } else {
                    _stopAfterPosition.send(null to false)
                }
            }
        }
    }

    fun moveSong(fromPosition: Int, toPosition: Int) {
        mediaController?.moveMediaItem(
            position.getIndexForPosition(fromPosition),
            position.getIndexForPosition(toPosition)
        )
    }

    fun moveToNextPosition(fromPosition: Int) {
        moveSong(fromPosition, position.next)
    }

    fun removePosition(positionToRemove: Int) {
        mediaController?.removeMediaItem(position.getIndexForPosition(positionToRemove))
    }

    fun restorePlayback() = viewModelScope.launch {
        mediaController?.let { controller ->
            if (!controller.playWhenReady) {
                val resultFuture = controller.sendCustomCommand(
                    SessionCommand(Playback.RESTORE_PLAYBACK, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                val result = runCatching { resultFuture.await() }
                    .getOrDefault(SessionResult(SessionError.ERROR_UNKNOWN))
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    controller.playWhenReady = true
                }
            }
        }
    }

    fun generatePlayerScheme(
        context: Context,
        mode: PlayerColorScheme.Mode,
        color: PaletteColor
    ) = viewModelScope.launch(Dispatchers.Default) {
        val currentScheme = colorScheme.mode.takeIf { it == PlayerColorSchemeMode.AppTheme }
        if (currentScheme == mode && colorScheme.appThemeToken.isValid(context))
            return@launch

        val result = runCatching {
            PlayerColorScheme.autoColorScheme(context, color, mode)
        }
        if (result.isSuccess) {
            _colorScheme.value = result.getOrThrow()
        } else if (result.isFailure) {
            Log.e(TAG, "Failed to load color scheme", result.exceptionOrNull())
        }
    }

    private val appContext: Context by inject()

    companion object {
        private const val TAG = "PlayerViewModel"
    }
}