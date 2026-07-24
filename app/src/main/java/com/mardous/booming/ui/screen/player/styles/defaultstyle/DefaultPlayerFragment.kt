package com.mardous.booming.ui.screen.player.styles.defaultstyle

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
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
import com.mardous.booming.ui.screen.player.PlayerGesturesController.GestureType
import com.mardous.booming.util.DISPLAY_NEXT_SONG
import com.mardous.booming.util.Preferences

class DefaultPlayerFragment : AbsPlayerFragment(R.layout.fragment_default_player),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var _binding: FragmentDefaultPlayerBinding? = null
    private val binding get() = _binding!!
	// 在 DefaultPlayerFragment 中添加成员变量
    private var lastProcessedSongId: Long = -1L

	private var isDraggingInlineSlider = false // 🔑 防冲突：记录用户是否正在拖拽迷你进度条
	
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
				// 直接获取纯粹的艺术家名字，绝不夹杂专辑名
                    //val artist = if (Preferences.preferAlbumArtistName) {
                       // song.albumArtistName().displayArtistName()
                   // } else {
                    //    song.displayArtistName()
                    //}
                    //leftInfoText.text = "${song.title} - $artist"
					
					// 🔑 优化1：只显示歌曲名，彻底丢弃歌手信息
                    leftInfoText.text = song.title
                    setMarquee(leftInfoText, marquee = true)
					// 2. 【核心优化拦截】：如果是同一首歌（仅车机后台替换了 MediaItem），严禁重复触发 Palette 取色与变色动画！
                   if (song.id != lastProcessedSongId) {
                   lastProcessedSongId = song.id
                   // 在这里才触发 Palette 取色和背景/文字颜色渐变动画
                   }
                }
            }
        }
		// 🔑 优化2：为迷你进度条设置拖拽快进功能
        val inlineProgressBar = view.findViewById<SeekBar>(R.id.inlineProgressSlider)
        inlineProgressBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDraggingInlineSlider = true
                // 开启触摸隔离护盾：防止拖拽进度条时，触发了平板的外层左右滑动切歌
                seekBar?.parent?.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isDraggingInlineSlider = false
                seekBar?.parent?.requestDisallowInterceptTouchEvent(false)
                seekBar?.progress?.let { progress ->
                    // 动态自适应跳转：兼容 Int 和 Long 两种可能的底层源码类型，绝不报错
                    runCatching { playerViewModel.seekTo(progress) }
                        .onFailure { runCatching { playerViewModel.seekTo(progress.toLong()) } }
                }
            }
        })
		// 🔑 优化3：“影子同步”机制 —— 最稳定地获取进度，绝不引发编译错误
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            while (kotlinx.coroutines.isActive) {
                // 直接去子 Fragment 里抓取那个已经完美运行的主进度条
                val mainSlider = view.findViewById<SeekBar>(R.id.progressSlider)
                if (inlineProgressBar != null && mainSlider != null && !isDraggingInlineSlider) {
                    // 实时镜像它的最大值和当前进度
                    inlineProgressBar.max = mainSlider.max
                    if (inlineProgressBar.progress != mainSlider.progress) {
                        inlineProgressBar.progress = mainSlider.progress
                    }
                }
                kotlinx.coroutines.delay(500) // 每 0.5 秒同步一次，极致省电不卡顿
            }}
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
        
        val isLyricsVisible = rightLyrics?.isVisible == true
        
        rightLyrics?.isVisible = !isLyricsVisible
        rightControls?.isVisible = isLyricsVisible
        toolbar?.isVisible = isLyricsVisible
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
        
        // ！！！完美同步右上角的动态智能取色逻辑 (scheme.onSurfaceColor)！！！
        //val leftInfoText = view?.findViewById<TextView>(R.id.leftCoverInfoText)
        //if (leftInfoText != null) {
        //    val oldTextColor = leftInfoText.currentTextColor
        //    targets.add(leftInfoText.tintTarget(oldTextColor, scheme.onSurfaceColor))
        //}

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