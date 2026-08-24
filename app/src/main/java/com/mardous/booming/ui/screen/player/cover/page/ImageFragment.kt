/*
 * Copyright (c) 2025 Christians Martínez Alvarado
 */

package com.mardous.booming.ui.screen.player.cover.page

import androidx.core.view.updateMarginsRelative
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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil3.dispose
import coil3.request.Disposable
import coil3.request.crossfade
import coil3.toBitmap
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
        if (nowPlayingScreen.supportsCarouselEffect && Preferences.isCarouselEffect) {
            return R.layout.fragment_album_cover_carousel
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
            val image = albumCover as? ShapeableImageView ?: return
            
            // 剥离多余外部约束，完全交给原生 ShapeableImageView 处理
            image.setPadding(0, 0, 0, 0)
            
            viewLifecycleOwner.lifecycleScope.launch {
                VideoCoverStateManager.states.collect { states ->
                    val newState = states[song.id] ?: CoverShapeState.UNKNOWN
                    
                    if (isFirstStateEmission) {
                        isFirstStateEmission = false
                        currentShapeState = newState
                        if (newState == CoverShapeState.CIRCLE) {
                            applyCircleModernStyleInstant(image)
                        } else {
                            applySquareStyleInstant(image)
                        }
                    } else {
                        if (newState != currentShapeState) {
                            if (newState == CoverShapeState.CIRCLE) {
                                animateMorphToCircle(image) // 🎬 绝美形变动画起飞！
                            } else if (newState == CoverShapeState.SQUARE) {
                                applySquareStyleInstant(image)
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
            (albumCover as? ShapeableImageView)?.setCornerRadius(cornerRadius.toFloat())
        }
    }

    // ==========================================
    // 🎬 核心动效：从方形外围缓缓收缩成 3D 悬窗圆盘
    // ==========================================
    private fun animateMorphToCircle(image: ShapeableImageView) {
        // 🛡️ 终极防切硬修复：如果宽高等于0（布局未完成），放入队列等它准备好再动画！
        if (image.width == 0 || image.height == 0) {
            image.post { animateMorphToCircle(image) }
            return
        }

        val density = resources.displayMetrics.density
        val defaultCorner = Preferences.getNowPlayingImageCornerRadius(requireContext()) * density
        val targetCorner = Math.min(image.width, image.height) / 2f
        val targetStrokeWidth = 6f * density // 科技感玻璃倒角宽度

        rotationAnimator?.cancel()
        image.rotation = 0f

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850L // 850毫秒：极其平滑、从容的收缩过程
            interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
            
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                
                // 1. 方形圆角 -> 完美正圆的丝滑收敛
                val currentCorner = defaultCorner + (targetCorner - defaultCorner) * fraction
                image.shapeAppearanceModel = image.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(AbsoluteCornerSize(currentCorner))
                    .build()
                
                // 2. 科技感 3D 玻璃倒角渐显 (极其高级的半透明白 #40FFFFFF)
                image.strokeWidth = targetStrokeWidth * fraction
                val alpha = (0x40 * fraction).toInt() // Alpha 渐变到 25% 的高光边缘
                image.strokeColor = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.argb(alpha, 255, 255, 255)
                )
            }
        }
        
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // 动画结束，锁定相对比例，防止车机分屏时被拉伸成椭圆
                image.shapeAppearanceModel = image.shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(RelativeCornerSize(0.5f))
                    .build()
                startRotation(image)
            }
        })
        
        animator.start()
    }

    // 🎯 瞬间呈现科技圆盘（切歌前已知无视频）
    private fun applyCircleModernStyleInstant(image: ShapeableImageView) {
        val density = resources.displayMetrics.density
        
        image.shapeAppearanceModel = image.shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(RelativeCornerSize(0.5f))
            .build()
            
        // 瞬间满配 3D 玻璃光泽
        image.strokeWidth = 6f * density
        image.strokeColor = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.argb(0x40, 255, 255, 255)
        )
        
        startRotation(image)
    }

    // 🎯 回归静态方形（视频准备就绪，尺寸与视频层 100% 一致）
    private fun applySquareStyleInstant(image: ShapeableImageView) {
        val density = resources.displayMetrics.density
        
        rotationAnimator?.cancel()
        image.rotation = 0f
        image.strokeWidth = 0f // 撤去光泽
        
        val cornerRadiusPx = Preferences.getNowPlayingImageCornerRadius(requireContext()) * density
        image.shapeAppearanceModel = image.shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(AbsoluteCornerSize(cornerRadiusPx))
            .build()
    }

    private fun startRotation(targetView: View) {
        if (rotationAnimator == null) {
            rotationAnimator = ObjectAnimator.ofFloat(targetView, View.ROTATION, 0f, 360f).apply {
                duration = 40000L // 40秒慵懒的高级转速
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