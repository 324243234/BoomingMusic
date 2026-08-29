package com.mardous.booming.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

object NeteaseCacheSweeper {
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun cleanUp(context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // 1. 强力抹除 ExoPlayer 网络流碎片
                val exoCacheDir = File(context.cacheDir, "exo_cache") 
                if (exoCacheDir.exists()) exoCacheDir.deleteRecursively()
                
                // 2. 清理下载引擎可能残留的意外中断临时文件
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("temp_") && file.name.endsWith(".mp3")) {
                        file.delete()
                    }
                }
                
                // 3. 抹除 AnimatedCanvasFetcher 里的临时大视频热缓存
                com.mardous.booming.data.local.lyrics.ttml.AnimatedCanvasFetcher.clearAllTempCache(context)
                
                // 4. 强制释放无用大图片
                System.gc()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}