/*
 * Copyright (c) 2025 Christians Martínez Alvarado
 */

package com.mardous.booming.ui.screen.player.cover.page

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.os.BundleCompat
import androidx.core.view.updateLayoutParams
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
    private var isFirstStateEmission = true

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
            val image = albumCover as? ShapeableImageView
            
            // 🌟 解除互相干涉，双层独立运算防切角
            card?.clipToOutline = false
            card?.clipChildren = false

            viewLifecycleOwner.lifecycleScope.launch {
                VideoCoverStateManager.states.collect { states ->
                    val newState = states[song.id] ?: CoverShapeState.UNKNOWN
                    
                    if (isFirstStateEmission) {
                        isFirstStateEmission = false
                        currentShapeState = newState
                        when (newState) {
                            CoverShapeState.CIRCLE -> applyCircleModernStyleInstant(card, image)
                            else -> applySquareStyleInstant(card, image)
                        }
                    } else {
                        if (newState != currentShapeState) {
                            if (newState == CoverShapeState.CIRCLE) {
                                animateMorphToCircle(card, image) // 🎬 启动平滑形变动画
                            } else if (newState == CoverShapeState.SQUARE) {
                                applySquareStyleInstant(card, image)
                            }
                            currentShapeState = newState
                        }
                    }
                }
            }

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
            // 原版其它主题逻辑，不干涉
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

    // ==========================================
    // 🎬 顶级动效：纯 GPU 缩放，绝不干涉 Layout 边距
    // ==========================================
    private fun animateMorphToCircle(card: MaterialCardView?, image: ShapeableImageView?) {
        if (image == null) return
        val density = resources.displayMetrics.density
        
        val defaultCorner = Preferences.getNowPlayingImageCornerRadius(requireContext()) * density
        val maxRadius = Math.min(image.width, image.height) / 2f
        if (maxRadius <= 0f) {
            applyCircleModernStyleInstant(card, image)
            return
        }

        // 🌟 核心：只用 scale 控制图片缩小，绝不改变任何 margin，保证对齐不崩坏！
        val targetScale = 0.82f 
        val maxStroke = (1.5f * density).toInt()

        rotationAnimator?.cancel()
        card?.rotation = 0f
        image.rotation = 0f

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850L // 850毫秒平滑收缩
            interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
            
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                
                // 1. 封面平滑往里缩放 (而不是挤边距)
                val scale = 1.0f - (1.0f - targetScale) * fraction
                image.scaleX = scale
                image.scaleY = scale
                
                // 2. 方形圆角 -> 完美正圆的平滑过渡
                val currentCorner = defaultCorner + (maxRadius - defaultCorner) * fraction
                image.shapeAppearanceModel = image.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(AbsoluteCornerSize(currentCorner))
                    .build()
                card?.radius = currentCorner
                
                // 3. 科技感玻璃底盘渐显 (深空蓝黑 #33001525)
                val bgAlpha = (0x33 * fraction).toInt()
                card?.setCardBackgroundColor(android.graphics.Color.argb(bgAlpha, 0x00, 0x15, 0x25))
                
                // 4. 3D 高光边框渐显 (半透明银白 #66FFFFFF)
                card?.strokeWidth = (maxStroke * fraction).toInt()
                val strokeAlpha = (0x66 * fraction).toInt()
                card?.strokeColor = android.graphics.Color.argb(strokeAlpha, 0xFF, 0xFF, 0xFF)
            }
        }
        
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                image.shapeAppearanceModel = image.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(RelativeCornerSize(0.5f))
                    .build()
                startRotation(card ?: image)
            }
        })
        
        animator.start()
    }

    // 🎯 瞬间科技悬窗（切歌已知无视频时直切）
    private fun applyCircleModernStyleInstant(card: MaterialCardView?, image: ShapeableImageView?) {
        if (image == null) return
        val density = resources.displayMetrics.density
        
        image.scaleX = 0.82f
        image.scaleY = 0.82f
        image.shapeAppearanceModel = image.shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(RelativeCornerSize(0.5f))
            .build()
            
        card?.radius = 10000f
        card?.setCardBackgroundColor(android.graphics.Color.parseColor("#33001525"))
        card?.strokeWidth = (1.5f * density).toInt()
        card?.strokeColor = android.graphics.Color.parseColor("#66FFFFFF") 
        
        startRotation(card ?: image)
    }

    // 🎯 完美回归方形：重置 Scale 比例，尺寸将再次和动态视频绝对 100% 对齐！
    private fun applySquareStyleInstant(card: MaterialCardView?, image: ShapeableImageView?) {
        if (image == null) return
        val density = resources.displayMetrics.density
        
        rotationAnimator?.cancel()
        card?.rotation = 0f
        image.rotation = 0f
        
        // 🌟 核心修复点：将 Scale 恢复到 1.0f 即可，彻底删除了 setMargins 的脏代码！
        image.scaleX = 1.0f
        image.scaleY = 1.0f
        
        val cornerRadius = Preferences.getNowPlayingImageCornerRadius(requireContext())
        val cornerRadiusPx = cornerRadius.toFloat() * density
        
        image.shapeAppearanceModel = image.shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(AbsoluteCornerSize(cornerRadiusPx))
            .build()
            
        card?.radius = cornerRadiusPx
        card?.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
        card?.strokeWidth = 0
    }

    private fun startRotation(targetView: View?) {
        if (targetView == null) return
        
        if (rotationAnimator == null) {
            rotationAnimator = ObjectAnimator.ofFloat(targetView, View.ROTATION, 0f, 360f).apply {
                duration = 40000L 
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
        fun onColorReady(color: PaletteColor, request: Int)
    }

    companion object {
        fun newInstance(song: Song) = ImageFragment().withArgs {
            putParcelable(EXTRA_SONG, song)
        }
    }
}