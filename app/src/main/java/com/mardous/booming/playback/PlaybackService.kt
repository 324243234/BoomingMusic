package com.mardous.booming.playback

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.service.media.MediaBrowserService
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import androidx.core.os.postDelayed
import androidx.media.utils.MediaConstants
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.UnshuffledShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult.AcceptedResultBuilder
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.mardous.booming.R
import com.mardous.booming.coil.CoilBitmapLoader
import com.mardous.booming.core.appwidgets.WidgetData
import com.mardous.booming.core.appwidgets.WidgetDataSource
import com.mardous.booming.core.appwidgets.WidgetPresenter
import com.mardous.booming.core.appwidgets.config.SongSource
import com.mardous.booming.core.appwidgets.state.PlaybackState
import com.mardous.booming.core.audio.AudioOutputObserver
import com.mardous.booming.core.model.queue.QueuePosition
import com.mardous.booming.data.local.MediaStoreObserver
import com.mardous.booming.data.local.ReplayGainTagExtractor
// 🌟 修复包名 1：LyricsRepository 依然在老地方，必须保留
import com.mardous.booming.data.repository.LyricsRepository
import com.mardous.booming.data.model.QueueSong
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.data.model.network.ScrobblingService
// 🌟 修复包名 2：同步作者的更新，引入新的 Repository 路径
import com.mardous.booming.data.repository.Repository
import com.mardous.booming.extensions.isBluetoothA2dpConnected
import com.mardous.booming.extensions.isBluetoothA2dpDisconnected
import com.mardous.booming.extensions.showToast
import com.mardous.booming.extensions.utilities.toEnum
import com.mardous.booming.playback.equalizer.EqualizerManager
import com.mardous.booming.playback.library.LibraryProvider
import com.mardous.booming.playback.library.MediaIDs
import com.mardous.booming.playback.processor.BalanceAudioProcessor
import com.mardous.booming.playback.processor.ReplayGainAudioProcessor
import com.mardous.booming.playback.renderer.AlacWorkaroundCodecSelector
import com.mardous.booming.playback.renderer.BoomingMusicRenderersFactory
import com.mardous.booming.ui.screen.MainActivity
import com.mardous.booming.util.CLEAR_QUEUE_ON_COMPLETION
import com.mardous.booming.util.ENABLE_HISTORY
import com.mardous.booming.util.IGNORE_AUDIO_FOCUS
import com.mardous.booming.util.MP3_INDEX_SEEKING
import com.mardous.booming.util.PAUSE_ON_ZERO_VOLUME
import com.mardous.booming.util.PLAY_ON_STARTUP_MODE
import com.mardous.booming.util.PackageValidator
import com.mardous.booming.util.PlayOnStartupMode
import com.mardous.booming.util.Preferences
import com.mardous.booming.util.Preferences.requireString
import com.mardous.booming.util.QUEUE_NEXT_MODE
import com.mardous.booming.util.REWIND_WITH_BACK
import com.mardous.booming.util.SEEK_INTERVAL
import com.mardous.booming.util.STOP_WHEN_CLOSED_FROM_RECENTS
import com.mardous.booming.util.SongPlayCountHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalAtomicApi::class)
class PlaybackService :
    MediaLibraryService(),
    MediaLibrarySession.Callback,
    Player.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val serviceScope = CoroutineScope(Job() + Main)
    private val uiHandler = Handler(Looper.getMainLooper())

    private val preferences: SharedPreferences by inject()
    private val sleepTimer: SleepTimer by inject()
    private val equalizerManager: EqualizerManager by inject()
    private val audioOutputObserver: AudioOutputObserver by inject()
    private val repository: Repository by inject()
    // 🌟 本地扩展：注入歌词仓库
    private val lyricsRepository: LyricsRepository by inject()

    private val queueStateHolder: QueueStateHolder by inject()
    private val isInTimelineUpdate = AtomicBoolean(false)
    private var generateQueueJob: Job? = null

    private val libraryProvider = LibraryProvider(repository)
    private val songPlayCountHelper = SongPlayCountHelper()
    private val mediaStoreObserver = MediaStoreObserver(uiHandler) {
        WidgetDataSource.invalidate()
        dispatchPlayQueue(player)
        mediaSession?.broadcastCustomCommand(
            SessionCommand(Playback.EVENT_MEDIA_CONTENT_CHANGED, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    private val currentDurationMs get() = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
    private val currentPositionMs get() = player.currentPosition.coerceAtLeast(0L)

    private val widgets = WidgetPresenter(this, serviceScope, object : WidgetPresenter.Playback {
        override val isPlaying get() = player.isPlaying
        override val positionMs get() = currentPositionMs
        override val durationMs get() = currentDurationMs
        override suspend fun snapshot(needs: Set<WidgetData>) = buildPlaybackState(needs)
    })

    private val playerThread = HandlerThread("Booming-ExoPlayer", Process.THREAD_PRIORITY_AUDIO)
    private val balanceProcessor: BalanceAudioProcessor by inject()
    private val replayGainProcessor: ReplayGainAudioProcessor by inject()

    private lateinit var packageValidator: PackageValidator
    private lateinit var nm: NotificationManager
    private lateinit var persistentStorage: PersistentStorage
    private lateinit var customCommands: List<CommandButton>
    private lateinit var player: AdvancedForwardingPlayer
    private var mediaSession: MediaLibrarySession? = null

    private var eqStateHandler: Handler = Handler(Looper.getMainLooper())

    // 🌟 本地扩展：特有变量
    private var bluetoothLyricManager: BluetoothLyricManager? = null
    private var carWithUpdateJob: Job? = null
    private var lastProcessedMediaId: String? = null
    private var lastTimelineHashCode: Int = 0
    private var currentIsFavorite = false

    private var errorRecoveryRetryCount = 0
    private var pausedByZeroVolume = false
    private var hasSetUnshuffledOrder = false
    private var stopIndex = -1

    private var headsetClickCount = 0
    private val headsetClickRunnable = Runnable {
        if (!::player.isInitialized) return@Runnable
        val count = headsetClickCount
        headsetClickCount = 0
        when (count) {
            1 -> if (player.isPlaying) player.pause() else resumePlayback()
            2 -> player.seekToNext()
            3 -> player.seekToPrevious()
        }
    }

    private fun resumePlayback() {
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> player.seekTo(player.currentMediaItemIndex, C.TIME_UNSET)
        }
        player.play()
    }

    private var fadeOutAnimator: ValueAnimator? = null

    val isInTransientFocusLoss: Boolean
        get() = player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS

    val isPlaying: Boolean
        get() = player.isPlaying

    private val shuffleCommand: CommandButton
        get() = if (player.shuffleModeEnabled) {
            customCommands[1]
        } else {
            customCommands[0]
        }

    // 🌟 融合点 1：吸收作者最新更新的 Repeat 循环按键控制
    private val repeatCommand: CommandButton
        get() = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> customCommands[3]
            Player.REPEAT_MODE_ONE -> customCommands[4]
            else -> customCommands[2]
        }

    private val pauseOnZeroVolume: Boolean
        get() = preferences.getBoolean(PAUSE_ON_ZERO_VOLUME, false)
    private val sequentialTimeline: Boolean
        get() = preferences.getString(QUEUE_NEXT_MODE, "1") == "1"
    private val handleAudioFocus: Boolean
        get() = preferences.getBoolean(IGNORE_AUDIO_FOCUS, false).not()
    private val maxSeekToPreviousMs: Long
        get() = if (preferences.getBoolean(REWIND_WITH_BACK, true)) REWIND_INSTEAD_PREVIOUS_MILLIS else 0
    private val seekInterval: Long
        get() = preferences.getInt(SEEK_INTERVAL, 10) * 1000L

    // 🌟 【本地核心 1】：无状态瞬间解析，防止队列假死
    private suspend fun resolveSongInstantly(mediaItem: MediaItem?): Song {
        if (mediaItem == null) return Song.emptySong
        return withContext(IO) {
            val songId = mediaItem.mediaId.toLongOrNull()
            if (songId != null) {
                val song = runCatching { repository.songById(songId) }.getOrNull()
                if (song != null && song != Song.emptySong) return@withContext song
            }
            runCatching { repository.songByMediaItem(mediaItem, ignoreBlacklist = true) }.getOrNull() ?: Song.emptySong
        }
    }

    override fun onCreate() {
        super.onCreate()
        nm = requireNotNull(getSystemService<NotificationManager>())
        createNotificationChannel()

        packageValidator = PackageValidator(this, R.xml.allowed_media_browser_callers)

        // 🌟 融合点 2：吸收作者加入的循环逻辑相关 CommandButton
        customCommands = listOf(
            CommandButton.Builder(CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(getString(R.string.shuffle_mode))
                .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE, true)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
                .setDisplayName(getString(R.string.shuffle_mode))
                .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE, false)
                .build(),
            CommandButton.Builder(CommandButton.ICON_REPEAT_OFF)
                .setDisplayName(getString(R.string.repeat_mode))
                .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_ALL)
                .build(),
            CommandButton.Builder(CommandButton.ICON_REPEAT_ALL)
                .setDisplayName(getString(R.string.repeat_mode))
                .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_ONE)
                .build(),
            CommandButton.Builder(CommandButton.ICON_REPEAT_ONE)
                .setDisplayName(getString(R.string.repeat_mode))
                .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_OFF)
                .build()
        )

        playerThread.start()
        player = AdvancedForwardingPlayer(
            ExoPlayer.Builder(this)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(), handleAudioFocus
                )
                .setRenderersFactory(
                    BoomingMusicRenderersFactory(this, balanceProcessor, replayGainProcessor)
                        .setEnableAudioFloatOutput(equalizerManager.audioFloatOutput.value)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                        .setMediaCodecSelector(AlacWorkaroundCodecSelector())
                        .setEnableDecoderFallback(true)
                )
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        this, DefaultExtractorsFactory()
                            .setConstantBitrateSeekingEnabled(true)
                            .also {
                                if (preferences.getBoolean(MP3_INDEX_SEEKING, false)) {
                                    it.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
                                }
                            }
                    )
                )
                .setSkipSilenceEnabled(equalizerManager.skipSilence.value)
                .setHandleAudioBecomingNoisy(true)
                .setMaxSeekToPreviousPositionMs(maxSeekToPreviousMs)
                .setSeekBackIncrementMs(seekInterval)
                .setSeekForwardIncrementMs(seekInterval)
                .setPlaybackLooper(playerThread.looper)
                .build()
        )

        player.exoPlayer.shuffleOrder = ImprovedShuffleOrder(0, 0, Random.nextLong())
        player.setSequentialTimelineEnabled(sequentialTimeline)
        player.addListener(this)

        mediaSession = MediaLibrarySession.Builder(this, player, this)
            .setId(packageName)
            .setSessionActivity(createSessionActivityIntent())
            .setBitmapLoader(CacheBitmapLoader(CoilBitmapLoader(this@PlaybackService)))
            .build()

        setForegroundServiceTimeoutMs(FOREGROUND_SERVICE_TIMEOUT)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { _ -> NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.playing_notification_description
            ).apply {
                setSmallIcon(R.drawable.ic_stat_music_playback)
            }
        )

        mediaStoreObserver.init(this)

        persistentStorage = PersistentStorage(this, serviceScope, player)
        persistentStorage.restoreState { items, shuffleOrder ->
            player.setMediaItems(items.mediaItems, items.startIndex, items.startPositionMs)
            player.prepare()
            if (player.shuffleModeEnabled && shuffleOrder != null) {
                player.exoPlayer.shuffleOrder = shuffleOrder
            }
        }

        sleepTimer.addFinishListener { sleepParams ->
            if (player.playWhenReady && player.isPlaying) {
                if (sleepParams.pendingQuit) {
                    player.exoPlayer.pauseAtEndOfMediaItems = true
                } else {
                    if (sleepParams.fadeOut) {
                        launchMusicFadeOut(sleepParams.fadeDuration)
                    } else {
                        player.pause()
                    }
                }
            }
        }

        // 🌟 本地扩展：蓝牙歌词初始化
        if (preferences.getBoolean("enable_bluetooth_lyrics", false)) {
            bluetoothLyricManager = BluetoothLyricManager(player, serviceScope, lyricsRepository, preferences)
        }

        preferences.registerOnSharedPreferenceChangeListener(this)
        audioOutputObserver.startObserver()

        prepareEqualizerAndSoundSettings()
        registerReceivers()
        widgets.start()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if ((!isPlaybackOngoing && !isInTransientFocusLoss) ||
            preferences.getBoolean(STOP_WHEN_CLOSED_FROM_RECENTS, false)) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        widgets.stop()
        
        // 🌟 本地扩展释放资源
        carWithUpdateJob?.cancel()
        bluetoothLyricManager?.release()
        
        if (bluetoothConnectedRegistered) {
            unregisterReceiver(bluetoothReceiver)
            bluetoothConnectedRegistered = false
        }
        if (headsetReceiverRegistered) {
            unregisterReceiver(headsetReceiver)
            headsetReceiverRegistered = false
        }
        eqStateHandler.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacks(headsetClickRunnable)
        serviceScope.cancel()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        audioOutputObserver.stopObserver()
        mediaStoreObserver.stop(this)
        mediaSession?.release()
        player.removeListener(this)
        player.release()
        playerThread.quitSafely()
        equalizerManager.release()
        sleepTimer.release()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY_SONG) {
            val songId = intent.getLongExtra(EXTRA_SONG_ID, -1L)
            if (songId != -1L) {
                val source = intent.getStringExtra(EXTRA_SONG_SOURCE)?.toEnum<SongSource>()
                    ?: SongSource.Recent
                playSong(songId, source)
            }
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        val myPackageName = this.packageName
        if (myPackageName == controllerInfo.packageName ||
            controllerInfo.packageName == MediaBrowserService.SERVICE_INTERFACE ||
            controllerInfo.packageName == MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME) {
            return mediaSession
        }
        val controllerType = controllerInfo.connectionHints.getString(CONNECTION_HINT_KEY_CONTROLLER_INFO_TYPE)
        if (controllerType == Intent.ACTION_MEDIA_BUTTON) {
            val sessionId = controllerInfo.connectionHints.getString(CONNECTION_HINT_KEY_SESSION_ID)
            if (sessionId == this.packageName) {
                return mediaSession
            }
        } else if (packageValidator.isKnownCaller(controllerInfo.packageName, controllerInfo.uid)) {
            return mediaSession
        }
        return null
    }

    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.ConnectionResult> {
        val connectionResult = AcceptedResultBuilder(session, controller).build()
        val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
        if (controller.uid == Process.myUid()) {
            availableSessionCommands.add(SessionCommand(Playback.CYCLE_REPEAT, Bundle.EMPTY))
            availableSessionCommands.add(SessionCommand(Playback.TOGGLE_SHUFFLE, Bundle.EMPTY))
            availableSessionCommands.add(SessionCommand(Playback.TOGGLE_FAVORITE, Bundle.EMPTY))
            availableSessionCommands.add(SessionCommand(Playback.RESTORE_PLAYBACK, Bundle.EMPTY))
            availableSessionCommands.add(SessionCommand(Playback.SET_UNSHUFFLED_ORDER, Bundle.EMPTY))
            availableSessionCommands.add(SessionCommand(Playback.SET_STOP_POSITION, Bundle.EMPTY))
        }

        // 🌟 本地扩展：允许车机端触发自定义模式和收藏
        availableSessionCommands.add(SessionCommand("ucar.media.action.PLAY_MODE", Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand("ucar.media.action.COLLECT", Bundle.EMPTY))

        return Futures.immediateFuture(
            MediaSession.ConnectionResult.accept(
                availableSessionCommands.build(),
                connectionResult.availablePlayerCommands
            )
        )
    }

    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent
    ): Boolean {
        val ke = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        if (ke != null && (ke.keyCode == KeyEvent.KEYCODE_HEADSETHOOK || ke.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            if (ke.action == KeyEvent.ACTION_DOWN && ke.repeatCount == 0) {
                headsetClickCount++
                uiHandler.removeCallbacks(headsetClickRunnable)
                if (headsetClickCount >= 3) {
                    uiHandler.post(headsetClickRunnable)
                } else {
                    uiHandler.postDelayed(headsetClickRunnable, 300)
                }
            }
            return true
        }
        return super.onMediaButtonEvent(session, controllerInfo, intent)
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        equalizerManager.setSessionId(audioSessionId)
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val isKnownCaller = packageValidator.isKnownCaller(browser.packageName, browser.uid)
        val outExtras = Bundle().apply {
            putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, isKnownCaller)
        }
        val libraryParams = LibraryParams.Builder()
            .setOffline(true)
            .setExtras(outExtras)
            .build()
        val mediaItem = if (isKnownCaller) {
            when {
                params?.isRecent == true -> {
                    MediaItem.Builder()
                        .setMediaId(MediaIDs.RECENT_SONGS)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build()
                        )
                        .build()
                }

                else -> {
                    MediaItem.Builder()
                        .setMediaId(MediaIDs.ROOT)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build()
                        )
                        .build()
                }
            }
        } else {
            MediaItem.EMPTY
        }
        return Futures.immediateFuture(LibraryResult.ofItem(mediaItem, libraryParams))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        session.denyUntrusted<ImmutableList<MediaItem>>(browser)?.let { return it }
        return serviceScope.future(IO) {
            val result = runCatching {
                libraryProvider.getChildren(this@PlaybackService, parentId)
            }
            if (result.isSuccess) {
                LibraryResult.ofItemList(result.getOrThrow(), params)
            } else {
                LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            }
        }
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        session.denyUntrusted<MediaItem>(browser)?.let { return it }
        return serviceScope.future(IO) {
            val mediaItem = runCatching { libraryProvider.getItem(mediaId) }
                .getOrDefault(MediaItem.EMPTY)
            if (mediaItem != MediaItem.EMPTY) {
                LibraryResult.ofItem(mediaItem, null)
            } else {
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.denyUntrusted<Void>(browser)?.let { return it }
        return serviceScope.future(IO) {
            runCatching { libraryProvider.search(browser.uid, query) }
                .onSuccess { session.notifySearchResultChanged(browser, query, it.size, params) }

            LibraryResult.ofVoid()
        }
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        session.denyUntrusted<ImmutableList<MediaItem>>(browser)?.let { return it }
        return Futures.immediateFuture(
            LibraryResult.ofItemList(libraryProvider.searchResult(browser.uid), params)
        )
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        return serviceScope.future(IO) {
            runCatching { libraryProvider.getMediaItemsForPlayback(controller.uid, mediaItems) }
                .getOrDefault(emptyList())
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaItemsWithStartPosition> {
        player.exoPlayer.let { exoPlayer ->
            if (exoPlayer.shuffleOrder !is ImprovedShuffleOrder && !hasSetUnshuffledOrder) {
                exoPlayer.shuffleOrder = ImprovedShuffleOrder(
                    firstIndex = player.currentMediaItemIndex,
                    length = player.mediaItemCount,
                    randomSeed = Random.nextLong()
                )
            }

            (exoPlayer.shuffleOrder as? ImprovedShuffleOrder)
                ?.playerIndex = startIndex

            hasSetUnshuffledOrder = false
        }
        return serviceScope.future(IO) {
            if (mediaSession.isAutomotiveController(controller) ||
                mediaSession.isAutoCompanionController(controller)) {
                runCatching { libraryProvider.getMediaItemsForAAOSPlayback(controller.uid, mediaItems) }
                    .getOrNull()
                    .let {
                        MediaItemsWithStartPosition(
                            it?.first ?: emptyList(),
                            it?.second ?: C.INDEX_UNSET,
                            startPositionMs
                        )
                    }
            } else {
                runCatching {
                    libraryProvider.getMediaItemsForPlayback(
                        controller.uid,
                        mediaItems = mediaItems,
                        tryToResolveComplexPaths = true
                    )
                }.getOrDefault(emptyList()).let {
                    MediaItemsWithStartPosition(it, startIndex, startPositionMs)
                }
            }
        }.also { future ->
            future.addListener({
                val result = runCatching { future.get() }.getOrNull()
                if (result != null && result.mediaItems.isNotEmpty()) {
                    this.mediaSession?.broadcastCustomCommand(
                        SessionCommand(Playback.EVENT_PLAYBACK_STARTED, Bundle.EMPTY),
                        Bundle.EMPTY
                    )
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun <T : Any> MediaSession.denyUntrusted(
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<LibraryResult<T>>? =
        if (isTrustedController(controller)) null
        else Futures.immediateFuture(LibraryResult.ofError<T>(SessionError.ERROR_PERMISSION_DENIED))

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        return when (customCommand.customAction) {
            Playback.TOGGLE_SHUFFLE -> serviceScope.future(Main) {
                awaitRestoration()
                toggleShuffle()
                awaitSavedState()
                SessionResult(SessionResult.RESULT_SUCCESS, modesBundle())
            }

            Playback.CYCLE_REPEAT -> serviceScope.future(Main) {
                awaitRestoration()
                cycleRepeat()
                awaitSavedState()
                SessionResult(SessionResult.RESULT_SUCCESS, modesBundle())
            }

            // 🌟 融合点：兼容 Ucar 与自身按键收藏逻辑
            Playback.TOGGLE_FAVORITE, "ucar.media.action.COLLECT" -> serviceScope.future(Main) {
                toggleFavorite()
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            // 🌟 本地扩展：接管车机端模式切换
            "ucar.media.action.PLAY_MODE" -> serviceScope.future(Main) {
                if (player.shuffleModeEnabled) {
                    player.shuffleModeEnabled = false
                    player.repeatMode = Player.REPEAT_MODE_ONE
                } else if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                    player.shuffleModeEnabled = false
                    player.repeatMode = Player.REPEAT_MODE_ALL
                } else {
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    player.shuffleModeEnabled = true
                }
                
                updateCarWithMetadata()
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            Playback.RESTORE_PLAYBACK -> {
                val playOnStartupMode = preferences.requireString(PLAY_ON_STARTUP_MODE, PlayOnStartupMode.NEVER)
                if (playOnStartupMode != PlayOnStartupMode.NEVER) {
                    CallbackToFutureAdapter.getFuture { completer ->
                        persistentStorage.waitForRestoration {
                            if (!player.currentTimeline.isEmpty) {
                                mediaSession?.broadcastCustomCommand(
                                    SessionCommand(
                                        Playback.EVENT_PLAYBACK_RESTORED,
                                        Bundle.EMPTY
                                    ),
                                    Bundle.EMPTY
                                )
                                completer.set(SessionResult(SessionResult.RESULT_SUCCESS))
                            } else {
                                completer.setException(IllegalStateException("Timeline is empty"))
                            }
                        }
                    }
                } else {
                    Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                }
            }

            Playback.SET_UNSHUFFLED_ORDER -> {
                hasSetUnshuffledOrder = true
                player.exoPlayer.shuffleOrder = UnshuffledShuffleOrder(player.mediaItemCount)
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            Playback.SET_STOP_POSITION -> {
                val newStopIndex = customCommand.customExtras.getInt("index", -1)
                val canceled = newStopIndex > -1 && newStopIndex == stopIndex
                if (canceled) {
                    player.exoPlayer.pauseAtEndOfMediaItems = false
                    stopIndex = -1
                } else if (newStopIndex == player.currentMediaItemIndex) {
                    player.exoPlayer.pauseAtEndOfMediaItems = true
                    stopIndex = -1
                } else {
                    player.exoPlayer.pauseAtEndOfMediaItems = false
                    stopIndex = newStopIndex
                }
                Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                        putBoolean("canceled", canceled)
                    })
                )
            }

            else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaItemsWithStartPosition> {
        if (persistentStorage.restorationState.isRestored) {
            return Futures.immediateFailedFuture(IllegalStateException("No MediaItems saved"))
        } else {
            val settableFuture = SettableFuture.create<MediaItemsWithStartPosition>()
            persistentStorage.waitForMediaItems { items, shuffleOrder ->
                if (items.mediaItems.isNotEmpty()) {
                    if (player.shuffleModeEnabled && shuffleOrder != null) {
                        player.exoPlayer.shuffleOrder = shuffleOrder
                    }
                    settableFuture.set(items)
                } else {
                    settableFuture.setException(IllegalStateException("No MediaItems saved"))
                }
            }
            return settableFuture
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (player.playbackState == Player.STATE_ENDED &&
            preferences.getBoolean(CLEAR_QUEUE_ON_COMPLETION, false)) {
            player.exoPlayer.clearMediaItems()
        }
        refreshMediaButtonCustomLayout()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            // 🌟 本地扩展：增加哈希校验防止UI闪烁
            var currentHash = 1
            val window = Timeline.Window()
            for (i in 0 until timeline.windowCount) {
                currentHash = 31 * currentHash + timeline.getWindow(i, window).mediaItem.mediaId.hashCode()
            }
            
            if (currentHash == lastTimelineHashCode) {
                return
            }
            lastTimelineHashCode = currentHash

            buildPlayQueue(player) { songs, position ->
                queueStateHolder.submitQueue(songs, position)
                persistentStorage.saveState(true)
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
            player.exoPlayer.pauseAtEndOfMediaItems = false
            sleepTimer.consumePendingQuit()
            if (stopIndex == player.currentMediaItemIndex) {
                stopIndex = -1
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            widgets.refreshPosition()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) {
            val currentDurationMs = player.mediaMetadata.durationMs ?: 0
            if (currentDurationMs > 0) {
                if (!player.currentTimeline.isEmpty) {
                    persistentStorage.saveState()
                }
            }
        }
        songPlayCountHelper.notifyPlayStateChanged(isPlaying)
        widgets.refresh()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        widgets.refreshModes(shuffleModeEnabled, player.repeatMode)
        refreshMediaButtonCustomLayout()
        persistentStorage.saveState()
        updateCarWithMetadata() // 🌟 本地扩展同步
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        widgets.refreshModes(player.shuffleModeEnabled, repeatMode)
        refreshMediaButtonCustomLayout()
        persistentStorage.saveState()
        updateCarWithMetadata() // 🌟 本地扩展同步
    }

    /**
     * 🌟 【本地核心 2】：独立稳定运行的 CarWith LRC 投喂引擎。
     */
    private fun updateCarWithMetadata() {
        carWithUpdateJob?.cancel()

        val isShuffleEnabled = player.shuffleModeEnabled
        val currentRepeatMode = player.repeatMode

        carWithUpdateJob = serviceScope.launch(Main) {
            delay(50) 
            
            val currentIndex = player.currentMediaItemIndex
            if (currentIndex < 0 || currentIndex >= player.mediaItemCount) return@launch
            val expectedMediaItem = player.getMediaItemAt(currentIndex)
            val expectedMediaId = expectedMediaItem.mediaId
            
            withContext(IO) {
                val song = resolveSongInstantly(expectedMediaItem)
                if (song == Song.emptySong) return@withContext

                val isFavorite = runCatching<Boolean> { repository.isSongFavorite(song.id) }.getOrDefault(false)
                val collectState = if (isFavorite) "1" else "0"

                val rawLyrics = runCatching { lyricsRepository.fileLyrics(song) ?: lyricsRepository.embeddedLyrics(song) }.getOrNull()
                val parsedLyrics = rawLyrics?.let { runCatching { lyricsRepository.parseRawLyrics(song, it) }.getOrNull() }

                val showTranslation = preferences.getBoolean("lyrics_show_translation", false)

                val lrcText = parsedLyrics?.lines?.joinToString("\n") { line ->
                    val timeMs = line.start
                    val min = timeMs / 60000
                    val sec = (timeMs % 60000) / 1000
                    val ms = (timeMs % 1000) / 10
                    val timeStr = String.format("[%02d:%02d.%02d]", min, sec, ms)
                    
                    val content = line.content.content 
                    val translation = line.translation?.content
                    
                    if (showTranslation && !translation.isNullOrBlank()) {
                        "$timeStr$content 「$translation」"
                    } else {
                        "$timeStr$content"
                    }
                } ?: ""

                val playMode: Long = when {
                    isShuffleEnabled -> 0L
                    currentRepeatMode == Player.REPEAT_MODE_ONE -> 1L
                    else -> 2L
                }

                withContext(Main) {
                    val latestIndex = player.currentMediaItemIndex
                    if (latestIndex < 0 || latestIndex >= player.mediaItemCount) return@withContext
                    val latestItem = player.getMediaItemAt(latestIndex)
                    
                    if (latestItem.mediaId != expectedMediaId) return@withContext

                    val currentExtras = latestItem.mediaMetadata.extras ?: Bundle.EMPTY

                    val currentCollectState = currentExtras.getString("ucar.media.metadata.COLLECT_STATE") ?: ""
                    val currentPlayMode = currentExtras.getLong("ucar.media.metadata.PLAY_MODE", -1L)
                    val currentLyric = currentExtras.getString("ucar.media.metadata.LYRICS_WHOLE") ?: ""

                    if (currentCollectState == collectState &&
                        currentPlayMode == playMode &&
                        currentLyric == lrcText) {
                        return@withContext
                    }

                    val newExtras = Bundle(currentExtras).apply {
                        putLong("ucar.media.metadata.PLAY_MODE", playMode)
                        putString("ucar.media.metadata.COLLECT_STATE", collectState)
                        putString("ucar.media.metadata.LYRICS_WHOLE", lrcText) 
                        putString("android.media.metadata.LYRIC", lrcText) 
                    }

                    val updatedMetadata = latestItem.mediaMetadata.buildUpon().setExtras(newExtras).build()
                    val updatedItem = latestItem.buildUpon().setMediaMetadata(updatedMetadata).build()

                    player.exoPlayer.replaceMediaItem(latestIndex, updatedItem)
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val isPlaying = player.isPlaying
        val newMediaId = mediaItem?.mediaId

        if (newMediaId != null && newMediaId == lastProcessedMediaId) {
            return
        }
        lastProcessedMediaId = newMediaId

        serviceScope.launch(IO) {
            // 🌟 核心破冰：无状态瞬间解析
            val newSong = resolveSongInstantly(mediaItem)

            currentIsFavorite = runCatching<Boolean> { repository.isSongFavorite(newSong.id) }.getOrDefault(false)

            withContext(Main) {
                refreshMediaButtonCustomLayout()
                bluetoothLyricManager?.loadLyricsForSong(newSong)
            }

            if (newSong != Song.emptySong) {
                replayGainProcessor.currentGain = ReplayGainTagExtractor.getReplayGain(newSong)
            }

            val previousSong = songPlayCountHelper.song
            val shouldBumpPlayCount = songPlayCountHelper.shouldBumpPlayCount()
            songPlayCountHelper.notifySongChanged(newSong, isPlaying)

            val enableHistory = preferences.getBoolean(ENABLE_HISTORY, true)
            if (enableHistory && newSong != Song.emptySong && !newSong.resolvedFromFile) {
                if (preferences.getBoolean(ENABLE_HISTORY, true)) {
                    repository.upsertSongInHistory(newSong)
                }
                if (!NetworkFeature.Lastfm.NowPlaying.isAvailable) {
                    launch { repository.updateNowPlaying(ScrobblingService.Lastfm, newSong) }
                }
                if (NetworkFeature.ListenBrainz.NowPlaying.isAvailable) {
                    launch { repository.updateNowPlaying(ScrobblingService.ListenBrainz, newSong) }
                }
            }
            if (enableHistory && previousSong != Song.emptySong && !previousSong.resolvedFromFile) {
                val timestampMillis = System.currentTimeMillis()
                val timestampSeconds = (timestampMillis / 1000)
                if (shouldBumpPlayCount) {
                    repository.insertOrIncrementPlayCount(
                        song = previousSong,
                        timePlayed = timestampMillis
                    )
                    if (NetworkFeature.Lastfm.Scrobbling.isAvailable) {
                        launch { repository.scrobble(ScrobblingService.Lastfm, previousSong, timestampSeconds) }
                    }
                    if (NetworkFeature.ListenBrainz.Scrobbling.isAvailable) {
                        launch { repository.scrobble(ScrobblingService.ListenBrainz, previousSong, timestampSeconds) }
                    }
                } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    repository.insertOrIncrementSkipCount(previousSong)
                }
            }
        }

        updateCarWithMetadata()

        if (player.currentMediaItemIndex == stopIndex) {
            player.exoPlayer.pauseAtEndOfMediaItems = true
        }

        persistentStorage.saveState()
        widgets.refresh()
    }

    // 🌟 修复编译错误：由于 SDK 变动，这个方法签名必须为 nullable
    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (preferences == null) return
        when (key) {
            QUEUE_NEXT_MODE -> {
                player.setSequentialTimelineEnabled(sequentialTimeline)
            }

            ENABLE_HISTORY -> {
                if (!preferences.getBoolean(key, true)) {
                    serviceScope.launch(IO) {
                        repository.clearSongHistory()
                        repository.clearPlayCount()
                    }
                }
            }

            IGNORE_AUDIO_FOCUS -> {
                player.setAudioAttributes(player.audioAttributes, handleAudioFocus)
            }

            REWIND_WITH_BACK -> {
                player.exoPlayer.setMaxSeekToPreviousPositionMs(maxSeekToPreviousMs)
            }

            SEEK_INTERVAL -> {
                player.exoPlayer.setSeekBackIncrementMs(seekInterval)
                player.exoPlayer.setSeekForwardIncrementMs(seekInterval)
            }

            "enable_bluetooth_lyrics" -> {
                val enabled = preferences.getBoolean(key, false)
                if (enabled && bluetoothLyricManager == null) {
                    bluetoothLyricManager = BluetoothLyricManager(player, serviceScope, lyricsRepository, preferences)
                    val currentIndex = player.currentMediaItemIndex
                    if (currentIndex >= 0 && currentIndex < player.mediaItemCount) {
                        val currentMediaItem = player.getMediaItemAt(currentIndex)
                        serviceScope.launch(IO) {
                            val song = resolveSongInstantly(currentMediaItem)
                            if (song != Song.emptySong) {
                                withContext(Main) {
                                    bluetoothLyricManager?.loadLyricsForSong(song)
                                }
                            }
                        }
                    }
                } else if (!enabled) {
                    bluetoothLyricManager?.release()
                    bluetoothLyricManager = null
                }
            }

            "preferred_lyrics_file_format" -> {
                updateCarWithMetadata()
                val currentIndex = player.currentMediaItemIndex
                if (currentIndex >= 0 && currentIndex < player.mediaItemCount) {
                    val currentMediaItem = player.getMediaItemAt(currentIndex)
                    serviceScope.launch(IO) {
                        val song = resolveSongInstantly(currentMediaItem)
                        if (song != Song.emptySong) {
                            withContext(Main) {
                                bluetoothLyricManager?.forceReloadLyricsForSong(song)
                            }
                        }
                    }
                }
            }

            "lyrics_show_translation" -> {
                updateCarWithMetadata()
                uiHandler.post {
                    bluetoothLyricManager?.forceInstantUpdate()
                }
            }
        }
    }

    private suspend fun buildPlaybackState(needs: Set<WidgetData>): PlaybackState {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
            ?: return PlaybackState()

        val base = PlaybackState(
            isPlaying = player.isPlaying,
            songId = id,
            positionMs = currentPositionMs,
            durationMs = currentDurationMs,
            isShuffleMode = player.shuffleModeEnabled,
            repeatMode = player.repeatMode
        )
        return withContext(IO) { WidgetDataSource.enrich(this@PlaybackService, base, needs) }
    }

    private fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    private fun cycleRepeat() {
        player.repeatMode = nextRepeatMode(player.repeatMode)
    }

    private suspend fun awaitRestoration() = suspendCancellableCoroutine { continuation ->
        persistentStorage.waitForRestoration { continuation.resume(Unit) }
    }

    private suspend fun awaitSavedState() {
        persistentStorage.saveState()
        persistentStorage.awaitPendingSave()
    }

    private fun modesBundle() = Bundle().apply {
        putBoolean(Playback.EXTRA_SHUFFLE_MODE, player.shuffleModeEnabled)
        putInt(Playback.EXTRA_REPEAT_MODE, player.repeatMode)
    }

    private suspend fun toggleFavorite() {
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex >= player.mediaItemCount) return
        
        val currentMediaItem = player.getMediaItemAt(currentIndex)

        withContext(IO) {
            val song = resolveSongInstantly(currentMediaItem)
            
            if (song != Song.emptySong) {
                repository.toggleFavorite(song)
                currentIsFavorite = !currentIsFavorite
            }
        }

        withContext(Main) {
            refreshMediaButtonCustomLayout()
        }

        widgets.refresh()
        mediaSession?.broadcastCustomCommand(
            SessionCommand(Playback.EVENT_FAVORITE_CONTENT_CHANGED, Bundle.EMPTY),
            Bundle.EMPTY
        )

        updateCarWithMetadata()
    }

    private fun dispatchPlayQueue(player: Player) {
        buildPlayQueue(player) { songs, position ->
            queueStateHolder.submitQueue(songs, position)
        }
    }

    private fun buildPlayQueue(
        player: Player,
        onCompletion: (List<QueueSong>, QueuePosition) -> Unit
    ) {
        if (isInTimelineUpdate.load()) return

        generateQueueJob?.cancel()
        generateQueueJob = serviceScope.launch {
            delay(QUEUE_DEBOUNCE.milliseconds)

            val timeline = player.currentTimeline
            if (timeline.isEmpty) {
                onCompletion(emptyList(), QueuePosition.Undefined)
                return@launch
            }

            val shuffleModeEnabled = player.shuffleModeEnabled
            val currentMediaItemIndex = player.currentMediaItemIndex

            val snapshot = player.captureQueueSnapshot(
                timeline = timeline,
                currentMediaItemIndex = currentMediaItemIndex,
                shuffleMode = shuffleModeEnabled
            )
            val position = snapshot.createPosition()

            val (songs, missingMediaItems) = withContext(IO) {
                repository.songsByMediaItems(snapshot.mediaItems, ignoreBlacklist = true)
                    .let { (songs, mediaItems) -> snapshot.deriveQueueSongs(songs) to mediaItems }
            }

            val missingIds = missingMediaItems.mapTo(mutableSetOf()) { it.mediaId }
            withContext(Main) {
                if (missingIds.isNotEmpty() &&
                    isInTimelineUpdate.compareAndSet(false, newValue = true)) {
                    player.removeMediaItemsById(missingIds)
                }

                if (isInTimelineUpdate.exchange(false)) {
                    buildPlayQueue(player, onCompletion)
                    return@withContext
                }

                onCompletion(
                    songs,
                    position.copy(
                        current = position.getPositionForIndex(player.currentMediaItemIndex)
                    )
                )
            }
        }
    }

    private fun playSong(songId: Long, source: SongSource) = serviceScope.launch {
        val resolved = runCatching {
            withContext(IO) {
                val songs = libraryProvider.getPlayableSongs(source.mediaId)
                val position = songs.indexOfFirst { it.id == songId }
                if (position != -1) songs to position
                else listOfNotNull(repository.songById(songId).takeIf { it != Song.emptySong }) to 0
            }
        }.onFailure {
            Log.e(TAG, "Couldn't resolve song $songId from ${source.mediaId}", it)
        }.getOrNull()

        val (queue, index) = resolved ?: run { stopSelf(); return@launch }
        if (queue.isEmpty()) {
            stopSelf()
            return@launch
        }
        awaitRestoration()
        player.setMediaItems(queue.map { song -> buildPlayableMediaItem(song) }, index, C.TIME_UNSET)
        player.playWhenReady = true
        player.prepare()
    }

    private fun createSessionActivityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createNotificationChannel() {
        var notificationChannel = nm.getNotificationChannel(CHANNEL_ID)
        if (notificationChannel == null) {
            notificationChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playing_notification_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.playing_notification_description)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                    setShowBadge(false)
                }
            }
            nm.createNotificationChannel(notificationChannel)
        }
    }

    // 🌟 融合点 3：这里把作者最新的循环按键和你的收藏合二为一
    private fun refreshMediaButtonCustomLayout() {
        val hasTimeline = !player.currentTimeline.isEmpty
        mediaSession?.connectedControllers?.forEach { controllerInfo ->
            if (mediaSession?.isRemoteController(controllerInfo) == true) {
                val buttonLayout = if (hasTimeline) {
                    val favButton = CommandButton.Builder()
                        .setDisplayName("Favorite")
                        .setSessionCommand(SessionCommand(Playback.TOGGLE_FAVORITE, Bundle.EMPTY))
                        .setIconResId(if (currentIsFavorite) R.drawable.ic_favorite_24dp else R.drawable.ic_favorite_outline_24dp)
                        .build()

                    ImmutableList.of(favButton, repeatCommand, shuffleCommand)
                } else {
                    emptyList()
                }
                mediaSession?.setMediaButtonPreferences(controllerInfo, buttonLayout)
            }
        }
    }

    private fun launchMusicFadeOut(durationMs: Long = 1000) {
        cancelSleepTimerFadeOut()

        fadeOutAnimator = ValueAnimator.ofFloat(player.volume, 0f).apply {
            duration = durationMs
            addUpdateListener { animation ->
                player.volume = animation.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    restorePlayerVolume()
                }

                override fun onAnimationEnd(animation: Animator) {
                    player.pause()
                    restorePlayerVolume()
                }
            })
        }
        fadeOutAnimator?.start()
    }

    private fun cancelSleepTimerFadeOut() {
        fadeOutAnimator?.cancel()
        fadeOutAnimator = null

        restorePlayerVolume()
    }

    private fun restorePlayerVolume() {
        player.volume = equalizerManager.volumeState.value.currentVolume
    }

    private fun prepareEqualizerAndSoundSettings() {
        serviceScope.launch {
            equalizerManager.initializeEqualizer()
        }
        serviceScope.launch {
            equalizerManager.volumeState.collect { volume ->
                cancelSleepTimerFadeOut()
                player.volume = volume.currentVolume
            }
        }
        serviceScope.launch {
            equalizerManager.audioOffload.collect { audioOffloadingEnabled ->
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setAudioOffloadPreferences(
                        AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(
                                if (audioOffloadingEnabled)
                                    AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                                else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                            )
                            .setIsSpeedChangeSupportRequired(true)
                            .build()
                    )
                    .build()
            }
        }
        serviceScope.launch {
            equalizerManager.skipSilence.collect {
                player.exoPlayer.skipSilenceEnabled = it
            }
        }
        serviceScope.launch {
            equalizerManager.tempoState.collect {
                player.playbackParameters = PlaybackParameters(it.speed, it.actualPitch)
            }
        }
        serviceScope.launch {
            audioOutputObserver.systemVolumeState.collect { systemVolume ->
                if (pauseOnZeroVolume && persistentStorage.restorationState.isRestored) {
                    if (isPlaying && systemVolume.currentVolume <= 0f) {
                        player.pause()
                        pausedByZeroVolume = true
                    } else if (pausedByZeroVolume && systemVolume.currentVolume >= 0.1f) {
                        player.play()
                        pausedByZeroVolume = false
                    }
                }
            }
        }
    }

    private fun updateEqualizerSessionState(isPlaying: Boolean) {
        eqStateHandler.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacks(headsetClickRunnable)
        if (isPlaying) {
            equalizerManager.setSessionIsActive(true)
        } else {
            eqStateHandler.postDelayed(500) {
                equalizerManager.setSessionIsActive(false)
            }
        }
    }

    private fun registerReceivers() {
        if (!bluetoothConnectedRegistered) {
            ContextCompat.registerReceiver(this, bluetoothReceiver, bluetoothConnectedIntentFilter,
                ContextCompat.RECEIVER_EXPORTED)
            bluetoothConnectedRegistered = true
        }

        if (!headsetReceiverRegistered) {
            ContextCompat.registerReceiver(this, headsetReceiver, headsetReceiverIntentFilter,
                ContextCompat.RECEIVER_EXPORTED)
            headsetReceiverRegistered = true
        }
    }

    private var bluetoothConnectedRegistered = false
    private val bluetoothConnectedIntentFilter = IntentFilter().apply {
        addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
    }
    private val bluetoothReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothA2dp.STATE_CONNECTED -> if (Preferences.isResumeOnConnect(true)) {
                            player.play()
                        }
                        BluetoothA2dp.STATE_DISCONNECTED -> if (Preferences.isPauseOnDisconnect(true)) {
                            player.pause()
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED ->
                    if (context.isBluetoothA2dpConnected() && Preferences.isResumeOnConnect(true)) {
                        player.play()
                    }
                BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    if (context.isBluetoothA2dpDisconnected() && Preferences.isPauseOnDisconnect(true)) {
                        player.pause()
                    }
            }
        }
    }

    private var receivedHeadsetConnected = false
    private var headsetReceiverRegistered = false
    private val headsetReceiverIntentFilter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_HEADSET_PLUG == intent.action && !isInitialStickyBroadcast) {
                when (intent.getIntExtra("state", -1)) {
                    0 -> if (Preferences.isPauseOnDisconnect(false)) {
                        player.pause()
                    }
                    1 -> if (Preferences.isResumeOnConnect(false)) {
                        if (player.currentMediaItem != null) {
                            player.play()
                        } else {
                            receivedHeadsetConnected = true
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val PACKAGE_NAME = "com.mardous.booming"

        const val ACTION_PLAY_SONG = "$PACKAGE_NAME.action.ACTION_PLAY_SONG"
        const val EXTRA_SONG_ID = "$PACKAGE_NAME.extra.SONG_ID"
        const val EXTRA_SONG_SOURCE = "$PACKAGE_NAME.extra.SONG_SOURCE"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "playing_notification"

        private const val TAG = "PlaybackService"

        private const val MAX_RETRY_COUNT_AFTER_ERROR = 3

        private const val REWIND_INSTEAD_PREVIOUS_MILLIS = 5000L

        private const val FOREGROUND_SERVICE_TIMEOUT = (60 * 1000) * 2L
        
        private const val QUEUE_DEBOUNCE = 50L
    }
}