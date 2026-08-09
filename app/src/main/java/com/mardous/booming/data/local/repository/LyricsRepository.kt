package com.mardous.booming.data.local.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.mardous.booming.data.local.EditTarget
import com.mardous.booming.data.local.MetadataReader
import com.mardous.booming.data.local.MetadataWriter
import com.mardous.booming.data.local.lyrics.lrc.LrcLyricsParser
import com.mardous.booming.data.local.lyrics.ttml.TtmlLyricsParser
import com.mardous.booming.data.local.room.LyricsDao
import com.mardous.booming.data.local.room.LyricsEntity
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.LyricsFile
import com.mardous.booming.data.model.lyrics.LyricsSource
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import com.mardous.booming.data.remote.lyrics.LyricsDownloadService
import com.mardous.booming.extensions.hasR
import com.mardous.booming.extensions.media.isArtistNameUnknown
import com.mardous.booming.util.Preferences.requireString
import org.mozilla.universalchardet.UniversalDetector
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.regex.Pattern

interface LyricsRepository {
    suspend fun parseRawLyrics(song: Song, rawLyrics: RawLyrics): SyncedLyrics?

    suspend fun fileLyrics(song: Song): RawLyrics.File?
    suspend fun embeddedLyrics(song: Song): RawLyrics.Embedded?
    suspend fun storedLyrics(song: Song, allowDownload: Boolean): RawLyrics.Stored?
    suspend fun downloadLyrics(song: Song, searchTitle: String, searchArtist: String): RawLyrics.Remote?

    suspend fun saveLyrics(
        song: Song,
        originalLyricsBySource: Map<LyricsSource, RawLyrics?>,
        newContentBySource: Map<LyricsSource, String>
    ): Boolean?

    suspend fun writableUris(song: Song): List<Uri>
    suspend fun deleteAllLyrics()

    fun clearMemoryCache()
}

