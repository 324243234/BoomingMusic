package com.mardous.booming.ui.screen.lyrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.preference.PreferenceManager
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.toBitmap
import com.mardous.booming.R
import com.mardous.booming.core.model.LibraryMargin
import com.mardous.booming.core.model.lyrics.LyricsViewSettings
import com.mardous.booming.core.model.lyrics.LyricsViewSettings.BackgroundEffect
import com.mardous.booming.core.model.lyrics.LyricsViewState
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.theme.NowPlayingScreen
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
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        if (isPowerSaveMode) return@LaunchedEffect

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

    val hasBackgroundEffects = remember(lyricsViewSettings.backgroundEffect, gradientColors) {
        (lyricsViewSettings.backgroundEffect.isGradient && gradientColors.size >= 2) ||
                lyricsViewSettings.backgroundEffect.isBlur
    }

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
                }, label = "LyricsBackground"
            ) { (effect, colors) ->
                when {
                    effect.isGradient && colors.size >= 2 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .animatedGradient(colors, isPlaying)
                        )
                    }

                    effect.isBlur -> {
                        val backgroundColor = Color(0xFF1A1A1A)

                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(song)
                                    .size(200)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
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
                    }
                    else -> {}
                }
            }

            LyricsSurface(
                playerViewModel = playerViewModel,
                uiState = uiState,
                settings = lyricsViewSettings,
                contentPadding = PaddingValues(vertical = 96.dp, horizontal = 16.dp),
                fadingEdges = FadingEdges(top = 56.dp, bottom = 32.dp),
                textAlign = TextAlign.Start,
                isPlaying = isPlaying,
                isPowerSaveMode = isPowerSaveMode,
                hasBackgroundEffects = hasBackgroundEffects,
                onSeekTo = { position ->
                    playerViewModel.seekTo(position)
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

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val currentScreen = Preferences.nowPlayingScreen
    val hideExpandButton = isLandscape && (currentScreen == NowPlayingScreen.Default || currentScreen == NowPlayingScreen.Gradient)

    val translationKey = "lyrics_show_translation"
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var isTranslationEnabled by remember { mutableStateOf(prefs.getBoolean(translationKey, true)) }

    // 🌟 原生降维打击：直接利用作者解析好的数据模型 (line.translation)，秒杀一切正则和反射！
    var hasTranslation by remember(uiState) { mutableStateOf(false) }
    
    LaunchedEffect(uiState) {
        withContext(Dispatchers.Default) {
            hasTranslation = try {
                // 🌟 修复: 引入局部常量 currentState，解决 Kotlin 委托属性无法智能转换 (Smart Cast) 的问题
                when (val currentState = uiState) {
                    is LyricsUiState.Synced -> {
                        // 底层的 LrcLyricsParser 和 TtmlLyricsParser 已经完成了所有艰苦的解析工作，
                        // 我们只需检查任何一行是否存在真实的 translation 即可！
                        currentState.syncedLyrics.lines.any { line -> 
                            line.translation != null && !line.translation.isEmpty 
                        }
                    }
                    is LyricsUiState.Plain -> {
                        // 极低概率的兜底：纯文本且未同步的歌词
                        currentState.lyrics.contains("x-translation", ignoreCase = true)
                    }
                    else -> false
                }
            } catch (e: Exception) {
                false
            }
        }
    }

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

            // 全局悬浮侧边栏
            Column(
                modifier = Modifier
                    .wrapContentSize()
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 8.dp), 
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. 放大按钮
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

                // 2. 翻译按钮 (根据原生的解析结果 智能显示/隐藏)
                if (hasTranslation) {
                    IconButton(
                        modifier = Modifier.size(36.dp),
                        onClick = {
                            try {
                                val newState = !isTranslationEnabled
                                isTranslationEnabled = newState
                                prefs.edit().putBoolean(translationKey, newState).apply()
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    ) {
                        // 🌟 Unicode 防御机制：\u8BD1 等价于 "译"，无论文件被破坏成什么编码，UI永远显示正常中文！
                        Text(
                            text = "\u8BD1", 
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isTranslationEnabled) 0.4f else 0.6f) 
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
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    
    var isOverheating by remember { mutableStateOf(false) }
    var isLowBattery by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        
        val initialIntent = context.registerReceiver(null, filter)
        initialIntent?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) {
                isLowBattery = (level * 100 / scale.toFloat()) <= 20f
            }
        }

        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level != -1 && scale != -1) {
                    isLowBattery = (level * 100 / scale.toFloat()) <= 20f
                }
            }
        }
        
        context.registerReceiver(batteryReceiver, filter)
        onDispose { context.unregisterReceiver(batteryReceiver) }
    }
        
    DisposableEffect(Unit) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isOverheating = (powerManager?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE
        }

        val thermalListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager.OnThermalStatusChangedListener { status ->
                isOverheating = status >= PowerManager.THERMAL_STATUS_SEVERE
            }
        } else null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            powerManager?.addThermalStatusListener(thermalListener)
        }

        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
                powerManager?.removeThermalStatusListener(thermalListener)
            }
        }
    }

    val contentColor = when {
        hasBackgroundEffects -> Color.White
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
                
                LaunchedEffect(song) { scrollState.scrollTo(0) }

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
                val view = LocalView.current

                var basePosition by remember { mutableLongStateOf(0L) }
                var baseRealtime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                var playbackSpeed by remember { mutableFloatStateOf(1f) }

                LaunchedEffect(playerViewModel) {
                    playerViewModel.playbackSpeed.collect { speed ->
                        playbackSpeed = speed
                    }
                }

                LaunchedEffect(lyricsViewState, playerViewModel) {
                    playerViewModel.progressFlow.collect { position ->
                        basePosition = position
                        baseRealtime = SystemClock.elapsedRealtime()
                        
                        if (view.isShown) {
                            lyricsViewState.updatePosition(position)
                        }
                    }
                }

                LaunchedEffect(lyricsViewState, isPlaying, isPowerSaveMode, isOverheating, isLowBattery) {
                    var wasVisible = view.isShown
                    
                    while (isActive) {
                        val isVisible = view.isShown

                        if (isVisible && !wasVisible) {
                            delay(150L)
                        }

                        if (isPlaying && isVisible) {
                            if (isPowerSaveMode || isOverheating || isLowBattery) {
                                val elapsed = SystemClock.elapsedRealtime() - baseRealtime
                                val smoothPosition = basePosition + (elapsed * playbackSpeed).toLong()
                                lyricsViewState.updatePosition(smoothPosition)
                                delay(33L)
                            } else {
                                withFrameNanos {
                                    val elapsed = SystemClock.elapsedRealtime() - baseRealtime
                                    val smoothPosition = basePosition + (elapsed * playbackSpeed).toLong()
                                    lyricsViewState.updatePosition(smoothPosition)
                                }
                            }
                        } else {
                            delay(100L)
                        }

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