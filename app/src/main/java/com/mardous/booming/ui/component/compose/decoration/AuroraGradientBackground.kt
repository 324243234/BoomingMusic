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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
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

// 🌟 CarWith 工况节流时钟 (24fps，大幅降低 H.264 编码压力与发热)
private const val FRAME_INTERVAL_MS = 42L

/**
 * 🚀 Apple Music 级动态流体背景 (CarWith 终极性能版)
 * 架构：100% 还原 Halcyon 源码级底层引擎，纯 CPU 色块混合，防闪烁过渡
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
    val sharedClockMs = rememberContinuousClock(shouldAnimate)

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A0E)).clipToBounds()) {
        
        // 🌟 盖楼式单向溶解过渡 (防闪黑漏底)
        AnimatedContent(
            targetState = coverBitmap,
            transitionSpec = {
                (fadeIn(tween(1200, easing = LinearEasing)) togetherWith fadeOut(tween(durationMillis = 10, delayMillis = 1200)))
                    .apply { targetContentZIndex = 1f }
            },
            label = "MagmaColorCrossfade"
        ) { currentCover ->
            if (currentCover != null) {
                CPUFluidEngine(currentCover, sharedClockMs)
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CPUFluidEngine(coverBitmap: Bitmap, sharedClockMs: Long) {
    val context = LocalContext.current
    val densityDpi = context.resources.displayMetrics.densityDpi
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val scaledTimeMs = scaledAppleFlowTimeMs(sharedClockMs, 10)
    val frameTimeMs = (scaledTimeMs / FRAME_INTERVAL_MS) * FRAME_INTERVAL_MS

    val sourceBitmap = remember(coverBitmap) { coverBitmap.scaledForFlowSource() }
    val washPrimary = Color(0x33000000).toArgb()
    val washSecondary = Color(0x2E000000).toArgb()
    val normalizedBlur = 60f

    val frameBitmap by produceState<Bitmap?>(
        initialValue = null,
        sourceBitmap, viewportSize, frameTimeMs, densityDpi
    ) {
        val w = viewportSize.width
        val h = viewportSize.height
        if (w > 0 && h > 0) {
            value = withContext(Dispatchers.Default) {
                createAppleFlowFrameBitmap(sourceBitmap, w, h, frameTimeMs, densityDpi, normalizedBlur, washPrimary, washSecondary)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged { viewportSize = it }) {
        val ready = frameBitmap
        val source = sourceBitmap
        when {
            ready != null -> Image(
                bitmap = ready.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds 
            )
            source != null -> Image(
                bitmap = source.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur((normalizedBlur * 0.45f).dp),
                contentScale = ContentScale.Crop,
                alpha = 0.72f
            )
        }
    }
}

// ============================================================================
// 🛠️ Halcyon 色彩底层算法 (全量重构合并版)
// ============================================================================

internal fun scaledAppleFlowTimeMs(elapsedMs: Long, speedTenths: Int): Long =
    elapsedMs.coerceAtLeast(0L) * speedTenths.coerceIn(5, 60) / 10L

@Composable
private fun rememberContinuousClock(animate: Boolean): Long {
    var sharedClockMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        while (isActive) {
            sharedClockMs = withFrameNanos { it } / 1_000_000L
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

private fun createAppleFlowFrameBitmap(
    cover: Bitmap,
    viewportW: Int,
    viewportH: Int,
    timeMs: Long,
    densityDpi: Int,
    blur: Float,
    washPrimaryArgb: Int,
    washSecondaryArgb: Int
): Bitmap {
    val downsample = appleFlowDownsampleFactor(densityDpi)
    val w = ((viewportW * 1.3f) / downsample).roundToInt().coerceAtLeast(1)
    val h = ((viewportH * 1.3f) / downsample).roundToInt().coerceAtLeast(1)
    val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(frame)

    val diagonal = (max(w, h) * 1.3f).roundToInt().coerceAtLeast(1).toFloat()
    val coverScale = diagonal / max(cover.height, 1)
    val translateX = -(diagonal - w) / 2f
    val translateY = -(diagonal - h) / 2f
    val rotatePivot = diagonal / 2f
    val centerX = w / 2f
    val centerY = h / 2f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(2.5f) })
    }

    val rot = (timeMs % 70_000L) / 70_000f * 360f
    
    drawFlowLayer(
        canvas, cover, paint, coverScale, rotatePivot, translateX, translateY,
        w.toFloat(), h.toFloat(), centerX, centerY,
        rotation = (timeMs % 120_000L) / 120_000f * -360f, offsetXFactor = 0f, offsetYFactor = 0f, extraRotation = null
    )
    drawFlowLayer(
        canvas, cover, paint, coverScale, rotatePivot, translateX, translateY,
        w.toFloat(), h.toFloat(), centerX, centerY,
        rotation = (timeMs % 90_000L) / 90_000f * 360f, offsetXFactor = -0.95f, offsetYFactor = -0.7f, extraRotation = null
    )
    drawFlowLayer(
        canvas, cover, paint, coverScale, rotatePivot, translateX, translateY,
        w.toFloat(), h.toFloat(), centerX, centerY,
        rotation = rot, offsetXFactor = -0.5f, offsetYFactor = 0.7f, extraRotation = rot
    )

    canvas.drawColor(washPrimaryArgb)
    canvas.drawColor(washSecondaryArgb)

    val blurRadius = (((blur.coerceIn(30f, 100f) - 30f) / 70f) * 17f + 8f).roundToInt().coerceIn(8, 25)
    val blurred = blurBitmapFast(frame, blurRadius)

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