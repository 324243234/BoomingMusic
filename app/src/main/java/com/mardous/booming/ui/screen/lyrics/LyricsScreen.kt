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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
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

@Composable
fun rememberSmoothPlaybackPosition(
    playerPosition: Long,
    playbackSpeed: Float,
    isPlaying: Boolean
): State<Long> {
    val position = remember { mutableLongStateOf(playerPosition) }
    LaunchedEffect(playerPosition, isPlaying) {
        val baseRealtime = SystemClock.elapsedRealtime()
        if (!isPlaying) {
            position.longValue = playerPosition
            return@LaunchedEffect
        }

        while (isActive) {
            withFrameNanos {
                val elapsed = SystemClock.elapsedRealtime() - baseRealtime
                position.longValue = playerPosition + (elapsed * playbackSpeed).toLong()
            }
        }
    }

    return position
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
                                    .blur(90.dp)
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
                onSeekToLine = {
                    playerViewModel.seekTo(it.start)
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

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val isGradientTheme = com.mardous.booming.util.Preferences.nowPlayingScreen == com.mardous.booming.core.model.theme.NowPlayingScreen.Gradient

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
                onSeekToLine = {
                    playerViewModel.seekTo(it.start)
                    if (lyricsViewSettings.resumeOnSeek) {
                        playerViewModel.play()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            
            // 【全局悬浮功能区】：将翻译按钮和全屏按钮纵向打包在一起
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .wrapContentSize()
                    .align(Alignment.BottomEnd)
                    // 智能边距：如果是 Gradient 平板，底部留出 80dp 给 XML 的收藏按钮让位，否则正常 16dp
                    .padding(
                        end = 16.dp, 
                        bottom = if (isLandscape && isGradientTheme) 80.dp else 16.dp
                    ),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                
                // 1. 全局物理翻译开关
                androidx.compose.material3.IconButton(
                    onClick = {
                        try {
                            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                            val key = "enable_lyrics_translation" // 盲猜键名，如果有偏差请改成你项目里控制翻译的真实 key
                            val isEnabled = prefs.getBoolean(key, false)
                            prefs.edit().putBoolean(key, !isEnabled).apply()
                            android.widget.Toast.makeText(context, if (!isEnabled) "歌词翻译已开启" else "歌词翻译已关闭", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                ) {
                    Text(
                        text = "译",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        ),
                        // 动态提取主题色，与下面的按钮完美同色
                        color = MaterialTheme.colorScheme.onSurface 
                    )
                }

                // 2. 原始的右下角放大按钮（非 Gradient 横屏时显示）
                if (!(isLandscape && isGradientTheme)) {
                    FilledIconButton(
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface
                        ),
                        onClick = onExpandClick
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_open_in_full_24dp),
                            contentDescription = stringResource(R.string.action_lyrics_editor)
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
    onSeekToLine: (SyncedLyrics.Line) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
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

                val playerPosition by playerViewModel.progressFlow.collectAsStateWithLifecycle()
                val playbackSpeed by playerViewModel.playbackSpeed.collectAsStateWithLifecycle()

                val smoothProgress by rememberSmoothPlaybackPosition(
                    playerPosition = playerPosition,
                    playbackSpeed = playbackSpeed,
                    isPlaying = isPlaying
                )

                LaunchedEffect(playerPosition) {
                    lyricsViewState.updatePosition(smoothProgress)
                }

                LyricsView(
                    state = lyricsViewState,
                    settings = settings,
                    contentPadding = contentPadding,
                    fadingEdges = fadingEdges,
                    contentColor = contentColor,
                    isPowerSaveMode = isPowerSaveMode,
                    hasBackgroundEffects = hasBackgroundEffects,
                    onLineClick = { onSeekToLine(it) }
                )
            }
        }
    }
}