package com.mardous.booming.ui.component.compose.decoration

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.isActive
import org.intellij.lang.annotations.Language
import kotlin.math.cos
import kotlin.math.sin

// 🚀 CarWith 终极定制版 AGSL 流体引擎 (The Apple Music Warp)
@Language("AGSL")
private const val FLUID_SHADER = """
    uniform float2 resolution;
    uniform float time;
    uniform float2 bitmapSize; // 接收真实的贴图尺寸 (64x64)
    uniform shader imageTexture;
    layout(color) uniform half4 darkOverlay; // 护眼遮罩

    half4 main(in float2 fragCoord) {
        // 1. 将屏幕坐标归一化到 [0.0, 1.0]
        float2 uv = fragCoord / resolution.xy;
        
        // 降低时间流速，适应 CarWith 的 H.264 视频流编码，避免引发高频马赛克
        float t = time * 0.08; 
        
        // 2. 伪分形布朗运动 (Pseudo-FBM) 域扭曲
        // 利用低频三角函数叠加，模拟极具粘稠感的有机液体漩涡
        
        // 第一阶 (Octave 1)：大面积的基础流动
        float2 q;
        q.x = sin(uv.y * 2.5 + t) * 0.15 + cos(uv.x * 1.5 - t * 0.5) * 0.1;
        q.y = cos(uv.x * 2.5 + t * 0.8) * 0.15 - sin(uv.y * 1.5 - t * 0.4) * 0.1;
        
        // 第二阶 (Octave 2)：在第一阶的基础上叠加细微的局部干涉，产生漩涡感
        float2 r;
        r.x = sin((uv.y + q.y) * 3.0 - t * 1.2) * 0.1;
        r.y = cos((uv.x + q.x) * 3.0 + t * 1.5) * 0.1;
        
        // 合并坐标扭曲
        float2 distortedUV = uv + q + r;
        
        // 3. 硬件级双线性采样
        // 坐标系闭环：扭曲后的 UV (约-0.2~1.2) 乘以实际图片尺寸 (64x64)。
        // 借助底层的 TileMode.MIRROR，越界坐标将被完美折返成平滑的镜面流体。
        half4 fluidColor = imageTexture.eval(distortedUV * bitmapSize);
        
        // 4. Rec. 709 视网膜级色彩提纯
        // 解决 64x64 极度压缩带来的“泥浆/发灰”效应。
        // 采用国际标准 Rec.709 的人眼亮度权重计算法：
        half luminance = dot(fluidColor.rgb, half3(0.2126, 0.7152, 0.0722));
        // 将原色推离灰度轴心 1.5 倍，爆发极致绚丽的色彩
        half3 vibrantColor = mix(half3(luminance), fluidColor.rgb, 1.5);
        
        // 5. 强光护眼压罩
        // 无论流体多么鲜艳，强制覆盖 65% 的深色，确保车机界面的白色 UI/歌词永远处于高对比度
        half3 finalColor = mix(vibrantColor, darkOverlay.rgb, 0.65);
        
        return half4(finalColor, 1.0);
    }
"""

@Composable
fun AuroraGradientBackground(
    fluidTexture: ImageBitmap?,
    fallbackColors: List<Color>,
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && fluidTexture != null) {
        AgslFluidBackground(fluidTexture, { timeState }, modifier)
    } else {
        val c1 = fallbackColors.getOrNull(0) ?: Color(0xFF1E1E22)
        val c2 = fallbackColors.getOrNull(1) ?: Color(0xFF121215)
        val c3 = fallbackColors.getOrNull(2) ?: Color(0xFF25252A)
        val baseBgColor = remember { Color(0xFF0C0C0F) }
        CanvasAuroraBackground(c1, c2, c3, baseBgColor, { timeState }, modifier)
    }
}

@SuppressLint("NewApi")
@Composable
private fun AgslFluidBackground(
    fluidTexture: ImageBitmap,
    timeProvider: () -> Float,
    modifier: Modifier
) {
    val shader = remember { RuntimeShader(FLUID_SHADER) }
    
    val bitmapShader = remember(fluidTexture) {
        BitmapShader(fluidTexture.asAndroidBitmap(), Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
    }
    
    val brush = remember(shader, bitmapShader) { 
        shader.setInputShader("imageTexture", bitmapShader)
        ShaderBrush(shader) 
    }
    
    // 极深高级灰底图：防止在亮色封面下车主视线被干扰
    val darkOverlayColor = remember { Color(0xFF070709).toArgb() }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                shader.setFloatUniform("resolution", size.width, size.height)
                // 严密对接：将图片真实尺寸精确传导给 AGSL
                shader.setFloatUniform("bitmapSize", fluidTexture.width.toFloat(), fluidTexture.height.toFloat())
                shader.setFloatUniform("time", timeProvider())
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