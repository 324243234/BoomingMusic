package com.mardous.booming.core.model.lyrics

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import kotlin.math.abs

@Stable
class LyricsViewState(val lyrics: SyncedLyrics?) {

    var position by mutableLongStateOf(0L)
        private set

    internal var currentLineIndex by mutableIntStateOf(-1)
        private set

    private var previousLineIndex by mutableIntStateOf(-1)

    internal var currentWordIndex by mutableIntStateOf(-1)
        private set

    internal var currentBackgroundIndex by mutableIntStateOf(-1)
        private set

    private var shouldCrossfade by mutableStateOf(false)

    fun updatePosition(newPosition: Long) {
        val targetPosition = newPosition + (lyrics?.offset ?: 0)

        // 🌟【终极防跳帧护盾：单调时间过滤】
        // 如果底层传来的新时间比当前 UI 时间落后，且误差小于 400ms
        // 说明这是 ExoPlayer 底层 AudioTrack 的时钟抖动回退，直接拦截！
        // 绝不允许 K歌高亮往回缩水。只有相差 >400ms，才认为是用户真正的手动 Seek。
        if (targetPosition < position && (position - targetPosition) < 400L) {
            return
        }

        position = targetPosition

        val newLineIndex = findLineIndexAt(position)
        val lineJump = abs(newLineIndex - currentLineIndex)

        shouldCrossfade = lineJump > 1

        previousLineIndex = if (lineJump <= 1) currentLineIndex else -1
        currentLineIndex = newLineIndex
        currentWordIndex = findWordIndexAt(position, currentLineIndex)
        currentBackgroundIndex = findBackgroundIndexAt(position,  currentLineIndex)
    }

    private fun findLineIndexAt(position: Long): Int {
        if (position < 0 || lyrics == null) return -1
        val lines = lyrics.lines
        for (i in lines.lastIndex downTo 0) {
            if (position >= lines[i].start) {
                return i
            }
        }
        return -1
    }

    private fun findWordIndexAt(position: Long, lineIndex: Int): Int {
        if (lyrics == null || lineIndex !in lyrics.lines.indices) return -1
        val words = lyrics.lines[lineIndex].content.mainSyllables
        for (i in words.indices) {
            if (position < words[i].start) {
                return i - 1
            }
        }
        return words.lastIndex
    }

    private fun findBackgroundIndexAt(position: Long, lineIndex: Int): Int {
        if (lyrics == null || lineIndex !in lyrics.lines.indices) return -1
        val line = lyrics.lines[lineIndex]
        if (!line.hasBackgroundVocals) return -1
        val backgrounds = line.content.backgroundSyllables
        for (i in backgrounds.indices) {
            if (position < backgrounds[i].start) {
                return i - 1
            }
        }
        return backgrounds.lastIndex
    }
}