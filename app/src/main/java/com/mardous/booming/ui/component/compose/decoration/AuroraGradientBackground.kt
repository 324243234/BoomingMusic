package com.mardous.booming.ui.component.compose.decoration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// 🌟 CarWith 工况节流时钟 (24fps，大幅降低 H.264 编码压力与发热)
private const val FRAME_INTERVAL_MS = 42L

/**
 * 🚀 Apple Music 级动态流体背景 (CarWith 终极性能版)
 * 架构：Halcyon 源码级色彩提纯 + 纯 CPU 微缩画布色块混合 + 高度防闪烁过渡
 */
@Composable
fun AuroraGradientBackground(
    coverBitmap: Bitmap?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // --- 🛡️ 硬件工况熔断机制 (防断连) ---
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

    // 🌟 步骤 1：后台异步执行 Halcyon 天才取色算法，提取 4 个高纯度颜色
    val fluidColors by produceState<List<Color>?>(initialValue = null, coverBitmap) {
        if (coverBitmap == null) {
            value = null
        } else {
            value = withContext(Dispatchers.Default) {
                extractHalcyonPalette(coverBitmap)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A0E)).clipToBounds()) {
        
        // 🌟 步骤 2：手写盖楼式单向溶解过渡 (防闪黑漏底)
        AnimatedContent(
            targetState = fluidColors,
            transitionSpec = {
                (fadeIn(tween(1200, easing = LinearEasing)) togetherWith fadeOut(tween(durationMillis = 10, delayMillis = 1200)))
                    .apply { targetContentZIndex = 1f }
            },
            label = "MagmaColorCrossfade"
        ) { colors ->
            if (colors != null && colors.size >= 4) {
                // 将提取出的纯色交由纯 CPU 引擎绘制成岩浆
                CPUFluidEngine(colors, sharedClockMs)
            } else {
                Box(Modifier.fillMaxSize())
            }
        }

        // --- 🛡️ 护眼压层：针对车机驾驶视角优化对比度 ---
        Box(modifier = Modifier.fillMaxSize().background(Color(0x33000000)))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0x45000000), Color.Transparent, Color(0x55000000))
                    )
                )
        )
    }
}

/** 
 * 🚀 极致省电版岩浆引擎：在微缩画布上画彩色圆圈，然后用 Box Blur 糊成流体
 * 完美还原 Shader 的效果，但 0% GPU 负载！
 */
