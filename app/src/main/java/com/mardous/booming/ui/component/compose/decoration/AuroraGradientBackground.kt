package com.mardous.booming.ui.component.compose.decoration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

/**
 * 专为 CarWith 车载与极端工况优化的极光背景
 * 具备温控熔断、低电量保护、省电模式降级及零 GC 内存开销特性
 */
@Composable
fun AuroraGradientBackground(
    colors: List<Color>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 1. 硬件状态感知：省电模式、发热状态、低电量
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
                        if (level != -1 && scale != -1) {
                            isLowBattery = (level * 100f / scale) <= 20f
                        }
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
            if (level != -1 && scale != -1) {
                isLowBattery = (level * 100f / scale) <= 20f
            }
        }

        onDispose {
            context.unregisterReceiver(batteryReceiver)
        }
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

    val shouldAnimate = isPlaying && !isPowerSaveMode && !isOverheating && !isLowBattery

    // 2. 颜色预处理与对象池化
    val c1 = remember(colors) { (colors.getOrNull(0) ?: Color(0xFF2C3E50)).boostForAurora() }
    val c2 = remember(colors) { (colors.getOrNull(1) ?: Color(0xFF3498DB)).boostForAurora() }
    val c3 = remember(colors) { (colors.getOrNull(2) ?: c1).boostForAurora() }

    val c1List = remember(c1) { listOf(c1.copy(alpha = 0.65f), Color.Transparent) }
    val c2List = remember(c2) { listOf(c2.copy(alpha = 0.55f), Color.Transparent) }
    val c3List = remember(c3) { listOf(c3.copy(alpha = 0.45f), Color.Transparent) }
    val baseBgColor = remember { Color(0xFF0C0C0F) }

    // 3. 动态时间轴控制（🌟 优化：运用 200*PI 完美数学公约数，杜绝 Float 精度溢出毛刺）
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(shouldAnimate) {
        if (shouldAnimate) {
            var lastTime = 0L
            while (isActive) {
                withInfiniteAnimationFrameMillis { frameTime ->
                    if (lastTime != 0L) {
                        val delta = (frameTime - lastTime) / 1000f
                        time = (time + delta) % 628.3185f // 200*PI 完美利萨如循环周期
                    }
                    lastTime = frameTime
                }
            }
        }
    }

    // 🌟 优化：提取 Radius 计算，并且在 Offset.Zero 构建不变的静态 Brush (死死锁住，0 JNI分配)
    var maxRadius by remember { mutableFloatStateOf(0f) }
    
    val brush1 = remember(c1List, maxRadius) {
        if (maxRadius > 0f) Brush.radialGradient(c1List, Offset.Zero, maxRadius) else SolidColor(Color.Transparent)
    }
    val brush2 = remember(c2List, maxRadius) {
        if (maxRadius > 0f) Brush.radialGradient(c2List, Offset.Zero, maxRadius * 0.9f) else SolidColor(Color.Transparent)
    }
    val brush3 = remember(c3List, maxRadius) {
        if (maxRadius > 0f) Brush.radialGradient(c3List, Offset.Zero, maxRadius * 0.85f) else SolidColor(Color.Transparent)
    }

    // 4. 绝对零 GC 开销的极客底层绘制
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                maxRadius = (if (w > h) w else h) * 0.9f
            }
            .drawBehind {
                if (maxRadius == 0f) return@drawBehind

                val w = size.width
                val h = size.height
                
                // 暗底铺设（保证车内夜间对比度）
                drawRect(baseBgColor)

                // 🌟 核心优化：笔刷不动，平移画布 (translate)。从源头切断 Shader 对象重建
                val x1 = w * 0.5f + w * 0.35f * sin(time * 0.15f)
                val y1 = h * 0.5f + h * 0.25f * cos(time * 0.11f)
                translate(left = x1, top = y1) {
                    drawCircle(brush1, maxRadius, Offset.Zero, blendMode = BlendMode.Screen)
                }

                val x2 = w * 0.5f + w * 0.4f * sin(time * 0.19f + 2f)
                val y2 = h * 0.5f + h * 0.3f * cos(time * 0.14f + 1f)
                translate(left = x2, top = y2) {
                    drawCircle(brush2, maxRadius * 0.9f, Offset.Zero, blendMode = BlendMode.Screen)
                }

                val x3 = w * 0.5f + w * 0.25f * sin(time * 0.12f + 4f)
                val y3 = h * 0.5f + h * 0.4f * cos(time * 0.17f + 3f)
                translate(left = x3, top = y3) {
                    drawCircle(brush3, maxRadius * 0.85f, Offset.Zero, blendMode = BlendMode.Screen)
                }
            }
    )
}

private fun Color.boostForAurora(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    hsv[1] = (hsv[1] * 1.3f).coerceIn(0.55f, 0.9f)
    hsv[2] = (hsv[2] * 0.85f).coerceIn(0.3f, 0.65f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}