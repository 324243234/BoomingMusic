package com.mardous.booming.ui.component.compose.decoration

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AuroraGradientBackground(
    colors: List<Color>,
    isPlaying: Boolean, // 🌟 核心省电：接收播放状态
    modifier: Modifier = Modifier
) {
    // 1. 色彩防脏预处理：提升饱和度，压低亮度，确保车内不刺眼、不发灰
    val c1 = (colors.getOrNull(0) ?: Color(0xFF2C3E50)).boostForAurora()
    val c2 = (colors.getOrNull(1) ?: Color(0xFF3498DB)).boostForAurora()
    val c3 = (colors.getOrNull(2) ?: c1).boostForAurora()

    // 2. 纯数学时间轴控制：暂停时彻底挂起，CPU/GPU 零消耗
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            var lastTime = 0L
            while (isActive) {
                withInfiniteAnimationFrameMillis { frameTime ->
                    if (lastTime != 0L) {
                        val delta = (frameTime - lastTime) / 1000f
                        // 🌟 优化 1: 取模防止长途自驾导致 Float 精度溢出引起画面发抖
                        time = (time + delta) % 100000f 
                    }
                    lastTime = frameTime
                }
            }
        }
    }

    // 3. 超低功耗绘制 (🌟 优化 3: 使用 drawBehind 替代 Canvas 减少节点层级)
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                val maxRadius = w.coerceAtLeast(h) * 0.9f

                drawRect(Color(0xFF0C0C0F)) // 极暗底色，突显歌词

                // 🌟 优化 2: 引入 BlendMode.Screen，让光斑交界处产生真实的“光学发光”融合反应
                val x1 = w * 0.5f + w * 0.35f * sin(time * 0.15f)
                val y1 = h * 0.5f + h * 0.25f * cos(time * 0.11f)
                drawCircle(
                    brush = Brush.radialGradient(listOf(c1.copy(alpha = 0.65f), Color.Transparent), Offset(x1, y1), maxRadius),
                    radius = maxRadius, 
                    center = Offset(x1, y1),
                    blendMode = BlendMode.Screen 
                )

                val x2 = w * 0.5f + w * 0.4f * sin(time * 0.19f + 2f)
                val y2 = h * 0.5f + h * 0.3f * cos(time * 0.14f + 1f)
                drawCircle(
                    brush = Brush.radialGradient(listOf(c2.copy(alpha = 0.55f), Color.Transparent), Offset(x2, y2), maxRadius * 0.9f),
                    radius = maxRadius * 0.9f, 
                    center = Offset(x2, y2),
                    blendMode = BlendMode.Screen
                )

                val x3 = w * 0.5f + w * 0.25f * sin(time * 0.12f + 4f)
                val y3 = h * 0.5f + h * 0.4f * cos(time * 0.17f + 3f)
                drawCircle(
                    brush = Brush.radialGradient(listOf(c3.copy(alpha = 0.45f), Color.Transparent), Offset(x3, y3), maxRadius * 0.85f),
                    radius = maxRadius * 0.85f, 
                    center = Offset(x3, y3),
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