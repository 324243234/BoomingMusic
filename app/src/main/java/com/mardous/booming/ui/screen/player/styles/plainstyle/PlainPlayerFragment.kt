/*
 * Copyright (c) 2025 Christians Martínez Alvarado
 */

package com.mardous.booming.ui.screen.player.styles.plainstyle


import com.mardous.booming.data.model.Song
import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.mardous.booming.R
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.player.PlayerColorSchemeMode
import com.mardous.booming.core.model.player.PlayerTintTarget
import com.mardous.booming.core.model.player.surfaceTintTarget
import com.mardous.booming.core.model.player.tintTarget
import com.mardous.booming.core.model.theme.NowPlayingScreen
import com.mardous.booming.data.repository.LyricsRepository
import com.mardous.booming.data.repository.Repository
import com.mardous.booming.databinding.FragmentPlainPlayerBinding
import com.mardous.booming.extensions.getOnBackPressedDispatcher
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.whichFragment
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.AbsPlayerFragment
import com.mardous.booming.ui.screen.lyrics.LyricsViewModel
import com.mardous.booming.ui.screen.player.PlayerGesturesController.GestureType
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.io.File

class PlainPlayerFragment : AbsPlayerFragment(R.layout.fragment_plain_player) {

    private val sharedPreferences: SharedPreferences by inject()
    private val lyricsViewModel: LyricsViewModel by activityViewModel()
    private val lyricsRepository: LyricsRepository by inject()
    private val repository: Repository by inject()

