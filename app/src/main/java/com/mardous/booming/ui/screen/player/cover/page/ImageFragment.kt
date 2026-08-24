/*
 * Copyright (c) 2025 Christians Martínez Alvarado
 */

package com.mardous.booming.ui.screen.player.cover.page

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.os.BundleCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMarginsRelative
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil3.dispose
import coil3.request.Disposable
import coil3.request.crossfade
import coil3.toBitmap
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.AbsoluteCornerSize
import com.google.android.material.shape.RelativeCornerSize
import com.mardous.booming.R
import com.mardous.booming.coil.songImage
import com.mardous.booming.core.model.PaletteColor
import com.mardous.booming.core.model.theme.NowPlayingScreen
import com.mardous.booming.core.palette.PaletteProcessor
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.EXTRA_SONG
import com.mardous.booming.extensions.requestView
import com.mardous.booming.extensions.resources.setCornerRadius
import com.mardous.booming.extensions.withArgs
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.activityViewModel

// 🌟 跨组件视频状态共享器
enum class CoverShapeState { UNKNOWN, SQUARE, CIRCLE }

object VideoCoverStateManager {
    val states = MutableStateFlow<Map<Long, CoverShapeState>>(emptyMap())
    fun updateState(songId: Long, state: CoverShapeState) {
        states.value = states.value + (songId to state)
    }
}

class ImageFragment : Fragment() {

    private val playerViewModel: PlayerViewModel by activityViewModel()

    private var isColorReady = false
    private lateinit var color: PaletteColor
    private lateinit var song: Song
    private var colorReceiver: ColorReceiver? = null
    private var request = 0

    private var disposable: Disposable? = null
    private var albumCover: ImageView? = null
    
    private var rotationAnimator: ObjectAnimator? = null
    private var currentShapeState = CoverShapeState.UNKNOWN

    private val nowPlayingScreen: NowPlayingScreen
        get() = Preferences.nowPlayingScreen

    private fun getLayoutWithPlayerTheme(): Int {
        if (nowPlayingScreen.supportsCarouselEffect) {
            if (Preferences.isCarouselEffect) {
                return R.layout.fragment_album_cover_carousel
            }
        }
        return nowPlayingScreen.albumCoverLayoutRes
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        song = BundleCompat.getParcelable(requireArguments(), EXTRA_SONG, Song::class.java)!!
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(getLayoutWithPlayerTheme(), container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        albumCover = view.findViewById(R.id.player_image)
        setupImageStyle()
        loadAlbumCover()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rotationAnimator?.cancel()
        rotationAnimator = null
        albumCover?.dispose()
        colorReceiver = null
    }

    private fun setupImageStyle() {
        if (nowPlayingScreen == NowPlayingScreen.Plain) {
            val card = requestView { it.findViewById<View>(R.id.player_image_card) } as? MaterialCardView
            card?.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            card?.strokeWidth = 0
            card?.radius = 0f
            card?.cardElevation = 0f

            // 监听形状状态，采用零CPU开销的硬件加速切换
            viewLifecycleOwner.lifecycleScope.launch {
                VideoCoverStateManager.states.collect { states ->
                    val newState = states[song.id] ?: CoverShapeState.UNKNOWN
                    if (newState != currentShapeState) {
                        currentShapeState = newState
                        applyShapeState(newState)
                    }
                }
            }

            // 音乐播放状态随动
            viewLifecycleOwner.lifecycleScope.launch {
                playerViewModel.isPlayingFlow.collect { isPlaying ->
                    if (isPlaying) {
                        if (rotationAnimator?.isPaused == true) rotationAnimator?.resume()
                        else if (rotationAnimator?.isRunning == false && currentShapeState == CoverShapeState.CIRCLE) {
                            rotationAnimator?.start()
                        }
                    } else {
                        rotationAnimator?.pause()
                    }
                }
            }
        } else {
            // 原版其它主题逻辑，绝不干涉
            if (!nowPlayingScreen.supportsCustomCornerRadius) return
            val cornerRadius = Preferences.getNowPlayingImageCornerRadius(requireContext())
            when (val image = albumCover) {
                is ShapeableImageView -> image.setCornerRadius(cornerRadius.toFloat())
                else -> {
                    val fallbackCard = requestView { it.findViewById<View>(R.id.player_image_card) }
                    if (fallbackCard is MaterialCardView) fallbackCard.setCornerRadius(cornerRadius.toFloat())
                }
            }
            if (nowPlayingScreen.supportsSmallImage && Preferences.isSmallImage) {
                val carouselCard = requestView { it.findViewById<View>(R.id.player_image_card) }
                if (carouselCard == null) {
                    albumCover?.let { image ->
                        image.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                            updateMarginsRelative(start = marginStart * 2, end = marginEnd * 2)
                        }
                    }
                }
            }
        }
    }

