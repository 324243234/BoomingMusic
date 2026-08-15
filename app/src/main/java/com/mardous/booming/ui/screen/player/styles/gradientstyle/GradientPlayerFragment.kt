package com.mardous.booming.ui.screen.player.styles.gradientstyle

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.viewpager.widget.ViewPager
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.google.android.material.button.MaterialButton
import com.mardous.booming.R
import com.mardous.booming.core.model.action.NowPlayingAction
import com.mardous.booming.core.model.player.*
import com.mardous.booming.core.model.theme.NowPlayingScreen
import com.mardous.booming.databinding.FragmentGradientPlayerBinding
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.media.albumArtistName
import com.mardous.booming.extensions.media.displayArtistName
import com.mardous.booming.extensions.resources.applyColor
import com.mardous.booming.extensions.resources.withAlpha
import com.mardous.booming.extensions.whichFragment
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.AbsPlayerFragment
import com.mardous.booming.ui.screen.player.PlayerGesturesController.GestureType
import com.mardous.booming.util.Preferences
import com.mardous.booming.ui.screen.lyrics.LyricsViewModel
import com.mardous.booming.data.local.repository.LyricsRepository
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.android.ext.android.inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GradientPlayerFragment : AbsPlayerFragment(R.layout.fragment_gradient_player), View.OnClickListener {

    private val sharedPreferences: SharedPreferences by inject()
    private val lyricsViewModel: LyricsViewModel by activityViewModel()
    private val lyricsRepository: LyricsRepository by inject()
    private val repository: com.mardous.booming.data.local.repository.Repository by inject()

    private var _binding: FragmentGradientPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var controlsFragment: GradientPlayerControlsFragment

    private var canvasExoPlayer: ExoPlayer? = null
    private var videoFetchJob: Job? = null
    private var lastProcessedSongId: Long = -1L
    private var isDraggingInlineSlider = false

    private val powerManager by lazy { requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val batteryManager by lazy { requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager }

    // ★ 流光背景动画引用
    private var fluidAnimatorX: ObjectAnimator? = null
    private var fluidAnimatorY: ObjectAnimator? = null

    override val colorSchemeMode: PlayerColorSchemeMode
        get() = Preferences.getNowPlayingColorSchemeMode(NowPlayingScreen.Gradient)

    override val playerControlsFragment: AbsPlayerControlsFragment
        get() = controlsFragment

    private fun isDeviceStressed(): Boolean {
        if (powerManager.isPowerSaveMode) return true
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (batteryLevel <= 20) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return true
        }
        return false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGradientPlayerBinding.bind(view)
        
        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE

        if (isLandscapeOrTablet) {
            val maskView = view.findViewById<View>(R.id.mask)
            val lp = maskView?.layoutParams as? ConstraintLayout.LayoutParams
            lp?.let {
                it.matchConstraintPercentWidth = 0.35f
                it.horizontalBias = 1.0f
                maskView.layoutParams = it
            }
            setupSlidingGhostMode(view)
        } else {
            view.findViewById<View>(R.id.rightLyricsContainer)?.visibility = View.GONE
        }

        view.findViewById<View>(R.id.openQueueButton)?.isVisible = !isLandscapeOrTablet
        view.findViewById<View>(R.id.showLyricsButton)?.isVisible = !isLandscapeOrTablet
        
        view.findViewById<View>(R.id.goToArtistButton)?.isVisible = isLandscapeOrTablet
        view.findViewById<View>(R.id.goToAlbumButton)?.isVisible = isLandscapeOrTablet
        view.findViewById<View>(R.id.toggleLyricsFormatButton)?.isVisible = isLandscapeOrTablet
        view.findViewById<View>(R.id.equalizerButton)?.isVisible = isLandscapeOrTablet

        val coverView = view.findViewById<View>(R.id.playerAlbumCoverFragment)
        val lyricsContainer = view.findViewById<View>(R.id.rightLyricsContainer)
        val playbackControls = view.findViewById<View>(R.id.playbackControlsFragment)
        val bottomAction = view.findViewById<View>(R.id.bottomActionContainer)
        
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val safeInsets = insets.getInsets(Type.systemBars() or Type.displayCutout())

            if (isLandscapeOrTablet) {
                // 左侧封面：全方位躲避刘海及多位置系统工具栏
                val lpCover = coverView?.layoutParams as? ConstraintLayout.LayoutParams
                lpCover?.let {
                    it.topMargin = safeInsets.top       
                    it.bottomMargin = safeInsets.bottom 
                    it.marginStart = safeInsets.left    
                    coverView.layoutParams = it
                }

                // ★ 核心修复：右侧歌词和控制台同步附加顶部 padding，确保和左侧封面高度严丝合缝对齐！
                lyricsContainer?.updatePadding(
                    top = safeInsets.top,
                    bottom = safeInsets.bottom,
                    right = safeInsets.right
                )
                playbackControls?.updatePadding(
                    top = safeInsets.top,
                    right = safeInsets.right
                )
                bottomAction?.updatePadding(
                    bottom = safeInsets.bottom, 
                    right = safeInsets.right
                )
            } else {
                val lpCover = coverView?.layoutParams as? ConstraintLayout.LayoutParams
                lpCover?.let {
                    it.topMargin = 0
                    it.bottomMargin = 0
                    it.marginStart = 0
                    coverView.layoutParams = it
                }
                bottomAction?.updatePadding(bottom = safeInsets.bottom, left = safeInsets.left, right = safeInsets.right)
            }
            insets
        }

        setupListeners() 
        setupVideoPlayer(view)
        setupFluidBackground(view) // ★ 注入苹果级动态流光背景机制
        setupLyricsSyncState()
        setupNewActionButtons(view)
    }

    // ★ 全新底层硬件级 Apple 流光呼吸动画架构（利用 Android 12+ 巨幅高斯模糊与矩阵漂移）
    private fun setupFluidBackground(view: View) {
        val fluidBg = view.findViewById<ImageView>(R.id.fluidBackground) ?: return

        // 利用现代 Android GPU 加速的 RenderEffect 实现性能无损的极致模糊
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fluidBg.setRenderEffect(RenderEffect.createBlurEffect(150f, 150f, Shader.TileMode.MIRROR))
        }

        // 呼吸流动动画 (Lissajous 曲线平滑运动原理)
        fluidAnimatorX = ObjectAnimator.ofFloat(fluidBg, View.SCALE_X, 1.1f, 1.4f).apply {
            duration = 16000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        fluidAnimatorY = ObjectAnimator.ofFloat(fluidBg, View.SCALE_Y, 1.1f, 1.4f).apply {
            duration = 19000L // 特意错开 X 和 Y 的周期，产生极度有机的非线性流动感
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                // 仅在音乐播放且设备未发热降频时激活流动，极致省电
                playerViewModel.isPlayingFlow.collect { isPlaying ->
                    if (isPlaying && !isDeviceStressed()) {
                        if (fluidAnimatorX?.isPaused == true) fluidAnimatorX?.resume() else fluidAnimatorX?.start()
                        if (fluidAnimatorY?.isPaused == true) fluidAnimatorY?.resume() else fluidAnimatorY?.start()
                    } else {
                        fluidAnimatorX?.pause()
                        fluidAnimatorY?.pause()
                    }
                }
            }
            launch {
                // 切歌时原生利用 Coil 提取新封面到底层，并用 1 秒的交叉淡入呈现优雅转场
                playerViewModel.currentSongFlow.collect { song ->
                    if (song != null) {
                        SingletonImageLoader.get(requireContext()).enqueue(
                            ImageRequest.Builder(requireContext())
                                .data(song)
                                .target(fluidBg)
                                .crossfade(1000)
                                .build()
                        )
                    }
                }
            }
        }
    }

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
                    }
                }
                else -> {}
            }
        }
        return super.gestureDetected(gestureType)
    }

    private fun handleCoverClick() {
        val rightLyricsContainer = view?.findViewById<View>(R.id.rightLyricsContainer)
        val playbackControls = view?.findViewById<View>(R.id.playbackControlsFragment)
        val bottomAction = view?.findViewById<View>(R.id.bottomActionContainer)
        
        val willShowLyrics = rightLyricsContainer?.isInvisible != false
        
        rightLyricsContainer?.isInvisible = !willShowLyrics
        playbackControls?.isInvisible = willShowLyrics
        bottomAction?.isInvisible = willShowLyrics
    }

    private fun setupListeners() {
        view?.findViewById<View>(R.id.openQueueButton)?.setOnClickListener(this)
        view?.findViewById<View>(R.id.showLyricsButton)?.setOnClickListener(this)
        view?.findViewById<View>(R.id.soundSettingsButton)?.setOnClickListener(this)
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.openQueueButton -> onQuickActionEvent(NowPlayingAction.OpenPlayQueue)
            R.id.showLyricsButton -> onQuickActionEvent(NowPlayingAction.Lyrics)
            R.id.soundSettingsButton -> onQuickActionEvent(NowPlayingAction.SoundSettings)
        }
    }

    private fun setupNewActionButtons(view: View) {
        view.findViewById<ImageView>(R.id.lyricsNextButton)?.setOnClickListener { playerViewModel.seekToNext() }
        view.findViewById<ImageView>(R.id.lyricsFavoriteButton)?.setOnClickListener {
            try {
                val intent = android.content.Intent(requireContext(), Class.forName("com.mardous.booming.playback.PlaybackService")).apply {
                    action = "com.mardous.booming.action.ACTION_TOGGLE_FAVORITE"
                }
                requireContext().startService(intent)
            } catch (e: Exception) { e.printStackTrace() }
        }

        view.findViewById<View>(R.id.goToArtistButton)?.setOnClickListener { controlsFragment.popupMenu?.menu?.performIdentifierAction(R.id.action_go_to_artist, 0) }
        view.findViewById<View>(R.id.goToAlbumButton)?.setOnClickListener { controlsFragment.popupMenu?.menu?.performIdentifierAction(R.id.action_go_to_album, 0) }
        view.findViewById<View>(R.id.equalizerButton)?.setOnClickListener { controlsFragment.popupMenu?.menu?.performIdentifierAction(R.id.action_equalizer, 0) }
        
        val toggleFormatBtn = view.findViewById<MaterialButton>(R.id.toggleLyricsFormatButton)
        updateFormatIcon(toggleFormatBtn)
        toggleFormatBtn?.setOnClickListener { toggleLyricsFormat(toggleFormatBtn) }
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

    private fun toggleLyricsFormat(btn: MaterialButton?) {
        val currentFormat = sharedPreferences.getString("preferred_lyrics_file_format", "ttml") ?: "ttml"
        val isCurrentlyTtml = currentFormat.equals("ttml", ignoreCase = true) || currentFormat == "0"
        val newFormat = if (isCurrentlyTtml) "lrc" else "ttml"
        lyricsRepository.clearMemoryCache()
        sharedPreferences.edit(commit = true) { putString("preferred_lyrics_file_format", newFormat) }
        context?.let { Toast.makeText(it, if (isCurrentlyTtml) "已切换为 LRC 滚动歌词" else "已切换为 TTML 逐字歌词", Toast.LENGTH_SHORT).show() }
        updateFormatIcon(btn)
        playerViewModel.currentSongFlow.value?.let { lyricsViewModel.updateSong(it) }
    }

    private fun updateFormatIcon(btn: MaterialButton?) {
        val currentFormat = sharedPreferences.getString("preferred_lyrics_file_format", "ttml") ?: "ttml"
        val isCurrentlyTtml = currentFormat.equals("ttml", ignoreCase = true) || currentFormat == "0"
        btn?.setIconResource(if (isCurrentlyTtml) R.drawable.ic_lyrics_24dp else R.drawable.ic_lyrics_outline_24dp)
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

    private fun setupVideoPlayer(view: View) {
        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
        if (!isLandscapeOrTablet) return

        canvasExoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
            playbackParameters = PlaybackParameters(0.85f)
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setMaxVideoSize(854, 480).build()

            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { view.findViewById<PlayerView>(R.id.canvasPlayerView)?.let { if (it.alpha < 1f) it.animate().alpha(1f).setDuration(800).start() } }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        view.findViewById<PlayerView>(R.id.canvasPlayerView)?.animate()?.alpha(0f)?.setDuration(700)?.withEndAction { view.postDelayed({ canvasExoPlayer?.seekTo(0); canvasExoPlayer?.play() }, 1000) }?.start()
                    }
                }
            })
        }
        view.findViewById<PlayerView>(R.id.canvasPlayerView)?.apply { player = canvasExoPlayer; useController = false; setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM) }
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        view?.findViewById<ImageView>(R.id.lyricsFavoriteButton)?.apply {
            tag = isFavorite
            setImageResource(if (isFavorite) R.drawable.ic_favorite_24dp else R.drawable.ic_favorite_outline_24dp)
        }
    }

    private fun setupLyricsSyncState() {
        val inlineSlider = view?.findViewById<SeekBar>(R.id.lyricsInlineProgressSlider)
        inlineSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isDraggingInlineSlider = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { playerViewModel.seekTo(it.toLong()) }
                seekBar?.postDelayed({ isDraggingInlineSlider = false }, 500)
            }
        })

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                playerViewModel.currentSongFlow.collect { song ->
                    if (song != null && song.id != lastProcessedSongId) {
                        val titleText = view?.findViewById<TextView>(R.id.lyricsSongTitleText)
                        titleText?.text = song.title
                        titleText?.let { setMarquee(it, marquee = true) }
                        
                        val artist = if (Preferences.preferAlbumArtistName) song.albumArtistName().displayArtistName() else song.displayArtistName()
                        view?.findViewById<TextView>(R.id.lyricsSongArtistText)?.text = "- $artist"
                        
                        launch(Dispatchers.IO) {
                            val isFav = repository.isSongFavorite(song.id)
                            withContext(Dispatchers.Main) { updateFavoriteIcon(isFav) }
                        }

                        videoFetchJob?.cancel(); canvasExoPlayer?.stop(); canvasExoPlayer?.clearMediaItems()
                        view?.findViewById<PlayerView>(R.id.canvasPlayerView)?.alpha = 0f

                        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
                            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
                        val isVideoEnabled = sharedPreferences.getBoolean("pref_enable_video_cover", true)
                        
                        if (isLandscapeOrTablet && isVideoEnabled && !isDeviceStressed()) {
                            videoFetchJob = launch {
                                delay(400)
                                val videoUri = withContext(Dispatchers.IO) { com.mardous.booming.data.local.lyrics.ttml.AnimatedCanvasFetcher.fetchCanvasUri(requireContext(), song) }
                                if (isActive && !videoUri.isNullOrBlank() && !isDeviceStressed()) {
                                    val recheckEnabled = sharedPreferences.getBoolean("pref_enable_video_cover", true)
                                    if (recheckEnabled) {
                                        withContext(Dispatchers.Main) { canvasExoPlayer?.setMediaItem(MediaItem.fromUri(videoUri)); canvasExoPlayer?.prepare(); canvasExoPlayer?.play() }
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
            
            launch {
                val leftCurrTime = view?.findViewById<TextView>(R.id.lyricsCurrentTime)
                val leftTotTime = view?.findViewById<TextView>(R.id.lyricsTotalTime)
                playerViewModel.progressFlow.sample(60L).collect { progress ->
                    if (!isDraggingInlineSlider) {
                        inlineSlider?.let { slider ->
                            val currentProgress = progress.toInt()
                            val mainSlider = view?.findViewById<com.mardous.booming.ui.component.views.MusicSlider>(R.id.progressSlider)
                            val rightCurrTime = view?.findViewById<TextView>(R.id.songCurrentProgress)
                            val rightTotTime = view?.findViewById<TextView>(R.id.songTotalTime)

                            mainSlider?.let { main ->
                                val max = main.valueTo.toInt()
                                if (slider.max != max) { slider.max = max; leftTotTime?.text = rightTotTime?.text }
                            }
                            slider.progress = currentProgress
                            rightCurrTime?.text?.let { rightText -> if (leftCurrTime != null && leftCurrTime.text != rightText) leftCurrTime.text = rightText }
                        }
                    }
                }
            }
        }
    }

    override fun onIsFavoriteChanged(isFavorite: Boolean, withAnimation: Boolean) {
        controlsFragment.setFavorite(isFavorite, withAnimation)
        updateFavoriteIcon(isFavorite)
    }

    override fun onMenuInflated(menu: Menu) {
        super.onMenuInflated(menu)
        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE

        val toggleItem = menu.findItem(R.id.action_toggle_lyrics_format)
        toggleItem?.setOnMenuItemClickListener { toggleLyricsFormat(null); true }

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
                        } else { Toast.makeText(context, "获取失败：全网未找到该歌曲的逐字歌词", Toast.LENGTH_SHORT).show() }
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
            menu.removeItem(R.id.action_sound_settings)
            menu.removeItem(R.id.action_favorite)
        } else {
            menu.removeItem(R.id.action_playing_queue)
            menu.removeItem(R.id.action_show_lyrics)
            menu.removeItem(R.id.action_sound_settings)
            menu.removeItem(R.id.action_favorite)
        }
    }

    override fun onCreateChildFragments() {
        super.onCreateChildFragments()
        controlsFragment = whichFragment(R.id.playbackControlsFragment)
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldMaskColor = binding.mask.backgroundTintList?.defaultColor ?: Color.TRANSPARENT
        val oldPrimaryTextColor = view?.findViewById<MaterialButton>(R.id.soundSettingsButton)?.iconTint?.defaultColor ?: Color.WHITE
        
        val lyricsTitle = view?.findViewById<TextView>(R.id.lyricsSongTitleText)
        val lyricsArtist = view?.findViewById<TextView>(R.id.lyricsSongArtistText)
        val lyricsCurrTime = view?.findViewById<TextView>(R.id.lyricsCurrentTime)
        val lyricsTotTime = view?.findViewById<TextView>(R.id.lyricsTotalTime)
        val lyricsSlider = view?.findViewById<SeekBar>(R.id.lyricsInlineProgressSlider)
        
        val lyricsFav = view?.findViewById<ImageView>(R.id.lyricsFavoriteButton)
        val lyricsNext = view?.findViewById<ImageView>(R.id.lyricsNextButton)
        
        val btnQueue = view?.findViewById<MaterialButton>(R.id.openQueueButton)
        val btnShowLyrics = view?.findViewById<MaterialButton>(R.id.showLyricsButton)
        val btnArtist = view?.findViewById<MaterialButton>(R.id.goToArtistButton)
        val btnAlbum = view?.findViewById<MaterialButton>(R.id.goToAlbumButton)
        val btnFormat = view?.findViewById<MaterialButton>(R.id.toggleLyricsFormatButton)
        val btnSound = view?.findViewById<MaterialButton>(R.id.soundSettingsButton)
        val btnEq = view?.findViewById<MaterialButton>(R.id.equalizerButton)
        
        val oldTitleColor = lyricsTitle?.currentTextColor ?: oldPrimaryTextColor
        val oldArtistColor = lyricsArtist?.currentTextColor ?: oldPrimaryTextColor
        
        lyricsSlider?.applyColor(scheme.onSurfaceColor)

        return mutableListOf(
            binding.colorBackground.surfaceTintTarget(scheme.surfaceColor),
            binding.mask.tintTarget(oldMaskColor, scheme.surfaceColor)
        ).also {
            it.addAll(playerControlsFragment.getTintTargets(scheme))
            lyricsTitle?.let { title -> it.add(title.tintTarget(oldTitleColor, scheme.onSurfaceColor)) }
            lyricsArtist?.let { artist -> it.add(artist.tintTarget(oldArtistColor, scheme.onSurfaceColor.withAlpha(0.7f))) }
            lyricsCurrTime?.let { curr -> it.add(curr.tintTarget(curr.currentTextColor, scheme.onSurfaceColor.withAlpha(0.6f))) }
            lyricsTotTime?.let { tot -> it.add(tot.tintTarget(tot.currentTextColor, scheme.onSurfaceColor.withAlpha(0.6f))) }
            
            lyricsFav?.let { fav -> it.add(fav.tintTarget(fav.imageTintList?.defaultColor ?: oldPrimaryTextColor, scheme.onSurfaceColor)) }
            lyricsNext?.let { next -> it.add(next.tintTarget(next.imageTintList?.defaultColor ?: oldPrimaryTextColor, scheme.onSurfaceColor)) }
            
            btnQueue?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> it.add(t) }
            btnShowLyrics?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> it.add(t) }
            btnArtist?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> it.add(t) }
            btnAlbum?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> it.add(t) }
            btnFormat?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> it.add(t) }
            btnSound?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> it.add(t) }
            btnEq?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> it.add(t) }
        }
    }

    override fun onLyricsVisibilityChange(animatorSet: AnimatorSet, lyricsVisible: Boolean) {
        view?.findViewById<MaterialButton>(R.id.showLyricsButton)?.let {
            it.setIconResource(if (lyricsVisible) R.drawable.ic_lyrics_24dp else R.drawable.ic_lyrics_outline_24dp)
            it.contentDescription = getString(if (lyricsVisible) R.string.action_hide_lyrics else R.string.action_show_lyrics)
        }
        controlsFragment.popupMenu?.menu?.findItem(R.id.action_show_lyrics)?.apply {
            setIcon(if (lyricsVisible) R.drawable.ic_lyrics_24dp else R.drawable.ic_lyrics_outline_24dp)
            title = getString(if (lyricsVisible) R.string.action_hide_lyrics else R.string.action_show_lyrics)
        }
    }

    override fun onDestroyView() {
        fluidAnimatorX?.cancel()
        fluidAnimatorY?.cancel()
        fluidAnimatorX = null
        fluidAnimatorY = null
        videoFetchJob?.cancel()
        canvasExoPlayer?.release()
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() { super.onResume(); if (!isDeviceStressed()) canvasExoPlayer?.play() }
    override fun onPause() { super.onPause(); canvasExoPlayer?.pause() }
}