/*
 * Copyright (c) 2025 Christians Martínez Alvarado
 */

package com.mardous.booming.ui.screen.player.cover.page

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
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
import com.google.android.material.shape.ShapeAppearanceModel
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class ImageFragment : Fragment() {

    // 🌟 注入全局播放器状态，用于控制唱片随音乐启停
    private val playerViewModel: PlayerViewModel by activityViewModel()

    private var isColorReady = false
    private lateinit var color: PaletteColor
    private lateinit var song: Song
    private var colorReceiver: ColorReceiver? = null
    private var request = 0

    private var disposable: Disposable? = null
    private var albumCover: ImageView? = null
    
    // 🌟 旋转动画对象
    private var rotationAnimator: ObjectAnimator? = null

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
        // 🌟 专属隔离：只有在 Plain 主题下，才启用黑胶旋转模式！
        if (nowPlayingScreen == NowPlayingScreen.Plain) {
            when (val image = albumCover) {
                is ShapeableImageView -> {
                    // 1. 切割成纯正的圆形 (PILL)
                    image.shapeAppearanceModel = image.shapeAppearanceModel.toBuilder()
                        .setAllCornerSizes(ShapeAppearanceModel.PILL)
                        .build()

                    // 2. 复刻参考图中的深邃边缘质感
                    val density = resources.displayMetrics.density
                    image.strokeWidth = 6f * density // 边缘黑胶刻录边框宽度
                    image.strokeColor = ColorStateList.valueOf(Color.parseColor("#111115")) // 极夜黑深灰色
                }
            }

            // 3. 初始化旋转黑胶引擎（转速：25秒一圈，呈现慵懒复古感，线性匀速不卡顿）
            rotationAnimator = ObjectAnimator.ofFloat(albumCover, View.ROTATION, 0f, 360f).apply {
                duration = 25000L 
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
            }

            // 4. 智能随动：音乐播放则转，暂停则停
            viewLifecycleOwner.lifecycleScope.launch {
                playerViewModel.isPlayingFlow.collect { isPlaying ->
                    if (isPlaying) {
                        if (rotationAnimator?.isPaused == true) {
                            rotationAnimator?.resume()
                        } else if (rotationAnimator?.isRunning == false) {
                            rotationAnimator?.start()
                        }
                    } else {
                        rotationAnimator?.pause()
                    }
                }
            }
        } else {
            // ==========================================
            // 传统的方形/圆角处理逻辑，不对其他主题造成任何污染
            // ==========================================
            if (!nowPlayingScreen.supportsCustomCornerRadius) return

            val cornerRadius = Preferences.getNowPlayingImageCornerRadius(requireContext())
            when (val image = albumCover) {
                is ShapeableImageView -> image.setCornerRadius(cornerRadius.toFloat())
                else -> {
                    val card = requestView { it.findViewById<View>(R.id.player_image_card) }
                    if (card is MaterialCardView) {
                        card.setCornerRadius(cornerRadius.toFloat())
                    }
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

    private fun loadAlbumCover() {
        disposable?.dispose()
        disposable = albumCover?.songImage(song) {
            crossfade(false)
            memoryCacheKey("nowplaying:song:${song.id}")
            listener(
                onError = { request, result ->
                    context?.let {
                        setPalette(PaletteColor.errorColor(it))
                    }
                },
                onSuccess = { request, result ->
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
        fun onColorReady(color: PaletteColor, request: Int)
    }

    companion object {
        fun newInstance(song: Song) = ImageFragment().withArgs {
            putParcelable(EXTRA_SONG, song)
        }
    }
}