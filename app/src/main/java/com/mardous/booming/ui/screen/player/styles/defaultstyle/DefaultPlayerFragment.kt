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
import androidx.core.view.isInvisible // 🔑 导入 isInvisible 防跳动
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

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            playerViewModel.currentSongFlow.collect { song ->
                val leftInfoText = view.findViewById<TextView>(R.id.leftCoverInfoText)
                if (song != null && leftInfoText != null) {
                    leftInfoText.text = song.title
                    setMarquee(leftInfoText, marquee = true)

                    if (song.id != lastProcessedSongId) {
                        lastProcessedSongId = song.id
                    }
                }
            }
        }

        val inlineProgressBar = view.findViewById<SeekBar>(R.id.inlineProgressSlider)
        inlineProgressBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDraggingInlineSlider = true
                seekBar?.parent?.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isDraggingInlineSlider = false
                seekBar?.parent?.requestDisallowInterceptTouchEvent(false)
                seekBar?.progress?.let { progress ->
                    playerViewModel.seekTo(progress.toLong())
                }
            }
        })

        // 🔑 终极性能优化版：影子同步护盾
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            while (isActive) {
                val mainSlider = view.findViewById<MusicSlider>(R.id.progressSlider)
                
                // 🛡️ 性能防线：只有当进度条真正可见 (View.VISIBLE) 时，才做计算与渲染！完全掐断后台耗电。
                if (inlineProgressBar != null && inlineProgressBar.visibility == View.VISIBLE && mainSlider != null && !isDraggingInlineSlider) {
                    inlineProgressBar.max = mainSlider.max.toInt()
                    if (inlineProgressBar.progress != mainSlider.progress) {
                        inlineProgressBar.progress = mainSlider.progress.toInt()
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
        val inlineProgressBar = view?.findViewById<View>(R.id.inlineProgressSlider)
        
        val isLyricsCurrentlyVisible = rightLyrics?.isVisible == true
        
        // 判定即将显示的是什么：
        val willShowLyrics = !isLyricsCurrentlyVisible
        
        rightLyrics?.isVisible = willShowLyrics
        rightControls?.isVisible = !willShowLyrics
        toolbar?.isVisible = !willShowLyrics
        
        // 🔑 防抖动逻辑交互：
        // 按你的需求：当点击封面显示右边歌词时，左侧也显示进度条；隐藏右侧歌词时，左侧也隐藏。
        // 我们用 isInvisible 替代 isVisible=false，这样它“隐身”时依然占着空间，封面就不会来回跳动！
        inlineProgressBar?.isInvisible = !willShowLyrics
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