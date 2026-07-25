package com.mardous.booming.ui.screen.player.styles.defaultstyle

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import kotlinx.coroutines.isActive
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
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

class DefaultPlayerFragment : AbsPlayerFragment(R.layout.fragment_default_player),
    SharedPreferences.OnSharedPreferenceChangeListener {

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

        // 1. 歌曲信息更新机制 (恢复完美的过滤专辑名逻辑 + 极致防抖)
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            playerViewModel.currentSongFlow.collect { song ->
                if (song != null) {
                    // 🛡️ 性能底线：仅在切歌时执行唯一一次文本渲染！
                    if (song.id != lastProcessedSongId) {
                        val titleText = view.findViewById<TextView>(R.id.rightSongTitleText)
                        val artistText = view.findViewById<TextView>(R.id.rightSongArtistText)
                        
                        titleText?.text = song.title
                        setMarquee(titleText, marquee = true)

                        // 🔑 恢复之前的成品逻辑：直接获取纯粹的艺术家名字，绝不夹杂专辑名！
                        val artist = if (Preferences.preferAlbumArtistName) {
                            song.albumArtistName().displayArtistName()
                        } else {
                            song.displayArtistName()
                        }
                        artistText?.text = "- $artist"

                        lastProcessedSongId = song.id
                    }
                }
            }
        }

        // 2. 左侧迷你进度条拖拽控制（极致流畅与防回弹优化）
        val inlineProgressBar = view.findViewById<SeekBar>(R.id.inlineProgressSlider)
        
        inlineProgressBar?.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false 
        }

        inlineProgressBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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

        // 3. 影子同步机制（极致精简版）
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            while (isActive) {
                val mainSlider = view.findViewById<MusicSlider>(R.id.progressSlider)
                
                if (inlineProgressBar != null && mainSlider != null && !isDraggingInlineSlider) {
                    inlineProgressBar.max = mainSlider.valueTo.toInt()
                    val currentProgress = mainSlider.value.toInt()
                    
                    if (inlineProgressBar.progress != currentProgress) {
                        inlineProgressBar.progress = currentProgress
                    }
                }
                kotlinx.coroutines.delay(500)
            }
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
        val rightLyrics = view?.findViewById<View>(R.id.rightLyricsFragment)
        val rightControls = view?.findViewById<View>(R.id.playbackControlsFragment)
        val toolbar = view?.findViewById<View>(R.id.toolbar)
        val rightSongInfoContainer = view?.findViewById<View>(R.id.rightSongInfoContainer)
        
        val isLyricsCurrentlyVisible = rightLyrics?.isVisible == true
        val willShowLyrics = !isLyricsCurrentlyVisible
        
        rightLyrics?.isVisible = willShowLyrics
        rightSongInfoContainer?.isVisible = willShowLyrics
        
        rightControls?.isVisible = !willShowLyrics
        toolbar?.isVisible = !willShowLyrics
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
        
        // 🔑 取色性能极致优化：直接“白嫖”源作者已经算好的调色板 (scheme)！
        // 这里的 scheme 携带的 onSurfaceColor，正是原版控制台用来给歌曲信息染色的色值。
        val titleText = view?.findViewById<TextView>(R.id.rightSongTitleText)
        val artistText = view?.findViewById<TextView>(R.id.rightSongArtistText)
        
        if (titleText != null) {
            targets.add(titleText.tintTarget(titleText.currentTextColor, scheme.onSurfaceColor))
        }
        
        if (artistText != null) {
            targets.add(artistText.tintTarget(artistText.currentTextColor, scheme.onSurfaceColor))
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