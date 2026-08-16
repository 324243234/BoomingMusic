package com.mardous.booming.ui.screen.player.styles.gradientstyle

import android.animation.AnimatorSet
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.core.view.ViewCompat
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
import com.mardous.booming.data.repository.LyricsRepository
import com.mardous.booming.ui.component.views.MusicSlider
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
    private val repository: com.mardous.booming.data.repository.Repository by inject()

    private var _binding: FragmentGradientPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var controlsFragment: GradientPlayerControlsFragment

    private var canvasExoPlayer: ExoPlayer? = null
    private var videoFetchJob: Job? = null
    private var lastProcessedSongId: Long = -1L
    private var isDraggingInlineSlider = false

    private val powerManager by lazy { requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val batteryManager by lazy { requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager }

    override val colorSchemeMode: PlayerColorSchemeMode
        get() = Preferences.getNowPlayingColorSchemeMode(NowPlayingScreen.Gradient)

    override val playerControlsFragment: AbsPlayerControlsFragment
        get() = controlsFragment

    override fun onIsFavoriteChanged(isFavorite: Boolean, withAnimation: Boolean) {
        controlsFragment.setFavorite(isFavorite, withAnimation)
        updateFavoriteIcon(isFavorite)
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        binding.lyricsFavoriteButton?.apply {
            tag = isFavorite
            setImageResource(if (isFavorite) R.drawable.ic_favorite_24dp else R.drawable.ic_favorite_outline_24dp)
        }
    }

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
            val lp = binding.mask?.layoutParams as? ConstraintLayout.LayoutParams
            lp?.let {
                it.matchConstraintPercentWidth = 0.30f
                it.horizontalBias = 1.0f
                binding.mask?.layoutParams = it
            }
            setupSlidingGhostMode()
        } else {
            binding.rightLyricsContainer?.visibility = View.GONE
        }

        binding.openQueueButton?.visibility = if (!isLandscapeOrTablet) View.VISIBLE else View.GONE
        binding.showLyricsButton?.visibility = if (!isLandscapeOrTablet) View.VISIBLE else View.GONE
        
        binding.goToArtistButton?.visibility = if (isLandscapeOrTablet) View.VISIBLE else View.GONE
        binding.goToAlbumButton?.visibility = if (isLandscapeOrTablet) View.VISIBLE else View.GONE
        binding.toggleLyricsFormatButton?.visibility = if (isLandscapeOrTablet) View.VISIBLE else View.GONE
        binding.equalizerButton?.visibility = if (isLandscapeOrTablet) View.VISIBLE else View.GONE

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val safeInsets = insets.getInsets(Type.systemBars() or Type.displayCutout())

            if (isLandscapeOrTablet) {
                binding.playerAlbumCoverFragment?.let { cover ->
                    val lpCover = cover.layoutParams as? ConstraintLayout.LayoutParams
                    lpCover?.let {
                        it.topMargin = safeInsets.top        
                        it.bottomMargin = safeInsets.bottom 
                        it.marginStart = safeInsets.left    
                        cover.layoutParams = it
                    }
                }

                binding.rightLyricsContainer?.let { lyrics ->
                    val lpLyrics = lyrics.layoutParams as? ConstraintLayout.LayoutParams
                    lpLyrics?.let {
                        it.topMargin = safeInsets.top
                        it.bottomMargin = safeInsets.bottom
                        it.marginEnd = safeInsets.right
                        lyrics.layoutParams = it
                    }
                }

                binding.playbackControlsFragment?.let { controls ->
                    val lpControls = controls.layoutParams as? ConstraintLayout.LayoutParams
                    lpControls?.let {
                        it.topMargin = safeInsets.top
                        it.marginEnd = safeInsets.right
                        controls.layoutParams = it
                    }
                }

                binding.bottomActionContainer?.updatePadding(bottom = safeInsets.bottom, right = safeInsets.right)
            } else {
                binding.playerAlbumCoverFragment?.let { cover ->
                    val lpCover = cover.layoutParams as? ConstraintLayout.LayoutParams
                    lpCover?.let {
                        it.topMargin = 0
                        it.bottomMargin = 0
                        it.marginStart = 0
                        cover.layoutParams = it
                    }
                }
                binding.rightLyricsContainer?.let { lyrics ->
                    val lpLyrics = lyrics.layoutParams as? ConstraintLayout.LayoutParams
                    lpLyrics?.let {
                        it.topMargin = 0
                        it.bottomMargin = 0
                        it.marginEnd = 0
                        lyrics.layoutParams = it
                    }
                }
                binding.playbackControlsFragment?.let { controls ->
                    val lpControls = controls.layoutParams as? ConstraintLayout.LayoutParams
                    lpControls?.let {
                        it.topMargin = 0
                        it.marginEnd = 0
                        controls.layoutParams = it
                    }
                }
                
                binding.bottomActionContainer?.updatePadding(bottom = safeInsets.bottom, left = safeInsets.left, right = safeInsets.right)
            }
            insets
        }

        setupListeners() 
        setupVideoPlayer()
        setupLyricsSyncState()
        setupNewActionButtons()
        controlsFragment = whichFragment(R.id.playbackControlsFragment)
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
        binding.playbackControlsFragment?.visibility = if (willShowLyrics) View.INVISIBLE else View.VISIBLE
        binding.bottomActionContainer?.visibility = if (willShowLyrics) View.INVISIBLE else View.VISIBLE
    }

    private fun setupListeners() {
        binding.openQueueButton?.setOnClickListener(this)
        binding.showLyricsButton?.setOnClickListener(this)
        binding.soundSettingsButton?.setOnClickListener(this)
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.openQueueButton -> onQuickActionEvent(NowPlayingAction.OpenPlayQueue)
            R.id.showLyricsButton -> onQuickActionEvent(NowPlayingAction.Lyrics)
            R.id.soundSettingsButton -> onQuickActionEvent(NowPlayingAction.SoundSettings)
        }
    }

    private fun setupNewActionButtons() {
        binding.lyricsNextButton?.setOnClickListener { playerViewModel.seekToNext() }
        
        // ★ 核心修复：直接使用原作者自带的 toggleFavorite() 接口
        binding.lyricsFavoriteButton?.setOnClickListener {
            // 1. 乐观更新（按钮瞬间变色，不卡顿）
            val isFav = it.tag as? Boolean ?: false
            updateFavoriteIcon(!isFav)
            
            // 2. 官方通信：通知 Service 写数据库并广播全局状态
            playerViewModel.toggleFavorite() 
        }

        binding.goToArtistButton?.setOnClickListener { controlsFragment.popupMenu?.menu?.performIdentifierAction(R.id.action_go_to_artist, 0) }
        binding.goToAlbumButton?.setOnClickListener { controlsFragment.popupMenu?.menu?.performIdentifierAction(R.id.action_go_to_album, 0) }
        binding.equalizerButton?.setOnClickListener { controlsFragment.popupMenu?.menu?.performIdentifierAction(R.id.action_equalizer, 0) }
        
        val toggleFormatBtn = binding.toggleLyricsFormatButton
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

    private fun setupSlidingGhostMode() {
        viewLifecycleOwner.lifecycleScope.launch {
            delay(500) 
            val coverFragment = childFragmentManager.findFragmentById(R.id.playerAlbumCoverFragment)
            coverFragment?.view?.let { innerView ->
                val viewPager = findViewPager(innerView)
                viewPager?.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                    override fun onPageScrollStateChanged(state: Int) {
                        if (state == ViewPager.SCROLL_STATE_DRAGGING) {
                            binding.canvasPlayerView?.animate()?.cancel()
                            binding.canvasPlayerView?.alpha = 0f
                        } else if (state == ViewPager.SCROLL_STATE_IDLE) {
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

    private fun setupLyricsSyncState() {
        binding.lyricsInlineProgressSlider?.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false 
        }

        binding.lyricsInlineProgressSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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
                        binding.lyricsSongTitleText?.text = song.title
                        binding.lyricsSongTitleText?.let { setMarquee(it, marquee = true) }
                        
                        val artist = if (Preferences.preferAlbumArtistName) song.albumArtistName().displayArtistName() else song.displayArtistName()
                        binding.lyricsSongArtistText?.text = "- $artist"
                        
                        launch(Dispatchers.IO) {
                            val isFav = repository.isSongFavorite(song.id)
                            withContext(Dispatchers.Main) { updateFavoriteIcon(isFav) }
                        }

                        videoFetchJob?.cancel(); canvasExoPlayer?.stop(); canvasExoPlayer?.clearMediaItems()
                        binding.canvasPlayerView?.alpha = 0f

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
                playerViewModel.progressFlow.sample(60L).collect { progress ->
                    if (!isDraggingInlineSlider) {
                        val slider = binding.lyricsInlineProgressSlider
                        val currentProgress = progress.toInt()
                        
                        val mainSlider = binding.root.findViewById<MusicSlider>(R.id.progressSlider)
                        val rightCurrTime = binding.root.findViewById<TextView>(R.id.songCurrentProgress)
                        val rightTotTime = binding.root.findViewById<TextView>(R.id.songTotalTime)

                        mainSlider?.let { main ->
                            val max = main.valueTo.toInt()
                            if (slider?.max != max) { 
                                slider?.max = max
                                binding.lyricsTotalTime?.text = rightTotTime?.text 
                            }
                        }
                        slider?.progress = currentProgress
                        rightCurrTime?.text?.let { rightText -> 
                            if (binding.lyricsCurrentTime?.text != rightText) {
                                binding.lyricsCurrentTime?.text = rightText
                            } 
                        }
                    }
                }
            }
        }
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
        val oldMaskColor = binding.mask?.backgroundTintList?.defaultColor ?: Color.TRANSPARENT
        val oldPrimaryTextColor = binding.soundSettingsButton?.iconTint?.defaultColor ?: Color.WHITE
        
        binding.lyricsInlineProgressSlider?.applyColor(scheme.onSurfaceColor)

        val targets = mutableListOf<PlayerTintTarget>()
        binding.colorBackground?.let { targets.add(it.surfaceTintTarget(scheme.surfaceColor)) }
        binding.mask?.let { targets.add(it.tintTarget(oldMaskColor, scheme.surfaceColor)) }
        
        targets.addAll(playerControlsFragment.getTintTargets(scheme))
        
        binding.lyricsSongTitleText?.let { targets.add(it.tintTarget(it.currentTextColor, scheme.onSurfaceColor)) }
        binding.lyricsSongArtistText?.let { targets.add(it.tintTarget(it.currentTextColor, scheme.onSurfaceColor.withAlpha(0.7f))) }
        binding.lyricsCurrentTime?.let { targets.add(it.tintTarget(it.currentTextColor, scheme.onSurfaceColor.withAlpha(0.6f))) }
        binding.lyricsTotalTime?.let { targets.add(it.tintTarget(it.currentTextColor, scheme.onSurfaceColor.withAlpha(0.6f))) }
        
        binding.lyricsFavoriteButton?.let { targets.add(it.tintTarget(it.imageTintList?.defaultColor ?: oldPrimaryTextColor, scheme.onSurfaceColor)) }
        binding.lyricsNextButton?.let { targets.add(it.tintTarget(it.imageTintList?.defaultColor ?: oldPrimaryTextColor, scheme.onSurfaceColor)) }
        
        binding.openQueueButton?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> targets.add(t) }
        binding.showLyricsButton?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> targets.add(t) }
        binding.goToArtistButton?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> targets.add(t) }
        binding.goToAlbumButton?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> targets.add(t) }
        binding.toggleLyricsFormatButton?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> targets.add(t) }
        binding.soundSettingsButton?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> targets.add(t) }
        binding.equalizerButton?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)?.let { t -> targets.add(t) }
        
        return targets
    }

    override fun onLyricsVisibilityChange(animatorSet: AnimatorSet, lyricsVisible: Boolean) {
        binding.showLyricsButton?.let {
            it.setIconResource(if (lyricsVisible) R.drawable.ic_lyrics_24dp else R.drawable.ic_lyrics_outline_24dp)
            it.contentDescription = getString(if (lyricsVisible) R.string.action_hide_lyrics else R.string.action_show_lyrics)
        }
        controlsFragment.popupMenu?.menu?.findItem(R.id.action_show_lyrics)?.apply {
            setIcon(if (lyricsVisible) R.drawable.ic_lyrics_24dp else R.drawable.ic_lyrics_outline_24dp)
            title = getString(if (lyricsVisible) R.string.action_hide_lyrics else R.string.action_show_lyrics)
        }
    }

    override fun onDestroyView() {
        videoFetchJob?.cancel()
        canvasExoPlayer?.release()
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() { super.onResume(); if (!isDeviceStressed()) canvasExoPlayer?.play() }
    override fun onPause() { super.onPause(); canvasExoPlayer?.pause() }
}