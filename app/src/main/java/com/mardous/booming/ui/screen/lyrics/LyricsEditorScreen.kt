@file:SuppressLint("LocalContextGetResourceValueCall")
package com.mardous.booming.ui.screen.lyrics

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
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
import com.mardous.booming.data.model.lyrics.LyricsMode
import com.mardous.booming.data.model.lyrics.LyricsSource
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.extensions.hasR
import com.mardous.booming.extensions.isPowerSaveMode
import com.mardous.booming.extensions.media.displayArtistName
import com.mardous.booming.extensions.media.isArtistNameUnknown
import com.mardous.booming.extensions.openUrl
import com.mardous.booming.extensions.resolveColor
import com.mardous.booming.extensions.showToast
import com.mardous.booming.extensions.webSearch
import com.mardous.booming.ui.component.compose.AnimatedEqBars
import com.mardous.booming.ui.component.compose.ButtonGroup
import com.mardous.booming.ui.component.compose.DialogListItemWithCheckBox
import com.mardous.booming.ui.component.compose.DialogListItemWithRadio
import com.mardous.booming.ui.component.compose.MediaImage
import com.mardous.booming.ui.component.compose.ObserveAsEvent
import com.mardous.booming.ui.component.compose.color.extractGradientColors
import com.mardous.booming.ui.component.compose.decoration.FadingEdges
import com.mardous.booming.ui.component.compose.decoration.animatedGradient
import com.mardous.booming.ui.component.compose.decoration.fadingEdges
import com.mardous.booming.ui.component.compose.lyrics.LyricsView
import com.mardous.booming.ui.component.compose.menu.MenuItem
import com.mardous.booming.ui.component.compose.menu.OverflowMenu
import com.mardous.booming.ui.component.compose.menu.TopAppBarMenu
import com.mardous.booming.ui.component.views.PlaceholderDrawable
import com.mardous.booming.ui.screen.library.LibraryViewModel
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.ui.theme.PlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinActivityViewModel
import kotlin.time.Duration.Companion.milliseconds

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

private val SnapshotMapSaver = Saver<SnapshotStateMap<LyricsSource, String>, Bundle>(
    save = { map ->
        Bundle().also { bundle ->
            map.forEach { (key, value) -> bundle.putString(key.name, value) }
        }
    },
    restore = { bundle ->
        mutableStateMapOf<LyricsSource, String>().also { map ->
            for (key in bundle.keySet()) {
                val source = LyricsSource.entries.firstOrNull { it.name == key }
                if (source != null) {
                    map[source] = bundle.getString(key).orEmpty()
                }
            }
        }
    }
)

private fun TextFieldState.setContent(content: String?) {
    edit { replace(0, length, content.orEmpty()) }
}

enum class LyricsEditorResult {
    NoChanges, Failed, Success
}

