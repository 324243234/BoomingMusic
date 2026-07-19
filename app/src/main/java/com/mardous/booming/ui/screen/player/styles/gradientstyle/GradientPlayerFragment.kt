/*
 * Copyright (c) 2024 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

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
import androidx.core.view.updatePadding
import com.mardous.booming.R
import com.mardous.booming.core.model.action.NowPlayingAction
import com.mardous.booming.core.model.player.*
import com.mardous.booming.core.model.theme.NowPlayingScreen
import com.mardous.booming.databinding.FragmentGradientPlayerBinding
import com.mardous.booming.extensions.whichFragment
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.AbsPlayerFragment
import com.mardous.booming.util.Preferences

class GradientPlayerFragment : AbsPlayerFragment(R.layout.fragment_gradient_player), View.OnClickListener {

    private var _binding: FragmentGradientPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var controlsFragment: GradientPlayerControlsFragment

    override val colorSchemeMode: PlayerColorSchemeMode
        get() = Preferences.getNowPlayingColorSchemeMode(NowPlayingScreen.Gradient)

    override val playerControlsFragment: AbsPlayerControlsFragment
        get() = controlsFragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
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
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGradientPlayerBinding.bind(view)
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
            // 同步原来的收藏按钮（如果有的话）
            binding.favoriteButton?.setIsFavorite(isFavorite, withAnimation)
            
            // 【新增】：同步歌词悬浮的收藏按钮状态
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