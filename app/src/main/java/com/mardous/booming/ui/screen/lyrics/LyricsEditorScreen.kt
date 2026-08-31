package com.mardous.booming.ui.screen.lyrics

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.*
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
import com.mardous.booming.R
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.LyricsMode
import com.mardous.booming.data.model.lyrics.LyricsSource
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.extensions.hasR
import com.mardous.booming.extensions.media.displayArtistName
import com.mardous.booming.extensions.media.isArtistNameUnknown
import com.mardous.booming.extensions.openUrl
import com.mardous.booming.extensions.showToast
import com.mardous.booming.extensions.webSearch
import com.mardous.booming.ui.component.compose.ButtonGroup
import com.mardous.booming.ui.component.compose.DialogListItemWithCheckBox
import com.mardous.booming.ui.component.compose.DialogListItemWithRadio
import com.mardous.booming.ui.component.compose.MediaImage
import com.mardous.booming.ui.component.compose.ObserveAsEvent
import com.mardous.booming.ui.component.compose.menu.MenuItem
import com.mardous.booming.ui.component.compose.menu.OverflowMenu
import com.mardous.booming.ui.component.compose.menu.TopAppBarMenu
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinActivityViewModel
import kotlin.time.Duration.Companion.milliseconds

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
                    readOnly = false, 
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
                    readOnly = false,
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