package com.mardous.booming.ui.component.compose.decoration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb // 🌟 修复: 补回扩展函数导包
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

// 🌟 专为 CarWith 视频流优化的节流时钟 (约 24 FPS)
// 极致省电，降低 H.264 编码压力，防止车机互联时手机发热降频
private const val FRAME_INTERVAL_MS = 42L

/**
 * 🚀 Apple Music 级动态流体背景 (CarWith 终极性能版)
 * 核心逻辑：基于 Halcyon 的极小分辨率离屏渲染 + 多轨道错位旋转 + 重度均值模糊
 */
@Composable
fun AuroraGradientBackground(
    coverBitmap: Bitmap?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val densityDpi = context.resources.displayMetrics.densityDpi

    // --- 🛡️ 硬件工况熔断机制 (专为车机环境设计) ---
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

        // 🌟 修复: 更正 Android 版本常量名为 Build.VERSION_CODES.Q
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

    // 只有在非省电、未过热、电量充足时才播放动态效果
    val shouldAnimate = !isPowerSaveMode && !isOverheating && !isLowBattery

    // --- 🎨 核心渲染管线 ---
    
    // 1. 初次降采样：将原图压缩至 256x256，避免浪费内存带宽
    val sourceBitmap = remember(coverBitmap) { coverBitmap?.scaledForFlowSource() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // 2. 挂载全局节流时钟 (24fps 步进)
    val sharedClockMs = rememberThrottledFlowTimeMs(sourceBitmap, shouldAnimate)
    val frameTimeMs = (sharedClockMs / FRAME_INTERVAL_MS) * FRAME_INTERVAL_MS

    // 3. 定义模糊度与护眼压层 (保证车机歌词的高对比度可读性)
    val normalizedBlur = 65f 
    val washColor = Color(0x35000005).toArgb() // 浅浅的深色压层

    // 4. 异步生产微缩模糊帧 (CPU 密集型操作，放在 Default 线程)
    val frameBitmap by produceState<Bitmap?>(
        initialValue = null,
        sourceBitmap,
        viewportSize,
        frameTimeMs,
        normalizedBlur,
        densityDpi,
        washColor
    ) {
        val cover = sourceBitmap
        val w = viewportSize.width
        val h = viewportSize.height
        if (cover == null || w <= 0 || h <= 0) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            createAppleFlowFrameBitmap(cover, w, h, frameTimeMs, densityDpi, normalizedBlur, washColor)
        }
    }

    // --- 🖥️ UI 上屏显示 ---
    Box(modifier = modifier.background(Color(0xFF0C0C0F))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { viewportSize = it }
        ) {
            val ready = frameBitmap
            val source = sourceBitmap
            when {
                // 首选：展示异步渲染好的超柔和流体帧
                ready != null -> Image(
                    bitmap = ready.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds // 利用硬件进行最后一次平滑放大
                )
                // 降级兜底：在计算出第一帧之前，或者因为性能熔断关闭了动画时，显示纯静态高斯模糊
                source != null -> Image(
                    bitmap = source.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur((normalizedBlur * 0.45f).dp),
                    contentScale = ContentScale.Crop,
                    alpha = 0.8f
                )
            }
        }
        
        // 🌟 护城河：屏幕上下边缘柔和黑色渐变，确保状态栏和底部控制栏文字清晰
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x35000000),
                            Color.Transparent,
                            Color(0x45000000)
                        )
                    )
                )
        )
    }
}

// ============================================================================
// 🛠️ Halcyon 核心底层算法 (纯净移植版)
// ============================================================================

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

private fun Bitmap.scaledForFlowSource(maxDimension: Int = 256): Bitmap {
    val longest = max(width, height)
    if (longest <= maxDimension || longest <= 0) return this
    val scale = maxDimension.toFloat() / longest
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1),
        true
    )
}

private fun appleFlowDownsampleFactor(densityDpi: Int): Float = if (densityDpi >= 420) 24f else 16f

/**
 * 在极小分辨率的 Bitmap 上，利用 CPU 绘制多层旋转叠加，并执行均值模糊
 */
