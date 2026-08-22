/*
 * Copyright (c) 2025 Christians Martínez Alvarado
 */

package com.mardous.booming.ui.screen.player.styles.plainstyle

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mardous.booming.R
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.player.PlayerColorSchemeMode
import com.mardous.booming.core.model.player.PlayerTintTarget
import com.mardous.booming.core.model.player.iconButtonTintTarget
import com.mardous.booming.core.model.player.tintTarget
import com.mardous.booming.data.model.Song
import com.mardous.booming.databinding.FragmentPlainPlayerPlaybackControlsBinding
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.SkipButtonTouchHandler.Companion.DIRECTION_NEXT
import com.mardous.booming.ui.component.base.SkipButtonTouchHandler.Companion.DIRECTION_PREVIOUS
import com.mardous.booming.ui.component.views.MusicSlider
import com.mardous.booming.ui.screen.player.PlayerAnimator
import java.util.LinkedList

class PlainPlayerControlsFragment : AbsPlayerControlsFragment(R.layout.fragment_plain_player_playback_controls) {

    private var _binding: FragmentPlainPlayerPlaybackControlsBinding? = null
    private val binding get() = _binding!!

    override val playPauseFab: FloatingActionButton get() = binding.playPauseButton
    override val repeatButton: MaterialButton get() = binding.repeatButton
    override val shuffleButton: MaterialButton get() = binding.shuffleButton
    override val musicSlider: MusicSlider? get() = binding.progressSlider
    override val songCurrentProgress: TextView get() = binding.songCurrentProgress
    override val songTotalTime: TextView get() = binding.songTotalTime
    override val songInfoView: TextView? get() = binding.songInfo

    private lateinit var audioManager: AudioManager
    private val volumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val newVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val slider = view?.findViewById<SeekBar>(R.id.volumeSlider)
                if (slider != null && slider.progress != newVolume) {
                    slider.progress = newVolume
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlainPlayerPlaybackControlsBinding.bind(view)
        binding.playPauseButton.setOnClickListener(this)
        binding.shuffleButton.setOnClickListener(this)
        binding.repeatButton.setOnClickListener(this)
        binding.nextButton.setOnTouchListener(getSkipButtonTouchHandler(DIRECTION_NEXT))
        binding.previousButton.setOnTouchListener(getSkipButtonTouchHandler(DIRECTION_PREVIOUS))

        setupVolumeSlider()

        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
        view.findViewById<View>(R.id.volumeContainer)?.isVisible = isLandscapeOrTablet
    }

