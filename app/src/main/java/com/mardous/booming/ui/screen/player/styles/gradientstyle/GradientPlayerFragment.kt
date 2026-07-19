package com.mardous.booming.ui.screen.player.styles.gradientstyle

import com.mardous.booming.extensions.navigation.findActivityNavController
import com.mardous.booming.ui.screen.MainActivity
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import com.mardous.booming.R
import com.mardous.booming.core.model.action.NowPlayingAction
import com.mardous.booming.core.model.player.*
import com.mardous.booming.core.model.theme.NowPlayingScreen
import com.mardous.booming.databinding.FragmentGradientPlayerBinding
import com.mardous.booming.extensions.isLandscape
import com.mardous.booming.extensions.whichFragment
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.AbsPlayerFragment
import com.mardous.booming.util.Preferences

class GradientPlayerFragment : AbsPlayerFragment(R.layout.fragment_gradient_player), View.OnClickListener {

    private var _binding: FragmentGradientPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var controlsFragment: GradientPlayerControlsFragment
    
    private var isFavorite: Boolean = false 

    override val colorSchemeMode: PlayerColorSchemeMode
        get() = Preferences.getNowPlayingColorSchemeMode(NowPlayingScreen.Gradient)

    override val playerControlsFragment: AbsPlayerControlsFragment
        get() = controlsFragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        _binding = FragmentGradientPlayerBinding.bind(view)
        
        if (isLandscape()) {
            // 点击左侧封面玻璃层，切换右侧显示状态
            binding.coverClickOverlay?.setOnClickListener {
                val isLyricsVisible = binding.rightLyricsFragment?.isVisible == true
                
                // 取反切换
                binding.rightLyricsFragment?.isVisible = !isLyricsVisible
                binding.lyricsFavoriteButton?.isVisible = !isLyricsVisible
                
                // 原有控件隐藏
                binding.rightControlsGroup?.isVisible = isLyricsVisible
            }

            // 让新的悬浮收藏按钮具备收藏功能
            binding.lyricsFavoriteButton?.let { 
                setViewAction(it, NowPlayingAction.ToggleFavoriteState) 
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomActionContainer) { v: View, insets: WindowInsetsCompat ->
            val navigationBar = insets.getInsets(Type.systemBars())
            v.updatePadding(bottom = navigationBar.bottom)
            val displayCutout = insets.getInsets(Type.displayCutout())
            v.updatePadding(left = displayCutout.left, right = displayCutout.right)
            insets
        }
        setupListeners()
    }

    private fun setupListeners() {
        binding.openQueueButton.setOnClickListener(this)
        binding.showLyricsButton.setOnClickListener(this)
        binding.soundSettingsButton.setOnClickListener(this)
        binding.fullscreenLyricsButton?.setOnClickListener(this)
    }

    override fun onClick(v: View) {
        when (v) {
            binding.openQueueButton -> onQuickActionEvent(NowPlayingAction.OpenPlayQueue)
            binding.showLyricsButton -> onQuickActionEvent(NowPlayingAction.Lyrics)
            binding.soundSettingsButton -> onQuickActionEvent(NowPlayingAction.SoundSettings)
            binding.fullscreenLyricsButton -> {
                try {
                    // 【核心修复】：先把覆盖在上面的播放器面板“收起”，暴露底层的界面
                    (activity as? MainActivity)?.collapsePanel()
                    
                    // 然后再使用底层的 Activity 导航器，跳转到全屏歌词！
                    val navId = resources.getIdentifier("nav_lyrics", "id", requireContext().packageName)
                    if (navId != 0) {
                        findActivityNavController(R.id.fragment_container).navigate(navId)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onIsFavoriteChanged(isFavorite: Boolean, withAnimation: Boolean) {
        if (this.isFavorite != isFavorite) {
            this.isFavorite = isFavorite
            binding.lyricsFavoriteButton?.setIsFavorite(isFavorite, withAnimation)
        }
        controlsFragment.setFavorite(isFavorite, withAnimation)
    }

    override fun onMenuInflated(menu: Menu) {
        super.onMenuInflated(menu)
        menu.removeItem(R.id.action_playing_queue)
        menu.removeItem(R.id.action_show_lyrics)
        menu.removeItem(R.id.action_sound_settings)
        menu.removeItem(R.id.action_favorite)
    }

    override fun onCreateChildFragments() {
        super.onCreateChildFragments()
        controlsFragment = whichFragment(R.id.playbackControlsFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldMaskColor = binding.mask.backgroundTintList?.defaultColor
            ?: Color.TRANSPARENT
        val oldPrimaryTextColor = binding.openQueueButton.iconTint.defaultColor
        
        // 【新增】：获取收藏按钮原本的背景色
        val oldFavBgColor = binding.lyricsFavoriteButton?.backgroundTintList?.defaultColor 
            ?: Color.TRANSPARENT
        // 【终极修复】：使用 listOfNotNull 完美处理可选按钮，且补齐 了逗号，杜绝一切语法与类型报错
        return listOfNotNull(
            binding.colorBackground.surfaceTintTarget(scheme.surfaceColor),
            binding.mask.tintTarget(oldMaskColor, scheme.surfaceColor),
            binding.openQueueButton.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor),
            binding.showLyricsButton.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor),
            binding.soundSettingsButton.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor),
            // 【精准修复】：同时给收藏按钮的 背景(tintTarget) 和 图标(iconButtonTintTarget) 动态上色！
            binding.lyricsFavoriteButton?.tintTarget(oldFavBgColor, scheme.secondaryContainerColor),
            binding.lyricsFavoriteButton?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor),
            binding.fullscreenLyricsButton?.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)
        ).toMutableList().also {
            it.addAll(playerControlsFragment.getTintTargets(scheme))
        }
    }

    override fun onLyricsVisibilityChange(animatorSet: AnimatorSet, lyricsVisible: Boolean) {
        _binding?.showLyricsButton?.let {
            if (lyricsVisible) {
                it.setIconResource(R.drawable.ic_lyrics_24dp)
                it.contentDescription = getString(R.string.action_hide_lyrics)
            } else {
                it.setIconResource(R.drawable.ic_lyrics_outline_24dp)
                it.contentDescription = getString(R.string.action_show_lyrics)
            }
        }
        if (lyricsVisible) {
            animatorSet.play(ObjectAnimator.ofFloat(binding.mask, View.ALPHA, 0f))
        } else {
            animatorSet.play(ObjectAnimator.ofFloat(binding.mask, View.ALPHA, 1f))
        }
    }
}