private fun createAppleFlowFrameBitmap(
    cover: Bitmap,
    viewportW: Int,
    viewportH: Int,
    timeMs: Long,
    densityDpi: Int,
    blur: Float,
    washColor: Int
): Bitmap {
    // 1. 极致降维：屏幕尺寸缩小 16 或 24 倍
    val downsample = appleFlowDownsampleFactor(densityDpi)
    val w = ((viewportW * 1.3f) / downsample).roundToInt().coerceAtLeast(1)
    val h = ((viewportH * 1.3f) / downsample).roundToInt().coerceAtLeast(1)
    val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(frame)

    // 2. 防露底计算：放大 1.3 倍以保证无论怎么旋转都不会露出边界
    val diagonal = (max(w, h) * 1.3f).roundToInt().coerceAtLeast(1).toFloat()
    val coverScale = diagonal / max(cover.height, 1)
    val translateX = -(diagonal - w) / 2f
    val translateY = -(diagonal - h) / 2f
    val rotatePivot = diagonal / 2f
    val centerX = w / 2f
    val centerY = h / 2f

    // 3. Apple Music 灵魂：2.5倍色彩饱和度提纯，让所有颜色极其鲜艳通透
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(2.5f) })
    }

    // 4. 三轨流体力学重构：
    // 通过三个图层极度缓慢（70秒-120秒）的反向及偏移旋转，视觉上形成有机液体的揉捏感
    val rot = (timeMs % 70_000L) / 70_000f * 360f
    
    // 图层 1 (底层)
    drawFlowLayer(
        canvas, cover, paint, coverScale, rotatePivot, translateX, translateY,
        w.toFloat(), h.toFloat(), centerX, centerY,
        rotation = (timeMs % 120_000L) / 120_000f * -360f, offsetXFactor = 0f, offsetYFactor = 0f, extraRotation = null
    )
    // 图层 2 (中层交叉)
    drawFlowLayer(
        canvas, cover, paint, coverScale, rotatePivot, translateX, translateY,
        w.toFloat(), h.toFloat(), centerX, centerY,
        rotation = (timeMs % 90_000L) / 90_000f * 360f, offsetXFactor = -0.95f, offsetYFactor = -0.7f, extraRotation = null
    )
    // 图层 3 (顶层旋涡)
    drawFlowLayer(
        canvas, cover, paint, coverScale, rotatePivot, translateX, translateY,
        w.toFloat(), h.toFloat(), centerX, centerY,
        rotation = rot, offsetXFactor = -0.5f, offsetYFactor = 0.7f, extraRotation = rot
    )

    // 5. 覆盖一层统一的低亮度薄纱，保证 UI 界面在任何极其鲜艳的封面上都清晰可见
    canvas.drawColor(washColor)

    // 6. 执行 CPU 均值模糊，彻底消除图形的物理边界，化为流体
    val blurRadius = (((blur.coerceIn(30f, 100f) - 30f) / 70f) * 17f + 8f).roundToInt().coerceIn(8, 25)
    val blurred = blurBitmapFast(frame, blurRadius)

    // 7. 裁掉刚才为了防露边多画的 1.3 倍冗余区
    val cropW = (blurred.width / 1.3f).roundToInt().coerceIn(1, blurred.width)
    val cropH = (blurred.height / 1.3f).roundToInt().coerceIn(1, blurred.height)
    return Bitmap.createBitmap(
        blurred,
        ((blurred.width - cropW) / 2).coerceAtLeast(0),
        ((blurred.height - cropH) / 2).coerceAtLeast(0),
        cropW,
        cropH
    )
}

private fun drawFlowLayer(
    canvas: Canvas,
    cover: Bitmap,
    paint: Paint,
    scale: Float,
    rotatePivot: Float,
    translateX: Float,
    translateY: Float,
    viewW: Float,
    viewH: Float,
    centerX: Float,
    centerY: Float,
    rotation: Float,
    offsetXFactor: Float,
    offsetYFactor: Float,
    extraRotation: Float?
) {
    val matrix = Matrix()
    matrix.setScale(scale, scale)
    matrix.postRotate(rotation, rotatePivot, rotatePivot)
    matrix.postTranslate(translateX, translateY)
    if (offsetXFactor != 0f || offsetYFactor != 0f) {
        matrix.postTranslate(viewW * offsetXFactor, viewH * offsetYFactor)
    }
    if (extraRotation != null) {
        matrix.postRotate(extraRotation, centerX, centerY)
    }
    canvas.drawBitmap(cover, matrix, paint)
}

/** 极致性能：纯 CPU 实现的双通道 Box Blur (均值模糊)，专供微缩画布使用，1~2ms 内极速执行 */
private fun blurBitmapFast(bitmap: Bitmap, radius: Int): Bitmap {
    if (radius <= 0) return bitmap
    val r = radius.coerceIn(1, 25)
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 1 || height <= 1) return bitmap

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val window = r * 2 + 1

    val temp = IntArray(width * height)
    
    // 水平处理通道
    for (y in 0 until height) {
        val rowStart = y * width
        var a = 0; var red = 0; var green = 0; var blue = 0
        for (k in -r..r) {
            val p = pixels[rowStart + k.coerceIn(0, width - 1)]
            a += (p ushr 24) and 0xff
            red += (p ushr 16) and 0xff
            green += (p ushr 8) and 0xff
            blue += p and 0xff
        }
        for (x in 0 until width) {
            temp[rowStart + x] = ((a / window) shl 24) or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
            val outIdx = rowStart + (x - r).coerceIn(0, width - 1)
            val inIdx = rowStart + (x + r + 1).coerceIn(0, width - 1)
            val pOut = pixels[outIdx]
            val pIn = pixels[inIdx]
            a += ((pIn ushr 24) and 0xff) - ((pOut ushr 24) and 0xff)
            red += ((pIn ushr 16) and 0xff) - ((pOut ushr 16) and 0xff)
            green += ((pIn ushr 8) and 0xff) - ((pOut ushr 8) and 0xff)
            blue += (pIn and 0xff) - (pOut and 0xff)
        }
    }

    // 垂直处理通道
    for (x in 0 until width) {
        var a = 0; var red = 0; var green = 0; var blue = 0
        for (k in -r..r) {
            val p = temp[k.coerceIn(0, height - 1) * width + x]
            a += (p ushr 24) and 0xff
            red += (p ushr 16) and 0xff
            green += (p ushr 8) and 0xff
            blue += p and 0xff
        }
        for (y in 0 until height) {
            pixels[y * width + x] = ((a / window) shl 24) or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
            val outIdx = (y - r).coerceIn(0, height - 1) * width + x
            val inIdx = (y + r + 1).coerceIn(0, height - 1) * width + x
            val pOut = temp[outIdx]
            val pIn = temp[inIdx]
            a += ((pIn ushr 24) and 0xff) - ((pOut ushr 24) and 0xff)
            red += ((pIn ushr 16) and 0xff) - ((pOut ushr 16) and 0xff)
            green += ((pIn ushr 8) and 0xff) - ((pOut ushr 8) and 0xff)
            blue += (pIn and 0xff) - (pOut and 0xff)
        }
    }

    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    result.setPixels(pixels, 0, width, 0, 0, width, height)
    return result
}