package com.mardous.booming.ui.screen.player.styles.defaultstyle

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.mardous.booming.R
import com.mardous.booming.core.model.action.NowPlayingAction
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.player.PlayerColorSchemeMode
import com.mardous.booming.core.model.player.PlayerTintTarget
import com.mardous.booming.core.model.player.iconButtonTintTarget
import com.mardous.booming.core.model.player.tintTarget
import com.mardous.booming.data.model.Song
import com.mardous.booming.databinding.FragmentDefaultPlayerPlaybackControlsBinding
import com.mardous.booming.extensions.resources.centerPivot
import com.mardous.booming.extensions.resources.showBounceAnimation
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.SkipButtonTouchHandler.Companion.DIRECTION_NEXT
import com.mardous.booming.ui.component.base.SkipButtonTouchHandler.Companion.DIRECTION_PREVIOUS
import com.mardous.booming.ui.component.views.MusicSlider
import com.mardous.booming.ui.screen.player.PlayerAnimator
import com.mardous.booming.util.DISPLAY_NEXT_SONG
import com.mardous.booming.util.Preferences
import java.util.LinkedList

class DefaultPlayerControlsFragment : AbsPlayerControlsFragment(R.layout.fragment_default_player_playback_controls) {

    private var _binding: FragmentDefaultPlayerPlaybackControlsBinding? = null
    private val binding get() = _binding!!

    private lateinit var audioManager: AudioManager
    private var volumeObserver: ContentObserver? = null

    override val playPauseFab: FloatingActionButton
        get() = binding.playPauseButton

    override val repeatButton: MaterialButton?
        get() = binding.repeatButton

    override val shuffleButton: MaterialButton?
        get() = binding.shuffleButton

    override val musicSlider: MusicSlider?
        get() = binding.progressSlider

    override val songCurrentProgress: TextView
        get() = binding.songCurrentProgress

    override val songTotalTime: TextView
        get() = binding.songTotalTime

    override val songTitleView: TextView?
        get() = binding.title

    override val songArtistView: TextView?
        get() = binding.text

    override val songInfoView: TextView?
        get() = binding.songInfo

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDefaultPlayerPlaybackControlsBinding.bind(view)
        binding.playPauseButton.doOnLayout { it.centerPivot() }
        binding.playPauseButton.setOnClickListener(this)
        binding.shuffleButton.setOnClickListener(this)
        binding.repeatButton.setOnClickListener(this)
        binding.nextButton.setOnTouchListener(getSkipButtonTouchHandler(DIRECTION_NEXT))
        binding.previousButton.setOnTouchListener(getSkipButtonTouchHandler(DIRECTION_PREVIOUS))