    private fun applyShapeState(state: CoverShapeState) {
        val image = albumCover as? ShapeableImageView ?: return
        val density = resources.displayMetrics.density

        when (state) {
            CoverShapeState.CIRCLE -> {
                // 1. 完美复刻《夜航星》参考图质感：全画面铺满 + 20%透明度边缘玻璃光环
                image.setPadding(0, 0, 0, 0)
                image.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                image.strokeWidth = 14f * density
                image.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#33000000"))
                image.shapeAppearanceModel = image.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(RelativeCornerSize(0.5f))
                    .build()
                
                startRotation()
            }
            else -> {
                // SQUARE 或 UNKNOWN：回归静态方形底盘
                rotationAnimator?.cancel()
                image.rotation = 0f
                image.setPadding(0, 0, 0, 0)
                image.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                image.strokeWidth = 0f
                
                val cornerRadius = Preferences.getNowPlayingImageCornerRadius(requireContext())
                image.shapeAppearanceModel = image.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(AbsoluteCornerSize(cornerRadius.toFloat() * density))
                    .build()
            }
        }
    }

    private fun startRotation() {
        val image = albumCover ?: return
        if (rotationAnimator == null) {
            rotationAnimator = ObjectAnimator.ofFloat(image, View.ROTATION, 0f, 360f).apply {
                duration = 40000L // 40秒一圈极度慵懒的高级转速
                interpolator = android.view.animation.LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
            }
        }
        if (playerViewModel.isPlaying && rotationAnimator?.isRunning != true) {
            rotationAnimator?.start()
        }
    }

    private fun loadAlbumCover() {
        disposable?.dispose()
        disposable = albumCover?.songImage(song) {
            crossfade(false)
            memoryCacheKey("nowplaying:song:${song.id}")
            listener(
                onError = { _, _ ->
                    context?.let { setPalette(PaletteColor.errorColor(it)) }
                },
                onSuccess = { _, result ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val color = withContext(Dispatchers.Default) {
                            context?.let { fragmentCtx ->
                                PaletteProcessor.getPaletteColor(fragmentCtx, result.image.toBitmap())
                            }
                        }
                        if (isActive && color != null) {
                            setPalette(color)
                        }
                    }
                }
            )
        }
    }

    private fun setPalette(color: PaletteColor) {
        this.color = color
        isColorReady = true
        if (colorReceiver != null) {
            colorReceiver!!.onColorReady(color, request)
            colorReceiver = null
        }
    }

    fun receivePalette(paletteReceiver: ColorReceiver, request: Int) {
        if (isColorReady) {
            paletteReceiver.onColorReady(color, request)
        } else {
            this.colorReceiver = paletteReceiver
            this.request = request
        }
    }

    interface ColorReceiver {
        // 🌟 修复编译崩溃：将手误打错的 fn 改回正统的 fun 关键字
        fun onColorReady(color: PaletteColor, request: Int)
    }

    companion object {
        fun newInstance(song: Song) = ImageFragment().withArgs {
            putParcelable(EXTRA_SONG, song)
        }
    }
}