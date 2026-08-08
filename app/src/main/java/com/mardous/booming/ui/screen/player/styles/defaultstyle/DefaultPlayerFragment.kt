package com.mardous.booming.ui.screen.player.styles.defaultstyle

import org.koin.androidx.viewmodel.ext.android.activityViewModel
import androidx.core.content.edit
import android.widget.Toast
import com.mardous.booming.ui.screen.lyrics.LyricsViewModel
import com.mardous.booming.extensions.resources.withAlpha
import com.mardous.booming.extensions.resources.applyColor
import android.content.SharedPreferences
import kotlinx.coroutines.flow.sample
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.updatePadding
import androidx.core.view.isInvisible
import com.mardous.booming.R
import com.mardous.booming.core.model.action.NowPlayingAction
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.player.PlayerColorSchemeMode
import com.mardous.booming.core.model.player.PlayerTintTarget
import com.mardous.booming.core.model.player.surfaceTintTarget
import com.mardous.booming.core.model.player.tintTarget
import com.mardous.booming.core.model.theme.NowPlayingScreen
import com.mardous.booming.databinding.FragmentDefaultPlayerBinding
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.media.albumArtistName
import com.mardous.booming.extensions.media.displayArtistName
import com.mardous.booming.extensions.whichFragment
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.AbsPlayerFragment
import com.mardous.booming.ui.component.views.MusicSlider
import com.mardous.booming.ui.screen.player.PlayerGesturesController.GestureType
import com.mardous.booming.util.DISPLAY_NEXT_SONG
import com.mardous.booming.util.Preferences
import org.koin.android.ext.android.inject
// 🌟 引入底层的歌词数据仓库，用于手动强杀缓存
import com.mardous.booming.data.local.repository.LyricsRepository

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

    override val playerControlsFragment: AbsPlayerControlsFragment
        get() = controlsFragment

    override val colorSchemeMode: PlayerColorSchemeMode
        get() = Preferences.getNowPlayingColorSchemeMode(NowPlayingScreen.Default)

    override val playerToolbar: Toolbar
        get() = binding.toolbar

    override val blurView: ImageView
        get() = binding.blur

    private var primaryControlColor: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDefaultPlayerBinding.bind(view)
        
        // 🌟 核心修正：绝对不在这里覆写 Toolbar 监听器，保障基类事件分发健康
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
        
        binding.leftNextButton?.setOnClickListener {
            playerViewModel.seekToNext()
        }

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                playerViewModel.currentSongFlow.collect { song ->
                    if (song != null && song.id != lastProcessedSongId) {
                        kotlinx.coroutines.delay(80)
                        
                        binding.leftSongTitleText?.text = song.title
                        binding.leftSongTitleText?.let { setMarquee(it, marquee = true) }

                        val artist = if (Preferences.preferAlbumArtistName) {
                            song.albumArtistName().displayArtistName()
                        } else {
                            song.displayArtistName()
                        }
                        binding.leftSongArtistText?.text = "- $artist"

                        launch(Dispatchers.IO) {
                            val isFav = repository.isSongFavorite(song.id)
                            withContext(Dispatchers.Main) {
                                updateFavoriteIcon(isFav)
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
                                withContext(Dispatchers.Main) {
                                    updateFavoriteIcon(isFav)
                                }
                            }
                        }
                    }
                }
            }
        }

        binding.inlineProgressSlider?.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false 
        }

        binding.inlineProgressSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDraggingInlineSlider = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { progress ->
                    playerViewModel.seekTo(progress.toLong())
                }
                seekBar?.postDelayed({
                    isDraggingInlineSlider = false
                }, 500)
            }
        })

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            val mainSlider = view.findViewById<MusicSlider>(R.id.progressSlider)
            val rightCurrTime = view.findViewById<TextView>(R.id.songCurrentProgress)
            val rightTotTime = view.findViewById<TextView>(R.id.songTotalTime)
            val leftCurrTime = view.findViewById<TextView>(R.id.leftCurrentTime)
            val leftTotTime = view.findViewById<TextView>(R.id.leftTotalTime)

            var lastAppliedColor = android.graphics.Color.TRANSPARENT

            playerViewModel.progressFlow
                .sample(60L) 
                .collect { progress ->
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
                        GestureType.DoubleTap.TYPE_LEFT_EDGE -> {
                            playerViewModel.seekToPrevious()
                            return true
                        }
                        GestureType.DoubleTap.TYPE_RIGHT_EDGE -> {
                            playerViewModel.seekToNext()
                            return true
                        }
                        else -> {}
                    }
                }
                else -> {}
            }
        }
        return super.gestureDetected(gestureType)
    }

    private fun handleCoverClick() {
        val isLyricsCurrentlyVisible = binding.rightLyricsFragment?.isInvisible == false
        val willShowLyrics = !isLyricsCurrentlyVisible
        
        binding.rightLyricsFragment?.isInvisible = !willShowLyrics
        binding.playbackControlsFragment?.isInvisible = willShowLyrics
        binding.toolbar.isInvisible = willShowLyrics
    }

    private fun setupToolbar() {
        playerToolbar.setNavigationOnClickListener {
            onQuickActionEvent(NowPlayingAction.SoundSettings)
        }
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldPrimaryControlColor = primaryControlColor
        primaryControlColor = scheme.onSurfaceColor

        val targets = mutableListOf(
            binding.root.surfaceTintTarget(scheme.surfaceColor),
            binding.toolbar.tintTarget(oldPrimaryControlColor, scheme.onSurfaceColor)
        )
        
        binding.leftSongTitleText?.let { titleText ->
            targets.add(titleText.tintTarget(titleText.currentTextColor, scheme.onSurfaceColor))
        }
        binding.leftSongArtistText?.let { artistText ->
            val secondaryColor = scheme.onSurfaceColor.withAlpha(0.7f) 
            targets.add(artistText.tintTarget(artistText.currentTextColor, secondaryColor))
        }
        binding.leftFavoriteButton?.let { favBtn ->
            targets.add(favBtn.tintTarget(favBtn.imageTintList?.defaultColor ?: scheme.onSurfaceColor, scheme.onSurfaceColor))
        }
        binding.leftNextButton?.let { nextBtn ->
            targets.add(nextBtn.tintTarget(nextBtn.imageTintList?.defaultColor ?: scheme.onSurfaceColor, scheme.onSurfaceColor))
        }

        targets.addAll(playerControlsFragment.getTintTargets(scheme))
        return targets
    }
    
    // 🌟 核心修复：阻断内存竞态，保证读取最新配置并强制清空内存死数据
    private fun toggleLyricsFormat() {
        val currentFormat = sharedPreferences.getString("preferred_lyrics_file_format", "ttml") ?: "ttml"
        val isCurrentlyTtml = currentFormat.equals("ttml", ignoreCase = true) || currentFormat == "0"
        
        val newFormat = if (isCurrentlyTtml) "lrc" else "ttml"
        
        // 1. 同步阻塞式写入，确保立刻落盘
        sharedPreferences.edit(commit = true) {
            putString("preferred_lyrics_file_format", newFormat)
        }
        
        // 2. 视觉反馈
        val toastText = if (isCurrentlyTtml) "已切换为 LRC 滚动歌词" else "已切换为 TTML 逐字歌词"
        context?.let { Toast.makeText(it, toastText, Toast.LENGTH_SHORT).show() }
        playerToolbar.menu?.let { updateMenuTitle(it) }
        
        // 3. 越权手杀：强行干预底层缓存，避免等待 ViewModel 的回调时差
        lyricsRepository.clearMemoryCache()
        
        // 4. 重载当前歌曲数据
        playerViewModel.currentSongFlow.value?.let { currentSong ->
            lyricsViewModel.updateSong(currentSong)
        }
    }
    
    // 🌟 核心修复：严格遵循原项目的“实心/空心”双态图标 Material Design 规范
    private fun updateMenuTitle(menu: Menu) {
        val currentFormat = sharedPreferences.getString("preferred_lyrics_file_format", "ttml") ?: "ttml"
        val isCurrentlyTtml = currentFormat.equals("ttml", ignoreCase = true) || currentFormat == "0"
        
        val toggleItem = menu.findItem(R.id.action_toggle_lyrics_format)
        
        if (isCurrentlyTtml) {
            toggleItem?.title = "当前: TTML逐字"
            // 实心图标 (亮起状态)
            toggleItem?.setIcon(R.drawable.ic_lyrics_24dp)
        } else {
            toggleItem?.title = "当前: LRC滚动"
            // 空心描边图标 (暗淡状态)
            toggleItem?.setIcon(R.drawable.ic_lyrics_outline_24dp)
        }
    }

    override fun onMenuInflated(menu: Menu) {
        super.onMenuInflated(menu)
        menu.removeItem(R.id.action_sound_settings)
        menu.setShowAsAction(R.id.action_show_lyrics)
        
        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE

        // 🌟 核心修正：仅孤立拦截当前切换按钮的事件，其余任何菜单项全部交还系统自动分发
        val toggleItem = menu.findItem(R.id.action_toggle_lyrics_format)
        toggleItem?.setOnMenuItemClickListener {
            toggleLyricsFormat()
            true
        }

        if (isLandscapeOrTablet) {
            menu.findItem(R.id.action_favorite)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.findItem(R.id.action_go_to_album)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.findItem(R.id.action_go_to_artist)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.findItem(R.id.action_equalizer)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            
            menu.findItem(R.id.action_show_lyrics)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            toggleItem?.apply {
                isVisible = true
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
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
        if (key == DISPLAY_NEXT_SONG) {
            setupQueueMenuItem()
        }
    }

    override fun onDestroyView() {
        Preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroyView()
        _binding = null
    }
}