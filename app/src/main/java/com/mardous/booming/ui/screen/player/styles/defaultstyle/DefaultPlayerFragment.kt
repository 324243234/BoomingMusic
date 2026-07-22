package com.mardous.booming.ui.screen.player.styles.defaultstyle

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.mardous.booming.R
import com.mardous.booming.core.model.action.NowPlayingAction
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.player.PlayerColorSchemeMode
import com.mardous.booming.core.model.player.PlayerTintTarget
import com.mardous.booming.core.model.player.surfaceTintTarget
import com.mardous.booming.core.model.player.tintTarget
import com.mardous.booming.core.model.theme.NowPlayingScreen
import com.mardous.booming.databinding.FragmentDefaultPlayerBinding
import com.mardous.booming.extensions.whichFragment
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.AbsPlayerFragment
import com.mardous.booming.util.DISPLAY_NEXT_SONG
import com.mardous.booming.util.Preferences

class DefaultPlayerFragment : AbsPlayerFragment(R.layout.fragment_default_player),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var _binding: FragmentDefaultPlayerBinding? = null
    private val binding get() = _binding!!

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
        
        // 【新增】：初始化平板横屏全屏歌词及手势穿透
        setupGestureOverlay()
    }

    private fun setupGestureOverlay() {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            val gestureDetector = android.view.GestureDetector(requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
                // 单击：仅控制右侧 UI，左侧封面原地不动
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    val rightLyrics = view?.findViewById<View>(R.id.rightLyricsFragment)
                    val rightControls = view?.findViewById<View>(R.id.playbackControlsFragment)
                    val toolbar = view?.findViewById<View>(R.id.toolbar)
                    
                    val isLyricsVisible = rightLyrics?.isVisible == true
                    
                    // 完全隐藏/显示右侧控件，给歌词腾出完整空间
                    rightLyrics?.isVisible = !isLyricsVisible
                    rightControls?.isVisible = isLyricsVisible
                    toolbar?.isVisible = isLyricsVisible
                    return true
                }
            })

            // 【核心黑科技】：穿透机制
            view?.findViewById<View>(R.id.coverClickOverlay)?.setOnTouchListener { _, event ->
                // 1. 让探测器截获单击事件
                gestureDetector.onTouchEvent(event)
                
                // 2. 将所有真实的拖拽、滑动动作，【原封不动地转发】给底层的 CoverPagerFragment！
                // 这完美保留了原作者 ViewPager 的丝滑翻页动画！
                view?.findViewById<View>(R.id.playerAlbumCoverFragment)?.dispatchTouchEvent(event)
                
                // 3. 消费掉此事件，确保持续接收
                true
            }
        }
    }

    private fun setupToolbar() {
        playerToolbar.setNavigationOnClickListener {
            onQuickActionEvent(NowPlayingAction.SoundSettings)
        }
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldPrimaryControlColor = primaryControlColor
        primaryControlColor = scheme.onSurfaceColor
        return mutableListOf(
            binding.root.surfaceTintTarget(scheme.surfaceColor),
            binding.toolbar.tintTarget(oldPrimaryControlColor, scheme.onSurfaceColor)
        ).also {
            it.addAll(playerControlsFragment.getTintTargets(scheme))
        }
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