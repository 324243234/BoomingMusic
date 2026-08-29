package com.mardous.booming.core.model

import com.mardous.booming.data.model.Song

enum class AudioSourceType {
    LOCAL, RADIO, NETEASE, UNKNOWN
}

// 🌟 核心识别器：通过构建 Song 时的特征进行绝对物理隔离
fun Song.getAudioSourceType(): AudioSourceType {
    return when {
        // 电台：类型为直播，或者无时长且是 http 开头
        this.genreName == "直播" || (this.duration == 0L && this.data.startsWith("http")) -> AudioSourceType.RADIO
        // 网易云：构建时强行注入特征标签
        this.genreName == "Netease" -> AudioSourceType.NETEASE 
        // 本地：绝对路径或 content uri
        this.data.startsWith("/") || this.data.startsWith("content://") -> AudioSourceType.LOCAL
        else -> AudioSourceType.UNKNOWN
    }
}