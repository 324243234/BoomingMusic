package com.mardous.booming.ui.screen.player.styles.gradientstyle

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.mardous.booming.R
import com.mardous.booming.core.model.action.NowPlayingAction
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.core.model.player.PlayerTintTarget
import com.mardous.booming.core.model.player.iconButtonTintTarget
import com.mardous.booming.core.model.player.tintTarget
import com.mardous.booming.data.model.Song
import com.mardous.booming.databinding.FragmentGradientPlayerPlaybackControlsBinding
import com.mardous.booming.extensions.isLandscape
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.SkipButtonTouchHandler.Companion.DIRECTION_NEXT
import com.mardous.booming.ui.component.base.SkipButtonTouchHandler.Companion.DIRECTION_PREVIOUS
import com.mardous.booming.ui.component.views.MusicSlider

class GradientPlayerControlsFragment : AbsPlayerControlsFragment(R.layout.fragment_gradient_player_playback_controls) {

    private var _binding: FragmentGradientPlayerPlaybackControlsBinding? = null
    private val binding get() = _binding!!

    internal var popupMenu: PopupMenu? = null

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

    override val musicSlider: MusicSlider? get() = binding.progressSlider
    override val repeatButton: MaterialButton get() = binding.repeatButton
    override val shuffleButton: MaterialButton get() = binding.shuffleButton
    override val songCurrentProgress: TextView get() = binding.songCurrentProgress
    override val songTotalTime: TextView get() = binding.songTotalTime
    override val songTitleView: TextView? get() = binding.title
    override val songArtistView: TextView? get() = binding.text
    override val songInfoView: TextView get() = binding.songInfo

    private var isFavorite: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGradientPlayerPlaybackControlsBinding.bind(view)
        setupListeners()
        setViewAction(binding.favorite, NowPlayingAction.ToggleFavoriteState)
        popupMenu = playerFragment?.inflateMenuInView(binding.menu)
        
        setupVolumeSlider()

        // ★ 核心修复：仅在横屏/平板模式下显示音量控制栏，竖屏完全还原原作者布局
        val isLandscapeOrTablet = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
            (resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
        view.findViewById<View>(R.id.volumeContainer)?.isVisible = isLandscapeOrTablet

        ViewCompat.setOnApplyWindowInsetsListener(view) { v: View, insets: WindowInsetsCompat ->
            val displayCutout = insets.getInsets(Type.displayCutout())
            v.updatePadding(left = displayCutout.left, right = displayCutout.right)
            if (view.resources.isLandscape) {
                val systemBars = insets.getInsets(Type.systemBars())
                v.updatePadding(top = systemBars.top)
            }
            insets
        }
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

        androidx.core.content.ContextCompat.registerReceiver(
            context, volumeReceiver,
            android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        binding.playPauseButton.setOnClickListener(this)
        binding.shuffleButton.setOnClickListener(this)
        binding.repeatButton.setOnClickListener(this)
        binding.nextButton.setOnTouchListener(getSkipButtonTouchHandler(DIRECTION_NEXT))
        binding.previousButton.setOnTouchListener(getSkipButtonTouchHandler(DIRECTION_PREVIOUS))
    }

    override fun onClick(view: View) {
        super.onClick(view)
        when (view) {
            binding.shuffleButton -> playerViewModel.toggleShuffleMode()
            binding.repeatButton -> playerViewModel.cycleRepeatMode()
            binding.playPauseButton -> playerViewModel.togglePlayPause()
        }
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        val oldControlColor = binding.nextButton.iconTint.defaultColor
        val oldSliderColor = binding.progressSlider.currentColor
        val oldPrimaryTextColor = binding.title.currentTextColor
        val oldSecondaryTextColor = binding.text.currentTextColor

        val volumeDownIcon = view?.findViewById<ImageView>(R.id.volumeDownIcon)
        val volumeUpIcon = view?.findViewById<ImageView>(R.id.volumeUpIcon)
        val volumeSlider = view?.findViewById<SeekBar>(R.id.volumeSlider)
        val oldVolumeIconColor = volumeDownIcon?.imageTintList?.defaultColor ?: oldSecondaryTextColor

        val oldShuffleColor = getPlaybackControlsColor(isShuffleModeOn)
        val newShuffleColor = getPlaybackControlsColor(isShuffleModeOn, scheme.onSurfaceColor, scheme.onSurfaceVariantColor)
        val oldRepeatColor = getPlaybackControlsColor(isRepeatModeOn)
        val newRepeatColor = getPlaybackControlsColor(isRepeatModeOn, scheme.onSurfaceColor, scheme.onSurfaceVariantColor)

        volumeSlider?.let { slider ->
            val activeList = android.content.res.ColorStateList.valueOf(scheme.onSurfaceVariantColor)
            if (slider.progressTintList?.defaultColor != scheme.onSurfaceVariantColor) {
                slider.progressTintList = activeList
                slider.thumbTintList = activeList
            }
        }

        return listOfNotNull(
            binding.progressSlider.progressView?.tintTarget(oldSliderColor, scheme.onSurfaceColor),
            binding.menu.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.favorite.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.playPauseButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.nextButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.previousButton.iconButtonTintTarget(oldControlColor, scheme.onSurfaceColor),
            binding.shuffleButton.iconButtonTintTarget(oldShuffleColor, newShuffleColor),
            binding.repeatButton.iconButtonTintTarget(oldRepeatColor, newRepeatColor),
            binding.title.tintTarget(oldPrimaryTextColor, scheme.onSurfaceColor),
            binding.text.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            binding.songInfo.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            binding.songCurrentProgress.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            binding.songTotalTime.tintTarget(oldSecondaryTextColor, scheme.onSurfaceVariantColor),
            volumeDownIcon?.tintTarget(oldVolumeIconColor, scheme.onSurfaceVariantColor),
            volumeUpIcon?.tintTarget(oldVolumeIconColor, scheme.onSurfaceVariantColor)
        )
    }

    override fun onSongInfoChanged(currentSong: Song, nextSong: Song) {
        _binding?.let {
            it.title.text = currentSong.title
            it.text.text = getSongArtist(currentSong)
        }
    }

    override fun onExtraInfoChanged(extraInfo: String?) {
        _binding?.let {
            if (isExtraInfoEnabled()) {
                it.songInfo.text = extraInfo
                it.songInfo.isVisible = true
            } else {
                it.songInfo.isVisible = false
            }
        }
    }

    override fun onUpdatePlayPause(isPlaying: Boolean) {
        _binding?.playPauseButton?.setIconResource(if (isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp)
    }

    internal fun setFavorite(isFavorite: Boolean, withAnimation: Boolean) {
        if (this.isFavorite != isFavorite) {
            this.isFavorite = isFavorite
            playerFragment?.let { binding.favorite.setIsFavorite(isFavorite, withAnimation) }
        }
    }

    override fun onDestroyView() {
        runCatching { context?.unregisterReceiver(volumeReceiver) }
        super.onDestroyView()
        _binding = null
    }
}