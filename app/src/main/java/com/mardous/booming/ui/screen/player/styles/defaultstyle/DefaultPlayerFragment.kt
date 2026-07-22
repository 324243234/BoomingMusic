package com.mardous.booming.ui.screen.player.styles.defaultstyle

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import kotlin.math.abs

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
        
        setupGestureOverlay()
    }

    private fun setupGestureOverlay() {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            val gestureDetector = android.view.GestureDetector(requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
                
                // 完美接管单击，控制右侧显隐
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val rightLyrics = view?.findViewById<View>(R.id.rightLyricsFragment)
                    val rightControls = view?.findViewById<View>(R.id.playbackControlsFragment)
                    val toolbar = view?.findViewById<View>(R.id.toolbar)
                    
                    val isLyricsVisible = rightLyrics?.isVisible == true
                    
                    rightLyrics?.isVisible = !isLyricsVisible
                    rightControls?.isVisible = isLyricsVisible
                    toolbar?.isVisible = isLyricsVisible
                    return true
                }

                // 双击调用系统切歌
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    try {
                        val overlayWidth = view?.findViewById<View>(R.id.coverClickOverlay)?.width ?: 0
                        val audioManager = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        val keyCode = if (overlayWidth > 0 && e.x > overlayWidth / 2) {
                            android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        } else {
                            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        }
                        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
                        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
                    } catch (ex: Exception) { }
                    return true
                }

                // 长按收藏
                override fun onLongPress(e: MotionEvent) {
                    onQuickActionEvent(NowPlayingAction.ToggleFavoriteState)
                }
            })

            // 【零分配突变分发技术】：彻底杜绝卡顿，0次内存分配！
            var isDragging = false
            var startX = 0f
            var startY = 0f
            val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop

            view?.findViewById<View>(R.id.coverClickOverlay)?.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                
                val coverFragment = view?.findViewById<View>(R.id.playerAlbumCoverFragment)
                if (coverFragment != null) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            isDragging = false
                            startX = event.x
                            startY = event.y
                            coverFragment.dispatchTouchEvent(event) // 直接下发
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!isDragging && (abs(event.x - startX) > touchSlop || abs(event.y - startY) > touchSlop)) {
                                isDragging = true 
                            }
                            coverFragment.dispatchTouchEvent(event) // 直接下发，绝不克隆！
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) {
                                // 这是单击。利用原地篡改技术扼杀原作者的点击事件冲突！
                                val originalAction = event.action
                                event.action = MotionEvent.ACTION_CANCEL
                                coverFragment.dispatchTouchEvent(event)
                                event.action = originalAction // 恢复现场
                            } else {
                                coverFragment.dispatchTouchEvent(event)
                            }
                        }
                        else -> {
                            coverFragment.dispatchTouchEvent(event)
                        }
                    }
                }
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