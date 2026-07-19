package com.mardous.booming.ui.screen.player.styles.gradientstyle

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
    
    // 【修复1】：补齐缺失的 isFavorite 属性，防止代码引用报错[cite: 17]
    private var isFavorite: Boolean = false 

    override val colorSchemeMode: PlayerColorSchemeMode
        get() = Preferences.getNowPlayingColorSchemeMode(NowPlayingScreen.Gradient)

    override val playerControlsFragment: AbsPlayerControlsFragment
        get() = controlsFragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 【修复2】：必须先执行 bind，再调用 binding 设置点击事件，否则会引发空指针闪退[cite: 17]
        _binding = FragmentGradientPlayerBinding.bind(view)
        
        if (isLandscape()) {
            // 点击左侧封面玻璃层，切换右侧显示状态
            binding.coverClickOverlay?.setOnClickListener {
                val isLyricsVisible = binding.rightLyricsFragment?.isVisible == true
                
                // 取反切换
                binding.rightLyricsFragment?.isVisible = !isLyricsVisible
                binding.lyricsFloatingButtons?.isVisible = !isLyricsVisible
                
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
    }

    override fun onClick(v: View) {
        when (v) {
            binding.openQueueButton -> onQuickActionEvent(NowPlayingAction.OpenPlayQueue)
            binding.showLyricsButton -> onQuickActionEvent(NowPlayingAction.Lyrics)
            binding.soundSettingsButton -> onQuickActionEvent(NowPlayingAction.SoundSettings)
        }
    }

    override fun onIsFavoriteChanged(isFavorite: Boolean, withAnimation: Boolean) {
        if (this.isFavorite != isFavorite) {
            this.isFavorite = isFavorite
            // 【修复3】：只对右侧悬浮的心形按钮进行变色控制。去掉了旧代码对 binding.favoriteButton 的错误调用，因为该ID在 Gradient 的根部 XML 中不存在[cite: 17]
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
        return mutableListOf(
            binding.colorBackground.surfaceTintTarget(scheme.surfaceColor),
            binding.mask.tintTarget(oldMaskColor, scheme.surfaceColor),
            binding.openQueueButton.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor),
            binding.showLyricsButton.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor),
            binding.soundSettingsButton.iconButtonTintTarget(oldPrimaryTextColor, scheme.onSurfaceColor)
        ).also {
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