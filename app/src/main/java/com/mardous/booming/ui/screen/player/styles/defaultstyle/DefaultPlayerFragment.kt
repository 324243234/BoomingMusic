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

        // 1. 歌曲信息更新与取色防抖拦截
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

        // 2. 左侧迷你进度条拖拽控制（极致流畅与防回弹优化）
        val inlineProgressBar = view.findViewById<SeekBar>(R.id.inlineProgressSlider)
        
        // 🔑 核心优化 A：消除起手“粘滞感” (突破 Touch Slop)
        // 拦截 ACTION_DOWN，在手指触碰滑块的绝对瞬间，强制向所有父容器下达“禁止拦截”指令。
        // 彻底绕过手势判定的犹豫期，实现即触即滑，0延迟跟手！
        inlineProgressBar?.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            // 必须返回 false，不消费事件，让 SeekBar 继续处理自己的标准滑动逻辑
            false 
        }

        inlineProgressBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 🛡️ 性能底线：保持此处为空！
                // 绝不在高频拖拽中实时调用 seekTo 发送指令，完美保护 CPU 性能，杜绝发热和耗电。
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDraggingInlineSlider = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { progress ->
                    playerViewModel.seekTo(progress.toLong())
                }
                
                // 🔑 核心优化 B：消除松手后的“回弹拉扯感”
                // 底层音频 seekTo 存在合理的微秒级异步延迟。
                // 我们在松手后，给“拖拽锁定”状态强行续命 500 毫秒。
                // 保护滑块在此期间绝不会被“影子同步协程”拉回老位置，平滑过渡！
                seekBar?.postDelayed({
                    isDraggingInlineSlider = false
                }, 500)
            }
        })

        // 3. 影子同步机制（极致精简版）
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            while (isActive) {
                val mainSlider = view.findViewById<MusicSlider>(R.id.progressSlider)
                
                // 🛡️ 终极性能优化：左侧进度条常驻，只需拦截空指针和用户拖拽状态。
                // 彻底省去冗余的 View 状态判定，降低 CPU 开销。
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
        
        val isLyricsCurrentlyVisible = rightLyrics?.isVisible == true
        val willShowLyrics = !isLyricsCurrentlyVisible
        
        // 🔑 仅翻转右侧组件。
        // 左侧组件彻底解耦，静默常驻，杜绝 ConstraintLayout 全局重绘引发的卡顿。
        rightLyrics?.isVisible = willShowLyrics
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