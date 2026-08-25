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

// 🚀 100% 编译安全的纯数学 Mesh Gradient 流体
@Language("AGSL")
private const val FLUID_SHADER = """
    uniform vec2 resolution;
    uniform float time;
    
    // 🌟 AGSL 铁律：layout(color) 必须且只能是 half4，否则必崩溃黑屏！
    layout(color) uniform half4 c1_half; 
    layout(color) uniform half4 c2_half; 
    layout(color) uniform half4 c3_half; 
    layout(color) uniform half4 c4_half; 
    layout(color) uniform half4 darkOverlay_half;

    vec4 main(in vec2 fragCoord) {
        vec2 uv = fragCoord / resolution.xy;
        float t = time * 0.15; 
        
        // 🌟 内部类型安全转换：将 half4 转为 vec4，彻底消灭乘法异常
        vec4 c1 = vec4(c1_half);
        vec4 c2 = vec4(c2_half);
        vec4 c3 = vec4(c3_half);
        vec4 c4 = vec4(c4_half);
        vec4 darkOverlay = vec4(darkOverlay_half);

        // 1. 物理流体扭曲场
        vec2 warp;
        warp.x = sin(uv.y * 3.0 + t) * 0.15;
        warp.y = cos(uv.x * 3.0 - t * 0.8) * 0.15;
        vec2 wuv = uv + warp;
        
        float wx = smoothstep(-0.2, 1.2, wuv.x);
        float wy = smoothstep(-0.2, 1.2, wuv.y);
        
        // 2. 双线性像素融合 (无穷分辨率，绝无马赛克)
        vec3 topColor = mix(c1.rgb, c2.rgb, wx);
        vec3 bottomColor = mix(c3.rgb, c4.rgb, wx);
        vec3 meshColor = mix(topColor, bottomColor, wy);
        
        // 3. 色彩提亮与饱和度拔高，确保深色封面也能透出流光
        float lum = dot(meshColor, vec3(0.299, 0.587, 0.114));
        vec3 vibrantColor = mix(vec3(lum), meshColor, 1.4); 
        vibrantColor = vibrantColor * 1.15; 
        
        // 4. 固定护眼压罩（安全降至 35%）
        vec3 finalColor = mix(vibrantColor, darkOverlay.rgb, 0.35);
        
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

    val shouldAnimate = !isPowerSaveMode && !isOverheating && !isLowBattery

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

    val c1 = colors.getOrNull(0) ?: Color(0xFF1E1E22)
    val c2 = colors.getOrNull(1) ?: Color(0xFF121215)
    val c3 = colors.getOrNull(2) ?: Color(0xFF25252A)
    val c4 = colors.getOrNull(3) ?: Color(0xFF0F0F12)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AgslFluidBackground(c1, c2, c3, c4, { timeState }, modifier)
    } else {
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
    
    val darkOverlayColor = remember { Color(0xFF09090C).toArgb() }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("time", timeProvider())
                // 🌟 严谨对接：参数名必须与 Shader 中定义的 Uniform 变量名完全一致
                shader.setColorUniform("c1_half", c1.toArgb())
                shader.setColorUniform("c2_half", c2.toArgb())
                shader.setColorUniform("c3_half", c3.toArgb())
                shader.setColorUniform("c4_half", c4.toArgb())
                shader.setColorUniform("darkOverlay_half", darkOverlayColor)
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
    val c1List = remember(c1) { listOf(c1.copy(alpha = 0.75f), Color.Transparent) }
    val c2List = remember(c2) { listOf(c2.copy(alpha = 0.75f), Color.Transparent) }
    val c3List = remember(c3) { listOf(c3.copy(alpha = 0.75f), Color.Transparent) }
    val c4List = remember(c4) { listOf(c4.copy(alpha = 0.75f), Color.Transparent) }
    
    var maxRadius by remember { mutableFloatStateOf(0f) }
    
    val brush1 = remember(c1List, maxRadius) {
        if (maxRadius > 0f) Brush.radialGradient(c1List, Offset.Zero, maxRadius) else SolidColor(Color.Transparent)
    }
    val brush2 = remember(c2List, maxRadius) {
        if (maxRadius > 0f) Brush.radialGradient(c2List, Offset.Zero, maxRadius) else SolidColor(Color.Transparent)
    }
    val brush3 = remember(c3List, maxRadius) {
        if (maxRadius > 0f) Brush.radialGradient(c3List, Offset.Zero, maxRadius) else SolidColor(Color.Transparent)
    }
    val brush4 = remember(c4List, maxRadius) {
        if (maxRadius > 0f) Brush.radialGradient(c4List, Offset.Zero, maxRadius) else SolidColor(Color.Transparent)
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

                val x1 = w * 0.2f + w * 0.3f * sin(time * 0.15f)
                val y1 = h * 0.2f + h * 0.2f * cos(time * 0.11f)
                translate(left = x1, top = y1) { drawCircle(brush1, maxRadius, Offset.Zero, blendMode = BlendMode.Screen) }

                val x2 = w * 0.8f - w * 0.3f * cos(time * 0.19f)
                val y2 = h * 0.2f + h * 0.2f * sin(time * 0.14f)
                translate(left = x2, top = y2) { drawCircle(brush2, maxRadius, Offset.Zero, blendMode = BlendMode.Screen) }

                val x3 = w * 0.2f + w * 0.3f * cos(time * 0.12f)
                val y3 = h * 0.8f - h * 0.2f * sin(time * 0.17f)
                translate(left = x3, top = y3) { drawCircle(brush3, maxRadius, Offset.Zero, blendMode = BlendMode.Screen) }

                val x4 = w * 0.8f - w * 0.3f * sin(time * 0.16f)
                val y4 = h * 0.8f - h * 0.2f * cos(time * 0.13f)
                translate(left = x4, top = y4) { drawCircle(brush4, maxRadius, Offset.Zero, blendMode = BlendMode.Screen) }
            }
    )
}