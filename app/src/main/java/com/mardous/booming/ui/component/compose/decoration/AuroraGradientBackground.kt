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

// 🚀 终极架构：Apple Music 逆向 Metaball 有机流体引擎
// 通过计算像素到多个引力点的反比例距离权重，模拟极其粘稠、平滑的液体交融
@Language("AGSL")
private const val FLUID_SHADER = """
    uniform vec2 resolution;
    uniform float time;
    
    // 严格类型安全：使用 half4 接收 Kotlin 颜色，转换 vec4 运算
    layout(color) uniform half4 c1_in; 
    layout(color) uniform half4 c2_in; 
    layout(color) uniform half4 c3_in; 
    layout(color) uniform half4 c4_in; 
    layout(color) uniform half4 overlay_in;

    vec4 main(in vec2 fragCoord) {
        vec2 uv = fragCoord / resolution.xy;
        float aspect = resolution.x / resolution.y;
        vec2 p = uv * 2.0 - 1.0;
        p.x *= aspect; // 修正屏幕比例，防止流体被拉伸变形

        vec4 c1 = vec4(c1_in);
        vec4 c2 = vec4(c2_in);
        vec4 c3 = vec4(c3_in);
        vec4 c4 = vec4(c4_in);
        vec4 overlay = vec4(overlay_in);

        float t = time * 0.18; // 控制液体呼吸的节奏

        // 1. 底层空间的水波扭曲 (让圆球产生液体的边缘)
        vec2 warp;
        warp.x = sin(p.y * 1.5 + t) * 0.25;
        warp.y = cos(p.x * 1.5 - t * 0.8) * 0.25;
        vec2 wp = p + warp;

        // 2. 四个彩色引力点的李萨如运动轨迹
        vec2 p1 = vec2(sin(t * 0.8), cos(t * 0.6)) * 0.8;
        vec2 p2 = vec2(cos(t * 1.1), sin(t * 0.9)) * 0.8;
        vec2 p3 = vec2(sin(t * 0.7 + 2.0), cos(t * 1.3 + 1.0)) * 0.8;
        vec2 p4 = vec2(cos(t * 1.2 + 3.0), sin(t * 0.6 + 2.0)) * 0.8;

        // 3. 计算每个像素到引力点的距离
        float d1 = length(wp - p1);
        float d2 = length(wp - p2);
        float d3 = length(wp - p3);
        float d4 = length(wp - p4);

        // 4. Metaball 平滑融合法则 (Inverse Distance Weighting)
        // 距离越近，该颜色权重呈指数级暴增，产生像岩浆/水银一样的融合效果
        float w1 = 1.0 / pow(d1 + 0.15, 2.5);
        float w2 = 1.0 / pow(d2 + 0.15, 2.5);
        float w3 = 1.0 / pow(d3 + 0.15, 2.5);
        float w4 = 1.0 / pow(d4 + 0.15, 2.5);

        float sum = w1 + w2 + w3 + w4;

        // 加权混合出当前像素最终的流体颜色
        vec3 fluidColor = (c1.rgb * w1 + c2.rgb * w2 + c3.rgb * w3 + c4.rgb * w4) / sum;

        // 5. 饱和度二次提纯与护眼叠层
        // Apple Music 的质感关键在于：流体的饱和度要极高，然后上面再盖一层半透明暗色
        float lum = dot(fluidColor, vec3(0.299, 0.587, 0.114));
        vec3 vibrant = mix(vec3(lum), fluidColor, 1.45); 

        vec3 finalColor = mix(vibrant, overlay.rgb, 0.45);

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

    // 独立于播放状态，保证动效流畅不断层
    val shouldAnimate = !isPowerSaveMode && !isOverheating && !isLowBattery

    val c1 = colors.getOrNull(0) ?: Color(0xFF2C3E50)
    val c2 = colors.getOrNull(1) ?: Color(0xFF8E44AD)
    val c3 = colors.getOrNull(2) ?: Color(0xFFE74C3C)
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
        // 低版本优雅降级
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
    
    // 护眼叠层色
    val darkOverlayColor = remember { Color(0xFF0A0A0E).toArgb() }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("time", timeProvider())
                // 严谨对接 half4 类型
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
    val c1List = remember(c1) { listOf(c1.copy(alpha = 0.6f), Color.Transparent) }
    val c2List = remember(c2) { listOf(c2.copy(alpha = 0.6f), Color.Transparent) }
    val c3List = remember(c3) { listOf(c3.copy(alpha = 0.6f), Color.Transparent) }
    val c4List = remember(c4) { listOf(c4.copy(alpha = 0.6f), Color.Transparent) }
    
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