package com.mardous.booming.ui.component.compose.decoration

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.RuntimeShader
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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.isActive
import org.intellij.lang.annotations.Language
import kotlin.math.cos
import kotlin.math.sin

/**
 * 🌟 真正的流体力学 GPU 着色器 (AGSL)
 * 仅在 Android 13+ (API 33+) 激活。利用域扭曲(Domain Warping)算法产生极致粘稠的液态融合。
 */
@Language("AGSL")
private const val FLUID_SHADER = """
    uniform float2 resolution;
    uniform float time;
    
    // 🌟 修复：在 AGSL 语法中，layout(...) 修饰符必须放在 uniform 前面
    layout(color) uniform half4 c1;
    layout(color) uniform half4 c2;
    layout(color) uniform half4 c3;
    layout(color) uniform half4 bg;

    half4 main(in float2 fragCoord) {
        float2 uv = fragCoord / resolution.xy;
        float2 p = uv * 2.0 - 1.0; // 将坐标系映射到 -1 到 1
        p.x *= resolution.x / resolution.y; // 修正屏幕比例防拉伸
        
        float t = time * 0.15; // 控制流体流动的全局速度
        
        // 核心数学：利用分形正弦波进行多重域扭曲 (Domain Warping)，模拟粘稠流体力学
        for(float i = 1.0; i < 4.0; i += 1.0) {
            float2 newP = p;
            newP.x += 0.4 / i * sin(i * 2.0 * p.y + t);
            newP.y += 0.4 / i * cos(i * 1.5 * p.x - t * 0.8);
            p = newP;
        }
        
        // 提取扭曲后的坐标点权重
        float w1 = 0.5 + 0.5 * sin(p.x * 2.5 + t);
        float w2 = 0.5 + 0.5 * cos(p.y * 2.0 - t);
        
        // GPU 并行混合：Apple Music 同款液态色彩剥离
        half4 color = mix(c1, c2, w1);
        color = mix(color, c3, w2);
        
        // 叠加一层极光暗底，增强车内夜间的深邃通透感
        return mix(bg, color, 0.85);
    }
"""

/**
 * 专为 CarWith 车载与极端工况优化的世界级极光背景 (Dual-Engine 架构)
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

    val shouldAnimate = isPlaying && !isPowerSaveMode && !isOverheating && !isLowBattery

    // 2. 颜色提取与极光亮度提纯
    val c1 = remember(colors) { (colors.getOrNull(0) ?: Color(0xFF2C3E50)).boostForAurora() }
    val c2 = remember(colors) { (colors.getOrNull(1) ?: Color(0xFF3498DB)).boostForAurora() }
    val c3 = remember(colors) { (colors.getOrNull(2) ?: c1).boostForAurora() }
    val baseBgColor = remember { Color(0xFF0C0C0F) }

    // 3. 全局时间轴挂起驱动 (传入 Provider 函数，彻底杜绝 Compose 重组产生的内存抖动)
    var timeState by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(shouldAnimate) {
        if (shouldAnimate) {
            var lastTime = 0L
            while (isActive) {
                withInfiniteAnimationFrameMillis { frameTime ->
                    if (lastTime != 0L) {
                        val delta = (frameTime - lastTime) / 1000f
                        timeState = (timeState + delta) % 628.3185f // 200*PI 完美防溢出周期
                    }
                    lastTime = frameTime
                }
            }
        }
    }

    // 4. 双引擎路由：根据系统版本动态调度硬件
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AgslFluidBackground(c1, c2, c3, baseBgColor, { timeState }, modifier)
    } else {
        CanvasAuroraBackground(c1, c2, c3, baseBgColor, { timeState }, modifier)
    }
}

/**
 * 🚀 引擎 A：API 33+ 满血纯 GPU 流体着色器 (0 CPU 开销)
 */
@SuppressLint("NewApi")
@Composable
private fun AgslFluidBackground(
    c1: Color, c2: Color, c3: Color, baseBgColor: Color,
    timeProvider: () -> Float,
    modifier: Modifier
) {
    // 实例化 AGSL 着色器并永久缓存
    val shader = remember { RuntimeShader(FLUID_SHADER) }
    val brush = remember(shader) { ShaderBrush(shader) }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // 每帧仅更新 Uniform 变量，由 GPU 负责百万级像素流体计算
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("time", timeProvider())
                shader.setColorUniform("c1", c1.toArgb())
                shader.setColorUniform("c2", c2.toArgb())
                shader.setColorUniform("c3", c3.toArgb())
                shader.setColorUniform("bg", baseBgColor.toArgb())
                drawRect(brush)
            }
    )
}

/**
 * 🔋 引擎 B：降级兼容方案，0 GC 的 Canvas 数学极光引擎
 */
@Composable
private fun CanvasAuroraBackground(
    c1: Color, c2: Color, c3: Color, baseBgColor: Color,
    timeProvider: () -> Float,
    modifier: Modifier
) {
    val c1List = remember(c1) { listOf(c1.copy(alpha = 0.65f), Color.Transparent) }
    val c2List = remember(c2) { listOf(c2.copy(alpha = 0.55f), Color.Transparent) }
    val c3List = remember(c3) { listOf(c3.copy(alpha = 0.45f), Color.Transparent) }
    
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
                val time = timeProvider()
                
                drawRect(baseBgColor)

                // 使用 translate 平移画布，彻底掐断 JNI 对象重分配
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

// 通用色彩高亮提纯算法
private fun Color.boostForAurora(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    hsv[1] = (hsv[1] * 1.3f).coerceIn(0.55f, 0.9f)
    hsv[2] = (hsv[2] * 0.85f).coerceIn(0.3f, 0.65f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}