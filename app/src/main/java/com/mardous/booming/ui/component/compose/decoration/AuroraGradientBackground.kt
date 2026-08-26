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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.isActive
import org.intellij.lang.annotations.Language

// 🚀 终极架构：域扭曲大理石流体 (Domain Warped Marbling Fluid)
// 彻底抛弃距离公式，使用三角函数交织色彩，产生真实的油漆融合质感
@Language("AGSL")
private const val FLUID_SHADER = """
    uniform vec2 resolution;
    uniform float time;
    
    layout(color) uniform half4 c1_in; 
    layout(color) uniform half4 c2_in; 
    layout(color) uniform half4 c3_in; 
    layout(color) uniform half4 c4_in; 
    layout(color) uniform half4 overlay_in;

    vec4 main(in vec2 fragCoord) {
        vec2 uv = fragCoord / resolution.xy;
        // 转换至中心坐标，修复宽屏被拉伸问题
        vec2 p = uv * 2.0 - 1.0;
        p.x *= resolution.x / resolution.y;
        
        vec4 c1 = vec4(c1_in);
        vec4 c2 = vec4(c2_in);
        vec4 c3 = vec4(c3_in);
        vec4 c4 = vec4(c4_in);
        vec4 overlay = vec4(overlay_in);

        float t = time * 0.12; 

        // 1. 核心域扭曲 (The Apple Music "Twist")
        // 将坐标轴像揉面团一样反复折叠，彻底摧毁光晕边界
        for(float i = 1.0; i < 4.0; i += 1.0) {
            vec2 newP = p;
            newP.x += 0.35 / i * sin(i * 2.0 * p.y + t);
            newP.y += 0.35 / i * cos(i * 1.5 * p.x - t * 0.8);
            p = newP;
        }

        // 2. 正弦波交织混合 (Sine Wave Blending)
        // 关键在此：依靠扭曲后的坐标生成混合权重，颜色会变成带状和漩涡状
        float w1 = 0.5 + 0.5 * sin(p.x * 2.0 + t * 0.5);
        float w2 = 0.5 + 0.5 * cos(p.y * 2.0 - t * 0.4);
        float w3 = 0.5 + 0.5 * sin((p.x + p.y) * 1.5 + t * 0.6);

        // 逐步混合四种颜色
        vec3 colorA = mix(c1.rgb, c2.rgb, w1);
        vec3 colorB = mix(c3.rgb, c4.rgb, w2);
        vec3 fluidColor = mix(colorA, colorB, w3);

        // 3. Apple 级通透度与护眼压暗
        float lum = dot(fluidColor, vec3(0.299, 0.587, 0.114));
        vec3 vibrantColor = mix(vec3(lum), fluidColor, 1.35); 
        vibrantColor *= 1.15; // 提高整体呼吸亮度
        
        vec3 finalColor = mix(vibrantColor, overlay.rgb, 0.35); // 柔和暗色遮罩
        
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
        // Fallback for older devices: No fluid animation support natively.
        Box(modifier = modifier.fillMaxSize()) {}
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
    
    val darkOverlayColor = remember { Color(0xFF07070A).toArgb() }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("time", timeProvider())
                shader.setColorUniform("c1_in", c1.toArgb())
                shader.setColorUniform("c2_in", c2.toArgb())
                shader.setColorUniform("c3_in", c3.toArgb())
                shader.setColorUniform("c4_in", c4.toArgb())
                shader.setColorUniform("overlay_in", darkOverlayColor)
                drawRect(brush)
            }
    )
}