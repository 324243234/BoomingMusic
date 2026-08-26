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

// 🚀 核心架构：视频 1 同款的【域扭曲双线性网格渐变 (Domain Warped Bilinear Mesh Gradient)】
// 抛弃画圆算法，采用纯粹的空间褶皱，颜色将像丝带一样完美交织。
@Language("AGSL")
private const val FLUID_SHADER = """
    uniform vec2 resolution;
    uniform float time;
    
    // AGSL Android 13+ 严苛规范：layout(color) 必须为 half4
    layout(color) uniform half4 c1_in; 
    layout(color) uniform half4 c2_in; 
    layout(color) uniform half4 c3_in; 
    layout(color) uniform half4 c4_in; 
    layout(color) uniform half4 overlay_in;

    vec4 main(in vec2 fragCoord) {
        vec2 uv = fragCoord / resolution.xy;
        float t = time * 0.12; 
        
        // 类型安全转换：转为 vec4 杜绝所有的编译崩溃
        vec4 c1 = vec4(c1_in);
        vec4 c2 = vec4(c2_in);
        vec4 c3 = vec4(c3_in);
        vec4 c4 = vec4(c4_in);
        vec4 overlay = vec4(overlay_in);

        // 🌟 1. 视频 1 的灵魂：大尺度域扭曲 (Domain Warping)
        // 让 UV 坐标产生巨大的、舒缓的波浪起伏
        vec2 p = uv;
        p.x += 0.25 * sin(uv.y * 2.2 + t);
        p.y += 0.25 * cos(uv.x * 2.2 - t * 0.8);
        
        p.x += 0.15 * sin(p.y * 3.5 - t * 1.5);
        p.y += 0.15 * cos(p.x * 3.5 + t * 1.2);

        // 平滑限制坐标边界，防止抽取颜色时出现撕裂
        p = smoothstep(-0.2, 1.2, p);

        // 🌟 2. 双线性网格插值 (Bilinear Interpolation)
        // 这一步决定了它永远是无限分辨率、0 马赛克的丝滑颜色场
        vec3 topColor = mix(c1.rgb, c2.rgb, p.x);
        vec3 bottomColor = mix(c3.rgb, c4.rgb, p.x);
        vec3 fluidColor = mix(topColor, bottomColor, p.y);

        // 🌟 3. 动态混入安全护眼层
        // overlay_in 是在 Kotlin 传入的一个带 Alpha 通道的暗色 (比如透明度 25% 的纯黑)
        // 既能保证车机歌词不晃眼，又绝不会把画面彻底搞黑
        vec3 finalColor = mix(fluidColor, overlay.rgb, overlay.a);
        
        return vec4(finalColor, 1.0);
    }
"""

@Composable
fun AuroraGradientBackground(
    colors: List<Color>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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

    // 解除动画绑定，杜绝切换时的急刹车卡顿
    val shouldAnimate = !isPowerSaveMode && !isOverheating && !isLowBattery

    val c1 = colors.getOrNull(0) ?: Color(0xFFE74C3C)
    val c2 = colors.getOrNull(1) ?: Color(0xFFF39C12)
    val c3 = colors.getOrNull(2) ?: Color(0xFF8E44AD)
    val c4 = colors.getOrNull(3) ?: Color(0xFF3498DB)

    var timeState by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(shouldAnimate) {
        if (shouldAnimate) {
            var lastTime = 0L
            while (isActive) {
                withInfiniteAnimationFrameMillis { frameTime ->
                    if (lastTime != 0L) {
                        val delta = (frameTime - lastTime) / 1000f
                        timeState = (timeState + delta) % 628.3185f 
                    }
                    lastTime = frameTime
                }
            }
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AgslFluidBackground(c1, c2, c3, c4, { timeState }, modifier)
    } else {
        // 软渲染降级处理
        val baseBgColor = remember { Color(0xFF0C0C0F) }
        CanvasAuroraBackground(c1, c2, c3, c4, baseBgColor, { timeState }, modifier)
    }
}

@SuppressLint("NewApi")
@Composable
private fun AgslFluidBackground(
    c1: Color, c2: Color, c3: Color, c4: Color,
    timeProvider: () -> Float,
    modifier: Modifier
) {
    val shader = remember { RuntimeShader(FLUID_SHADER) }
    val brush = remember(shader) { ShaderBrush(shader) }
    
    // 🌟 一层若有若无的护眼层 (25% 透明度的黑)
    // 它能稍微压住太亮刺眼的光斑，但绝不抢戏，不破坏色彩的鲜活度
    val darkOverlayColor = remember { Color(0x40000000).toArgb() } 
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("time", timeProvider())
                // 严谨：参数名一一对应
                shader.setColorUniform("c1_in", c1.toArgb())
                shader.setColorUniform("c2_in", c2.toArgb())
                shader.setColorUniform("c3_in", c3.toArgb())
                shader.setColorUniform("c4_in", c4.toArgb())
                shader.setColorUniform("overlay_in", darkOverlayColor)
                drawRect(brush)
            }
    )
}

@Composable
private fun CanvasAuroraBackground(
    c1: Color, c2: Color, c3: Color, c4: Color, baseBgColor: Color,
    timeProvider: () -> Float,
    modifier: Modifier
) {
    val c1List = remember(c1) { listOf(c1.copy(alpha = 0.65f), Color.Transparent) }
    val c2List = remember(c2) { listOf(c2.copy(alpha = 0.55f), Color.Transparent) }
    val c3List = remember(c3) { listOf(c3.copy(alpha = 0.45f), Color.Transparent) }
    val c4List = remember(c4) { listOf(c4.copy(alpha = 0.50f), Color.Transparent) }
    
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
    val brush4 = remember(c4List, maxRadius) {
        if (maxRadius > 0f) Brush.radialGradient(c4List, Offset.Zero, maxRadius * 0.95f) else SolidColor(Color.Transparent)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                maxRadius = (if (w > h) w else h) * 1.1f
            }
            .drawBehind {
                if (maxRadius == 0f) return@drawBehind

                val w = size.width
                val h = size.height
                val time = timeProvider()
                
                drawRect(baseBgColor)

                val x1 = w * 0.5f + w * 0.35f * sin(time * 0.15f)
                val y1 = h * 0.5f + h * 0.25f * cos(time * 0.11f)
                translate(left = x1, top = y1) { drawCircle(brush1, maxRadius, Offset.Zero, blendMode = BlendMode.Screen) }

                val x2 = w * 0.5f + w * 0.4f * sin(time * 0.19f + 2f)
                val y2 = h * 0.5f + h * 0.3f * cos(time * 0.14f + 1f)
                translate(left = x2, top = y2) { drawCircle(brush2, maxRadius * 0.9f, Offset.Zero, blendMode = BlendMode.Screen) }

                val x3 = w * 0.5f + w * 0.25f * sin(time * 0.12f + 4f)
                val y3 = h * 0.5f + h * 0.4f * cos(time * 0.17f + 3f)
                translate(left = x3, top = y3) { drawCircle(brush3, maxRadius * 0.85f, Offset.Zero, blendMode = BlendMode.Screen) }
                
                val x4 = w * 0.5f + w * 0.3f * cos(time * 0.16f + 1f)
                val y4 = h * 0.5f + h * 0.35f * sin(time * 0.13f + 2f)
                translate(left = x4, top = y4) { drawCircle(brush4, maxRadius * 0.95f, Offset.Zero, blendMode = BlendMode.Screen) }
            }
    )
}