@Immutable
sealed class LyricsEditorUiState(open val isLoading: Boolean) {
    data object Disposed : LyricsEditorUiState(false)
    data class Visible(
        override val isLoading: Boolean,
        val lyrics: Map<LyricsSource, RawLyrics?> = emptyMap()
    ) : LyricsEditorUiState(isLoading) {
        fun getLyricsContent(source: LyricsSource) = lyrics[source]?.lyrics.orEmpty()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsEditorScreen(
    song: Song,
    viewModel: LyricsViewModel = koinActivityViewModel(),
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val coroutineScope = rememberCoroutineScope()
    val textFieldState = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }

    val permissionRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            onBackClick()
        }
    }

    LaunchedEffect(Unit) {
        delay(500.milliseconds)
        viewModel.preparePermissionRequest(song)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.disposeEditorContent()
        }
    }

    fun requestWritePermissions(uris: Collection<Uri>) {
        if (uris.isNotEmpty() && hasR()) {
            val contentResolver = context.contentResolver
            val missingPerms = uris.filterNot { uri ->
                context.checkUriPermission(
                    uri,
                    Process.myPid(),
                    Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                ) == PackageManager.PERMISSION_GRANTED
            }
            if (missingPerms.isNotEmpty()) {
                val pendingIntent = MediaStore.createWriteRequest(contentResolver, missingPerms)
                permissionRequestLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
            }
        }
    }

    var showNoConnectionDialog by remember { mutableStateOf(false) }
    var showManualSearchDialog by remember { mutableStateOf(false) }
    var showLyricsDownloadDialog by remember { mutableStateOf(false) }
    var showLyricsSearchDialog by remember { mutableStateOf(false) }
    var showTimeShiftDialog by remember { mutableStateOf(false) }
    var downloadedLyricsForSelector by rememberSaveable { mutableStateOf<RawLyrics.Remote?>(null) }

    ObserveAsEvent(viewModel.saveEvent) { saveResult ->
        val toastMessage = when (saveResult) {
            LyricsEditorResult.NoChanges -> context.getString(R.string.there_are_no_changes_to_save)
            LyricsEditorResult.Failed -> context.getString(R.string.could_not_save_some_changes)
            LyricsEditorResult.Success -> context.getString(R.string.changes_saved_successfully)
        }
        context.showToast(toastMessage)
    }

    ObserveAsEvent(viewModel.downloadEvent) { downloadedLyrics ->
        if (downloadedLyrics.hasBoth) {
            downloadedLyricsForSelector = downloadedLyrics
        } else if (downloadedLyrics.hasPlain) {
            textFieldState.setContent(downloadedLyrics.plain?.lyrics)
        } else if (downloadedLyrics.hasSynced) {
            textFieldState.setContent(downloadedLyrics.synced?.lyrics)
        } else {
            showManualSearchDialog = true
        }
    }

    ObserveAsEvent(viewModel.permissionRequestEvent) { uris ->
        requestWritePermissions(uris)
    }

    LaunchedEffect(Unit) {
        viewModel.loadEditorContent(song)
    }

    val uiState by viewModel.lyricsEditorUiState.collectAsStateWithLifecycle()
    val editedContent = rememberSaveable(saver = SnapshotMapSaver) { mutableStateMapOf() }
    var selectedSource by rememberSaveable { mutableStateOf(LyricsSource.Embedded) }
    val isFileSource by remember { derivedStateOf { selectedSource == LyricsSource.File } }

    LaunchedEffect(uiState, selectedSource) {
        uiState.let {
            if (it is LyricsEditorUiState.Visible && it.lyrics.isNotEmpty()) {
                textFieldState.setContent(
                    editedContent.getOrPut(selectedSource) {
                        it.getLyricsContent(selectedSource)
                    }
                )
            }
        }
    }

    LaunchedEffect(textFieldState.text) {
        if (editedContent.containsKey(selectedSource)) {
            editedContent[selectedSource] = textFieldState.text.toString()
        }
    }

    if (downloadedLyricsForSelector != null) {
        LyricsSelectorDialog(
            onDismissRequest = {
                downloadedLyricsForSelector = null
            },
            onModeSelected = {
                when (it) {
                    LyricsMode.Plain -> {
                        textFieldState.setContent(downloadedLyricsForSelector?.plain?.lyrics)
                    }
                    LyricsMode.Synced -> {
                        textFieldState.setContent(downloadedLyricsForSelector?.synced?.lyrics)
                    }
                }
                downloadedLyricsForSelector = null
            }
        )
    }

    if (showManualSearchDialog) {
        AlertDialog(
            onDismissRequest = { showManualSearchDialog = false },
            text = { Text(stringResource(R.string.cannot_download_lyrics)) },
            confirmButton = {
                Button(
                    onClick = {
                        context.openUrl(viewModel.getSearchUrl(song))
                        showManualSearchDialog = false
                    }
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualSearchDialog = false }) {
                    Text(stringResource(R.string.close_action))
                }
            }
        )
    }

    if (showNoConnectionDialog) {
        AlertDialog(
            onDismissRequest = { showNoConnectionDialog = false },
            text = { Text(stringResource(R.string.connection_unavailable)) },
            confirmButton = {
                Button(onClick = { showNoConnectionDialog = false }) {
                    Text(stringResource(R.string.close_action))
                }
            }
        )
    }

    if (showLyricsDownloadDialog) {
        LyricsSearchDialog(
            song = song,
            title = stringResource(R.string.download_lyrics),
            showProviders = true,
            onSearchClick = { title, artist, providers ->
                viewModel.downloadLyrics(song, title, artist, providers)
                showLyricsDownloadDialog = false
            },
            onDismissRequest = { showLyricsDownloadDialog = false }
        )
    }

    if (showLyricsSearchDialog) {
        LyricsSearchDialog(
            song = song,
            title = stringResource(R.string.search_lyrics),
            showProviders = false,
            onSearchClick = { title, artist, _ ->
                val searchSuffix = context.getString(R.string.lyrics).lowercase()
                if (artist.isArtistNameUnknown()) {
                    context.webSearch(title, searchSuffix)
                } else {
                    context.webSearch(title, artist, searchSuffix)
                }
                showLyricsSearchDialog = false
            },
            onDismissRequest = { showLyricsSearchDialog = false }
        )
    }

    if (showTimeShiftDialog) {
        TimeShiftDialog(
            onDismissRequest = { showTimeShiftDialog = false },
            onConfirm = { offsetMs ->
                val shiftedText = viewModel.shiftTimeline(textFieldState.text.toString(), offsetMs)
                textFieldState.setContent(shiftedText)
                showTimeShiftDialog = false
            }
        )
    }

    fun saveContent() {
        viewModel.saveLyrics(song, editedContent)
    }

    // ?? 完美结合作者更新 #537：放开移动网络限制（ignoreWifiSetting = true），无网时拦截
    fun downloadLyrics() {
        if (NetworkFeature.isOnline(ignoreWifiSetting = true)) {
            showLyricsDownloadDialog = true
        } else {
            showNoConnectionDialog =  true
        }
    }

    fun undoChanges() {
        uiState.let {
            if (it is LyricsEditorUiState.Visible) {
                textFieldState.setContent(it.getLyricsContent(selectedSource))
            }
        }
    }

    fun selectAllText() {
        focusRequester.requestFocus()
        keyboardController?.show()
        textFieldState.edit {
            selectAll()
        }
    }

    fun pasteFromClipboard() {
        coroutineScope.launch {
            val currentEntry = clipboard.getClipEntry()
            if (currentEntry != null && currentEntry.clipData.itemCount > 0) {
                if (currentEntry.clipData.description.getMimeType(0) == "text/plain") {
                    textFieldState.edit {
                        replace(0, length, currentEntry.clipData.getItemAt(0).text)
                    }
                }
            }
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back_24dp),
                            contentDescription = stringResource(R.string.back_action)
                        )
                    }
                },
                actions = {
                    if (isLandscape) {
                        TopAppBarMenu(
                            showItemIcons = true,
                            items = listOf(
                                MenuItem.Button.Action(
                                    text = stringResource(R.string.action_save),
                                    icon = painterResource(R.drawable.ic_save_24dp),
                                    enabled = !uiState.isLoading && !isFileSource,
                                    onClick = { saveContent() }
                                ),
                                MenuItem.Button.Action(
                                    text = stringResource(R.string.download_lyrics),
                                    icon = painterResource(R.drawable.ic_download_24dp),
                                    // ?? 完美结合作者更新 #537：移除 visible 限制，按钮常驻可用
                                    enabled = !uiState.isLoading && !isFileSource,
                                    onClick = { downloadLyrics() }
                                ),
                                MenuItem.Button.DropDown(
                                    text = "时间轴平移",
                                    icon = painterResource(R.drawable.ic_timer_24dp),
                                    onClick = { showTimeShiftDialog = true }
                                ),
                                MenuItem.Button.DropDown(
                                    text = "保存为本地文件",
                                    icon = painterResource(R.drawable.ic_save_24dp),
                                    onClick = { viewModel.saveLocalLyricsFile(context, song, textFieldState.text.toString()) }
                                ),
                                MenuItem.Button.DropDown(
                                    text = stringResource(R.string.search_lyrics),
                                    icon = painterResource(R.drawable.ic_search_24dp),
                                    onClick = { showLyricsSearchDialog = true }
                                ),
                                MenuItem.Button.DropDown(
                                    text = stringResource(android.R.string.paste),
                                    icon = painterResource(R.drawable.ic_content_paste_24dp),
                                    onClick = { pasteFromClipboard() }
                                ),
                                MenuItem.Button.DropDown(
                                    text = stringResource(R.string.select_all_title),
                                    icon = painterResource(R.drawable.ic_select_all_24dp),
                                    onClick = { selectAllText() }
                                ),
                                MenuItem.Button.DropDown(
                                    text = stringResource(R.string.undo_changes),
                                    icon = painterResource(R.drawable.ic_restart_alt_24dp),
                                    dangerous = true,
                                    onClick = { undoChanges() }
                                )
                            )
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (!isLandscape) {
                // ?? 完美结合作者更新 #537：移除底栏的 downloadEnabled 限制
                LyricsEditorBottomBar(
                    enabled = !uiState.isLoading,
                    isFileSource = isFileSource,
                    onSearchClick = { showLyricsSearchDialog = true },
                    onDownloadClick = { downloadLyrics() },
                    onSelectAllClick = { selectAllText() },
                    onPasteClick = { pasteFromClipboard() },
                    onUndoChangesClick = { undoChanges() },
                    onSaveClick = { saveContent() },
                    onTimeShiftClick = { showTimeShiftDialog = true },
                    onSaveFileClick = { viewModel.saveLocalLyricsFile(context, song, textFieldState.text.toString()) }
                )
            }
        }
    ) { paddingValues ->
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                ) {
                    LyricsEditorHeader(
                        song = song,
                        isLoading = uiState.isLoading
                    )

                    LyricsSourceSelector(
                        enabled = !uiState.isLoading,
                        selectedSource = selectedSource,
                        onSourceSelected = { selectedSource = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    state = textFieldState,
                    readOnly = false, // 解除限制，完全开放可编辑
                    placeholder = {
                        Text(stringResource(R.string.write_lyrics_here))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(focusRequester)
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(top = 8.dp, bottom = 16.dp)
            ) {
                LyricsEditorHeader(
                    song = song,
                    isLoading = uiState.isLoading,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                )

                LyricsSourceSelector(
                    enabled = !uiState.isLoading,
                    selectedSource = selectedSource,
                    onSourceSelected = { selectedSource = it },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                OutlinedTextField(
                    state = textFieldState,
                    readOnly = false, // 解除限制，完全开放可编辑
                    placeholder = {
                        Text(stringResource(R.string.write_lyrics_here))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .focusRequester(focusRequester)
                )
            }
        }
    }
}

@Composable
fun LyricsSelectorDialog(
    onDismissRequest: () -> Unit,
    onModeSelected: (LyricsMode) -> Unit
) {
    var selectedMode by remember { mutableStateOf(LyricsMode.Plain) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.choose_lyrics)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                DialogListItemWithRadio(
                    title = stringResource(R.string.plain_lyrics),
                    onClick = {
                        selectedMode = LyricsMode.Plain
                    },
                    isSelected = selectedMode == LyricsMode.Plain,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                DialogListItemWithRadio(
                    title = stringResource(R.string.synced_lyrics),
                    onClick = {
                        selectedMode = LyricsMode.Synced
                    },
                    isSelected = selectedMode == LyricsMode.Synced,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onModeSelected(selectedMode)
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

// 时间调整弹窗组件
@Composable
fun TimeShiftDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var offsetText by remember { mutableStateOf("") }
    var isAdvance by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("调整时间轴") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("输入平移的毫秒数 (1000毫秒 = 1秒)：")
                OutlinedTextField(
                    value = offsetText,
                    onValueChange = { if (it.all { char -> char.isDigit() }) offsetText = it },
                    label = { Text("毫秒") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !isAdvance, onClick = { isAdvance = false })
                        Text("延后 (+)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = isAdvance, onClick = { isAdvance = true })
                        Text("提前 (-)")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val ms = offsetText.toLongOrNull() ?: 0L
                onConfirm(if (isAdvance) -ms else ms)
            }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsSearchDialog(
    song: Song,
    title: String,
    showProviders: Boolean,
    onSearchClick: (title: String, artist: String, providers: List<LyricsProvider>) -> Unit,
    onDismissRequest: () -> Unit
) {
    var searchTitle by remember { mutableStateOf(song.title) }
    var searchArtist by remember { mutableStateOf(song.artistName) }

    val allProviders = remember { LyricsProvider.AvailableProviders }
    val selectedProviders = remember {
        mutableStateListOf<LyricsProvider>().apply {
            addAll(allProviders.filter { it.isEnabled })
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    OutlinedTextField(
                        value = searchTitle,
                        onValueChange = { searchTitle = it },
                        label = { Text(stringResource(R.string.title)) },
                        placeholder = { Text(song.title) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item { Spacer(Modifier.height(16.dp)) }

                item {
                    OutlinedTextField(
                        value = searchArtist,
                        onValueChange = { searchArtist = it },
                        label = { Text(stringResource(R.string.artist)) },
                        placeholder = { Text(song.artistName) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (showProviders) {
                    item { Spacer(Modifier.height(16.dp)) }

                    item {
                        Text(
                            text = stringResource(R.string.allow_lyrics_download_from),
                            style = MaterialTheme.typography.labelMedium,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .padding(bottom = 4.dp)
                        )
                    }

                    items(allProviders, key = { it.name }) {
                        DialogListItemWithCheckBox(
                            title = it.displayName,
                            isSelected = it in selectedProviders,
                            onClick = {
                                if (it in selectedProviders) {
                                    selectedProviders.remove(it)
                                } else {
                                    selectedProviders.add(it)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSearchClick(
                        searchTitle.ifEmpty { song.title },
                        searchArtist.ifEmpty { song.artistName },
                        selectedProviders
                    )
                },
                enabled = !showProviders || selectedProviders.isNotEmpty()
            ) {
                Text(stringResource(R.string.continue_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsSourceSelector(
    selectedSource: LyricsSource,
    onSourceSelected: (LyricsSource) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    ButtonGroup(
        onSelected = onSourceSelected,
        buttonItems = LyricsSource.entries,
        buttonStateResolver = { source -> source == selectedSource },
        buttonTextResolver = { source -> stringResource(source.titleRes) },
        buttonIconResolver = { source, isChecked ->
            if (isChecked) when (source) {
                LyricsSource.Embedded -> painterResource(R.drawable.ic_audio_file_24dp)
                LyricsSource.Downloaded -> painterResource(R.drawable.ic_download_24dp)
                LyricsSource.File -> painterResource(R.drawable.ic_file_open_24dp)
            } else null
        },
        enabled = enabled,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsEditorHeader(
    song: Song,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        configuration.smallestScreenWidthDp >= 600) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MediaImage(
                model = song,
                modifier = Modifier
                    .size(148.dp)
                    .clip(MaterialTheme.shapes.medium),
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = song.title,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.displayArtistName(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isLoading) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MediaImage(
                model = song,
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.small),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.displayArtistName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isLoading) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LyricsEditorBottomBar(
    enabled: Boolean,
    isFileSource: Boolean,
    onSearchClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onPasteClick: () -> Unit,
    onSaveClick: () -> Unit,
    onUndoChangesClick: () -> Unit,
    onSelectAllClick: () -> Unit,
    onTimeShiftClick: () -> Unit,
    onSaveFileClick: () -> Unit 
) {
    FlexibleBottomAppBar {
        IconButton(
            onClick = onSearchClick,
            enabled = enabled
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_search_24dp),
                contentDescription = stringResource(R.string.search_lyrics)
            )
        }
        IconButton(
            onClick = onDownloadClick,
            enabled = enabled 
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_download_24dp),
                contentDescription = stringResource(R.string.download_lyrics)
            )
        }
        FilledIconButton(
            onClick = onSaveClick,
            shapes = IconButtonDefaults.shapes(
                shape = IconButtonDefaults.smallSquareShape,
                pressedShape = IconButtonDefaults.smallPressedShape
            ),
            enabled = enabled && !isFileSource
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_save_24dp),
                contentDescription = stringResource(R.string.action_save)
            )
        }
        IconButton(
            onClick = onPasteClick,
            enabled = enabled
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_content_paste_24dp),
                contentDescription = stringResource(android.R.string.paste)
            )
        }
        OverflowMenu(
            enabled = enabled,
            items = listOf(
                MenuItem.Button.DropDown(
                    text = "时间轴平移",
                    icon = painterResource(R.drawable.ic_timer_24dp),
                    onClick = { onTimeShiftClick() }
                ),
                MenuItem.Button.DropDown(
                    text = "保存为本地文件",
                    icon = painterResource(R.drawable.ic_save_24dp),
                    onClick = { onSaveFileClick() }
                ),
                MenuItem.Button.DropDown(
                    text = stringResource(R.string.select_all_title),
                    icon = painterResource(R.drawable.ic_select_all_24dp),
                    onClick = { onSelectAllClick() }
                ),
                MenuItem.Button.DropDown(
                    text = stringResource(R.string.undo_changes),
                    icon = painterResource(R.drawable.ic_restart_alt_24dp),
                    dangerous = true,
                    onClick = { onUndoChangesClick() }
                )
            )
        )
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
    val hasBackgroundEffects = lyricsViewSettings.backgroundEffect == LyricsViewSettings.BackgroundEffect.Aurora
    val uiState by lyricsViewModel.lyricsUiState.collectAsState()
    val playerColorScheme by playerViewModel.colorSchemeFlow.collectAsState(
        initial = PlayerColorScheme.themeColorScheme(context)
    )

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val currentScreen = Preferences.nowPlayingScreen
    val hideExpandButton = isLandscape && (currentScreen == NowPlayingScreen.Default || currentScreen == NowPlayingScreen.Gradient || currentScreen == NowPlayingScreen.Plain)
    
    val translationKey = "lyrics_show_translation"
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var isTranslationEnabled by remember { mutableStateOf(prefs.getBoolean(translationKey, true)) }

    var hasTranslation by remember(uiState) { mutableStateOf(false) }
    
    LaunchedEffect(uiState) {
        withContext(Dispatchers.Default) {
            hasTranslation = try {
                when (val currentState = uiState) {
                    is LyricsUiState.Synced -> {
                        currentState.syncedLyrics.lines.any { line -> 
                            line.translation != null && !line.translation.isEmpty 
                        }
                    }
                    is LyricsUiState.Plain -> {
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
                hasBackgroundEffects = hasBackgroundEffects,
                onSeekTo = { position ->
                    playerViewModel.seekTo(position) 
                    if (lyricsViewSettings.resumeOnSeek) {
                        playerViewModel.play()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                modifier = Modifier
                    .wrapContentSize()
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 8.dp), 
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                        Text(
                            text = "\u8BD1", 
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isTranslationEnabled) 0.6f else 1.0f) 
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