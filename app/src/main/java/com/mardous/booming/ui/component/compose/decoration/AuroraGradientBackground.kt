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
 * 🚀 Apple Music 级动态流体背景 (GPU 硬件加速版 - CarWith 终极状态)
 * 完美保留原版视觉逻辑：原图放大 + 3层错位旋转 + 2.5倍高饱和 + 硬件级高斯模糊
 * 彻底消灭 CPU 像素计算与 Bitmap 内存抖动！
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

    // 2. 核心技术：通过 ColorFilter 强行拉升 GPU 渲染管线的饱和度至 2.5 倍
    val colorFilter = remember {
        ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(2.5f) })
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A0E)).clipToBounds()) {
        if (coverBitmap != null) {
            val imageBitmap = remember(coverBitmap) { coverBitmap.asImageBitmap() }

            // 🌟 核心渲染引擎：交由 GPU 硬件级处理模糊 (RenderEffect)
            // 80.dp 的重度模糊足以将下方的旋转图层融化成毫无边界的流光
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(80.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            ) {
                // 计算三个图层的缓慢旋转角度 (完美复刻 120s, 90s, 70s 周期)
                val rot1 = (timeMs % 120_000L) / 120_000f * -360f
                val rot2 = (timeMs % 90_000L) / 90_000f * 360f
                val rot3 = (timeMs % 70_000L) / 70_000f * 360f

                // 图层 1：底层慢速基底
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(1.6f) // 放大 1.6 倍，防止旋转时露出屏幕边角
                        .graphicsLayer { rotationZ = rot1 }
                )

                // 图层 2：中层偏移旋转 (加 0.7f 透明度，使其与底层像水彩一样交融)
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
                            // 产生微小的位移差，形成漩涡感
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
        }

        // --- 🛡️ 护眼与 UI 隔离层 ---
        // 覆盖一层 30% 透明度的暗黑膜，压制过于刺眼的霓虹色
        Box(modifier = Modifier.fillMaxSize().background(Color(0x4C000000)))
        
        // 上下边缘叠加额外黑色渐变遮罩，确保歌词和状态栏文字绝对清晰
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
// 🛠️ 时间控制底层工具 (依然使用 Halcyon 优雅的节流时钟)
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