    private var _binding: FragmentPlainPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var controlsFragment: PlainPlayerControlsFragment
	
	
	// 1. 定义官方偏好设置变更监听器
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "now_playing_corner_radius") {
            // 当监听到圆角设置发生变化时，立即重新应用圆角
            applyDynamicCoverCornerRadius()
        }
    }

    // 2. 动态读取并应用圆角的专用方法
    private fun applyDynamicCoverCornerRadius() {
        val binding = _binding ?: return
        
        // 🌟 完美调用作者原生 API 获取用户设定的圆角大小
        val radiusDp = Preferences.getNowPlayingImageCornerRadius(requireContext())
        val radiusPx = radiusDp * resources.displayMetrics.density

        // 同步应用到动态视频的底层渲染容器上
        binding.canvasPlayerView?.apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    if (view.width > 0 && view.height > 0) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
            }
            clipToOutline = true
            invalidate() // 强制作出重绘，实现实时形变
        }
    }

    // ================= 视频引擎属性 =================
    private var canvasExoPlayer: ExoPlayer? = null
    private var videoFetchJob: Job? = null
    private var lastProcessedSongId: Long = -1L

    private val powerManager by lazy { requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val batteryManager by lazy { requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager }

    override val colorSchemeMode: PlayerColorSchemeMode
        get() = Preferences.getNowPlayingColorSchemeMode(NowPlayingScreen.Plain)

    override val playerControlsFragment: AbsPlayerControlsFragment
        get() = controlsFragment

    override val playerToolbar: Toolbar
        get() = binding.toolbar

    override val blurView: ImageView
        get() = binding.blur

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlainPlayerBinding.bind(view)
        setupToolbar()
        inflateMenuInView(playerToolbar)
        
        setupVideoPlayer()
        setupCanvasObserver()
        setupLyricsFavoriteButton()
		
		// 🌟 初次渲染时，应用一次圆角
        applyDynamicCoverCornerRadius()
        
        // 🌟 注册监听器，开启实时同步模式
        Preferences.registerOnSharedPreferenceChangeListener(preferenceListener)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v: View, insets: WindowInsetsCompat ->
            val systemBars = insets.getInsets(Type.systemBars())
            v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            val displayCutout = insets.getInsets(Type.displayCutout())
            v.updatePadding(left = displayCutout.left, right = displayCutout.right)
            WindowInsetsCompat.CONSUMED
        }
        
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            playerViewModel.currentSongFlow.collect { currentSong ->
                _binding?.let { nonNullBinding ->
                    nonNullBinding.title.text = currentSong.title
                    nonNullBinding.text.text = getSongArtist(currentSong)
                }
            }
        }
    }

    // ================= 1. 手势拦截核心逻辑 =================
    override fun gestureDetected(gestureType: GestureType): Boolean {
        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
        
        if (isLandscapeOrTablet) {
            when (gestureType) {
                is GestureType.Tap -> {
                    handleCoverClick()
                    return true
                }
                is GestureType.DoubleTap -> {
                    when (gestureType.type) {
                        GestureType.DoubleTap.TYPE_LEFT_EDGE -> { playerViewModel.seekToPrevious(); return true }
                        GestureType.DoubleTap.TYPE_RIGHT_EDGE -> { playerViewModel.seekToNext(); return true }
                        else -> {}
                    }
                }
                else -> {}
            }
        }
        return super.gestureDetected(gestureType)
    }

    private fun handleCoverClick() {
        val willShowLyrics = binding.rightLyricsContainer?.visibility != View.VISIBLE
        // 显示或隐藏右侧全新歌词覆层
        binding.rightLyricsContainer?.visibility = if (willShowLyrics) View.VISIBLE else View.INVISIBLE
        
        // 反向操作：隐藏或显示原版的所有右侧组件
        val originalVisibility = if (willShowLyrics) View.INVISIBLE else View.VISIBLE
        binding.title.visibility = originalVisibility
        binding.text.visibility = originalVisibility
        binding.playbackControlsFragment?.visibility = originalVisibility
        binding.toolbar.visibility = originalVisibility
    }

    private fun setupToolbar() {
        if (playerToolbar.navigationIcon != null) {
            playerToolbar.setNavigationOnClickListener { getOnBackPressedDispatcher().onBackPressed() }
        }
    }

    // ================= 2. 底部 Toolbar 菜单精准排版与功能重构 =================
    override fun onMenuInflated(menu: Menu) {
        super.onMenuInflated(menu)
        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE

        if (isLandscapeOrTablet) {
            // 清理掉作者原先霸占位置的图标
            menu.findItem(R.id.action_playing_queue)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.findItem(R.id.action_favorite)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.findItem(R.id.action_sleep_timer)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.findItem(R.id.action_show_lyrics)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)

            // 严格按顺序强制推入 Toolbar (SHOW_AS_ACTION_ALWAYS)
            if (menu.findItem(R.id.action_go_to_artist) == null) menu.add(Menu.NONE, R.id.action_go_to_artist, 1, "歌手").setIcon(R.drawable.ic_person_24dp)
            if (menu.findItem(R.id.action_go_to_album) == null) menu.add(Menu.NONE, R.id.action_go_to_album, 2, "专辑").setIcon(R.drawable.ic_album_24dp)
            if (menu.findItem(R.id.action_toggle_lyrics_format) == null) menu.add(Menu.NONE, R.id.action_toggle_lyrics_format, 3, "切换歌词格式").setIcon(R.drawable.ic_lyrics_24dp)
            if (menu.findItem(R.id.action_equalizer) == null) menu.add(Menu.NONE, R.id.action_equalizer, 4, "均衡器").setIcon(R.drawable.ic_equalizer_24dp)
            if (menu.findItem(R.id.action_toggle_video_cover) == null) menu.add(Menu.NONE, R.id.action_toggle_video_cover, 5, "动态封面开关").setIcon(R.drawable.ic_album_24dp)
            if (menu.findItem(R.id.action_sound_settings) == null) menu.add(Menu.NONE, R.id.action_sound_settings, 6, "声音设置").setIcon(R.drawable.ic_volume_up_24dp)

            // 其余核心扩展逻辑全部丢入三点溢出菜单
            if (menu.findItem(R.id.action_fetch_ttml) == null) menu.add(Menu.NONE, R.id.action_fetch_ttml, 10, "获取TTML")
            if (menu.findItem(R.id.action_delete_ttml) == null) menu.add(Menu.NONE, R.id.action_delete_ttml, 11, "删除TTML")
            if (menu.findItem(R.id.action_blacklist_video) == null) menu.add(Menu.NONE, R.id.action_blacklist_video, 12, "动封黑名单")
            if (menu.findItem(R.id.action_delete_from_device) == null) menu.add(Menu.NONE, R.id.action_delete_from_device, 13, "删除歌曲及关联文件")

            // 强制全部显示为图标
            listOf(R.id.action_go_to_artist, R.id.action_go_to_album, R.id.action_toggle_lyrics_format, R.id.action_equalizer, R.id.action_toggle_video_cover, R.id.action_sound_settings).forEach {
                menu.findItem(it)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            updateFormatIcon(menu.findItem(R.id.action_toggle_lyrics_format))
        } else {
            menu.setShowAsAction(R.id.action_playing_queue, mode = MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.setShowAsAction(R.id.action_favorite, mode = MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.setShowAsAction(R.id.action_sleep_timer, mode = MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.setShowAsAction(R.id.action_show_lyrics, mode = MenuItem.SHOW_AS_ACTION_ALWAYS)
        }

        // 绑定所有的逻辑回调
        playerToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_toggle_video_cover -> {
                    val newState = !sharedPreferences.getBoolean("pref_enable_video_cover", true)
                    sharedPreferences.edit(commit = true) { putBoolean("pref_enable_video_cover", newState) }
                    playerViewModel.currentSongFlow.value?.let { lyricsViewModel.updateSong(it) }
                    true
                }
                R.id.action_toggle_lyrics_format -> {
                    val currentFormat = sharedPreferences.getString("preferred_lyrics_file_format", "ttml") ?: "ttml"
                    val isTtml = currentFormat.equals("ttml", ignoreCase = true) || currentFormat == "0"
                    val newFormat = if (isTtml) "lrc" else "ttml"
                    lyricsRepository.clearMemoryCache()
                    sharedPreferences.edit(commit = true) { putString("preferred_lyrics_file_format", newFormat) }
                    context?.let { Toast.makeText(it, if (isTtml) "已切换为 LRC 滚动歌词" else "已切换为 TTML 逐字歌词", Toast.LENGTH_SHORT).show() }
                    updateFormatIcon(item)
                    playerViewModel.currentSongFlow.value?.let { lyricsViewModel.updateSong(it) }
                    true
                }
                R.id.action_fetch_ttml -> { fetchTtml(); true }
                R.id.action_delete_ttml -> { playerViewModel.currentSongFlow.value?.let { deleteAssociatedFiles(it, true) }; true }
                R.id.action_blacklist_video -> { playerViewModel.currentSongFlow.value?.let { addToVideoBlacklist(it) }; true }
                R.id.action_delete_from_device -> {
                    playerViewModel.currentSongFlow.value?.let { deleteAssociatedFiles(it, false) }
                    false // 🌟 返回 false 使得系统自带的“彻底删除原音频文件”弹窗能够继续被触发！
                }
                else -> false
            }
        }
    }

    private fun updateFormatIcon(item: MenuItem?) {
        val currentFormat = sharedPreferences.getString("preferred_lyrics_file_format", "ttml") ?: "ttml"
        val isTtml = currentFormat.equals("ttml", ignoreCase = true) || currentFormat == "0"
        item?.setIcon(if (isTtml) R.drawable.ic_lyrics_24dp else R.drawable.ic_lyrics_outline_24dp)
    }

    // ================= 3. 提取自 Gradient 的功能方法群 =================
    private fun fetchTtml() {
        playerViewModel.currentSongFlow.value?.let { currentSong ->
            val toast = Toast.makeText(context, "正在检索并获取逐字 TTML...", Toast.LENGTH_LONG)
            toast.show()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val ttmlContent = com.mardous.booming.data.local.lyrics.ttml.TtmlFetcher.fetchTtmlForSong(currentSong)
                withContext(Dispatchers.Main) {
                    toast.cancel()
                    if (!ttmlContent.isNullOrBlank()) {
                        try {
                            val parentDir = File(currentSong.data).parentFile
                            if (parentDir?.exists() == true) {
                                File(parentDir, "${File(currentSong.data).nameWithoutExtension}.ttml").writeText(ttmlContent)
                                Toast.makeText(context, "获取成功！已保存为 TTML", Toast.LENGTH_SHORT).show()
                                lyricsRepository.clearMemoryCache()
                                lyricsViewModel.updateSong(currentSong)
                            }
                        } catch (e: Exception) { }
                    } else { Toast.makeText(context, "获取失败：全网未找到该歌曲", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun addToVideoBlacklist(song: Song) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val songFile = File(song.data)
                val parentDir = songFile.parentFile ?: return@launch
                val audioFileName = songFile.nameWithoutExtension
                val possibleNames = listOf(audioFileName.lowercase(), "${song.artistName} - ${song.title}".lowercase(), "${song.title} - ${song.artistName}".lowercase())

                listOfNotNull(parentDir, File(parentDir, ".MP4").takeIf { it.exists() }).forEach { dir ->
                    dir.listFiles()?.forEach { file ->
                        val ext = if (file.isDirectory) file.name.substringAfterLast('.', "").lowercase() else file.extension.lowercase()
                        if ((ext == "mp4" || ext == "webm") && possibleNames.contains(file.nameWithoutExtension.lowercase())) {
                            if (file.isDirectory) file.deleteRecursively() else file.delete()
                        }
                    }
                }
                val hiddenDir = File(parentDir, ".MP4").apply { if (!exists()) mkdirs() }
                File(hiddenDir, "$audioFileName.mp4").takeIf { !it.exists() }?.mkdirs() // 黑名单占位

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "已拉黑并清理相关视频", Toast.LENGTH_SHORT).show()
                    videoFetchJob?.cancel()
                    canvasExoPlayer?.stop()
                    canvasExoPlayer?.clearMediaItems()
                    binding.canvasPlayerView?.alpha = 0f
                }
            } catch (e: Exception) {}
        }
    }

    private fun deleteAssociatedFiles(song: Song, onlyTtml: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val songFile = File(song.data)
                val parentDir = songFile.parentFile ?: return@launch
                val possibleNames = listOf(songFile.nameWithoutExtension.lowercase(), "${song.artistName} - ${song.title}".lowercase(), "${song.title} - ${song.artistName}".lowercase())
                val targets = if (onlyTtml) listOf("ttml") else listOf("ttml", "lrc", "mp4", "webm")
                
                var (deletedTtml, deletedOther) = false to false
                
                listOfNotNull(parentDir, File(parentDir, ".MP4").takeIf { it.exists() }).forEach { dir ->
                    dir.listFiles()?.forEach { file ->
                        val ext = if (file.isDirectory) file.name.substringAfterLast('.', "").lowercase() else file.extension.lowercase()
                        if (targets.contains(ext) && possibleNames.contains(file.nameWithoutExtension.lowercase())) {
                            if (file.isDirectory) { file.deleteRecursively(); if (ext != "ttml") deletedOther = true }
                            else {
                                runCatching { if (ext == "mp4" || ext == "webm") file.writeBytes(ByteArray(0)) else file.writeText("") }
                                if (file.delete() || file.length() == 0L) { if (ext == "ttml") deletedTtml = true else deletedOther = true }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (onlyTtml && deletedTtml) {
                        Toast.makeText(context, "TTML 彻底删除", Toast.LENGTH_SHORT).show()
                        lyricsRepository.clearMemoryCache()
                        lyricsViewModel.updateSong(song)
                    } else if (!onlyTtml && (deletedTtml || deletedOther)) {
                        lyricsRepository.clearMemoryCache()
                        Toast.makeText(context, "本地歌词及视频已清空", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    // ================= 4. ExoPlayer 引擎与视图状态控制 =================
    private fun isDeviceStressed(): Boolean {
        if (powerManager.isPowerSaveMode) return true
        if (batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) <= 20) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return true
        return false
    }

    private fun setupVideoPlayer() {
        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
        if (!isLandscapeOrTablet) return

        canvasExoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
            playbackParameters = PlaybackParameters(0.85f)
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setMaxVideoSize(854, 480).build()

            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { binding.canvasPlayerView?.let { if (it.alpha < 1f) it.animate().alpha(1f).setDuration(800).start() } }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        binding.canvasPlayerView?.animate()?.alpha(0f)?.setDuration(700)?.withEndAction { binding.canvasPlayerView?.postDelayed({ canvasExoPlayer?.seekTo(0); canvasExoPlayer?.play() }, 1000) }?.start()
                    }
                }
            })
        }
        binding.canvasPlayerView?.apply { player = canvasExoPlayer; useController = false; setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM) }
    }

    private fun setupCanvasObserver() {
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                playerViewModel.currentSongFlow.collect { song ->
                    if (song != null && song.id != lastProcessedSongId) {
                        
                        // 更新右侧全唱歌词区顶栏信息
                        binding.lyricsSongTitleText?.text = song.title
                        val artistStr = if (Preferences.preferAlbumArtistName && !song.albumArtistName.isNullOrEmpty()) song.albumArtistName else song.artistName
                        binding.lyricsSongArtistText?.text = artistStr

                        launch(Dispatchers.IO) {
                            val isFav = repository.isSongFavorite(song.id)
                            withContext(Dispatchers.Main) { updateFavoriteIcon(isFav) }
                        }

                        videoFetchJob?.cancel()
                        canvasExoPlayer?.stop()
                        canvasExoPlayer?.clearMediaItems()
                        binding.canvasPlayerView?.alpha = 0f

                        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
                            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
                        
                        if (isLandscapeOrTablet && sharedPreferences.getBoolean("pref_enable_video_cover", true) && !isDeviceStressed()) {
                            videoFetchJob = launch {
                                delay(400)
                                val videoUri = withContext(Dispatchers.IO) { com.mardous.booming.data.local.lyrics.ttml.AnimatedCanvasFetcher.fetchCanvasUri(requireContext(), song) }
                                if (isActive && !videoUri.isNullOrBlank() && !isDeviceStressed() && sharedPreferences.getBoolean("pref_enable_video_cover", true)) {
                                    withContext(Dispatchers.Main) { canvasExoPlayer?.setMediaItem(MediaItem.fromUri(videoUri)); canvasExoPlayer?.prepare(); canvasExoPlayer?.play() }
                                }
                            }
                        }
                        lastProcessedSongId = song.id
                    }
                }
            }
        }
    }

    private fun setupLyricsFavoriteButton() {
        binding.lyricsFavoriteButton?.setOnClickListener {
            val isFav = it.tag as? Boolean ?: false
            updateFavoriteIcon(!isFav)
            playerViewModel.toggleFavorite() 
        }
    }

    override fun onIsFavoriteChanged(isFavorite: Boolean, withAnimation: Boolean) {
        updateFavoriteIcon(isFavorite)
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        binding.lyricsFavoriteButton?.apply {
            tag = isFavorite
            setImageResource(if (isFavorite) R.drawable.ic_favorite_24dp else R.drawable.ic_favorite_outline_24dp)
        }
    }

    // ================= 5. Tint 颜色映射注入 =================
    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldPrimaryTextColor = binding.title.currentTextColor
        val oldSecondaryTextColor = binding.text.currentTextColor
        val alphaColor = ColorUtils.setAlphaComponent(scheme.onSurfaceColor, 178)

        val targets = mutableListOf(
            binding.root.surfaceTintTarget(scheme.surfaceColor),
            binding.toolbar.tintTarget(oldPrimaryTextColor, scheme.onSurfaceColor),
            binding.title.tintTarget(oldPrimaryTextColor, scheme.onSurfaceColor),
            binding.text.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor)
        )
        
        // 绑定全新的独立右侧覆层文字和图标色
        binding.lyricsSongTitleText?.let { targets.add(it.tintTarget(it.currentTextColor, scheme.onSurfaceColor)) }
        binding.lyricsSongArtistText?.let { targets.add(it.tintTarget(it.currentTextColor, alphaColor)) }
        binding.lyricsFavoriteButton?.let { targets.add(it.tintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)) }

        targets.addAll(playerControlsFragment.getTintTargets(scheme))
        return targets
    }

    override fun onCreateChildFragments() {
        super.onCreateChildFragments()
        controlsFragment = whichFragment(R.id.playbackControlsFragment)
    }

    override fun onDestroyView() {
	// 🌟 页面销毁时注销监听器，防止内存泄漏
        Preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
		
        videoFetchJob?.cancel()
        canvasExoPlayer?.release()
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() { 
        super.onResume()
        if (!isDeviceStressed()) canvasExoPlayer?.play() 
    }
    
    override fun onPause() { 
        super.onPause()
        canvasExoPlayer?.pause() 
    }
}