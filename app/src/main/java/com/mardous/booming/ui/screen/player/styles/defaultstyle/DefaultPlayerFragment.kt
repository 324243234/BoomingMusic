package com.mardous.booming.ui.screen.player.styles.defaultstyle

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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.isInvisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.viewpager.widget.ViewPager
import com.mardous.booming.R
import com.mardous.booming.core.model.action.NowPlayingAction
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.player.PlayerColorSchemeMode
import com.mardous.booming.core.model.player.PlayerTintTarget
import com.mardous.booming.core.model.player.surfaceTintTarget
import com.mardous.booming.core.model.player.tintTarget
import com.mardous.booming.core.model.theme.NowPlayingScreen
import com.mardous.booming.data.local.repository.LyricsRepository
import com.mardous.booming.databinding.FragmentDefaultPlayerBinding
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.media.albumArtistName
import com.mardous.booming.extensions.media.displayArtistName
import com.mardous.booming.extensions.resources.applyColor
import com.mardous.booming.extensions.resources.withAlpha
import com.mardous.booming.extensions.whichFragment
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.AbsPlayerFragment
import com.mardous.booming.ui.component.views.MusicSlider
import com.mardous.booming.ui.screen.lyrics.LyricsViewModel
import com.mardous.booming.ui.screen.player.PlayerGesturesController.GestureType
import com.mardous.booming.util.DISPLAY_NEXT_SONG
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.io.File

