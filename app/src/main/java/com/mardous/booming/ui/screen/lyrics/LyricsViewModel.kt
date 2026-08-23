package com.mardous.booming.ui.screen.lyrics

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.mardous.booming.core.model.lyrics.LyricsViewSettings
import com.mardous.booming.core.model.lyrics.LyricsViewSettings.BackgroundEffect
import com.mardous.booming.core.model.lyrics.LyricsViewSettings.Key
import com.mardous.booming.data.local.lyrics.InstrumentalDetector
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.LyricsSource
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.data.model.network.NetworkFeature.Lyrics.BetterLyrics
import com.mardous.booming.data.model.network.NetworkFeature.Lyrics.LRCLib
import com.mardous.booming.data.model.network.NetworkFeature.Lyrics.Lyrically
import com.mardous.booming.data.repository.LyricsRepository
import com.mardous.booming.extensions.files.belongsTo
import com.mardous.booming.extensions.media.isArtistNameUnknown
import com.mardous.booming.extensions.utilities.sanitize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.mardous.booming.core.model.lyrics.LyricsViewSettings.Mode as LyricsViewMode

// 🌟 1. 新增：导入目标枚举，区分常规字体和加粗字体
enum class FontTarget {
    REGULAR,
    BOLD
}

/**
 * @author Christians M. A. (mardous)
 */