    private fun setupVolumeSlider() {
        val context = context ?: return
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volumeSlider = view?.findViewById<SeekBar>(R.id.volumeSlider) ?: return

        volumeSlider.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeSlider.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        ContextCompat.registerReceiver(
            context, volumeReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldControlColor = binding.nextButton.iconTint.defaultColor
        val oldSliderColor = binding.progressSlider.currentColor
        val oldSecondaryTextColor = binding.songCurrentProgress.currentTextColor
        
        val volumeDownIcon = view?.findViewById<ImageView>(R.id.volumeDownIcon)
        val volumeUpIcon = view?.findViewById<ImageView>(R.id.volumeUpIcon)
        val volumeSlider = view?.findViewById<SeekBar>(R.id.volumeSlider)
        
        // 获取 XML 原始色，如果为空则兜底为旧文本色
        val oldVolumeIconColor = volumeDownIcon?.imageTintList?.defaultColor ?: oldSecondaryTextColor

        val oldShuffleColor = getPlaybackControlsColor(isShuffleModeOn)
        val newShuffleColor = getPlaybackControlsColor(isShuffleModeOn, scheme.onSurfaceColor, scheme.onSurfaceVariantColor)
        val oldRepeatColor = getPlaybackControlsColor(isRepeatModeOn)
        val newRepeatColor = getPlaybackControlsColor(isRepeatModeOn, scheme.onSurfaceColor, scheme.onSurfaceVariantColor)
        val oldPlayPauseColor = binding.playPauseButton.backgroundTintList?.defaultColor ?: oldControlColor
        val newEmphasisColor = if (scheme.mode == PlayerColorSchemeMode.VibrantColor) scheme.onSurfaceColor else scheme.primaryColor

        // 🌟 深度强制音量条主题变色：连同半透明底轨一起处理！
        volumeSlider?.let { slider ->
            val activeList = android.content.res.ColorStateList.valueOf(scheme.onSurfaceVariantColor)
            val backgroundAlphaColor = ColorUtils.setAlphaComponent(scheme.onSurfaceVariantColor, 76) // ~30% 透明度底轨
            val backgroundList = android.content.res.ColorStateList.valueOf(backgroundAlphaColor)
            
            if (slider.progressTintList?.defaultColor != scheme.onSurfaceVariantColor) {
                slider.progressTintList = activeList
                slider.thumbTintList = activeList
                slider.progressBackgroundTintList = backgroundList
            }
        }

        return listOfNotNull(
            binding.progressSlider.progressView?.tintTarget(oldSliderColor, newEmphasisColor),
            binding.songCurrentProgress.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            binding.songTotalTime.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            binding.songInfo.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            binding.playPauseButton.iconButtonTintTarget(oldPlayPauseColor, newEmphasisColor),
            binding.nextButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.previousButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.shuffleButton.iconButtonTintTarget(oldShuffleColor, newShuffleColor),
            binding.repeatButton.iconButtonTintTarget(oldRepeatColor, newRepeatColor),
            volumeDownIcon?.tintTarget(oldVolumeIconColor, scheme.onSurfaceVariantColor),
            volumeUpIcon?.tintTarget(oldVolumeIconColor, scheme.onSurfaceVariantColor)
        )
    }

    override fun onCreatePlayerAnimator(): PlayerAnimator {
        return PlainPlayerAnimator(binding, isControlAnimationEnabled)
    }

    override fun onSongInfoChanged(currentSong: Song, nextSong: Song) {}

    override fun onExtraInfoChanged(extraInfo: String?) {
        _binding?.let { nonNullBinding ->
            if (isExtraInfoEnabled()) {
                nonNullBinding.songInfo.text = extraInfo
                nonNullBinding.songInfo.isVisible = true
            } else {
                nonNullBinding.songInfo.isVisible = false
            }
        }
    }

    override fun onUpdatePlayPause(isPlaying: Boolean) {
        if (isPlaying) {
            _binding?.playPauseButton?.setImageResource(R.drawable.ic_pause_24dp)
        } else {
            _binding?.playPauseButton?.setImageResource(R.drawable.ic_play_24dp)
        }
    }

    override fun onClick(view: View) {
        super.onClick(view)
        when (view) {
            binding.repeatButton -> playerViewModel.cycleRepeatMode()
            binding.shuffleButton -> playerViewModel.toggleShuffleMode()
            binding.playPauseButton -> playerViewModel.togglePlayPause()
        }
    }

    override fun onDestroyView() {
        runCatching { context?.unregisterReceiver(volumeReceiver) }
        super.onDestroyView()
        _binding = null
    }

    private class PlainPlayerAnimator(
        private val binding: FragmentPlainPlayerPlaybackControlsBinding,
        isEnabled: Boolean
    ) : PlayerAnimator(isEnabled) {
        override fun onAddAnimation(animators: LinkedList<Animator>, interpolator: TimeInterpolator) {
            animators.add(
                ObjectAnimator.ofPropertyValuesHolder(
                    binding.playPauseButton,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f),
                    PropertyValuesHolder.ofFloat(View.ROTATION, 360f)
                ).apply { setInterpolator(DecelerateInterpolator()) }
            )
            addScaleAnimation(animators, binding.shuffleButton, interpolator, 100)
            addScaleAnimation(animators, binding.repeatButton, interpolator, 100)
            addScaleAnimation(animators, binding.previousButton, interpolator, 100)
            addScaleAnimation(animators, binding.nextButton, interpolator, 100)
            addScaleAnimation(animators, binding.songCurrentProgress, interpolator, 200)
            addScaleAnimation(animators, binding.songTotalTime, interpolator, 200)
        }

        override fun onPrepareForAnimation() {
            binding.playPauseButton.apply { scaleX = 0f; scaleY = 0f; rotation = 0f }
            prepareForScaleAnimation(binding.previousButton)
            prepareForScaleAnimation(binding.nextButton)
            prepareForScaleAnimation(binding.shuffleButton)
            prepareForScaleAnimation(binding.repeatButton)
            prepareForScaleAnimation(binding.songCurrentProgress)
            prepareForScaleAnimation(binding.songTotalTime)
        }
    }
}