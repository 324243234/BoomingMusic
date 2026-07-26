package com.mardous.booming.ui.screen.lyrics

import android.os.SystemClock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect  //
import androidx.compose.runtime.mutableFloatStateOf  //
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.mardous.booming.R
import com.mardous.booming.core.model.LibraryMargin
import com.mardous.booming.core.model.lyrics.LyricsViewSettings
import com.mardous.booming.core.model.lyrics.LyricsViewSettings.BackgroundEffect
import com.mardous.booming.core.model.lyrics.LyricsViewState
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import com.mardous.booming.extensions.isPowerSaveMode
import com.mardous.booming.extensions.resolveColor
import com.mardous.booming.ui.component.compose.AnimatedEqBars
import com.mardous.booming.ui.component.compose.color.extractGradientColors
import com.mardous.booming.ui.component.compose.decoration.FadingEdges
import com.mardous.booming.ui.component.compose.decoration.animatedGradient
import com.mardous.booming.ui.component.compose.decoration.fadingEdges
import com.mardous.booming.ui.component.compose.lyrics.LyricsView
import com.mardous.booming.ui.component.views.PlaceholderDrawable
import com.mardous.booming.ui.screen.library.LibraryViewModel
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.ui.theme.PlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinActivityViewModel

sealed class LyricsUiState(open val id: Long) {
    data class Loading(override val id: Long) : LyricsUiState(id)
    data class Empty(override val id: Long) : LyricsUiState(id)
    data class Instrumental(override val id: Long) : LyricsUiState(id)
    data class Plain(override val id: Long, val lyrics: String) : LyricsUiState(id)
    data class Synced(override val id: Long, val syncedLyrics: SyncedLyrics) : LyricsUiState(id)
}

@Composable
private fun rememberLyricsViewState(lyrics: SyncedLyrics): LyricsViewState {
    return remember(lyrics) { LyricsViewState(lyrics) }
}

//@Composable
//fun rememberSmoothPlaybackPosition(
//    playerPosition: Long,
//    playbackSpeed: Float,
//    isPlaying: Boolean
//): State<Long> {
//    val position = remember { mutableLongStateOf(playerPosition) }
//    LaunchedEffect(playerPosition, isPlaying) {
//        val baseRealtime = SystemClock.elapsedRealtime()
//        if (!isPlaying) {
//            position.longValue = playerPosition
//            return@LaunchedEffect
//        }
//
//        while (isActive) {
//            withFrameNanos {
//                val elapsed = SystemClock.elapsedRealtime() - baseRealtime
//                position.longValue = playerPosition + (elapsed * playbackSpeed).toLong()
//            }
//        }
//    }
//
//   return position
//}

