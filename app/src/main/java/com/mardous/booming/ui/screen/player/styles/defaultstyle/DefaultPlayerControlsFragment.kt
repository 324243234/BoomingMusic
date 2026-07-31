package com.mardous.booming.ui.screen.player.styles.defaultstyle

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
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

    override val playPauseFab: FloatingActionButton get() = binding.playPauseButton
    override val repeatButton: MaterialButton? get() = binding.repeatButton
    override val shuffleButton: MaterialButton? get() = binding.shuffleButton
    
    // 强制挂载隐藏元素防 Crash
    override val musicSlider: MusicSlider? get() = binding.progressSlider
    override val songCurrentProgress: TextView get() = binding.songCurrentProgress
    override val songTotalTime: TextView get() = binding.songTotalTime
    override val songTitleView: TextView? get() = binding.title
    override val songArtistView: TextView? get() = binding.text
    override val songInfoView: TextView? get() = binding.songInfo

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
        val volumeSlider = view?.findViewById<SeekBar>(R.id.volumeSlider) ?: return

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeSlider.max = maxVolume
        volumeSlider.progress = currentVolume
        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        androidx.core.content.ContextCompat.registerReceiver(
            requireContext(), volumeReceiver,
            android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onCreatePlayerAnimator(): PlayerAnimator {
        return DefaultPlayerAnimator(binding, isControlAnimationEnabled)
    }

    override fun onSongInfoChanged(currentSong: Song, nextSong: Song) {
        _binding?.let { nonNullBinding ->
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
                if (isControlAnimationEnabled) view.showBounceAnimation()
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
        when(key) { DISPLAY_NEXT_SONG -> setupQueueInfoView() }
    }

    override fun onDestroyView() {
        runCatching { requireContext().unregisterReceiver(volumeReceiver) }
        super.onDestroyView()
        _binding = null
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldPlayPauseColor = binding.playPauseButton.backgroundTintList?.defaultColor ?: Color.TRANSPARENT
        val oldControlColor = binding.nextButton.iconTint.defaultColor
        
        val volumeDownIcon = view?.findViewById<ImageView>(R.id.volumeDownIcon)
        val volumeUpIcon = view?.findViewById<ImageView>(R.id.volumeUpIcon)
        val volumeSlider = view?.findViewById<SeekBar>(R.id.volumeSlider)
        val oldVolumeIconColor = volumeDownIcon?.imageTintList?.defaultColor ?: scheme.onSurfaceVariantColor

        val newEmphasisColor = if (scheme.mode == PlayerColorSchemeMode.VibrantColor) scheme.onSurfaceColor else scheme.primaryColor
        
        val oldShuffleColor = getPlaybackControlsColor(isShuffleModeOn)
        val newShuffleColor = getPlaybackControlsColor(isShuffleModeOn, scheme.onSurfaceColor, scheme.onSurfaceVariantColor)
        val oldRepeatColor = getPlaybackControlsColor(isRepeatModeOn)
        val newRepeatColor = getPlaybackControlsColor(isRepeatModeOn, scheme.onSurfaceColor, scheme.onSurfaceVariantColor)
        
        volumeSlider?.let { slider ->
            val activeList = android.content.res.ColorStateList.valueOf(newEmphasisColor)
            if (slider.progressTintList?.defaultColor != newEmphasisColor) {
                slider.progressTintList = activeList
                slider.thumbTintList = activeList
            }
        }

        // 🌟 跨越层级，精准抓取左侧新建坞的元素注入变色 🌟
        val leftInfoText = view?.rootView?.findViewById<TextView>(R.id.leftSongInfoText)
        val leftFavBtn = view?.rootView?.findViewById<ImageView>(R.id.leftFavoriteButton)
        val leftCurrTime = view?.rootView?.findViewById<TextView>(R.id.leftCurrentTime)
        val leftTotTime = view?.rootView?.findViewById<TextView>(R.id.leftTotalTime)
        val leftSlider = view?.rootView?.findViewById<SeekBar>(R.id.leftProgressSlider)

        val oldLeftTextColor = leftInfoText?.currentTextColor ?: scheme.onSurfaceColor
        val oldSecondaryColor = leftCurrTime?.currentTextColor ?: scheme.onSurfaceVariantColor
        
        leftSlider?.let { slider ->
            val activeList = android.content.res.ColorStateList.valueOf(newEmphasisColor)
            if (slider.progressTintList?.defaultColor != newEmphasisColor) {
                slider.progressTintList = activeList
                slider.thumbTintList = activeList
            }
        }

        return listOfNotNull(
            binding.playPauseButton.tintTarget(oldPlayPauseColor, newEmphasisColor),
            binding.nextButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.previousButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.shuffleButton.iconButtonTintTarget(oldShuffleColor, newShuffleColor),
            binding.repeatButton.iconButtonTintTarget(oldRepeatColor, newRepeatColor),
            
            // 左侧坞实时变色绑定
            leftInfoText?.tintTarget(oldLeftTextColor, scheme.onSurfaceColor),
            leftFavBtn?.tintTarget(leftFavBtn.imageTintList?.defaultColor ?: scheme.onSurfaceColor, scheme.onSurfaceColor),
            leftCurrTime?.tintTarget(oldSecondaryColor, scheme.onSurfaceVariantColor),
            leftTotTime?.tintTarget(oldSecondaryColor, scheme.onSurfaceVariantColor),

            binding.songInfo?.tintTarget(oldSecondaryColor, scheme.onSurfaceVariantColor),
            binding.queueInfo.tintTarget(oldLeftTextColor, scheme.onSurfaceColor),
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
                ).apply { setInterpolator(DecelerateInterpolator()) }
            )
            addScaleAnimation(animators, binding.shuffleButton, interpolator, 100)
            addScaleAnimation(animators, binding.repeatButton, interpolator, 100)
            addScaleAnimation(animators, binding.previousButton, interpolator, 200)
            addScaleAnimation(animators, binding.nextButton, interpolator, 200)
        }
        override fun onPrepareForAnimation() {
            binding.playPauseButton.apply { scaleX = 0f; scaleY = 0f; rotation = 0f }
            prepareForScaleAnimation(binding.previousButton)
            prepareForScaleAnimation(binding.nextButton)
            prepareForScaleAnimation(binding.shuffleButton)
            prepareForScaleAnimation(binding.repeatButton)
        }
    }
}