class DefaultPlayerFragment : AbsPlayerFragment(R.layout.fragment_default_player),
    SharedPreferences.OnSharedPreferenceChangeListener {
    
    private val sharedPreferences: SharedPreferences by inject()
    private val lyricsViewModel: LyricsViewModel by activityViewModel()
    
    private val repository: com.mardous.booming.data.local.repository.Repository by inject()
    private val lyricsRepository: LyricsRepository by inject()

    private var _binding: FragmentDefaultPlayerBinding? = null
    private val binding get() = _binding!!

    private var lastProcessedSongId: Long = -1L
    private var isDraggingInlineSlider = false
    
    private lateinit var controlsFragment: DefaultPlayerControlsFragment

    private var canvasExoPlayer: ExoPlayer? = null
    private var videoFetchJob: Job? = null
    
    private val powerManager by lazy { requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val batteryManager by lazy { requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager }

    private fun isDeviceStressed(): Boolean {
        if (powerManager.isPowerSaveMode) return true
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (batteryLevel <= 20) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return true
        }
        return false
    }

    override val playerControlsFragment: AbsPlayerControlsFragment get() = controlsFragment
    override val colorSchemeMode: PlayerColorSchemeMode get() = Preferences.getNowPlayingColorSchemeMode(NowPlayingScreen.Default)
    override val playerToolbar: Toolbar get() = binding.toolbar
    private var primaryControlColor: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDefaultPlayerBinding.bind(view)
        
        setupToolbar()
        inflateMenuInView(playerToolbar)
        
        ViewCompat.setOnApplyWindowInsetsListener(view) { v: View, insets: WindowInsetsCompat ->
            val systemBars = insets.getInsets(Type.systemBars())
            v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            val displayCutout = insets.getInsets(Type.displayCutout())
            v.updatePadding(left = displayCutout.left, right = displayCutout.right)
            WindowInsetsCompat.CONSUMED
        }
        Preferences.registerOnSharedPreferenceChangeListener(this)

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        
        if (isLandscape) {
            setupSlidingGhostMode(view) 
            
            canvasExoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF 
                volume = 0f 
                playbackParameters = PlaybackParameters(0.85f) 
                
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setMaxVideoSize(854, 480)
                    .build()
                    
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        val playerView = view.findViewById<PlayerView>(R.id.canvasPlayerView)
                        if (playerView?.alpha ?: 1f < 1f) {
                            playerView?.animate()?.alpha(1f)?.setDuration(800)?.start()
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            val playerView = view.findViewById<PlayerView>(R.id.canvasPlayerView)
                            playerView?.animate()?.alpha(0f)?.setDuration(700)?.withEndAction {
                                playerView.postDelayed({
                                    canvasExoPlayer?.seekTo(0)
                                    canvasExoPlayer?.play()
                                }, 1000) 
                            }?.start()
                        }
                    }
                })
            }
            
            view.findViewById<PlayerView>(R.id.canvasPlayerView)?.apply {
                player = canvasExoPlayer
                useController = false 
                setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM) 
                
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                isLongClickable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setOnTouchListener { _, _ -> false }
            }
        }

        binding.leftFavoriteButton?.setOnClickListener {
            val isFav = it.tag as? Boolean ?: false
            updateFavoriteIcon(!isFav)
            try {
                val intent = android.content.Intent(requireContext(), Class.forName("com.mardous.booming.playback.PlaybackService")).apply {
                    action = "com.mardous.booming.action.ACTION_TOGGLE_FAVORITE"
                }
                requireContext().startService(intent)
            } catch (e: Exception) { e.printStackTrace() }
        }
        binding.leftNextButton?.setOnClickListener { playerViewModel.seekToNext() }

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                playerViewModel.currentSongFlow.collect { song ->
                    if (song != null && song.id != lastProcessedSongId) {
                        
                        videoFetchJob?.cancel() 
                        canvasExoPlayer?.stop() 
                        canvasExoPlayer?.clearMediaItems() 
                        
                        val playerView = view.findViewById<PlayerView>(R.id.canvasPlayerView)
                        playerView?.animate()?.cancel()
                        playerView?.alpha = 0f

                        binding.leftSongTitleText?.text = song.title
                        binding.leftSongTitleText?.let { setMarquee(it, marquee = true) }

                        val artist = if (Preferences.preferAlbumArtistName) song.albumArtistName().displayArtistName() else song.displayArtistName()
                        binding.leftSongArtistText?.text = "- $artist"

                        launch(Dispatchers.IO) {
                            val isFav = repository.isSongFavorite(song.id)
                            withContext(Dispatchers.Main) { updateFavoriteIcon(isFav) }
                        }

                        if (isLandscape) {
                            val isVideoEnabled = sharedPreferences.getBoolean("pref_enable_video_cover", true)

                            if (isVideoEnabled && !isDeviceStressed()) {
                                videoFetchJob = launch { 
                                    delay(400) 
                                    // 🌟 修复：传入 requireContext()
                                    val videoUri = withContext(Dispatchers.IO) {
                                        com.mardous.booming.data.local.lyrics.ttml.AnimatedCanvasFetcher.fetchCanvasUri(requireContext(), song)
                                    }

                                    if (isActive && !videoUri.isNullOrBlank() && !isDeviceStressed()) {
                                        val recheckEnabled = sharedPreferences.getBoolean("pref_enable_video_cover", true)
                                        if (recheckEnabled) {
                                            withContext(Dispatchers.Main) {
                                                canvasExoPlayer?.setMediaItem(MediaItem.fromUri(videoUri))
                                                canvasExoPlayer?.prepare()
                                                canvasExoPlayer?.play()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        lastProcessedSongId = song.id
                    }
                }
            }

            launch {
                playerViewModel.mediaEvent.collect { event ->
                    if (event == com.mardous.booming.core.model.MediaEvent.FavoriteContentChanged) {
                        val currentSong = playerViewModel.currentSongFlow.value
                        if (currentSong != null && currentSong.id != 0L) {
                            launch(Dispatchers.IO) {
                                val isFav = repository.isSongFavorite(currentSong.id)
                                withContext(Dispatchers.Main) { updateFavoriteIcon(isFav) }
                            }
                        }
                    }
                }
            }
        }

        binding.inlineProgressSlider?.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false 
        }

        binding.inlineProgressSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isDraggingInlineSlider = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { playerViewModel.seekTo(it.toLong()) }
                seekBar?.postDelayed({ isDraggingInlineSlider = false }, 500)
            }
        })

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            val mainSlider = view.findViewById<MusicSlider>(R.id.progressSlider)
            val rightCurrTime = view.findViewById<TextView>(R.id.songCurrentProgress)
            val rightTotTime = view.findViewById<TextView>(R.id.songTotalTime)
            val leftCurrTime = view.findViewById<TextView>(R.id.leftCurrentTime)
            val leftTotTime = view.findViewById<TextView>(R.id.leftTotalTime)
            var lastAppliedColor = android.graphics.Color.TRANSPARENT

            playerViewModel.progressFlow.sample(60L).collect { progress ->
                if (!isDraggingInlineSlider) {
                    binding.inlineProgressSlider?.let { slider ->
                        val currentProgress = progress.toInt()

                        mainSlider?.let { main ->
                            val max = main.valueTo.toInt()
                            if (slider.max != max) {
                                slider.max = max
                                leftTotTime?.text = rightTotTime?.text
                            }
                        
                            val mainColor = main.currentColor
                            if (mainColor != android.graphics.Color.TRANSPARENT && mainColor != lastAppliedColor) {
                                lastAppliedColor = mainColor 
                                slider.applyColor(mainColor)
                                val timeColor = mainColor.withAlpha(0.6f)
                                leftCurrTime?.applyColor(timeColor)
                                leftTotTime?.applyColor(timeColor)
                            }
                        }
                        slider.progress = currentProgress

                        rightCurrTime?.text?.let { rightText ->
                            if (leftCurrTime != null && leftCurrTime.text != rightText) {
                                leftCurrTime.text = rightText
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupSlidingGhostMode(rootView: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            delay(500) 
            val coverFragment = childFragmentManager.findFragmentById(R.id.playerAlbumCoverFragment)
            coverFragment?.view?.let { innerView ->
                val viewPager = findViewPager(innerView)
                viewPager?.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                    override fun onPageScrollStateChanged(state: Int) {
                        if (state == ViewPager.SCROLL_STATE_DRAGGING) {
                            val playerView = rootView.findViewById<PlayerView>(R.id.canvasPlayerView)
                            playerView?.animate()?.cancel()
                            playerView?.alpha = 0f
                        } else if (state == ViewPager.SCROLL_STATE_IDLE) {
                            if (canvasExoPlayer?.playbackState == Player.STATE_READY || canvasExoPlayer?.playbackState == Player.STATE_ENDED) {
                                val playerView = rootView.findViewById<PlayerView>(R.id.canvasPlayerView)
                                playerView?.animate()?.alpha(1f)?.setDuration(400)?.start()
                            }
                        }
                    }
                    override fun onPageScrolled(p0: Int, p1: Float, p2: Int) {}
                    override fun onPageSelected(p0: Int) {}
                })
            }
        }
    }

    private fun findViewPager(view: View): ViewPager? {
        if (view is ViewPager) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findViewPager(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        binding.leftFavoriteButton?.apply {
            tag = isFavorite
            setImageResource(if (isFavorite) R.drawable.ic_favorite_24dp else R.drawable.ic_favorite_outline_24dp)
        }
    }

    override fun gestureDetected(gestureType: GestureType): Boolean {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
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
        val willShowLyrics = binding.rightLyricsFragment?.isInvisible != false
        binding.rightLyricsFragment?.isInvisible = !willShowLyrics
        binding.playbackControlsFragment?.isInvisible = willShowLyrics
        binding.toolbar.isInvisible = willShowLyrics
    }

    private fun setupToolbar() {
        playerToolbar.setNavigationOnClickListener { onQuickActionEvent(NowPlayingAction.SoundSettings) }
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldPrimaryControlColor = primaryControlColor
        primaryControlColor = scheme.onSurfaceColor

        val targets = mutableListOf(
            binding.root.surfaceTintTarget(scheme.surfaceColor),
            binding.toolbar.tintTarget(oldPrimaryControlColor, scheme.onSurfaceColor)
        )
        
        binding.leftSongTitleText?.let { targets.add(it.tintTarget(it.currentTextColor, scheme.onSurfaceColor)) }
        binding.leftSongArtistText?.let { targets.add(it.tintTarget(it.currentTextColor, scheme.onSurfaceColor.withAlpha(0.7f))) }
        binding.leftFavoriteButton?.let { targets.add(it.tintTarget(it.imageTintList?.defaultColor ?: scheme.onSurfaceColor, scheme.onSurfaceColor)) }
        binding.leftNextButton?.let { targets.add(it.tintTarget(it.imageTintList?.defaultColor ?: scheme.onSurfaceColor, scheme.onSurfaceColor)) }

        targets.addAll(playerControlsFragment.getTintTargets(scheme))
        return targets
    }
    
    private fun toggleLyricsFormat() {
        val currentFormat = sharedPreferences.getString("preferred_lyrics_file_format", "ttml") ?: "ttml"
        val isCurrentlyTtml = currentFormat.equals("ttml", ignoreCase = true) || currentFormat == "0"
        val newFormat = if (isCurrentlyTtml) "lrc" else "ttml"
        
        // 🌟 【核心修复 1：时序修正】必须先清空内存里的旧格式歌词缓存
        lyricsRepository.clearMemoryCache()
        
        // 🌟 接着修改配置，瞬间触发 PlaybackService，确保它拿到的绝对是新格式
        sharedPreferences.edit(commit = true) { putString("preferred_lyrics_file_format", newFormat) }
        context?.let { Toast.makeText(it, if (isCurrentlyTtml) "已切换为 LRC 滚动歌词" else "已切换为 TTML 逐字歌词", Toast.LENGTH_SHORT).show() }
        playerToolbar.menu?.let { updateMenuTitle(it) }
        
        playerViewModel.currentSongFlow.value?.let { lyricsViewModel.updateSong(it) }
    }
    
    private fun updateMenuTitle(menu: Menu) {
        val currentFormat = sharedPreferences.getString("preferred_lyrics_file_format", "ttml") ?: "ttml"
        val isCurrentlyTtml = currentFormat.equals("ttml", ignoreCase = true) || currentFormat == "0"
        val toggleItem = menu.findItem(R.id.action_toggle_lyrics_format)
        
        if (isCurrentlyTtml) {
            toggleItem?.title = "当前: TTML逐字"
            toggleItem?.setIcon(R.drawable.ic_lyrics_24dp)
        } else {
            toggleItem?.title = "当前: LRC滚动"
            toggleItem?.setIcon(R.drawable.ic_lyrics_outline_24dp)
        }
    }

    private fun deleteAssociatedLyricsFiles(song: com.mardous.booming.data.model.Song, onlyTtml: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val songFile = File(song.data)
                val parentDir = songFile.parentFile ?: return@launch
                
                val possibleNamesLower = listOf(
                    songFile.nameWithoutExtension.lowercase(),
                    "${song.artistName} - ${song.title}".lowercase(),
                    "${song.title} - ${song.artistName}".lowercase()
                ).filter { it.isNotBlank() }
                
                var deletedTtml = false
                var deletedOther = false
                
                val filesInDir = parentDir.listFiles() ?: emptyArray()
                val targetExtensions = if (onlyTtml) listOf("ttml") else listOf("ttml", "lrc", "mp4", "webm") 
                
                for (file in filesInDir) {
                    if (!file.isFile) continue
                    val ext = file.extension.lowercase()
                    if (!targetExtensions.contains(ext)) continue 
                    
                    val fileNameLower = file.nameWithoutExtension.lowercase()
                    
                    if (possibleNamesLower.contains(fileNameLower)) {
                        runCatching { 
                            if (ext == "mp4" || ext == "webm") file.writeBytes(ByteArray(0))
                            else file.writeText("") 
                        } 
                        
                        if (file.delete() || !file.exists() || file.length() == 0L) {
                            if (ext == "ttml") deletedTtml = true
                            else deletedOther = true
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    if (onlyTtml) {
                        val msg = if (deletedTtml) "TTML 歌词文件已彻底删除" else "未找到匹配的本地 TTML 文件"
                        context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
                        if (deletedTtml) {
                            lyricsRepository.clearMemoryCache()
                            lyricsViewModel.updateSong(song)
                        }
                    } else {
                        if (deletedTtml || deletedOther) lyricsRepository.clearMemoryCache()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    context?.let { Toast.makeText(it, "关联文件清理失败，存储读写异常", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    override fun onMenuInflated(menu: Menu) {
        super.onMenuInflated(menu)
        menu.removeItem(R.id.action_sound_settings)
        menu.setShowAsAction(R.id.action_show_lyrics)
        
        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE

        val toggleItem = menu.findItem(R.id.action_toggle_lyrics_format)
        toggleItem?.setOnMenuItemClickListener { toggleLyricsFormat(); true }

        val videoToggleItem = menu.findItem(R.id.action_toggle_video_cover)
        if (isLandscapeOrTablet) {
            videoToggleItem?.isVisible = true
            val isVideoEnabled = sharedPreferences.getBoolean("pref_enable_video_cover", true)
            videoToggleItem?.title = if (isVideoEnabled) "动态封面: 关闭" else "动态封面: 开启"
            
            videoToggleItem?.setOnMenuItemClickListener {
                val newState = !sharedPreferences.getBoolean("pref_enable_video_cover", true)
                sharedPreferences.edit(commit = true) { putBoolean("pref_enable_video_cover", newState) }
                videoToggleItem.title = if (newState) "动态封面: 关闭" else "动态封面: 开启"
                playerViewModel.currentSongFlow.value?.let { lyricsViewModel.updateSong(it) }
                true
            }
        } else {
            videoToggleItem?.isVisible = false
        }

        menu.findItem(R.id.action_fetch_ttml)?.setOnMenuItemClickListener {
            playerViewModel.currentSongFlow.value?.let { currentSong ->
                val toast = Toast.makeText(context, "正在检索并获取逐字 TTML...", Toast.LENGTH_LONG)
                toast.show()

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val ttmlContent = com.mardous.booming.data.local.lyrics.ttml.TtmlFetcher.fetchTtmlForSong(currentSong)
                    withContext(Dispatchers.Main) {
                        toast.cancel()
                        if (!ttmlContent.isNullOrBlank()) {
                            try {
                                val songFile = File(currentSong.data)
                                val parentDir = songFile.parentFile
                                if (parentDir != null && parentDir.exists()) {
                                    File(parentDir, "${songFile.nameWithoutExtension}.ttml").writeText(ttmlContent)
                                    Toast.makeText(context, "获取成功！已保存为 TTML", Toast.LENGTH_SHORT).show()
                                    lyricsRepository.clearMemoryCache()
                                    lyricsViewModel.updateSong(currentSong)
                                }
                            } catch (e: Exception) { Toast.makeText(context, "保存文件失败，请检查读写权限", Toast.LENGTH_SHORT).show() }
                        } else {
                            Toast.makeText(context, "获取失败：全网未找到该歌曲的逐字歌词", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            true 
        }
        
        menu.findItem(R.id.action_delete_ttml)?.setOnMenuItemClickListener {
            playerViewModel.currentSongFlow.value?.let { deleteAssociatedLyricsFiles(it, onlyTtml = true) }
            true 
        }

        menu.findItem(R.id.action_delete_from_device)?.setOnMenuItemClickListener {
            playerViewModel.currentSongFlow.value?.let { deleteAssociatedLyricsFiles(it, onlyTtml = false) }
            false 
        }

        if (isLandscapeOrTablet) {
            menu.findItem(R.id.action_favorite)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.findItem(R.id.action_go_to_album)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.findItem(R.id.action_go_to_artist)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.findItem(R.id.action_equalizer)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.findItem(R.id.action_show_lyrics)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            toggleItem?.apply { isVisible = true; setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        } else {
            menu.findItem(R.id.action_favorite)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.findItem(R.id.action_go_to_album)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.findItem(R.id.action_go_to_artist)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            toggleItem?.isVisible = false
            menu.findItem(R.id.action_show_lyrics)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        updateMenuTitle(menu)
        setupQueueMenuItem(menu)
    }

    override fun onCreateChildFragments() {
        super.onCreateChildFragments()
        controlsFragment = whichFragment(R.id.playbackControlsFragment)
    }

    private fun setupQueueMenuItem(menu: Menu = playerToolbar.menu) {
        menu.findItem(R.id.action_playing_queue)?.let {
            it.isVisible = !Preferences.isShowNextSong
            it.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == DISPLAY_NEXT_SONG) setupQueueMenuItem()
    }

    override fun onResume() {
        super.onResume()
        if (!isDeviceStressed()) canvasExoPlayer?.play()
    }

    override fun onPause() {
        super.onPause()
        canvasExoPlayer?.pause()
    }

    override fun onDestroyView() {
        Preferences.unregisterOnSharedPreferenceChangeListener(this)
        videoFetchJob?.cancel()
        videoFetchJob = null
        canvasExoPlayer?.release()
        canvasExoPlayer = null
        super.onDestroyView()
        _binding = null
    }
}