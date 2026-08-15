package com.mardous.booming.ui.screen.player.styles.gradientstyle

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
import com.mardous.booming.databinding.FragmentGradientPlayerBinding
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

class GradientPlayerFragment : AbsPlayerFragment(R.layout.fragment_gradient_player),
    SharedPreferences.OnSharedPreferenceChangeListener {
    
    private val sharedPreferences: SharedPreferences by inject()
    private val lyricsViewModel: LyricsViewModel by activityViewModel()
    
    private val repository: com.mardous.booming.data.local.repository.Repository by inject()
    private val lyricsRepository: LyricsRepository by inject()

    private var _binding: FragmentGradientPlayerBinding? = null
    private val binding get() = _binding!!

    private var lastProcessedSongId: Long = -1L
    private var isDraggingInlineSlider = false
    
    private lateinit var controlsFragment: GradientPlayerControlsFragment

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
    override val colorSchemeMode: PlayerColorSchemeMode get() = Preferences.getNowPlayingColorSchemeMode(NowPlayingScreen.Gradient)
    
    // 🌟 1. 复刻 Default：直接让基类控制收藏按钮的联动与数据库同步
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGradientPlayerBinding.bind(view)
        
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

        // 🌟 2. 复刻 Default：直接绑定收藏按钮点击事件，调用控制台的 toggleFavorite
        binding.lyricsFavoriteButton?.setOnClickListener {
            controlsFragment.toggleFavorite()
        }
        binding.lyricsNextButton?.setOnClickListener { playerViewModel.seekToNext() }

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

                        binding.lyricsSongTitleText?.text = song.title
                        binding.lyricsSongTitleText?.let { setMarquee(it, marquee = true) }

                        val artist = if (Preferences.preferAlbumArtistName) song.albumArtistName().displayArtistName() else song.displayArtistName()
                        binding.lyricsSongArtistText?.text = "- $artist"

                        launch(Dispatchers.IO) {
                            val isFav = repository.isSongFavorite(song.id)
                            withContext(Dispatchers.Main) { updateFavoriteIcon(isFav) }
                        }

                        if (isLandscape) {
                            val isVideoEnabled = sharedPreferences.getBoolean("pref_enable_video_cover", true)

                            if (isVideoEnabled && !isDeviceStressed()) {
                                videoFetchJob = launch { 
                                    delay(400) 
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
                            lastProcessedSongId = song.id
                        }
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
            val mainSlider = view.findViewById<MusicSlider>(R.id.progressSlider)
            val rightCurrTime = view.findViewById<TextView>(R.id.songCurrentProgress)
            val rightTotTime = view.findViewById<TextView>(R.id.songTotalTime)
            val leftCurrTime = view.findViewById<TextView>(R.id.lyricsCurrentTime)
            val leftTotTime = view.findViewById<TextView>(R.id.lyricsTotalTime)
            var lastAppliedColor = android.graphics.Color.TRANSPARENT

            playerViewModel.progressFlow.sample(60L).collect { progress ->
                if (!isDraggingInlineSlider) {
                    binding.lyricsInlineProgressSlider?.let { slider ->
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
        binding.bottomActionContainer?.isInvisible = willShowLyrics
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val targets = mutableListOf(
            binding.colorBackground.surfaceTintTarget(scheme.surfaceColor)
        )
        
        binding.lyricsSongTitleText?.let { targets.add(it.tintTarget(it.currentTextColor, scheme.onSurfaceColor)) }
        binding.lyricsSongArtistText?.let { targets.add(it.tintTarget(it.currentTextColor, scheme.onSurfaceColor.withAlpha(0.7f))) }
        binding.lyricsFavoriteButton?.let { targets.add(it.tintTarget(it.imageTintList?.defaultColor ?: scheme.onSurfaceColor, scheme.onSurfaceColor)) }
        binding.lyricsNextButton?.let { targets.add(it.tintTarget(it.imageTintList?.defaultColor ?: scheme.onSurfaceColor, scheme.onSurfaceColor)) }

        targets.addAll(playerControlsFragment.getTintTargets(scheme))
        return targets
    }

    override fun onCreateChildFragments() {
        super.onCreateChildFragments()
        controlsFragment = whichFragment(R.id.playbackControlsFragment)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {}

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