@Composable
private fun CPUFluidEngine(colors: List<Color>, sharedClockMs: Long) {
    val context = LocalContext.current
    val densityDpi = context.resources.displayMetrics.densityDpi
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val scaledTimeMs = scaledAppleFlowTimeMs(sharedClockMs, 10)
    val frameTimeMs = (scaledTimeMs / FRAME_INTERVAL_MS) * FRAME_INTERVAL_MS

    val frameBitmap by produceState<Bitmap?>(
        initialValue = null,
        colors, viewportSize, frameTimeMs, densityDpi
    ) {
        val w = viewportSize.width
        val h = viewportSize.height
        if (w > 0 && h > 0) {
            value = withContext(Dispatchers.Default) {
                createMagmaFrameBitmap(colors, w, h, frameTimeMs, densityDpi)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged { viewportSize = it }) {
        if (frameBitmap != null) {
            Image(
                bitmap = frameBitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds // 利用系统的硬件线性过滤进行最后的平滑放大
            )
        }
    }
}

// ============================================================================
// 🛠️ Halcyon 色彩提纯与 CPU 流体底层
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

/** 
 * 100% 还原自 Halcyon 源码的提纯器：专门对付发灰、发暗的封面
 */
private fun extractHalcyonPalette(bitmap: Bitmap): List<Color> {
    val sampleStep = (max(bitmap.width, bitmap.height) / 36).coerceAtLeast(1)
    val buckets = mutableMapOf<Int, LongArray>()
    val fallback = LongArray(4)
    var sampled = 0
    var brightNeutral = 0
    var eligible = 0

    val hsv = FloatArray(3)
    for (y in 0 until bitmap.height step sampleStep) {
        for (x in 0 until bitmap.width step sampleStep) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = android.graphics.Color.alpha(pixel)
            if (alpha > 24) {
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                android.graphics.Color.RGBToHSV(r, g, b, hsv)
                val sat = hsv[1]
                val lum = hsv[2]

                sampled++
                fallback[0]++
                fallback[1] += r.toLong()
                fallback[2] += g.toLong()
                fallback[3] += b.toLong()

                if (lum > 0.78f && sat < 0.18f) brightNeutral++

                // 剔除纯黑和高亮死区
                if (lum > 0.08f && !(lum > 0.94f && sat < 0.20f)) {
                    eligible++
                    val key = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
                    val bucket = buckets.getOrPut(key) { LongArray(4) }
                    bucket[0]++
                    bucket[1] += r.toLong()
                    bucket[2] += g.toLong()
                    bucket[3] += b.toLong()
                }
            }
        }
    }

    val baseColor = if (fallback[0] > 0L && sampled > 0 && brightNeutral.toFloat() / sampled > 0.56f && eligible.toFloat() / sampled < 0.24f) {
        val count = fallback[0].coerceAtLeast(1L)
        Color((fallback[1] / count).toInt(), (fallback[2] / count).toInt(), (fallback[3] / count).toInt())
    } else {
        // Halcyon 核心算法：权重偏爱高饱和度色彩！
        val best = buckets.values.maxByOrNull { bucket ->
            val count = bucket[0].coerceAtLeast(1L)
            val r = (bucket[1] / count).toInt()
            val g = (bucket[2] / count).toInt()
            val b = (bucket[3] / count).toInt()
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            val luminance = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            val balance = 1f - abs(luminance - 0.50f).coerceIn(0f, 0.50f) * 1.25f
            count.toFloat() * (0.55f + hsv[1] * 1.65f) * (0.75f + balance * 0.55f)
        } ?: fallback

        val count = best[0].coerceAtLeast(1L)
        Color((best[1] / count).toInt(), (best[2] / count).toInt(), (best[3] / count).toInt())
    }

    val finalHsv = FloatArray(3)
    android.graphics.Color.colorToHSV(baseColor.toArgb(), finalHsv)
    
    // 如果依然是黑白灰，赋予高级深海蓝底色，否则强行拉升鲜艳度
    if (finalHsv[1] < 0.12f) { 
        finalHsv[0] = 220f; finalHsv[1] = 0.65f; finalHsv[2] = 0.75f
    } else {
        finalHsv[1] = finalHsv[1].coerceAtLeast(0.48f) 
        finalHsv[2] = finalHsv[2].coerceIn(0.50f, 0.90f)
    }

    val primary = Color(android.graphics.Color.HSVToColor(finalHsv))
    
    // 衍生出 3 个相近但有区分度的互补色，用于画流体的团块
    fun derive(shift: Float, satMod: Float, valMod: Float): Color {
        val dHsv = finalHsv.copyOf()
        dHsv[0] = (dHsv[0] + shift + 360f) % 360f
        dHsv[1] = (dHsv[1] * satMod).coerceIn(0.4f, 1f)
        dHsv[2] = (dHsv[2] * valMod).coerceIn(0.4f, 1f)
        return Color(android.graphics.Color.HSVToColor(dHsv))
    }

    return listOf(
        primary,
        derive(35f, 1.1f, 0.9f),
        derive(-30f, 0.95f, 1.05f),
        derive(55f, 1.05f, 0.85f)
    )
}

/** 
 * 在微缩画布上画圆圈并进行 Box Blur，产生类似岩浆的效果 
 */
private fun createMagmaFrameBitmap(
    colors: List<Color>,
    viewportW: Int,
    viewportH: Int,
    timeMs: Long,
    densityDpi: Int
): Bitmap {
    // 极致降维，全高清屏幕计算的宽高仅几十像素
    val downsample = if (densityDpi >= 420) 24f else 16f
    val w = (viewportW / downsample).roundToInt().coerceAtLeast(1)
    val h = (viewportH / downsample).roundToInt().coerceAtLeast(1)
    
    val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(frame)

    // 铺底色
    canvas.drawColor(colors[0].toArgb())

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val t = timeMs / 1000f // 转换为秒

    val radius = max(w, h) * 0.75f // 超大色块互相覆盖，模糊后才会像液体

    // 色块 1：逆时针画圆运动
    paint.color = colors[1].toArgb()
    val cx1 = w / 2f + sin(t * 0.35f) * (w * 0.4f)
    val cy1 = h / 2f + cos(t * 0.28f) * (h * 0.4f)
    canvas.drawCircle(cx1.toFloat(), cy1.toFloat(), radius, paint)

    // 色块 2：顺时针画圆运动
    paint.color = colors[2].toArgb()
    val cx2 = w / 2f + cos(t * 0.42f) * (w * 0.45f)
    val cy2 = h / 2f + sin(t * 0.38f) * (h * 0.45f)
    canvas.drawCircle(cx2.toFloat(), cy2.toFloat(), radius, paint)

    // 色块 3：穿梭运动
    paint.color = colors[3].toArgb()
    val cx3 = w / 2f - sin(t * 0.25f) * (w * 0.35f)
    val cy3 = h / 2f - cos(t * 0.45f) * (h * 0.35f)
    canvas.drawCircle(cx3.toFloat(), cy3.toFloat(), radius, paint)

    // 强力 CPU 均值模糊，将圆形彻底糊成不可分辨的流体
    return blurBitmapFast(frame, 15)
}

/** 纯 CPU 双通道极速均值模糊 */
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