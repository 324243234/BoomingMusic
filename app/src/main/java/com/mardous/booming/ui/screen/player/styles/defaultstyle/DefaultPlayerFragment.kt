package com.mardous.booming.ui.screen.player.styles.defaultstyle

import android.content.SharedPreferences
import kotlinx.coroutines.flow.sample
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
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

class DefaultPlayerFragment : AbsPlayerFragment(R.layout.fragment_default_player),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var _binding: FragmentDefaultPlayerBinding? = null
    private val binding get() = _binding!!

    private var lastProcessedSongId: Long = -1L
    private var isDraggingInlineSlider = false
    
    private lateinit var controlsFragment: DefaultPlayerControlsFragment

    // 🌟 Koin 注入，用于安全查询数据库
    private val repository: com.mardous.booming.data.local.repository.Repository by inject()

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

        // 🌟 注册收藏按钮点击事件（极速乐观更新）
        binding.rightFavoriteButton?.setOnClickListener {
            val isFav = it.tag as? Boolean ?: false
            updateFavoriteIcon(!isFav)
            try {
                val intent = android.content.Intent(requireContext(), Class.forName("com.mardous.booming.playback.PlaybackService")).apply {
                    action = "com.mardous.booming.action.ACTION_TOGGLE_FAVORITE"
                }
                requireContext().startService(intent)
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 1. 歌曲信息更新机制 & 全局系统级收藏同步监听
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            
            // A. 监听切歌与歌曲信息改变
            launch {
                playerViewModel.currentSongFlow.collect { song ->
                    if (song != null && song.id != lastProcessedSongId) {
                        kotlinx.coroutines.delay(80)
                        
                        binding.rightSongTitleText?.text = song.title
                        binding.rightSongTitleText?.let { setMarquee(it, marquee = true) }

                        val artist = if (Preferences.preferAlbumArtistName) {
                            song.albumArtistName().displayArtistName()
                        } else {
                            song.displayArtistName()
                        }
                        binding.rightSongArtistText?.text = "- $artist"

                        lastProcessedSongId = song.id
                    }

                    // 🌟 切歌时：同步收藏状态
                    if (song != null && song.id != 0L) {
                        launch(Dispatchers.IO) {
                            val isFav = repository.isSongFavorite(song.id)
                            withContext(Dispatchers.Main) {
                                updateFavoriteIcon(isFav)
                            }
                        }
                    }
                }
            }

            // B. 🌟 系统级全局收藏事件监听（接收车机、通知栏传来的状态改变）
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

        // 2. 左侧迷你进度条拖拽控制
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

        // 3. 影子同步机制（使用 60ms 采样，兼顾极致丝滑与超低 CPU 消耗）
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            // 在外面找一次，缓存引用
            val mainSlider = view.findViewById<MusicSlider>(R.id.progressSlider)

            playerViewModel.progressFlow
                .sample(60L) // 💡 控制每 60ms 采样一次（约 16fps），绝对平滑且零浪费
                .collect { progress ->
                    if (!isDraggingInlineSlider) {
                        binding.inlineProgressSlider?.let { slider ->
                            val currentProgress = progress.toInt()

                            mainSlider?.let { 
                                val max = it.valueTo.toInt()
                                if (slider.max != max) slider.max = max
                            }

                            // 💡 移除原本 > 250 的阀门，直接平滑赋值
                            slider.progress = currentProgress
                        }
                    }
                }
        }
    }

    // 🌟 更新红心 UI 函数
    private fun updateFavoriteIcon(isFavorite: Boolean) {
        binding.rightFavoriteButton?.apply {
            tag = isFavorite
            setImageResource(if (isFavorite) R.drawable.ic_favorite_24dp else R.drawable.ic_favorite_outline_24dp)
        }
    }

    // 🌟 拦截动作下发：用于当前界面手势和点击的“乐观更新”，实现零延迟 UI 反馈
    override fun onQuickActionEvent(action: NowPlayingAction) {
        super.onQuickActionEvent(action)
        
        if (action.toString().contains("Favorite", ignoreCase = true)) {
            val isFav = binding.rightFavoriteButton?.tag as? Boolean ?: false
            updateFavoriteIcon(!isFav)
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
        binding.rightSongInfoContainer?.isInvisible = !willShowLyrics
        
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
        
        binding.rightSongTitleText?.let { titleText ->
            targets.add(titleText.tintTarget(titleText.currentTextColor, scheme.onSurfaceColor))
        }
        
        binding.rightSongArtistText?.let { artistText ->
            targets.add(artistText.tintTarget(artistText.currentTextColor, scheme.onSurfaceColor))
        }

        // 🌟 将收藏红心加入变色队列，无缝融合主题
        binding.rightFavoriteButton?.let { favBtn ->
            targets.add(favBtn.tintTarget(favBtn.imageTintList?.defaultColor ?: scheme.onSurfaceColor, scheme.onSurfaceColor))
        }

        targets.addAll(playerControlsFragment.getTintTargets(scheme))
        return targets
    }

    override fun onMenuInflated(menu: Menu) {
        super.onMenuInflated(menu)
        menu.removeItem(R.id.action_sound_settings)
        menu.setShowAsAction(R.id.action_favorite)
        menu.setShowAsAction(R.id.action_show_lyrics)
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