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

// 🚀 终极架构：纯数学网格渐变引擎 (Warped Bilinear Mesh Gradient)
// 绝对 0 纹理采样，100% 杜绝马赛克，完美契合 H.264 视频流编码器
@Language("AGSL")
private const val FLUID_SHADER = """
    uniform float2 resolution;
    uniform float time;
    
    layout(color) uniform half4 c1; // 左上角色彩
    layout(color) uniform half4 c2; // 右上角色彩
    layout(color) uniform half4 c3; // 左下角色彩
    layout(color) uniform half4 c4; // 右下角色彩
    layout(color) uniform half4 darkOverlay; // 护眼遮罩

    half4 main(in float2 fragCoord) {
        // 1. 标准化坐标 [0.0, 1.0]
        float2 uv = fragCoord / resolution.xy;
        float t = time * 0.15; 
        
        // 2. 空间流体力学扭曲 (Domain Warping)
        // 使用低频正余弦波模拟流体的搅动。这种扭曲不会产生任何尖锐边缘，对车机硬件编码极度友好。
        float2 warp;
        warp.x = sin(uv.y * 2.0 + t) * 0.2 + cos(uv.x * 1.5 - t * 0.5) * 0.15;
        warp.y = cos(uv.x * 2.5 + t * 0.8) * 0.2 - sin(uv.y * 1.5 - t * 0.4) * 0.15;
        
        // 将坐标进行扭曲，并通过 smoothstep 防止边缘硬切断
        float2 wuv = smoothstep(0.0, 1.0, uv + warp);
        
        // 3. Apple Music 级数学平滑：双线性插值 (Bilinear Interpolation)
        // 纯靠数学公式在四个角落的颜色之间进行无极融合，分辨率无限大，永远不可能有像素块！
        half3 topColor = mix(c1.rgb, c2.rgb, wuv.x);
        half3 bottomColor = mix(c3.rgb, c4.rgb, wuv.x);
        half3 meshColor = mix(topColor, bottomColor, wuv.y);
        
        // 4. 色彩提纯防灰暗
        half luminance = dot(meshColor, half3(0.2126, 0.7152, 0.0722));
        half3 vibrantColor = mix(half3(luminance), meshColor, 1.35); 
        
        // 5. 65% 暗色压罩混合
        half3 finalColor = mix(vibrantColor, darkOverlay.rgb, 0.65);
        
        return half4(finalColor, 1.0);
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
        CanvasAuroraBackground(c1, c2, c3, baseBgColor, { timeState }, modifier)
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
    
    // 护眼深色压罩
    val darkOverlayColor = remember { Color(0xFF09090C).toArgb() }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("time", timeProvider())
                shader.setColorUniform("c1", c1.toArgb())
                shader.setColorUniform("c2", c2.toArgb())
                shader.setColorUniform("c3", c3.toArgb())
                shader.setColorUniform("c4", c4.toArgb())
                shader.setColorUniform("darkOverlay", darkOverlayColor)
                drawRect(brush)
            }
    )
}

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

                val x1 = w * 0.5f + w * 0.35f * sin(time * 0.15f)
                val y1 = h * 0.5f + h * 0.25f * cos(time * 0.11f)
                translate(left = x1, top = y1) { drawCircle(brush1, maxRadius, Offset.Zero, blendMode = BlendMode.Screen) }

                val x2 = w * 0.5f + w * 0.4f * sin(time * 0.19f + 2f)
                val y2 = h * 0.5f + h * 0.3f * cos(time * 0.14f + 1f)
                translate(left = x2, top = y2) { drawCircle(brush2, maxRadius * 0.9f, Offset.Zero, blendMode = BlendMode.Screen) }

                val x3 = w * 0.5f + w * 0.25f * sin(time * 0.12f + 4f)
                val y3 = h * 0.5f + h * 0.4f * cos(time * 0.17f + 3f)
                translate(left = x3, top = y3) { drawCircle(brush3, maxRadius * 0.85f, Offset.Zero, blendMode = BlendMode.Screen) }
            }
    )
}