class RealLyricsRepository(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val lyricsDownloadService: LyricsDownloadService,
    private val lyricsDao: LyricsDao
) : LyricsRepository {

    private val memoryCache = LruCache<Long, Map<LyricsSource, RawLyrics>>(20)

    private val charsetDetector = UniversalDetector()

    private val lrcLyricsParser = LrcLyricsParser()
    private val ttmlLyricsParser = TtmlLyricsParser()

    private val lyricsParsers = listOf(lrcLyricsParser, ttmlLyricsParser)

    override fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    override suspend fun parseRawLyrics(song: Song, rawLyrics: RawLyrics): SyncedLyrics? {
        val ignoreBlankLines = preferences.getBoolean(IGNORE_BLANK_LINES, false)
        try {
            return when (rawLyrics) {
                is RawLyrics.File -> {
                    if (rawLyrics.lyrics.isNotEmpty()) {
                        lyricsParsers.firstOrNull { it.handles(rawLyrics.file) }
                            ?.parse(rawLyrics.lyrics, song.duration, ignoreBlankLines)
                    } else null
                }

                is RawLyrics.Embedded -> {
                    if (!rawLyrics.lyrics.isNullOrEmpty()) {
                        lyricsParsers.firstOrNull { it.handles(rawLyrics.lyrics) }
                            ?.parse(rawLyrics.lyrics, song.duration, ignoreBlankLines)
                    } else null
                }

                is RawLyrics.Stored -> {
                    if (!rawLyrics.lyrics.isNullOrEmpty()) {
                        lyricsParsers.firstOrNull { it.handles(rawLyrics.lyrics) }
                            ?.parse(rawLyrics.lyrics, song.duration, ignoreBlankLines)
                            ?.copy(provider = rawLyrics.provider)
                    } else null
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't parse lyrics for song ${song.data}", e)
        }
        return null
    }

    override suspend fun fileLyrics(song: Song): RawLyrics.File? {
        getCachedLyrics<RawLyrics.File>(LyricsSource.File, song.id)?.let { return it }
        try {
            val preferredFormatValue = preferences.requireString("preferred_lyrics_file_format", "ttml").trim()
            
            val preferredFormat = when {
                preferredFormatValue.equals("ttml", ignoreCase = true) || preferredFormatValue == "0" -> LyricsFile.Format.TTML
                preferredFormatValue.equals("lrc", ignoreCase = true) || preferredFormatValue == "1" -> LyricsFile.Format.LRC
                else -> LyricsFile.Format.entries.firstOrNull { it.value.equals(preferredFormatValue, ignoreCase = true) }
            } ?: LyricsFile.Format.TTML

            val rawLyricsList = mutableListOf<RawLyrics.File>()
            val lyricsFiles = findLyricsFiles(song)

            val sortedFiles = lyricsFiles.sortedByDescending { it.format == preferredFormat }

            for (file in sortedFiles) {
                val actualFile = File(file.path)
                val lyrics = runCatching {
                    actualFile.inputStream().buffered().use { stream ->
                        val charset = detectEncoding(stream)
                        stream.reader(charset).use { it.readText() }.replace("\uFEFF", "").trim()
                    }
                }.getOrNull() ?: continue

                if (lyrics.isNotEmpty()) {
                    val rawLyrics = RawLyrics.File(file, lyrics)
                    if (file.format == preferredFormat) {
                        return cacheLyrics(song.id, rawLyrics)
                    }
                    rawLyricsList.add(rawLyrics)
                }
            }

            return rawLyricsList.firstOrNull()?.let {
                cacheLyrics(song.id, it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't find/read lyrics files for song ${song.data}", e)
        }
        return null
    }

    override suspend fun embeddedLyrics(song: Song): RawLyrics.Embedded? {
        if (song.id != Song.emptySong.id) {
            getCachedLyrics<RawLyrics.Embedded>(LyricsSource.Embedded, song.id)?.let { return it }
            try {
                val metadataReader = MetadataReader(song.uri)
                var lyrics = metadataReader.value(MetadataReader.LYRICS)
                if (lyrics.isNullOrEmpty()) {
                    lyrics = metadataReader.value("UNSYNCEDLYRICS")
                }
                return cacheLyrics(song.id, RawLyrics.Embedded(lyrics))
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't read embedded lyrics for song ${song.data}", e)
            }
        }
        return null
    }

    override suspend fun storedLyrics(song: Song, allowDownload: Boolean): RawLyrics.Stored? {
        if (song.id != Song.emptySong.id) try {
            getCachedLyrics<RawLyrics.Stored>(LyricsSource.Downloaded, song.id)?.let { return it }
            val storedLyrics = lyricsDao.getLyrics(song.id)
            if (storedLyrics == null && allowDownload) {
                val storableLyrics = lyricsDownloadService.remoteLyrics(song)
                    .prepareToStore()
                if (storableLyrics != null) {
                    if (storableLyrics.instrumental) {
                        lyricsDao.insertLyrics(
                            LyricsEntity(song.id, instrumental = true)
                        )
                    } else {
                        lyricsDao.insertLyrics(
                            LyricsEntity(
                                id = song.id,
                                lyrics = storableLyrics.lyrics,
                                provider = storableLyrics.provider
                            )
                        )
                    }
                    return cacheLyrics(song.id, storableLyrics)
                }
            } else if (storedLyrics != null) {
                return cacheLyrics(song.id, RawLyrics.Stored(
                    lyrics = storedLyrics.lyrics,
                    provider = storedLyrics.provider,
                    instrumental = storedLyrics.instrumental
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't fetch/download lyrics for song ${song.data}", e)
        }
        return null
    }

    override suspend fun downloadLyrics(
        song: Song,
        searchTitle: String,
        searchArtist: String
    ): RawLyrics.Remote? {
        if (song.id == Song.emptySong.id || searchArtist.isArtistNameUnknown()) {
            return null
        }
        return try {
            lyricsDownloadService.remoteLyrics(song, searchTitle, searchArtist, fromUser = true)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun saveLyrics(
        song: Song,
        originalLyricsBySource: Map<LyricsSource, RawLyrics?>,
        newContentBySource: Map<LyricsSource, String>
    ): Boolean? {
        try {
            val editedLyrics = newContentBySource.mapNotNull { (source, content) ->
                // 🌟 1. 恢复原作者的安全锁：如果是本地文件，直接踢出这套复杂的保存流程！
                if (source == LyricsSource.File) return@mapNotNull null
                
                val originalLyrics = originalLyricsBySource[source] ?: when (source) {
                    LyricsSource.Embedded -> RawLyrics.Embedded(null)
                    LyricsSource.Downloaded -> RawLyrics.Stored()
                    LyricsSource.File -> return@mapNotNull null 
                }
                if (originalLyrics.lyrics != content) {
                    RawLyrics.Edited(originalLyrics, content)
                } else null
            }

            if (editedLyrics.isEmpty()) {
                return null
            }

            return editedLyrics.all {
                when (it.originalLyrics) {
                    is RawLyrics.Embedded -> {
                        runCatching {
                            val metadataWriter = MetadataWriter()
                            metadataWriter.propertyMap(hashMapOf(MetadataReader.LYRICS to it.newContent))
                            metadataWriter.write(this.context, EditTarget.song(song)).isSuccess
                        }.getOrDefault(false).also { success ->
                            if (success) removeCachedLyrics(LyricsSource.Embedded, song.id)
                        }
                    }

                    is RawLyrics.Stored -> {
                        runCatching {
                            lyricsDao.insertLyrics(
                                LyricsEntity(
                                    id = song.id,
                                    lyrics = it.newContent,
                                    provider = it.newContentProvider,
                                    instrumental = it.instrumental
                                )
                            )
                            true
                        }.getOrDefault(false).also { success ->
                            if (success) removeCachedLyrics(LyricsSource.Downloaded, song.id)
                        }
                    }
                    
                    // 🌟 新增：支持将修改直接写入本地 .lrc 或 .ttml 物理文件
                    is RawLyrics.File -> {
                        runCatching {
                            val file = java.io.File(it.originalLyrics.file.path)
                            if (file.exists()) {
                                file.writeText(it.newContent)
                            }
                            true
                        }.getOrDefault(false).also { success ->
                            if (success) removeCachedLyrics(LyricsSource.File, song.id)
                        }
                    }

                    else -> false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while saving lyrics for song ${song.data}", e)
        }
        return false
    }

    override suspend fun writableUris(song: Song): List<Uri> {
        if (hasR()) {
            return listOf(song.uri).filterNot { it == Uri.EMPTY }
        }
        return emptyList()
    }

    override suspend fun deleteAllLyrics() {
        lyricsDao.removeLyrics()
        memoryCache.evictAll()
    }

    private inline fun <reified T : RawLyrics> getCachedLyrics(
        source: LyricsSource,
        songId: Long
    ): T? {
        val cachedLyrics = memoryCache[songId]
        if (cachedLyrics != null && cachedLyrics.containsKey(source)) {
            return cachedLyrics[source] as? T
        }
        return null
    }

    private fun <T : RawLyrics> cacheLyrics(songId: Long, lyrics: T): T {
        val source = when (lyrics) {
            is RawLyrics.Embedded -> LyricsSource.Embedded
            is RawLyrics.Stored -> LyricsSource.Downloaded
            is RawLyrics.File -> LyricsSource.File
            else -> null
        }
        if (source != null) {
            val cachedLyrics = memoryCache[songId]?.toMutableMap() ?: mutableMapOf()
            cachedLyrics[source] = lyrics
            memoryCache.put(songId, cachedLyrics)
        }
        return lyrics
    }

    private fun removeCachedLyrics(source: LyricsSource, songId: Long) {
        val cachedLyrics = memoryCache[songId]?.toMutableMap()
        if (cachedLyrics != null) {
            cachedLyrics.remove(source)
            memoryCache.put(songId, cachedLyrics)
        }
    }

    private fun findLyricsFiles(song: Song): List<LyricsFile> {
        val songFile = File(song.data)
        val parentDir = songFile.parentFile ?: return emptyList()

        val possibleNames = listOf(
            songFile.nameWithoutExtension,
            "${song.artistName} - ${song.title}"
        ).filter { it.isNotBlank() }

        val validFiles = mutableListOf<LyricsFile>()

        // 🌟 核心修复：抛弃 listFiles 和 Regex，双格式点射查询
        for (name in possibleNames) {
            val ttmlFile = File(parentDir, "$name.ttml")
            if (ttmlFile.exists() && ttmlFile.isFile) {
                validFiles.add(LyricsFile(ttmlFile.absolutePath, LyricsFile.Format.TTML))
            }
            
            val lrcFile = File(parentDir, "$name.lrc")
            if (lrcFile.exists() && lrcFile.isFile) {
                validFiles.add(LyricsFile(lrcFile.absolutePath, LyricsFile.Format.LRC))
            }
        }

        return validFiles
    }

    private fun detectEncoding(bis: BufferedInputStream): Charset {
        return if (preferences.getBoolean(FORCE_UTF_8_ENCODING, true)) {
            Charsets.UTF_8
        } else try {
            charsetDetector.reset()
            bis.mark(BUFFER_SIZE)

            val buf = ByteArray(BUFFER_SIZE)
            var nread: Int
            while ((bis.read(buf).also { nread = it }) > 0 && !charsetDetector.isDone) {
                charsetDetector.handleData(buf, 0, nread)
            }

            charsetDetector.dataEnd()
            charsetDetector.detectedCharset?.let {
                Charset.forName(it)
            } ?: Charsets.UTF_8
        } catch (e: IOException) {
            Log.e(TAG, "Couldn't detect lyrics file encoding", e)
            Charsets.UTF_8
        } finally {
            bis.reset()
            charsetDetector.reset()
        }
    }

    companion object {
        private const val TAG = "LyricsRepository"

        private const val BUFFER_SIZE = 4096
        private const val FORCE_UTF_8_ENCODING = "force_utf8_encoding_for_lyrics"
        private const val IGNORE_BLANK_LINES = "ignore_blank_lines_in_lyrics"
    }
}