package com.mardous.booming.ui.component.compose.decoration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// 🌟 依然保留 24fps 的节流逻辑，因为慢动作流体不需要 60fps 的过剩渲染
private const val FRAME_INTERVAL_MS = 42L

/**
 * 🚀 Apple Music 级动态流体背景 (支持切歌无缝溶解过渡)
 * 完全 GPU 硬件加速，极低功耗，绝佳沉浸感
 */
@Composable
fun AuroraGradientBackground(
    coverBitmap: Bitmap?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // --- 🛡️ 硬件工况熔断机制 ---
    var isPowerSaveMode by remember { mutableStateOf(false) }
    var isOverheating by remember { mutableStateOf(false) }
    var isLowBattery by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        isPowerSaveMode = powerManager?.isPowerSaveMode == true

        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        if (level != -1 && scale != -1) { isLowBattery = (level * 100f / scale) <= 20f }
                    }
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        isPowerSaveMode = powerManager?.isPowerSaveMode == true
                    }
                }
            }
        }
        val initialIntent = context.registerReceiver(batteryReceiver, filter)
        initialIntent?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) { isLowBattery = (level * 100f / scale) <= 20f }
        }
        onDispose { context.unregisterReceiver(batteryReceiver) }
    }

    DisposableEffect(context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager.OnThermalStatusChangedListener { status ->
                isOverheating = status >= PowerManager.THERMAL_STATUS_SEVERE
            }
        } else null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null && powerManager != null) {
            isOverheating = powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            powerManager.addThermalStatusListener(thermalListener)
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null && powerManager != null) {
                powerManager.removeThermalStatusListener(thermalListener)
            }
        }
    }

    val shouldAnimate = !isPowerSaveMode && !isOverheating && !isLowBattery

    // 1. 获取共享时间戳
    val sharedClockMs = rememberThrottledFlowTimeMs(coverBitmap, shouldAnimate)
    val timeMs = scaledAppleFlowTimeMs(sharedClockMs, 10)

    // 2. ColorFilter 强行拉升饱和度至 2.5 倍
    val colorFilter = remember {
        ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(2.5f) })
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A0E)).clipToBounds()) {
        
        // 🌟 性能大杀器：将 blur 滤镜放在最外层。切歌时 GPU 只需渲染 1 次模糊！
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(80.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
        ) {
            // 计算旋转角度 (提取到外部，保证切歌过渡时新旧封面的流体形状完美同步旋转，绝不突变)
            val rot1 = (timeMs % 120_000L) / 120_000f * -360f
            val rot2 = (timeMs % 90_000L) / 90_000f * 360f
            val rot3 = (timeMs % 70_000L) / 70_000f * 360f

            // 🌟 终极无缝溶解动画 (Crossfade 增强版)
            AnimatedContent(
                targetState = coverBitmap,
                transitionSpec = {
                    if (targetState == null) {
                        // 场景 1：如果新歌没有封面，老封面缓缓消散
                        fadeIn(tween(1200)) togetherWith fadeOut(tween(1200))
                    } else {
                        // 场景 2 (最核心)：切新歌时，新封面用 1200ms 浮现，而老封面被强行“按住”等待 1200ms 后才消失！
                        // 这样就绝对不会发生总透明度下降漏出底色的“掉闪”现象。
                        (fadeIn(tween(1200)) togetherWith fadeOut(tween(durationMillis = 10, delayMillis = 1200)))
                            .apply { targetContentZIndex = 1f }
                    }
                },
                label = "CoverFluidCrossfade"
            ) { currentCover ->
                if (currentCover != null) {
                    val imageBitmap = remember(currentCover) { currentCover.asImageBitmap() }

                    Box(modifier = Modifier.fillMaxSize()) {
                        // 图层 1：底层慢速基底
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = colorFilter,
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(1.6f)
                                .graphicsLayer { rotationZ = rot1 }
                        )

                        // 图层 2：中层偏移旋转
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = colorFilter,
                            alpha = 0.7f,
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(1.6f)
                                .graphicsLayer {
                                    rotationZ = rot2
                                    translationX = -size.width * 0.15f
                                    translationY = -size.height * 0.15f
                                }
                        )

                        // 图层 3：顶层极速叠加
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = colorFilter,
                            alpha = 0.5f,
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(1.6f)
                                .graphicsLayer {
                                    rotationZ = rot3 * 1.5f
                                    translationX = size.width * 0.15f
                                    translationY = size.height * 0.1f
                                }
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
        }

        // --- 🛡️ 护眼与 UI 隔离层 ---
        Box(modifier = Modifier.fillMaxSize().background(Color(0x4C000000)))
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0x35000000), Color.Transparent, Color(0x45000000))
                    )
                )
        )
    }
}

// ============================================================================
// 🛠️ 时间控制底层工具 
// ============================================================================

internal fun scaledAppleFlowTimeMs(elapsedMs: Long, speedTenths: Int): Long =
    elapsedMs.coerceAtLeast(0L) * speedTenths.coerceIn(5, 60) / 10L

@Composable
private fun rememberThrottledFlowTimeMs(key: Any?, animate: Boolean): Long {
    var sharedClockMs by remember(key) { mutableLongStateOf(0L) }
    LaunchedEffect(key, animate) {
        if (!animate) return@LaunchedEffect
        while (isActive) {
            val now = withFrameNanos { it }
            sharedClockMs = now / 1_000_000L
            delay(FRAME_INTERVAL_MS)
        }
    }
    return sharedClockMs
}