class LyricsViewModel(
    application: Application,
    private val preferences: SharedPreferences,
    private val repository: LyricsRepository
) : AndroidViewModel(application), OnSharedPreferenceChangeListener {

    private var instrumentalDetector: InstrumentalDetector

    private val _lyricsUiState = MutableStateFlow<LyricsUiState>(LyricsUiState.Empty(-1))
    val lyricsUiState = _lyricsUiState.asStateFlow()

    private val _lyricsEditorUiState = MutableStateFlow<LyricsEditorUiState>(LyricsEditorUiState.Disposed)
    val lyricsEditorUiState = _lyricsEditorUiState.asStateFlow()

    private val _lyricsDownloadEnabled = MutableStateFlow(isLyricsDownloadEnabled())
    val lyricsDownloadEnabled = _lyricsDownloadEnabled.asStateFlow()

    private val _saveEvent = Channel<LyricsEditorResult>(Channel.BUFFERED)
    val saveEvent = _saveEvent.receiveAsFlow()

    private val _downloadEvent = Channel<RawLyrics.Remote>(Channel.BUFFERED)
    val downloadEvent = _downloadEvent.receiveAsFlow()

    private val _permissionRequestEvent = Channel<List<Uri>>(Channel.BUFFERED)
    val permissionRequestEvent = _permissionRequestEvent.receiveAsFlow()

    private val _playerLyricsViewSettings = MutableStateFlow(createViewSettings(LyricsViewMode.Player))
    val playerLyricsViewSettings = _playerLyricsViewSettings.asStateFlow()

    private val _fullLyricsViewSettings = MutableStateFlow(createViewSettings(LyricsViewMode.Full))
    val fullLyricsViewSettings = _fullLyricsViewSettings.asStateFlow()

    private var lyricsJob: Job? = null

    init {
        instrumentalDetector = createInstrumentalDetector()
        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onCleared() {
        lyricsJob?.cancel()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onCleared()
    }

    fun getSearchUrl(song: Song): String {
        val query = if (song.isArtistNameUnknown()) song.title
        else "${song.artistName} ${song.title}"
        return "https://lrclib.net/search/${Uri.encode(query)}"
    }

    fun loadEditorContent(song: Song) = viewModelScope.launch(IO) {
        _lyricsEditorUiState.update {
            LyricsEditorUiState.Visible(isLoading = true)
        }

        val lyrics = getEditorLyricsBySources(song, LyricsSource.entries)
        _lyricsEditorUiState.value = LyricsEditorUiState.Visible(
            isLoading = false,
            lyrics = lyrics
        )
    }

    fun disposeEditorContent() = viewModelScope.launch(IO) {
        _lyricsEditorUiState.value = LyricsEditorUiState.Disposed
    }

    fun saveLyrics(song: Song, newLyrics: Map<LyricsSource, String>) = viewModelScope.launch(IO) {
        val uiState = _lyricsEditorUiState.updateAndGet {
            if (it is LyricsEditorUiState.Visible) {
                it.copy(isLoading = true)
            } else it
        }
        if (uiState is LyricsEditorUiState.Visible) {
            val event = when (val result = repository.saveLyrics(song, uiState.lyrics, newLyrics)) {
                null -> LyricsEditorResult.NoChanges
                else -> if (result) LyricsEditorResult.Success else LyricsEditorResult.Failed
            }

            _saveEvent.send(event)

            if (event == LyricsEditorResult.Success) {
                val newLyrics = getEditorLyricsBySources(song, newLyrics.keys.toList())
                _lyricsEditorUiState.value = uiState.copy(isLoading = false, lyrics = newLyrics)

                if (song.id == lyricsUiState.value.id) {
                    updateSong(song)
                }
            } else {
                _lyricsEditorUiState.value = uiState.copy(isLoading = false)
            }
        }
    }

    fun downloadLyrics(song: Song, title: String, artist: String) =
        viewModelScope.launch(IO) {
            val uiState = _lyricsEditorUiState.updateAndGet {
                if (it is LyricsEditorUiState.Visible) {
                    it.copy(isLoading = true)
                } else it
            }
            if (uiState is LyricsEditorUiState.Visible) {
                val onlineLyrics = repository.downloadLyrics(song, title, artist)
                if (onlineLyrics != null) {
                    _downloadEvent.send(onlineLyrics)
                } else {
                    _downloadEvent.send(RawLyrics.Remote())
                }
                _lyricsEditorUiState.value = uiState.copy(isLoading = false)
            }
        }

    fun preparePermissionRequest(song: Song) = viewModelScope.launch(IO) {
        _permissionRequestEvent.send(repository.writableUris(song))
    }

    fun deleteLyrics() = viewModelScope.launch(IO) {
        repository.deleteAllLyrics()
    }

    // 🌟 2. 升级导入逻辑：支持传参分辨导入的是 Regular 还是 Bold
    fun importCustomFont(context: Context, uri: Uri, target: FontTarget = FontTarget.REGULAR) = liveData(IO) {
        try {
            val targetName = target.name.lowercase()
            val defaultName = "custom_font_${targetName}_${System.currentTimeMillis()}.ttf"
            val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
            val rawFileName = context.contentResolver.query(uri, null, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) cursor.getString(nameIndex) else null
                    } else null
                } ?: defaultName

            val fileName = File(rawFileName).name.sanitize()
                .ifBlank { defaultName }

            var isValid = fileName.lowercase().endsWith(".ttf") || fileName.lowercase().endsWith(".otf")

            if (isValid) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val header = ByteArray(4)
                    if (input.read(header) == 4) {
                        val hex = header.joinToString("") { "%02X".format(it) }
                        isValid = hex == "00010000" || hex == "4F54544F"
                    } else {
                        isValid = false
                    }
                }
            }

            if (!isValid) {
                emit(false)
                return@liveData
            }

            val targetPrefKey = if (target == FontTarget.BOLD) PREF_CUSTOM_FONT_BOLD else PREF_CUSTOM_FONT_REGULAR

            // 🌟 清理旧的字重物理文件，防止多次导入占用空间
            val oldPath = preferences.getString(targetPrefKey, null)
            if (!oldPath.isNullOrBlank()) {
                val oldFile = File(oldPath)
                if (oldFile.exists() && oldFile.belongsTo(fontsDir)) {
                    oldFile.delete()
                }
            }

            val outFile = File(fontsDir, "font_${targetName}_$fileName")
            if (!outFile.belongsTo(fontsDir)) {
                emit(false)
                return@liveData
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 🌟 存储配置
            preferences.edit(commit = true) {
                putBoolean(Key.USE_CUSTOM_FONT, true)
                putString(targetPrefKey, outFile.absolutePath)
                
                // 为了兼容旧版逻辑，如果是导入 Regular，同时更新一下旧 Key
                if (target == FontTarget.REGULAR) {
                    putString(Key.SELECTED_CUSTOM_FONT, outFile.absolutePath)
                }
            }

            emit(outFile.length() > 0)
        } catch (e: Exception) {
            e.printStackTrace()
            emit(false)
        }
    }

    fun updateSong(song: Song) {
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            if (song == Song.emptySong) {
                _lyricsUiState.value = LyricsUiState.Empty(song.id)
            } else {
                _lyricsUiState.value = LyricsUiState.Loading(song.id)

                val lyricsState = getBestLyricsFromSources(
                    song = song,
                    sources = listOf(
                        LyricsSource.File,
                        LyricsSource.Embedded,
                        LyricsSource.Downloaded
                    )
                )
                if (isActive) {
                    _lyricsUiState.value = lyricsState
                }
            }
        }
    }

    private suspend fun getEditorLyricsBySources(
        song: Song,
        sources: List<LyricsSource>
    ) = sources.associateWith { source ->
        when (source) {
            LyricsSource.Downloaded -> repository.storedLyrics(song, false)
            LyricsSource.Embedded -> repository.embeddedLyrics(song)
            LyricsSource.File -> repository.fileLyrics(song)
        }
    }

    private suspend fun getBestLyricsFromSources(
        song: Song,
        sources: List<LyricsSource>
    ): LyricsUiState = withContext(IO) {
        var plainLyrics: String? = null
        if (instrumentalDetector.byTitle(song.title)) {
            return@withContext LyricsUiState.Instrumental(song.id)
        }
        for (source in sources) {
            when (source) {
                LyricsSource.File -> {
                    val fileLyrics = repository.fileLyrics(song)
                    if (fileLyrics != null) {
                        val lyrics = repository.parseRawLyrics(song, fileLyrics)
                        if (lyrics?.hasContent == true) {
                            return@withContext LyricsUiState.Synced(song.id, lyrics)
                        }
                    }
                }

                LyricsSource.Embedded -> {
                    val embeddedLyrics = repository.embeddedLyrics(song)
                    if (embeddedLyrics != null) {
                        if (instrumentalDetector.byLyrics(embeddedLyrics.lyrics)) {
                            return@withContext LyricsUiState.Instrumental(song.id)
                        }
                        val lyrics = repository.parseRawLyrics(song, embeddedLyrics)
                        if (lyrics?.hasContent == true) {
                            return@withContext LyricsUiState.Synced(song.id, lyrics)
                        } else {
                            if (plainLyrics.isNullOrEmpty()) {
                                plainLyrics = embeddedLyrics.lyrics
                            }
                        }
                    }
                }

                LyricsSource.Downloaded -> {
                    val downloadedLyrics = repository.storedLyrics(song, true)
                    if (downloadedLyrics != null) {
                        if (downloadedLyrics.instrumental) {
                            return@withContext LyricsUiState.Instrumental(song.id)
                        }
                        val lyrics = repository.parseRawLyrics(song, downloadedLyrics)
                        if (lyrics?.hasContent == true) {
                            return@withContext LyricsUiState.Synced(song.id, lyrics)
                        } else {
                            if (plainLyrics.isNullOrEmpty()) {
                                plainLyrics = downloadedLyrics.lyrics
                            }
                        }
                    }
                }
            }
        }
        if (!plainLyrics.isNullOrEmpty()) {
            return@withContext LyricsUiState.Plain(song.id, plainLyrics)
        }
        return@withContext LyricsUiState.Empty(song.id)
    }

    private fun createViewSettings(mode: LyricsViewMode): LyricsViewSettings {
	    
        // 1. 先获取用户在设置里选了什么
        val effectString = preferences.getString(Key.BACKGROUND_EFFECT, null)

        // 2. 核心逻辑：拦截拦截模糊和渐变，唯独给“极光”放行 VIP 通道！
        val background: BackgroundEffect =
            if (!mode.isFull && effectString != "aurora") { 
                // 🌟 如果不是全屏，且选的不是极光，统统按原作者逻辑强行关闭！
                BackgroundEffect.None
            } else when (effectString) {
                "gradient" -> BackgroundEffect.Gradient
                "aurora" -> BackgroundEffect.Aurora
                "blur" -> BackgroundEffect.Blur
                else -> BackgroundEffect.None
            }
            }
        val enableSyllableLyrics = preferences.getBoolean(Key.ENABLE_SYLLABLE_LYRICS, false)
        val enableKaraokeStyle = preferences.getBoolean(Key.ENABLE_KARAOKE_STYLE, false)
        val progressiveColoring = preferences.getBoolean(Key.PROGRESSIVE_COLORING, false)
        val showTranslation = preferences.getBoolean(Key.SHOW_TRANSLATION, true)
        val showTransliteration = preferences.getBoolean(Key.SHOW_TRANSLITERATION, false)
        val resumeOnSeek = preferences.getBoolean(Key.RESUME_ON_SEEK, false)
        val blurEffect = !background.isNone && preferences.getBoolean(Key.BLUR_EFFECT, false)
        val shadowEffect = !background.isNone && preferences.getBoolean(Key.SHADOW_EFFECT, false)
        
        // 🌟 3. 重构字体构建逻辑：智能组合原生双字重
        val fontFamily: FontFamily = if (preferences.getBoolean(Key.USE_CUSTOM_FONT, false)) {
            try {
                // 读取常规字体（优先用新 Key，找不到用旧 Key 兜底兼容）
                val regularPath = preferences.getString(PREF_CUSTOM_FONT_REGULAR, null)
                    ?: preferences.getString(Key.SELECTED_CUSTOM_FONT, null)
                // 读取加粗字体
                val boldPath = preferences.getString(PREF_CUSTOM_FONT_BOLD, null)

                val fonts = mutableListOf<Font>()

                if (!regularPath.isNullOrBlank()) {
                    val file = File(regularPath)
                    if (file.exists()) {
                        fonts.add(Font(file = file, weight = FontWeight.Normal))
                    }
                }

                if (!boldPath.isNullOrBlank()) {
                    val file = File(boldPath)
                    if (file.exists()) {
                        fonts.add(Font(file = file, weight = FontWeight.Bold))
                    }
                }

                if (fonts.isNotEmpty()) {
                    FontFamily(fonts)
                } else {
                    FontFamily.Default
                }
            } catch (_: Exception) {
                FontFamily.Default
            }
        } else {
            FontFamily.Default
        }
        
        val lineSpacing = preferences.getInt(Key.LINE_SPACING, 40)
        val syncedFontSize = if (mode == LyricsViewMode.Player) {
            preferences.getInt(Key.SYNCED_FONT_SIZE_PLAYER, 20)
        } else {
            preferences.getInt(Key.SYNCED_FONT_SIZE_FULL, 24)
        }
        val unsyncedFontSize = if (mode == LyricsViewMode.Player) {
            preferences.getInt(Key.UNSYNCED_FONT_SIZE_PLAYER, 16)
        } else {
            preferences.getInt(Key.UNSYNCED_FONT_SIZE_FULL, 20)
        }
        val syncedBoldFont = preferences.getBoolean(Key.SYNCED_BOLD_FONT, false)
        val syncedStyle = TextStyle(
            fontFamily = fontFamily,
            fontSize = syncedFontSize.sp,
            fontWeight = if (syncedBoldFont) FontWeight.Bold else FontWeight.Normal,
            fontSynthesis = FontSynthesis.Weight, // 🌟 兜底保障：哪怕用户只导了一个常规字体，这行也能让它自动算法加粗！
            lineHeight = (1f + (lineSpacing / 100f)).em
        )
        val unsyncedBoldFont = preferences.getBoolean(Key.UNSYNCED_BOLD_FONT, false)
        val unsyncedStyle = TextStyle(
            fontFamily = fontFamily,
            fontSize = unsyncedFontSize.sp,
            fontWeight = if (unsyncedBoldFont) FontWeight.Bold else FontWeight.Normal,
            fontSynthesis = FontSynthesis.Weight, // 同上
            lineHeight = (1f + (lineSpacing / 100f)).em
        )
        
        return LyricsViewSettings(
            mode = mode,
            isCenterCurrentLine = preferences.getBoolean(Key.CENTER_CURRENT_LINE, false),
            isCenterHorizontally = preferences.getBoolean(Key.CENTER_HORIZONTALLY, false),
            enableSyllableLyrics = enableSyllableLyrics,
            enableKaraokeStyle = enableKaraokeStyle,
            progressiveColoring = progressiveColoring,
            backgroundEffect = background,
            blurEffect = blurEffect,
            shadowEffect = shadowEffect,
            showTranslation = showTranslation,
            showTransliteration = showTransliteration,
            resumeOnSeek = resumeOnSeek,
            syncedStyle = syncedStyle,
            unsyncedStyle = unsyncedStyle,
            lineSpacing = ((lineSpacing / 2) + 8).coerceIn(8, 48)
        )
    }
	
	// 🌟 新增：智能时间轴平移算法
    fun shiftTimeline(content: String, offsetMs: Long): String {
        if (offsetMs == 0L || content.isBlank()) return content

        var newContent = content

        // 1. 处理 LRC 时间轴 [mm:ss.xx] 或 [mm:ss.xxx]
        val lrcRegex = Regex("""\[(\d{2,}):(\d{2})\.(\d{2,3})\]""")
        newContent = lrcRegex.replace(newContent) { match ->
            val m = match.groupValues[1].toLong()
            val s = match.groupValues[2].toLong()
            val msStr = match.groupValues[3]
            val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
            
            var totalMs = m * 60000 + s * 1000 + ms + offsetMs
            if (totalMs < 0) totalMs = 0
            
            val nm = totalMs / 60000
            val ns = (totalMs % 60000) / 1000
            if (msStr.length == 3) {
                String.format("[%02d:%02d.%03d]", nm, ns, totalMs % 1000)
            } else {
                String.format("[%02d:%02d.%02d]", nm, ns, (totalMs % 1000) / 10)
            }
        }

        // 2. 处理 TTML 时间轴 (HH:MM:SS.mmm)
        val ttmlRegex = Regex("""(begin|end)="(\d{2,}):(\d{2}):(\d{2})\.(\d{3})"""")
        newContent = ttmlRegex.replace(newContent) { match ->
            val attr = match.groupValues[1]
            val h = match.groupValues[2].toLong()
            val m = match.groupValues[3].toLong()
            val s = match.groupValues[4].toLong()
            val ms = match.groupValues[5].toLong()
            
            var totalMs = h * 3600000 + m * 60000 + s * 1000 + ms + offsetMs
            if (totalMs < 0) totalMs = 0
            
            val nh = totalMs / 3600000
            val nm = (totalMs % 3600000) / 60000
            val ns = (totalMs % 60000) / 1000
            val nms = totalMs % 1000
            
            String.format("%s=\"%02d:%02d:%02d.%03d\"", attr, nh, nm, ns, nms)
        }

        return newContent
    }

	// 🌟 新增：完全独立的本地文件覆盖逻辑，不走原作者复杂的数据库和标签通道
    fun saveLocalLyricsFile(context: android.content.Context, song: Song, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val songFile = java.io.File(song.data)
                val parentDir = songFile.parentFile ?: return@launch
                
                val possibleNames = listOf(
                    songFile.nameWithoutExtension,
                    "${song.artistName} - ${song.title}"
                ).filter { it.isNotBlank() }
                
                var saved = false
                val isTtml = content.trim().startsWith("<")
                val ext = if (isTtml) ".ttml" else ".lrc"
                
                for (name in possibleNames) {
                    val targetFile = java.io.File(parentDir, "$name$ext")
                    // 如果存在同名文件，或者当前尝试保存的就是主文件名，直接覆写
                    if (targetFile.exists() || name == songFile.nameWithoutExtension) {
                        targetFile.writeText(content)
                        saved = true
                        break
                    }
                }
                
                withContext(Dispatchers.Main) {
                    if (saved) {
                        android.widget.Toast.makeText(context, "成功覆写本地 $ext 文件", android.widget.Toast.LENGTH_SHORT).show()
                        repository.clearMemoryCache()
                        updateSong(song)
                    } else {
                        android.widget.Toast.makeText(context, "保存失败：未找到有效目录", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "保存失败，请检查存储权限", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
	
    private fun isLyricsDownloadEnabled(): Boolean {
        return BetterLyrics.isEnabled || Lyrically.isEnabled || LRCLib.isEnabled
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "preferred_lyrics_file_format" -> {
                repository.clearMemoryCache()
                val currentSongId = _lyricsUiState.value.id
                if (currentSongId != -1L) {
                    _playerLyricsViewSettings.value = createViewSettings(LyricsViewMode.Player)
                    _fullLyricsViewSettings.value = createViewSettings(LyricsViewMode.Full)
                }
            }

            // 🌟 监听双字重 Key 变动，实时刷新界面
            PREF_CUSTOM_FONT_REGULAR,
            PREF_CUSTOM_FONT_BOLD,
            Key.USE_CUSTOM_FONT,
            Key.SELECTED_CUSTOM_FONT,
            Key.ENABLE_SYLLABLE_LYRICS,
            Key.ENABLE_KARAOKE_STYLE,
            Key.CENTER_CURRENT_LINE,
            Key.CENTER_HORIZONTALLY,
            Key.LINE_SPACING,
            Key.PROGRESSIVE_COLORING,
            Key.SHOW_TRANSLATION,
            Key.SHOW_TRANSLITERATION,
            Key.RESUME_ON_SEEK,
            Key.BACKGROUND_EFFECT,
            Key.BLUR_EFFECT,
            Key.SHADOW_EFFECT,
            Key.SYNCED_BOLD_FONT,
            Key.UNSYNCED_BOLD_FONT -> {
                _playerLyricsViewSettings.value = createViewSettings(LyricsViewMode.Player)
                _fullLyricsViewSettings.value = createViewSettings(LyricsViewMode.Full)
            }
            Key.SYNCED_FONT_SIZE_PLAYER,
            Key.UNSYNCED_FONT_SIZE_PLAYER -> {
                _playerLyricsViewSettings.value = createViewSettings(LyricsViewMode.Player)
            }
            Key.SYNCED_FONT_SIZE_FULL,
            Key.UNSYNCED_FONT_SIZE_FULL -> {
                _fullLyricsViewSettings.value = createViewSettings(LyricsViewMode.Full)
            }
            NetworkFeature.NETWORK_FEATURES_KEY,
            NetworkFeature.BETTERLYRICS_ENABLED_KEY,
            NetworkFeature.LYRICALLY_ENABLED_KEY,
            NetworkFeature.LRCLIB_ENABLED_KEY -> {
                _lyricsDownloadEnabled.value = isLyricsDownloadEnabled()
            }
            INSTRUMENTAL_TRACK_IDENTIFIERS,
            MARK_INSTRUMENTAL_BY_TITLE -> {
                instrumentalDetector = createInstrumentalDetector()
            }
        }
    }

    private fun createInstrumentalDetector() =
        InstrumentalDetector(
            identifiers = preferences.getString(INSTRUMENTAL_TRACK_IDENTIFIERS, null)
                ?.split(",").orEmpty().toSet(),
            markByTitle = preferences.getBoolean(MARK_INSTRUMENTAL_BY_TITLE, false),
            maxLength = INSTRUMENTAL_IDENTIFIER_MAX_LENGTH
        )

    companion object {
        private const val INSTRUMENTAL_IDENTIFIER_MAX_LENGTH = 50
        private const val INSTRUMENTAL_TRACK_IDENTIFIERS = "instrumental_track_identifiers"
        private const val MARK_INSTRUMENTAL_BY_TITLE = "mark_instrumental_tracks_by_title"
        
        // 🌟 存储双字重路径的常量 Key
        const val PREF_CUSTOM_FONT_REGULAR = "selected_custom_font_regular"
        const val PREF_CUSTOM_FONT_BOLD = "selected_custom_font_bold"
    }
}