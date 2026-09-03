/*
 * Copyright (c) 2025 Christians Martínez Alvarado
 */

package com.mardous.booming.playback

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.mardous.booming.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
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
import androidx.media3.session.MediaSessionService
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
import com.mardous.booming.core.appwidgets.state.PlaybackState
import com.mardous.booming.core.audio.AudioOutputObserver
import com.mardous.booming.core.model.queue.QueuePosition
import com.mardous.booming.data.local.MediaStoreObserver
import com.mardous.booming.data.local.ReplayGainTagExtractor
import com.mardous.booming.data.model.QueueSong
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.data.model.network.ScrobblingService
import com.mardous.booming.data.repository.LyricsRepository
import com.mardous.booming.data.repository.Repository
import com.mardous.booming.extensions.showToast
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private val lyricsRepository: LyricsRepository by inject()
    private val playlistRepository: PlaylistRepository by inject()

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

    private var bluetoothLyricManager: BluetoothLyricManager? = null
    private var carWithUpdateJob: Job? = null
    private var lastProcessedMediaId: String? = null
    private var currentIsFavorite = false
    
    // 🌟 Tickle 欺骗开关：强制 Media3 底层触发 onMetadataChanged 回调
    private var carWithTickleToggle = false

    private var errorRecoveryRetryCount = 0
    private var pausedByZeroVolume = false
    private var hasSetUnshuffledOrder = false
    private var stopIndex = -1
    private var prefetchGainJob: Job? = null

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
        get() = if (player.shuffleModeEnabled) customCommands[1] else customCommands[0]

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

    override fun onCreate() {
        super.onCreate()
        nm = requireNotNull(getSystemService<NotificationManager>())
        createNotificationChannel()

        packageValidator = PackageValidator(this, R.xml.allowed_media_browser_callers)

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
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1")) 
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val defaultDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .also {
                if (preferences.getBoolean(MP3_INDEX_SEEKING, false)) {
                    it.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
                }
            }

        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
            .setDataSourceFactory(defaultDataSourceFactory)

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
                .setMediaSourceFactory(mediaSourceFactory)
                .setSkipSilenceEnabled(equalizerManager.skipSilence.value)
                .setHandleAudioBecomingNoisy(true)
                .setMaxSeekToPreviousPositionMs(maxSeekToPreviousMs)
                .setSeekBackIncrementMs(seekInterval)
                .setPlaybackLooper(playerThread.looper)
                .build()
        )

        player.exoPlayer.applyShuffleOrder(ImprovedShuffleOrder(0, 0, Random.nextLong()))
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
                player.exoPlayer.applyShuffleOrder(shuffleOrder)
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        val myPackageName = this.packageName
        val controllerPackageName = controllerInfo.packageName
        if (controllerPackageName == myPackageName ||
            controllerPackageName == MediaBrowserService.SERVICE_INTERFACE ||
            controllerPackageName == MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME) {
            return mediaSession
        }
        val controllerType = controllerInfo.connectionHints.getString(CONNECTION_HINT_KEY_CONTROLLER_INFO_TYPE)
        if (controllerType == Intent.ACTION_MEDIA_BUTTON &&
            controllerPackageName == MediaSessionService.SERVICE_INTERFACE) {
            val sessionId = controllerInfo.connectionHints.getString(CONNECTION_HINT_KEY_SESSION_ID)
            if (sessionId == myPackageName) {
                return mediaSession
            }
        } else if (packageValidator.isKnownCaller(controllerPackageName, controllerInfo.uid)) {
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

        // 🌟 注入 CarWith 专属控制指令，授权车机端能够向这里发送意图
        availableSessionCommands.add(SessionCommand("ucar.media.action.PLAY_MODE", Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand("ucar.media.action.COLLECT", Bundle.EMPTY))

        // 🌟【唤醒装甲】：握手建立后，延迟 150ms 强推一次带指纹的完整数据源，破除卡片上车空白的死结
        serviceScope.launch(Main) {
            delay(150)
            updateCarWithMetadata()
        }

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
        session.notifySearchResultChanged(browser, query, 0, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
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
        return serviceScope.future(IO) {
            val result = runCatching { libraryProvider.getSearchResult(query, page, pageSize) }
            if (result.isSuccess) {
                LibraryResult.ofItemList(result.getOrThrow(), params)
            } else {
                LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            }
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        return serviceScope.future(IO) {
            libraryProvider.resolveMediaItems(mediaItems)
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
                exoPlayer.applyRandomShuffleOrder()
            }

            (exoPlayer.shuffleOrder as? ImprovedShuffleOrder)
                ?.playerIndex = startIndex

            hasSetUnshuffledOrder = false
        }
        return serviceScope.future(IO) {
            val interceptedItems = interceptRadioMediaItems(mediaItems)
            var resolvedMediaItems: MediaItemsWithStartPosition? = null
            if (mediaItems.size == 1) {
                resolvedMediaItems = libraryProvider.tryToResolveComplexMediaItems(
                    mediaItems = mediaItems,
                    startIndex = startIndex,
                    startPositionMs = startPositionMs
                )
            }
            resolvedMediaItems ?: MediaItemsWithStartPosition(
                libraryProvider.resolveMediaItems(interceptedItems),
                startIndex,
                startPositionMs
            )
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

            // 🌟 响应 CarWith 车机端发送的收藏指令
            Playback.TOGGLE_FAVORITE, "ucar.media.action.COLLECT" -> serviceScope.future(Main) {
                awaitRestoration()
                toggleFavorite() 
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            // 🌟 响应 CarWith 车机端发送的播放模式切换指令
            "ucar.media.action.PLAY_MODE" -> serviceScope.future(Main) {
                // 车机传来的是卡片当前处于什么模式。我们读取后进行 +1 轮转。
                val currentCarMode = args.getString("ucar.media.bundle.PLAY_MODE")?.toIntOrNull()
                    ?: (if (player.shuffleModeEnabled) 0 else if (player.repeatMode == Player.REPEAT_MODE_ONE) 1 else 2)
                
                val nextMode = (currentCarMode + 1) % 3
                when (nextMode) {
                    0 -> {
                        player.repeatMode = Player.REPEAT_MODE_ALL
                        player.shuffleModeEnabled = true
                    }
                    1 -> {
                        player.shuffleModeEnabled = false
                        player.repeatMode = Player.REPEAT_MODE_ONE
                    }
                    else -> { // 2
                        player.shuffleModeEnabled = false
                        player.repeatMode = Player.REPEAT_MODE_ALL
                    }
                }
                // 状态变更后主动推回车机，更新 UI 显示
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
                with(player.exoPlayer) { applyShuffleOrder(UnshuffledShuffleOrder(mediaItemCount)) }
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
                        player.exoPlayer.applyShuffleOrder(shuffleOrder)
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
            val currentQueueSize = queueStateHolder.queueSize
            if (timeline.windowCount != currentQueueSize) {
                buildPlayQueue(player) { songs, position ->
                    queueStateHolder.submitQueue(songs, position)
                    persistentStorage.saveState(true)
                }
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
        updateCarWithMetadata()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        widgets.refreshModes(player.shuffleModeEnabled, repeatMode)
        refreshMediaButtonCustomLayout()
        persistentStorage.saveState()
        updateCarWithMetadata()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val isPlaying = player.isPlaying

        val newMediaId = mediaItem?.mediaId
        if (newMediaId != null && newMediaId == lastProcessedMediaId) return
        lastProcessedMediaId = newMediaId

        if (replayGainProcessor.mode.isOn) submitReplayGain(mediaItem)

        serviceScope.launch(IO) {
            val newSong = runCatching { repository.songByMediaItem(mediaItem, ignoreBlacklist = true) }.getOrNull() ?: Song.emptySong

            val streamUrl = mediaItem?.localConfiguration?.uri?.toString() ?: newSong.data
            val isRadioStream = newSong.duration <= 0L && streamUrl.startsWith("http")

            currentIsFavorite = if (isRadioStream && streamUrl.isNotEmpty()) {
                runCatching {
                    val targetNames = listOf("[Radio]我的电台", "[Radio]我的收藏")
                    var isFav = false
                    for (name in targetNames) {
                        val playlistId = repository.checkPlaylistExists(name).firstOrNull()?.playListId
                        if (playlistId != null) {
                            if (repository.playlistSongs(playlistId).any { it.data == streamUrl }) {
                                isFav = true
                                break
                            }
                        }
                    }
                    isFav
                }.getOrDefault(false)
            } else if (newSong != Song.emptySong) {
                runCatching { repository.isSongFavorite(newSong.id) }.getOrDefault(false)
            } else false

            withContext(Main) {
                refreshMediaButtonCustomLayout()
                
                // 🌟 切歌必触发刷新：无论有无开启蓝牙，全速分发装甲元数据
                updateCarWithMetadata()

                if (preferences.getBoolean("enable_bluetooth_lyrics", false)) {
                    bluetoothLyricManager?.loadLyricsForSong(newSong)
                }
            }

            val previousSong = songPlayCountHelper.song
            val shouldBumpPlayCount = songPlayCountHelper.shouldBumpPlayCount()
            songPlayCountHelper.notifySongChanged(newSong, isPlaying)

            val enableHistory = preferences.getBoolean(ENABLE_HISTORY, true)
            if (enableHistory && newSong != Song.emptySong && !newSong.resolvedFromFile) {
                if (preferences.getBoolean(ENABLE_HISTORY, true)) {
                    repository.upsertSongInHistory(newSong)
                }
                if (NetworkFeature.Lastfm.NowPlaying.isAvailable) {
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

        if (player.currentMediaItemIndex == stopIndex) {
            player.exoPlayer.pauseAtEndOfMediaItems = true
        }

        persistentStorage.saveState()
        widgets.refresh()
    }

    private fun submitReplayGain(currentItem: MediaItem? = player.currentMediaItem) {
        val uri = currentItem?.contentUri ?: return
        serviceScope.launch(IO) {
            replayGainProcessor.submitGain(uri, ReplayGainTagExtractor.getReplayGain(uri))
        }
    }

    private fun prefetchNextReplayGain() {
        if (!replayGainProcessor.mode.isOn) return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val uri = player.getMediaItemAt(nextIndex).contentUri ?: return
        if (ReplayGainTagExtractor.peek(uri) != null) return

        prefetchGainJob?.cancel()
        prefetchGainJob = serviceScope.launch(IO) { ReplayGainTagExtractor.getReplayGain(uri) }
    }

    override fun onPlayerError(error: PlaybackException) {
        val nextMediaIndex = player.nextMediaItemIndex
        if (nextMediaIndex != C.INDEX_UNSET &&
            errorRecoveryRetryCount < MAX_RETRY_COUNT_AFTER_ERROR) {
            errorRecoveryRetryCount++
            player.seekToNextMediaItem()
            player.prepare()
        }
        showToast(getString(R.string.playback_error_code, error.errorCodeName))
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
            events.contains(Player.EVENT_TIMELINE_CHANGED)) {
            if (player.isPlaying) errorRecoveryRetryCount = 0
            cancelSleepTimerFadeOut()
        }
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) &&
            !events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            updateEqualizerSessionState(player.isPlaying)
        }
        if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) {
            queueStateHolder.submitRepeatMode(player.repeatMode)
        }
        if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
            queueStateHolder.submitShuffleMode(player.shuffleModeEnabled)
            if (!events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                dispatchPlayQueue(player)
                if (player.shuffleModeEnabled && persistentStorage.restorationState.isRestored) {
                    val exoPlayer = this.player.exoPlayer
                    if (exoPlayer.mediaItemCount > 0) {
                        exoPlayer.applyRandomShuffleOrder()
                    }
                }
            }
        }
        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            val isStructuralChange = events.contains(Player.EVENT_TIMELINE_CHANGED) &&
                    player.currentTimeline.windowCount != queueStateHolder.queueSize
            if (!isStructuralChange) {
                queueStateHolder.setPlayerIndex(player.currentMediaItemIndex)
            }
        }
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED
            )) {
            prefetchNextReplayGain()
        }
    }
	
    override fun onMetadata(metadata: Metadata) {
        val currentItem = player.currentMediaItem
        val isRadio = currentItem?.localConfiguration?.uri?.toString()?.startsWith("http") == true 
                      && player.duration == C.TIME_UNSET

        if (!isRadio) return

        var streamTitle: String? = null

        for (i in 0 until metadata.length()) {
            val entry = metadata.get(i)
            if (entry is androidx.media3.extractor.metadata.icy.IcyInfo) {
                streamTitle = fixEncoding(entry.title ?: "")
            } else if (entry is androidx.media3.extractor.metadata.id3.TextInformationFrame) {
                if (entry.id.uppercase() == "TIT2" || entry.id.uppercase() == "TT2") {
                    streamTitle = fixEncoding(entry.value ?: "")
                }
            }
        }

        if (!streamTitle.isNullOrBlank()) {
            val cleanTitle = streamTitle.trim()
            val stationName = currentItem?.mediaMetadata?.title?.toString() ?: ""
            if (cleanTitle.isNotEmpty() && cleanTitle != "未知" && cleanTitle != stationName) {
                uiHandler.post { showToast("🎵 $cleanTitle") }
                com.mardous.booming.data.local.lyrics.RadioEpgFetcher.currentIcyMetadata.value = cleanTitle
            }
        }
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
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
                            val song = runCatching { repository.songByMediaItem(currentMediaItem, ignoreBlacklist = true) }.getOrNull() ?: Song.emptySong
                            if (song != Song.emptySong) {
                                withContext(Main) { bluetoothLyricManager?.loadLyricsForSong(song) }
                            }
                        }
                    }
                } else if (!enabled) {
                    bluetoothLyricManager?.release()
                    bluetoothLyricManager = null
                    updateCarWithMetadata()
                }
            }

            "preferred_lyrics_file_format", "lyrics_show_translation" -> {
                updateCarWithMetadata()
            }
        }
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
        withContext(IO) {
            val song = queueStateHolder.currentSong.first()
            if (song != Song.emptySong) {
                val isRadioStream = song.duration <= 0L && song.data.startsWith("http")

                if (isRadioStream) {
                    currentIsFavorite = playlistRepository.toggleRadioFavorite(song)
                } else {
                    repository.toggleFavorite(song)
                    currentIsFavorite = repository.isSongFavorite(song.id)
                }
            }
        }

        withContext(Main) {
            refreshMediaButtonCustomLayout()
            // 🌟 通知车机强刷红心图标
            updateCarWithMetadata()
        }

        widgets.refresh()
        mediaSession?.broadcastCustomCommand(
            SessionCommand(Playback.EVENT_FAVORITE_CONTENT_CHANGED, Bundle.EMPTY),
            Bundle.EMPTY
        )
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
                val (fetchedSongs, fetchedMissing) = repository.songsByMediaItems(snapshot.mediaItems, ignoreBlacklist = true)
                val dbSongs = fetchedSongs.toMutableList()
                
                val dbSongIds = dbSongs.map { it.id.toString() }.toSet()
                val missing = snapshot.mediaItems.filter { !dbSongIds.contains(it.mediaId) }
                val radioMissing = missing.filter { it.localConfiguration?.uri?.toString()?.startsWith("http") == true }
                
                val radioSongs = radioMissing.map { item ->
                    Song(
                        id = item.mediaId.toLongOrNull() ?: System.currentTimeMillis(),
                        data = item.localConfiguration?.uri?.toString() ?: "",
                        title = item.mediaMetadata.title?.toString() ?: "未知电台",
                        trackNumber = 0, year = 0, size = 0L, duration = 0L,
                        dateAdded = System.currentTimeMillis(),
                        rawDateModified = System.currentTimeMillis(),
                        albumId = -1L,
                        albumName = item.mediaMetadata.albumTitle?.toString() ?: "直播流",
                        artistId = -1L,
                        artistName = item.mediaMetadata.artist?.toString() ?: "网络电台",
                        albumArtistName = "网络电台", 
                        genreName = "直播"
                    )
                }
                dbSongs.addAll(radioSongs)
                val actualMissing = missing.filterNot { radioMissing.contains(it) }
                
                snapshot.deriveQueueSongs(dbSongs) to actualMissing
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

    private fun refreshMediaButtonCustomLayout() {
        val hasTimeline = !player.currentTimeline.isEmpty
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
        
        mediaSession?.setCustomLayout(buttonLayout)
        
        mediaSession?.connectedControllers?.forEach { controllerInfo ->
            mediaSession?.setMediaButtonPreferences(controllerInfo, buttonLayout)
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
            equalizerManager.replayGainState.map { it.mode }.distinctUntilChanged()
                .collect { mode -> if (mode.isOn) submitReplayGain() }
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
    }
    private val bluetoothReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                    BluetoothA2dp.STATE_CONNECTED -> if (Preferences.isResumeOnConnect(true)) {
                        if (!player.isPlaying) player.play()
                    }
                    BluetoothA2dp.STATE_DISCONNECTED -> if (Preferences.isPauseOnDisconnect(true)) {
                        if (player.isPlaying) player.pause()
                    }
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

    // 🌟 核心引擎：采用“零宽字符 (Tickle)”机制暴力打破 Media3 对 Bundle 比对失效的天然屏障
    private fun updateCarWithMetadata() {
        carWithUpdateJob?.cancel()

        carWithUpdateJob = serviceScope.launch(Main) {
            val currentIndex = player.currentMediaItemIndex
            if (currentIndex < 0 || currentIndex >= player.mediaItemCount) return@launch
            val expectedItem = player.getMediaItemAt(currentIndex)
            
            // 🌟 核心修复：在此处提前将 player 状态保存为局部变量（安全访问）
            val isShuffleEnabled = player.shuffleModeEnabled
            val currentRepeatMode = player.repeatMode

            withContext(IO) {
                val song = runCatching { repository.songByMediaItem(expectedItem, ignoreBlacklist = true) }.getOrNull() ?: Song.emptySong
                val streamUrl = expectedItem.localConfiguration?.uri?.toString() ?: song.data
                val isRadioStream = song.duration <= 0L && streamUrl.startsWith("http")

                val lrcText = if (isRadioStream) {
                    "📻 正在收听电台：${song.title}\n📡 节目排期请在手机端查看"
                } else {
                    val showTranslation = preferences.getBoolean("lyrics_show_translation", false)
                    val rawLyrics = if (song != Song.emptySong) {
                        runCatching { lyricsRepository.fileLyrics(song) ?: lyricsRepository.embeddedLyrics(song) ?: lyricsRepository.storedLyrics(song, allowDownload = false) }.getOrNull()
                    } else null

                    val parsedLyrics = rawLyrics?.let { runCatching { lyricsRepository.parseRawLyrics(song, it) }.getOrNull() }

                    val rawLrcText = parsedLyrics?.lines?.joinToString("\n") { line ->
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

                    if (rawLrcText.length > 8000) rawLrcText.substring(0, 8000) else rawLrcText
                }

                // 完全对齐 CarWith 所需的播放模式位操作
                val playMode: Long = when {
                    isShuffleEnabled -> 0L
                    currentRepeatMode == Player.REPEAT_MODE_ONE -> 1L
                    else -> 2L
                }
                
                // 完全对齐 CarWith 的红心状态（"1" 为高亮选中）
                val collectState = if (currentIsFavorite) "1" else "0"

                withContext(Main) {
                    val latestIndex = player.currentMediaItemIndex
                    if (latestIndex < 0 || latestIndex >= player.mediaItemCount) return@withContext
                    val latestItem = player.getMediaItemAt(latestIndex)

                    val currentExtras = latestItem.mediaMetadata.extras ?: Bundle.EMPTY
                    
                    val newExtras = Bundle(currentExtras).apply {
                        putLong("ucar.media.metadata.PLAY_MODE", playMode)
                        putString("ucar.media.metadata.COLLECT_STATE", collectState)
                        putString("ucar.media.metadata.LYRICS_WHOLE", lrcText)
                        putString("android.media.metadata.LYRIC", lrcText)
                    }

                    // 🌟 核心突破：Tickle(挠痒)机制。交替追加“零宽空格”，让底层 `equals()` 检查判定 Title 已更改，强制广播 `MediaMetadataChanged` 至车机端。
                    carWithTickleToggle = !carWithTickleToggle
                    val tickleStr = if (carWithTickleToggle) "\u200B" else ""
                    
                    val originalTitle = if (currentExtras.containsKey("BT_ORIGINAL_TITLE")) {
                        latestItem.mediaMetadata.title?.toString() ?: song.title
                    } else {
                        song.title
                    }

                    val cleanTitle = originalTitle.replace("\u200B", "")
                    val newTitle = cleanTitle + tickleStr

                    val updatedMetadata = latestItem.mediaMetadata.buildUpon()
                        .setTitle(newTitle)
                        .setExtras(newExtras)
                        .build()

                    val updatedItem = latestItem.buildUpon()
                        .setMediaMetadata(updatedMetadata)
                        .build()

                    val realPlayer = (player as? AdvancedForwardingPlayer)?.exoPlayer ?: player
                    realPlayer.replaceMediaItem(latestIndex, updatedItem)
                }
            }
        }
    }
    
    private suspend fun interceptRadioMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val radioPlaylists = repository.playlistsWithSongs(true).filter { it.playlistEntity.playlistName.startsWith("[Radio]") }
        val radioEntitiesMap = radioPlaylists.flatMap { it.songs }.associateBy { it.id }

        return mediaItems.map { item ->
            if (item.localConfiguration != null) return@map item
            val id = item.mediaId.toLongOrNull() ?: return@map item
            
            val rs = radioEntitiesMap[id]
            if (rs != null) {
                item.buildUpon()
                    .setUri(rs.data)
                    .setMediaMetadata(
                        item.mediaMetadata.buildUpon()
                            .setTitle(rs.title)
                            .setArtist("网络电台")
                            .setArtworkData(null, null) 
                            .setArtworkUri(null)        
                            .build()
                    )
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setMaxPlaybackSpeed(1.02f) 
                            .setMinPlaybackSpeed(0.98f) 
                            .setTargetOffsetMs(5000)
                            .build()
                    )
                    .build()
            } else {
                item
            }
        }
    }
	
    private fun fixEncoding(str: String): String {
        if (str.isEmpty()) return str
        if (str.matches(Regex(".*[\\u4e00-\\u9fa5]+.*"))) return str

        return try {
            val bytes = str.toByteArray(Charsets.ISO_8859_1)
            var fixed = String(bytes, Charsets.UTF_8)
            if (fixed.matches(Regex(".*[\\u4e00-\\u9fa5]+.*"))) return fixed

            fixed = String(bytes, java.nio.charset.Charset.forName("GBK"))
            if (fixed.matches(Regex(".*[\\u4e00-\\u9fa5]+.*"))) return fixed

            str
        } catch (e: Exception) {
            str
        }
    }
    
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "playing_notification"

        private const val MAX_RETRY_COUNT_AFTER_ERROR = 3
        private const val REWIND_INSTEAD_PREVIOUS_MILLIS = 5000L
        private const val FOREGROUND_SERVICE_TIMEOUT = (60 * 1000) * 2L
        
        private const val QUEUE_DEBOUNCE = 50L
    }
}