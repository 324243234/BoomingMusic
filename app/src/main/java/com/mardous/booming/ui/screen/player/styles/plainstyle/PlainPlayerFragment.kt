/*
 * Copyright (c) 2025 Christians Martínez Alvarado
 */

package com.mardous.booming.ui.screen.player.styles.plainstyle

import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
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
import com.mardous.booming.data.model.Song
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

    // 🌟 1. 定义官方偏好设置变更监听器
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "now_playing_corner_radius") {
            syncVideoCoverSizeAndCorners()
        }
    }

    // 🌟 2. 终极对齐：同时同步视频的“圆角”和“内边距(Margin)”，确保与静态图 100% 重合！
    private fun syncVideoCoverSizeAndCorners() {
        val binding = _binding ?: return
        
        // --- 修复偏大问题：注入作者静态封面的 @dimen/player_cover_margin ---
        val coverMargin = resources.getDimensionPixelSize(R.dimen.player_cover_margin)
        val lp = binding.canvasPlayerView?.layoutParams as? ConstraintLayout.LayoutParams
        if (lp != null) {
            lp.setMargins(coverMargin, coverMargin, coverMargin, coverMargin)
            binding.canvasPlayerView?.layoutParams = lp
        }

        // --- 同步圆角 ---
        val radiusDp = Preferences.getNowPlayingImageCornerRadius(requireContext())
        val radiusPx = radiusDp * resources.displayMetrics.density

        binding.canvasPlayerView?.apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    if (view.width > 0 && view.height > 0) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
            }
            clipToOutline = true
            invalidate()
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
        
        // 🌟 初始化时，精准计算边距与圆角对齐
        syncVideoCoverSizeAndCorners()
        
        Preferences.registerOnSharedPreferenceChangeListener(preferenceListener)

        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
        if (isLandscapeOrTablet) {
            // 🌟 开启滑动监测：一旦触发拖拽手势，立刻让动封隐形！
            setupSlidingGhostMode()
        }

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

    // ================= 手势拦截核心逻辑 =================
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
        binding.rightLyricsContainer?.visibility = if (willShowLyrics) View.VISIBLE else View.INVISIBLE
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

    // ================= 幽灵滑动模式 =================
    private fun setupSlidingGhostMode() {
        viewLifecycleOwner.lifecycleScope.launch {
            delay(500) 
            val coverFragment = childFragmentManager.findFragmentById(R.id.playerAlbumCoverFragment)
            coverFragment?.view?.let { innerView ->
                val viewPager = findViewPager(innerView)
                viewPager?.addOnPageChangeListener(object : androidx.viewpager.widget.ViewPager.OnPageChangeListener {
                    override fun onPageScrollStateChanged(state: Int) {
                        if (state == androidx.viewpager.widget.ViewPager.SCROLL_STATE_DRAGGING) {
                            // 手指开始滑动，瞬间干掉动封，露出底部的静态图片
                            binding.canvasPlayerView?.animate()?.cancel()
                            binding.canvasPlayerView?.alpha = 0f
                        } else if (state == androidx.viewpager.widget.ViewPager.SCROLL_STATE_IDLE) {
                            // 滑动完毕，渐变恢复动封
                            if (canvasExoPlayer?.playbackState == Player.STATE_READY || canvasExoPlayer?.playbackState == Player.STATE_ENDED) {
                                binding.canvasPlayerView?.animate()?.alpha(1f)?.setDuration(400)?.start()
                            }
                        }
                    }
                    override fun onPageScrolled(p0: Int, p1: Float, p2: Int) {}
                    override fun onPageSelected(p0: Int) {}
                })
            }
        }
    }

    private fun findViewPager(view: View): androidx.viewpager.widget.ViewPager? {
        if (view is androidx.viewpager.widget.ViewPager) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findViewPager(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    // ================= 底部 Toolbar 菜单精准排版与功能 =================
    override fun onMenuInflated(menu: Menu) {
        super.onMenuInflated(menu)
        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE

        if (isLandscapeOrTablet) {
            menu.findItem(R.id.action_playing_queue)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.findItem(R.id.action_favorite)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.findItem(R.id.action_sleep_timer)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.findItem(R.id.action_show_lyrics)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)

            if (menu.findItem(R.id.action_go_to_artist) == null) menu.add(Menu.NONE, R.id.action_go_to_artist, 1, "歌手").setIcon(R.drawable.ic_person_24dp)
            if (menu.findItem(R.id.action_go_to_album) == null) menu.add(Menu.NONE, R.id.action_go_to_album, 2, "专辑").setIcon(R.drawable.ic_album_24dp)
            if (menu.findItem(R.id.action_toggle_lyrics_format) == null) menu.add(Menu.NONE, R.id.action_toggle_lyrics_format, 3, "切换歌词格式").setIcon(R.drawable.ic_lyrics_24dp)
            if (menu.findItem(R.id.action_equalizer) == null) menu.add(Menu.NONE, R.id.action_equalizer, 4, "均衡器").setIcon(R.drawable.ic_equalizer_24dp)
            if (menu.findItem(R.id.action_toggle_video_cover) == null) menu.add(Menu.NONE, R.id.action_toggle_video_cover, 5, "动态封面开关").setIcon(R.drawable.ic_album_24dp) 
            if (menu.findItem(R.id.action_sound_settings) == null) menu.add(Menu.NONE, R.id.action_sound_settings, 6, "声音设置").setIcon(R.drawable.ic_volume_up_24dp)

            if (menu.findItem(R.id.action_fetch_ttml) == null) menu.add(Menu.NONE, R.id.action_fetch_ttml, 10, "获取TTML")
            if (menu.findItem(R.id.action_delete_ttml) == null) menu.add(Menu.NONE, R.id.action_delete_ttml, 11, "删除TTML")
            if (menu.findItem(R.id.action_blacklist_video) == null) menu.add(Menu.NONE, R.id.action_blacklist_video, 12, "动封黑名单")
            if (menu.findItem(R.id.action_delete_from_device) == null) menu.add(Menu.NONE, R.id.action_delete_from_device, 13, "删除歌曲及关联文件")

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
                    false 
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

    // ================= 扩展功能方法群 =================
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
                File(hiddenDir, "$audioFileName.mp4").takeIf { !it.exists() }?.mkdirs()

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

    // ================= ExoPlayer 引擎 =================
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

    // ================= 颜色映射注入 =================
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