@Composable
fun LyricsScreen(
    libraryViewModel: LibraryViewModel = koinActivityViewModel(),
    lyricsViewModel: LyricsViewModel = koinActivityViewModel(),
    playerViewModel: PlayerViewModel = koinActivityViewModel(),
    onEditClick: (Song) -> Unit
) {
    val context = LocalContext.current
    val isPowerSaveMode = context.isPowerSaveMode()

    val miniPlayerMargin by libraryViewModel.getMiniPlayerMargin().observeAsState(LibraryMargin(0))

    val lyricsViewSettings by lyricsViewModel.fullLyricsViewSettings.collectAsState()
    val uiState by lyricsViewModel.lyricsUiState.collectAsState()

    val song by playerViewModel.currentSongFlow.collectAsStateWithLifecycle()
    val isPlaying by playerViewModel.isPlayingFlow.collectAsStateWithLifecycle()

    var gradientColors by remember { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(song) {
        if (isPowerSaveMode)
            return@LaunchedEffect

        if (lyricsViewSettings.backgroundEffect == BackgroundEffect.Gradient) {
            withContext(Dispatchers.Default) {
                val result = SingletonImageLoader.get(context).execute(
                    ImageRequest.Builder(context)
                        .data(song)
                        .build()
                )
                gradientColors = if (result is SuccessResult) {
                    result.image.toBitmap().extractGradientColors(
                        context.resolveColor(PlaceholderDrawable.BACKGROUND_COLOR)
                    )
                } else {
                    emptyList()
                }
            }
        }
    }

    var hasBackgroundEffects by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets
            .navigationBars
            .add(WindowInsets(bottom = miniPlayerMargin.totalMargin)),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onEditClick(song) },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit_note_24dp),
                    contentDescription = stringResource(R.string.action_lyrics_editor)
                )
            }
        },
        modifier = Modifier.keepScreenOn()
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = Pair(lyricsViewSettings.backgroundEffect, gradientColors),
                transitionSpec = {
                    fadeIn(tween(1000)).togetherWith(fadeOut(tween(1000)))
                }
            ) { (effect, gradientColors) ->
                when {
                    effect.isGradient && gradientColors.size >= 2 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .animatedGradient(gradientColors, isPlaying)
                        )
                        hasBackgroundEffects = true
                    }

                    effect.isBlur -> {
                        val backgroundColor = Color(0xFF1A1A1A)

                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = song,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    //.blur(90.dp)
									
									.graphicsLayer()
									.blur(30.dp)
									
                                    .drawWithContent {
                                        drawContent()

                                        drawRect(
                                            brush = Brush.radialGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    backgroundColor.copy(alpha = 0.8f),
                                                    backgroundColor
                                                ),
                                                radius = size.minDimension * 0.9f
                                            )
                                        )
                                    }
                            )
                        }
                        hasBackgroundEffects = true
                    }

                    else -> {
                        hasBackgroundEffects = false
                    }
                }
            }

            LyricsSurface(
                playerViewModel = playerViewModel,
                uiState = uiState,
                settings = lyricsViewSettings,
                PaddingValues(vertical = 96.dp, horizontal = 16.dp),
                fadingEdges = FadingEdges(top = 56.dp, bottom = 32.dp),
                textAlign = TextAlign.Start,
                isPlaying = isPlaying,
                isPowerSaveMode = isPowerSaveMode,
                hasBackgroundEffects = hasBackgroundEffects,
                onSeekTo = { position ->
                    playerViewModel.seekTo(position) // 作者更新[cite: 7]
                    if (lyricsViewSettings.resumeOnSeek) {
                        playerViewModel.play()
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }
}

@Composable
fun CoverLyricsScreen(
    lyricsViewModel: LyricsViewModel,
    playerViewModel: PlayerViewModel,
    onExpandClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isPowerSaveMode = context.isPowerSaveMode()
    val isPlaying by playerViewModel.isPlayingFlow.collectAsStateWithLifecycle()
    val lyricsViewSettings by lyricsViewModel.playerLyricsViewSettings.collectAsState()
    val uiState by lyricsViewModel.lyricsUiState.collectAsState()
    val playerColorScheme by playerViewModel.colorSchemeFlow.collectAsState(
        initial = PlayerColorScheme.themeColorScheme(context)
    )
    val song by playerViewModel.currentSongFlow.collectAsStateWithLifecycle()

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val isDefaultTheme = com.mardous.booming.util.Preferences.nowPlayingScreen == com.mardous.booming.core.model.theme.NowPlayingScreen.Default
    val hideExpandButton = isLandscape && isDefaultTheme

    val translationKey = "lyrics_show_translation"
    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    var isTranslationEnabled by remember { mutableStateOf(prefs.getBoolean(translationKey, true)) }

    PlayerTheme(playerColorScheme) {
        Box(modifier = modifier.fillMaxSize()) {
            LyricsSurface(
                uiState = uiState,
                playerViewModel = playerViewModel,
                settings = lyricsViewSettings,
                contentPadding = PaddingValues(vertical = 72.dp, horizontal = 12.dp),
                fadingEdges = FadingEdges(top = 72.dp, bottom = 64.dp),
                textAlign = TextAlign.Center,
                isPlaying = isPlaying,
                isPowerSaveMode = isPowerSaveMode,
                hasBackgroundEffects = false,
                onSeekTo = { position ->
                    playerViewModel.seekTo(position) 
                    if (lyricsViewSettings.resumeOnSeek) {
                        playerViewModel.play()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 全局悬浮侧边栏：排布顺序：译 -> 放大。 🌟 padding 减小至 16dp，使其更靠近底部
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .wrapContentSize()
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 16.dp), 
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. 翻译按钮
                androidx.compose.material3.IconButton(
                    modifier = Modifier.size(36.dp),
                    onClick = {
                        try {
                            val newState = !isTranslationEnabled
                            isTranslationEnabled = newState
                            prefs.edit().putBoolean(translationKey, newState).apply()
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                ) {
                    Text(
                        text = "译",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isTranslationEnabled) 0.4f else 1.0f) 
                    )
                }

                // 2. 放大按钮
                if (!hideExpandButton) {
                    FilledIconButton(
                        modifier = Modifier.size(36.dp), 
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface
                        ),
                        onClick = onExpandClick
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_open_in_full_24dp),
                            contentDescription = stringResource(R.string.action_lyrics_editor),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LyricsSurface(
    playerViewModel: PlayerViewModel,
    uiState: LyricsUiState,
    settings: LyricsViewSettings,
    contentPadding: PaddingValues,
    fadingEdges: FadingEdges,
    textAlign: TextAlign?,
    isPlaying: Boolean,
    isPowerSaveMode: Boolean,
    hasBackgroundEffects: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    
    // 🌡️ 手机/车机全局温度雷达：记录设备物理发热状态
    var isOverheating by remember { mutableStateOf(false) }

    // 注册硬件温度回调（纯事件驱动，0 轮询开销，Unit 确保全局仅注册一次）
    DisposableEffect(Unit) {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        
        val thermalListener = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.os.PowerManager.OnThermalStatusChangedListener { status ->
                // 阈值：达到 MODERATE (中度发热) 即触发自我保护
                isOverheating = status >= android.os.PowerManager.THERMAL_STATUS_MODERATE
            }
        } else null

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && thermalListener != null) {
            powerManager?.addThermalStatusListener(thermalListener)
        }

        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && thermalListener != null) {
                powerManager?.removeThermalStatusListener(thermalListener)
            }
        }
    }

    val contentColor = when {
        hasBackgroundEffects -> androidx.compose.ui.graphics.Color.White
        else -> when (settings.mode) {
            LyricsViewSettings.Mode.Player -> colorScheme.onSurface
            else -> colorScheme.secondary
        }
    }
    
    Box(modifier) {
        when (uiState) {
            is LyricsUiState.Empty -> {
                Text(
                    text = stringResource(R.string.no_lyrics_found),
                    color = contentColor,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .align(Alignment.Center)
                )
            }

            is LyricsUiState.Loading -> {
                CircularWavyProgressIndicator(
                    color = contentColor,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            is LyricsUiState.Instrumental -> {
                AnimatedEqBars(
                    color = contentColor,
                    isPlaying = isPlaying,
                    barCount = 5,
                    modifier = Modifier
                        .size(56.dp)
                        .align(Alignment.Center)
                )
            }

            is LyricsUiState.Plain -> {
                val scrollState = rememberScrollState()

                val song by playerViewModel.currentSongFlow.collectAsStateWithLifecycle()
                LaunchedEffect(song) {
                    scrollState.scrollTo(0)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(rememberNestedScrollInteropConnection())
                        .fadingEdges(fadingEdges)
                        .verticalScroll(scrollState)
                        .padding(contentPadding)
                ) {
                    Text(
                        text = uiState.lyrics,
                        color = contentColor,
                        textAlign = textAlign,
                        style = settings.unsyncedStyle,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            is LyricsUiState.Synced -> {
                val lyricsViewState = rememberLyricsViewState(uiState.syncedLyrics)
                val view = androidx.compose.ui.platform.LocalView.current

                var basePosition by remember { mutableLongStateOf(0L) }
                var baseRealtime by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
                var playbackSpeed by remember { mutableFloatStateOf(1f) }

                // 1. 【解耦基建】：独立监听播放速度变化
                LaunchedEffect(playerViewModel) {
                    playerViewModel.playbackSpeed.collect { speed ->
                        playbackSpeed = speed
                    }
                }

                // 2. 【防跳秒锚点】：独立监听底层进度打底，确保切回界面时无缝接合
                LaunchedEffect(lyricsViewState, playerViewModel) {
                    playerViewModel.progressFlow.collect { position ->
                        basePosition = position
                        baseRealtime = android.os.SystemClock.elapsedRealtime()
                        
                        // 兜底：即使插值引擎在休眠，底层真实数据来了，只要肉眼可见就更新一次
                        if (view.isShown) {
                            lyricsViewState.updatePosition(position)
                        }
                    }
                }

                // 3. 👑【三段式终极调度引擎】
                LaunchedEffect(lyricsViewState, isPlaying, isPowerSaveMode, isOverheating) {
                    var wasVisible = view.isShown
                    
                    while (isActive) {
                        val isVisible = view.isShown

                        // 🛡️ 破晓护盾：从不可见（切桌面/被遮挡）变为可见的瞬间
                        if (isVisible && !wasVisible) {
                            // 主动挂起 150ms，100% 避让系统级的切屏过渡动画，杜绝掉帧卡顿
                            kotlinx.coroutines.delay(150L)
                        }

                        if (isPlaying && isVisible) {
                            // 【状态评估】：是否处于系统省电模式 或 物理发热状态？
                            if (isPowerSaveMode || isOverheating) {
                                // 🧊 【阶段一：自保降频模式 (30fps)】
                                // 主动降级为 33ms 软时钟，强制压制 CPU/GPU，控制温度
                                val elapsed = android.os.SystemClock.elapsedRealtime() - baseRealtime
                                val smoothPosition = basePosition + (elapsed * playbackSpeed).toLong()
                                lyricsViewState.updatePosition(smoothPosition)
                                
                                kotlinx.coroutines.delay(33L)
                            } else {
                                // 🔥 【阶段二：火力全开模式 (VSYNC)】
                                // 常温且电量健康，无缝接管系统底层 Choreographer 帧信号 (60Hz/120Hz 自动满血)
                                androidx.compose.runtime.withFrameNanos {
                                    val elapsed = android.os.SystemClock.elapsedRealtime() - baseRealtime
                                    val smoothPosition = basePosition + (elapsed * playbackSpeed).toLong()
                                    lyricsViewState.updatePosition(smoothPosition)
                                }
                            }
                        } else {
                            // 💤 【阶段三：深度休眠模式 (0fps)】
                            // 当切到桌面（桌面卡片由底层 Service 独立接管）、切播放列表或音乐暂停时
                            // 停止一切渲染插值，进入 100ms 的低频心跳巡检，功耗彻底归零
                            kotlinx.coroutines.delay(100L)
                        }

                        // 闭环状态更新
                        wasVisible = isVisible
                    }
                }

                LyricsView(
                    state = lyricsViewState,
                    settings = settings,
                    contentPadding = contentPadding,
                    fadingEdges = fadingEdges,
                    contentColor = contentColor,
                    isPowerSaveMode = isPowerSaveMode,
                    hasBackgroundEffects = hasBackgroundEffects,
                    onSeekTo = onSeekTo
                )
            }
        }
    }
}