        setupQueueInfoView()
        setupVolumeSlider()
    }

    private fun setupVolumeSlider() {
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volumeSlider = view?.findViewById<Slider>(R.id.volumeSlider) ?: return

        // 彻底清空所有自造滑块代码，保证极速稳定运行
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()

        volumeSlider.valueFrom = 0f
        volumeSlider.valueTo = maxVolume
        volumeSlider.value = currentVolume

        volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value.toInt(), 0)
            }
        }

        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                val newVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                if (volumeSlider.value != newVolume) {
                    volumeSlider.value = newVolume
                }
            }
        }
        requireContext().contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, volumeObserver!!
        )
    }

    override fun onCreatePlayerAnimator(): PlayerAnimator {
        return DefaultPlayerAnimator(binding, isControlAnimationEnabled)
    }

    override fun onSongInfoChanged(currentSong: Song, nextSong: Song) {
        _binding?.let { nonNullBinding ->
            nonNullBinding.title.text = currentSong.title
            nonNullBinding.text.text = getSongArtist(currentSong)
            nonNullBinding.queueInfo.text = getNextSongInfo(nextSong)
        }
    }

    override fun onExtraInfoChanged(extraInfo: String?) {
        _binding?.let { nonNullBinding ->
            if (isExtraInfoEnabled()) {
                nonNullBinding.songInfo?.text = extraInfo
                nonNullBinding.songInfo?.isVisible = true
            } else {
                nonNullBinding.songInfo?.isVisible = false
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
            binding.playPauseButton -> {
                playerViewModel.togglePlayPause()
                if (isControlAnimationEnabled) {
                    view.showBounceAnimation()
                }
            }
        }
    }

    private fun setupQueueInfoView() {
        _binding?.let { binding ->
            if (Preferences.isShowNextSong) {
                binding.queueInfo.visibility = View.VISIBLE
                setViewAction(binding.queueInfo, NowPlayingAction.OpenPlayQueue)
            } else {
                binding.queueInfo.visibility = View.GONE
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        when(key) {
            DISPLAY_NEXT_SONG -> {
                setupQueueInfoView()
            }
        }
    }

    override fun onDestroyView() {
        volumeObserver?.let {
            requireContext().contentResolver.unregisterContentObserver(it)
        }
        super.onDestroyView()
        _binding = null
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldPlayPauseColor = binding.playPauseButton.backgroundTintList?.defaultColor ?: Color.TRANSPARENT
        val oldControlColor = binding.nextButton.iconTint.defaultColor
        val oldSliderColor = binding.progressSlider.currentColor
        val oldPrimaryTextColor = binding.title.currentTextColor
        val oldSecondaryTextColor = binding.text.currentTextColor
        
        val volumeDownIcon = view?.findViewById<ImageView>(R.id.volumeDownIcon)
        val volumeUpIcon = view?.findViewById<ImageView>(R.id.volumeUpIcon)
        val volumeSlider = view?.findViewById<Slider>(R.id.volumeSlider)
        val oldVolumeIconColor = volumeDownIcon?.imageTintList?.defaultColor ?: oldSecondaryTextColor

        val newEmphasisColor = if (scheme.mode == PlayerColorSchemeMode.VibrantColor) {
            scheme.onSurfaceColor
        } else {
            scheme.primaryColor
        }
        val oldShuffleColor = getPlaybackControlsColor(isShuffleModeOn)
        val newShuffleColor = getPlaybackControlsColor(
            isShuffleModeOn, scheme.onSurfaceColor, scheme.onSurfaceVariantColor
        )
        val oldRepeatColor = getPlaybackControlsColor(isRepeatModeOn)
        val newRepeatColor = getPlaybackControlsColor(
            isRepeatModeOn, scheme.onSurfaceColor, scheme.onSurfaceVariantColor
        )
        
        // 【终极瘦身防抖机制】：与进度条统一色彩！
        volumeSlider?.let { slider ->
            val targetColor = newEmphasisColor
            val inactiveColor = scheme.onSurfaceVariantColor
            
            if (slider.trackActiveTintList?.defaultColor != targetColor) {
                slider.trackActiveTintList = android.content.res.ColorStateList.valueOf(targetColor)
                slider.trackInactiveTintList = android.content.res.ColorStateList.valueOf(inactiveColor)
                slider.thumbTintList = android.content.res.ColorStateList.valueOf(targetColor)
            }
        }

        return listOfNotNull(
            binding.playPauseButton.tintTarget(oldPlayPauseColor, newEmphasisColor),
            binding.progressSlider.progressView?.tintTarget(oldSliderColor, newEmphasisColor),
            binding.nextButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.previousButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.shuffleButton.iconButtonTintTarget(oldShuffleColor, newShuffleColor),
            binding.repeatButton.iconButtonTintTarget(oldRepeatColor, newRepeatColor),
            binding.title.tintTarget(oldPrimaryTextColor, scheme.onSurfaceColor),
            binding.text.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            binding.songInfo?.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            binding.queueInfo.tintTarget(oldPrimaryTextColor, scheme.onSurfaceColor),
            binding.songCurrentProgress.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            binding.songTotalTime.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            volumeDownIcon?.tintTarget(oldVolumeIconColor, scheme.onSurfaceVariantColor),
            volumeUpIcon?.tintTarget(oldVolumeIconColor, scheme.onSurfaceVariantColor)
        )
    }

    private class DefaultPlayerAnimator(
        private val binding: FragmentDefaultPlayerPlaybackControlsBinding,
        isEnabled: Boolean
    ) : PlayerAnimator(isEnabled) {
        override fun onAddAnimation(animators: LinkedList<Animator>, interpolator: TimeInterpolator) {
            animators.add(
                ObjectAnimator.ofPropertyValuesHolder(
                    binding.playPauseButton,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f),
                    PropertyValuesHolder.ofFloat(View.ROTATION, 360f)
                ).apply {
                    setInterpolator(DecelerateInterpolator())
                }
            )
            addScaleAnimation(animators, binding.shuffleButton, interpolator, 100)
            addScaleAnimation(animators, binding.repeatButton, interpolator, 100)
            addScaleAnimation(animators, binding.previousButton, interpolator, 200)
            addScaleAnimation(animators, binding.nextButton, interpolator, 200)
            addScaleAnimation(animators, binding.songCurrentProgress, interpolator, 200)
            addScaleAnimation(animators, binding.songTotalTime, interpolator, 200)
        }

        override fun onPrepareForAnimation() {
            binding.playPauseButton.apply {
                scaleX = 0f
                scaleY = 0f
                rotation = 0f
            }
            prepareForScaleAnimation(binding.previousButton)
            prepareForScaleAnimation(binding.nextButton)
            prepareForScaleAnimation(binding.shuffleButton)
            prepareForScaleAnimation(binding.repeatButton)
            prepareForScaleAnimation(binding.songCurrentProgress)
            prepareForScaleAnimation(binding.songTotalTime)
        }
    }
}