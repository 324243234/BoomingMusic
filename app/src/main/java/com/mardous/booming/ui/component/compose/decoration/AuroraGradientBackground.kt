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
import androidx.compose.ui.graphics.toArgb
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

    // 电池电量与省电广播监听
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

    // Android 10+ 硬件热温监听（Severe 及以上触发熔断）
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

    // 是否允许执行动态渲染
    val shouldAnimate = isPlaying && !isPowerSaveMode && !isOverheating && !isLowBattery

    // 2. 颜色预处理与对象池化（避免在 Draw 阶段分配内存）
    val c1 = remember(colors) { (colors.getOrNull(0) ?: Color(0xFF2C3E50)).boostForAurora() }
    val c2 = remember(colors) { (colors.getOrNull(1) ?: Color(0xFF3498DB)).boostForAurora() }
    val c3 = remember(colors) { (colors.getOrNull(2) ?: c1).boostForAurora() }

    val c1List = remember(c1) { listOf(c1.copy(alpha = 0.65f), Color.Transparent) }
    val c2List = remember(c2) { listOf(c2.copy(alpha = 0.55f), Color.Transparent) }
    val c3List = remember(c3) { listOf(c3.copy(alpha = 0.45f), Color.Transparent) }
    val baseBgColor = remember { Color(0xFF0C0C0F) }

    // 3. 动态时间轴控制（冻结时直接挂起循环）
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(shouldAnimate) {
        if (shouldAnimate) {
            var lastTime = 0L
            while (isActive) {
                withInfiniteAnimationFrameMillis { frameTime ->
                    if (lastTime != 0L) {
                        val delta = (frameTime - lastTime) / 1000f
                        time = (time + delta) % 6283.185f // 2000*PI 精度保护取模
                    }
                    lastTime = frameTime
                }
            }
        }
    }

    // 4. 零 GC 开销的底层绘制
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                val maxRadius = (if (w > h) w else h) * 0.9f

                // 暗底铺设（保证车内夜间对比度）
                drawRect(baseBgColor)

                // 光斑 1（主光束）
                val x1 = w * 0.5f + w * 0.35f * sin(time * 0.15f)
                val y1 = h * 0.5f + h * 0.25f * cos(time * 0.11f)
                val center1 = Offset(x1, y1)
                drawCircle(
                    brush = Brush.radialGradient(c1List, center1, maxRadius),
                    radius = maxRadius,
                    center = center1,
                    blendMode = BlendMode.Screen
                )

                // 光斑 2（反向副光束）
                val x2 = w * 0.5f + w * 0.4f * sin(time * 0.19f + 2f)
                val y2 = h * 0.5f + h * 0.3f * cos(time * 0.14f + 1f)
                val center2 = Offset(x2, y2)
                drawCircle(
                    brush = Brush.radialGradient(c2List, center2, maxRadius * 0.9f),
                    radius = maxRadius * 0.9f,
                    center = center2,
                    blendMode = BlendMode.Screen
                )

                // 光斑 3（点缀漫游光束）
                val x3 = w * 0.5f + w * 0.25f * sin(time * 0.12f + 4f)
                val y3 = h * 0.5f + h * 0.4f * cos(time * 0.17f + 3f)
                val center3 = Offset(x3, y3)
                drawCircle(
                    brush = Brush.radialGradient(c3List, center3, maxRadius * 0.85f),
                    radius = maxRadius * 0.85f,
                    center = center3,
                    blendMode = BlendMode.Screen
                )
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