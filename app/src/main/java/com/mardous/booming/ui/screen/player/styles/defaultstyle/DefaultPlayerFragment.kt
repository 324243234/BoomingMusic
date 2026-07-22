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
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.mardous.booming.R
import com.mardous.booming.core.model.action.NowPlayingAction
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.player.PlayerColorSchemeMode
import com.mardous.booming.core.model.player.PlayerTintTarget
import com.mardous.booming.core.model.player.surfaceTintTarget
import com.mardous.booming.core.model.player.tintTarget
import com.mardous.booming.core.model.player.iconButtonTintTarget
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
        
        setupGestureOverlay()
        setupLyricsActions()
    }

    private fun setupLyricsActions() {
        val favBtn = view?.findViewById<MaterialButton>(R.id.lyricsFavoriteButton)
        val transBtn = view?.findViewById<TextView>(R.id.lyricsTranslationButton)

        // 翻译开关逻辑
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val isTransOn = prefs.getBoolean("lyrics_show_translation", true)
        transBtn?.alpha = if (isTransOn) 0.4f else 1.0f
        transBtn?.setOnClickListener {
            val current = prefs.getBoolean("lyrics_show_translation", true)
            prefs.edit().putBoolean("lyrics_show_translation", !current).apply()
            it.alpha = if (!current) 0.4f else 1.0f
        }

        // 收藏按钮逻辑
        favBtn?.let { setViewAction(it, NowPlayingAction.ToggleFavoriteState) }
    }

    private fun setupGestureOverlay() {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            val gestureDetector = android.view.GestureDetector(requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
                // 单击：控制右侧 控件/歌词 切换，左侧原生封面丝毫不动
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    val rightLyrics = view?.findViewById<View>(R.id.rightLyricsFragment)
                    val rightControls = view?.findViewById<View>(R.id.playbackControlsFragment)
                    val toolbar = view?.findViewById<View>(R.id.toolbar)
                    val actionContainer = view?.findViewById<View>(R.id.lyricsActionContainer)
                    
                    val isLyricsVisible = rightLyrics?.isVisible == true
                    
                    rightLyrics?.isVisible = !isLyricsVisible
                    actionContainer?.isVisible = !isLyricsVisible
                    rightControls?.isVisible = isLyricsVisible
                    toolbar?.isVisible = isLyricsVisible
                    return true
                }

                // 双击：分屏判断，左上一首，右下一首
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
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
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                    return true
                }

                // 长按：触发收藏
                override fun onLongPress(e: android.view.MotionEvent) {
                    onQuickActionEvent(NowPlayingAction.ToggleFavoriteState)
                }
            })

            var isDragging = false
            var startX = 0f
            var startY = 0f
            val touchSlop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop

            view?.findViewById<View>(R.id.coverClickOverlay)?.setOnTouchListener { _, event ->
                // 先让探测器分析手势（它会处理单击、双击、长按）
                gestureDetector.onTouchEvent(event)
                
                // 【核心穿透逻辑】：如果是纯滑动，就传给底层封面引发原生滚动；如果是点击，抬手瞬间改成取消！
                val clonedEvent = android.view.MotionEvent.obtain(event)
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        isDragging = false
                        startX = event.x
                        startY = event.y
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = Math.abs(event.x - startX)
                        val dy = Math.abs(event.y - startY)
                        if (dx > touchSlop || dy > touchSlop) {
                            isDragging = true // 超过安全距离，判定为真实拖拽滑动
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // 没有发生拖拽，说明是个点击事件！
                            // 把抬手变成撤销（CANCEL），防止穿透到底层触发原作者的点击事件产生冲突
                            clonedEvent.action = android.view.MotionEvent.ACTION_CANCEL
                        }
                    }
                }
                
                // 放行事件给原生的 AlbumCoverFragment
                view?.findViewById<View>(R.id.playerAlbumCoverFragment)?.dispatchTouchEvent(clonedEvent)
                clonedEvent.recycle()
                
                true
            }
        }
    }

    private fun setupToolbar() {
        playerToolbar.setNavigationOnClickListener {
            onQuickActionEvent(NowPlayingAction.SoundSettings)
        }
    }

    override fun onIsFavoriteChanged(isFavorite: Boolean, withAnimation: Boolean) {
        super.onIsFavoriteChanged(isFavorite, withAnimation)
        view?.findViewById<MaterialButton>(R.id.lyricsFavoriteButton)?.let {
            it.setIconResource(if (isFavorite) R.drawable.ic_favorite_24dp else R.drawable.ic_favorite_outline_24dp)
        }
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldPrimaryControlColor = primaryControlColor
        primaryControlColor = scheme.onSurfaceColor
        
        val favBtn = view?.findViewById<MaterialButton>(R.id.lyricsFavoriteButton)
        val transBtn = view?.findViewById<TextView>(R.id.lyricsTranslationButton)

        return listOfNotNull(
            binding.root.surfaceTintTarget(scheme.surfaceColor),
            binding.toolbar.tintTarget(oldPrimaryControlColor, scheme.onSurfaceColor),
            // 给统一按钮组染上跟随主题的纯净颜色
            favBtn?.iconButtonTintTarget(oldPrimaryControlColor, scheme.onSurfaceColor),
            transBtn?.tintTarget(oldPrimaryControlColor, scheme.onSurfaceColor)
        ).toMutableList().also {
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