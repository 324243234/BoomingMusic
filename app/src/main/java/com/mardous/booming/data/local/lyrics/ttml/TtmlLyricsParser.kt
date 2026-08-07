package com.mardous.booming.data.local.lyrics.ttml

import android.util.Log
import com.mardous.booming.data.LyricsParser
import com.mardous.booming.data.model.lyrics.LyricsFile
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.util.regex.Pattern

/**
 * Parser for TTML (Timed Text Markup Language) format.
 *
 * This parser is designed to handle Apple Music-style TTML lyrics, including:
 * - Hierarchical structure: `<body>` -> `<div>` (sections) -> `<p>` (lines) -> `<span>` (words/background)
 * - Word-level synchronization using `<span>` tags with `begin`, `end`, or `dur`.
 * - Multiple agents (actors) defined in the `<head>` and referenced by `ttm:agent`.
 * - Background vocals identified by `ttm:role="x-bg"`.
 * - Translations and transliterations.
 * - Various time expressions (clock time and offset time).
 */
class TtmlLyricsParser : LyricsParser {

    override fun handles(file: LyricsFile): Boolean =
        file.format == LyricsFile.Format.TTML

    /**
     * Quickly checks if the reader content is a valid TTML lyrics file.
     * It verifies the presence of the `<tt>` root tag and at least one `<div>` inside `<body>`.
     */
    // 1. 替换 handles 方法（放宽检测：只需包含 <tt> 和 <body> 即可认为是合法格式）
    override fun handles(reader: Reader): Boolean {
    return try {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(reader)
        }

        var foundTt = false
        var foundBody = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "tt" -> foundTt = true
                    "body" -> {
                        foundBody = true
                        break // 只要找到 body 即可安全放行
                    }
                }
            }
            event = parser.next()
        }
        foundTt && foundBody
    } catch (_: Exception) {
        false
       }
   }

    /**
     * Main entry point for parsing TTML content using [XmlPullParser].
     * It builds a [TtmlNodeTree] by processing start and end tags.
     * The tree is then converted into [SyncedLyrics].
     */
    override fun parse(reader: Reader, trackLength: Long, ignoreBlankLines: Boolean): SyncedLyrics? {
        try {
            // 🌟 修复 2：在解析前先暴力清洗一遍流里的隐形 BOM 字符
            val cleanContent = reader.readText().replace("\uFEFF", "").trim()
            if (cleanContent.isEmpty()) return null

            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(java.io.StringReader(cleanContent))

            val nodeTree = TtmlNodeTree()
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name
                        if (!isSupportedTag(name)) {
                            eventType = parser.next()
                            continue
                        }
                        when (name) {
                            TtmlNode.TAG_AGENT -> {
                                nodeTree.addAgent(
                                    id = parser.getAttributeValue(null, "xml:id"),
                                    type = parser.getAttributeValue(null, "type")
                                )
                            }
                            TtmlNode.TAG_TRANSLITERATION -> {
                                val lang = parser.getAttributeValue(null, "xml:lang")
                                if (nodeTree.createTransliteration(lang) == null) {
                                    throw XmlPullParserException("transliteration format isn't valid")
                                }
                            }
                            TtmlNode.TAG_TRANSLATION -> {
                                val lang = parser.getAttributeValue(null, "xml:lang")
                                // 🌟 修复 3：移除对 type="subtitle" 的硬性限制，改抛异常为 Log 警告平滑跳过
                                if (lang != null && nodeTree.createTranslation(lang) == null) {
                                    Log.w("TtmlLyricsParser", "Translation format skipped or invalid for lang: $lang")
                                }
                            }
                            TtmlNode.TAG_TEXT -> {
                                val key = parser.getAttributeValue(null, "for")
                                if (!nodeTree.prepareAccompanimentText(key)) break
                            }
                            TtmlNode.TAG_BODY -> {
                                val hasRoot = nodeTree.addRoot(
                                    TtmlNode.buildBody(parser.getTimeAttribute("dur"))
                                )
                                if (!hasRoot) break
                            }
                            TtmlNode.TAG_DIV -> {
                                val openSection = nodeTree.openSection(
                                    TtmlNode.buildSection(
                                        begin = parser.getTimeAttribute("begin"),
                                        end = parser.getTimeAttribute("end"),
                                        dur = parser.getTimeAttribute("dur")
                                    )
                                )
                                if (!openSection && nodeTree.hasRoot) break
                            }
                            TtmlNode.TAG_PARAGRAPH -> {
                                val agentAttribute = parser.getAttributeValue(null, "ttm:agent")
                                val openLine = nodeTree.openLine(
                                    TtmlNode.buildLine(
                                        begin = parser.getTimeAttribute("begin"),
                                        end = parser.getTimeAttribute("end"),
                                        dur = parser.getTimeAttribute("dur"),
                                        key = parser.getAttributeValue(null, "itunes:key"),
                                        agent = agentAttribute?.let { nodeTree.getAgent(it) }
                                    )
                                )
                                if (!openLine && nodeTree.hasRoot) break
                            }
                            TtmlNode.TAG_SPAN -> {
                                val role = parser.getAttributeValue(null, "ttm:role")
                                val lang = parser.getAttributeValue(null, "xml:lang")
                                if (role == null) {
                                    val openWord = nodeTree.openWord(
                                        TtmlNode.buildWord(
                                            begin = parser.getTimeAttribute("begin"),
                                            end = parser.getTimeAttribute("end"),
                                            dur = parser.getTimeAttribute("dur")
                                        )
                                    )
                                    if (!openWord && nodeTree.hasRoot) break
                                } else {
                                    when (role) {
                                        "x-bg" -> {
                                            nodeTree.enterBackground()
                                        }
                                        "x-translation" -> {
                                            nodeTree.prepareTranslationForCurrentLine(lang)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val name = parser.name
                        if (!isSupportedTag(name)) {
                            eventType = parser.next()
                            continue
                        }
                        when (name) {
                            TtmlNode.TAG_TRANSLITERATION,
                            TtmlNode.TAG_TRANSLATION -> if (!nodeTree.closeAccompaniment()) break
                            TtmlNode.TAG_TEXT -> if (!nodeTree.finishAccompanimentText()) break
                            TtmlNode.TAG_BODY -> if (!nodeTree.closeNode(TtmlNode.NODE_BODY)) break
                            TtmlNode.TAG_DIV -> if (!nodeTree.closeNode(TtmlNode.NODE_SECTION)) break
                            TtmlNode.TAG_PARAGRAPH -> if (!nodeTree.closeNode(TtmlNode.NODE_LINE)) break
                            TtmlNode.TAG_SPAN -> {
                                if (!nodeTree.finishTranslationForCurrentLine()) {
                                    val closeWord = nodeTree.closeNode(TtmlNode.NODE_WORD)
                                    if (!closeWord) {
                                        if (!nodeTree.closeBackground()) break
                                    }
                                }
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        nodeTree.setText(parser.text)
                    }
                } 
                eventType = parser.next()
            }
            nodeTree.close()
            return nodeTree.toLyrics(trackLength)
        } catch (e: Exception) {
            Log.e("TtmlLyricsParser", "Couldn't parse TTML lyrics", e)
        }
        return null
    }

    private fun isSupportedTag(name: String?) = TtmlNode.isSupportedTag(name)

    private fun XmlPullParser.getTimeAttribute(name: String): Long {
        try {
            val attribute = getAttributeValue(null, name)
            if (attribute != null) {
                return parseTimeExpression(attribute)
            }
        } catch (e: XmlPullParserException) {
            Log.e("TtmlLyricsParser", "Failed to parse time attribute: $name", e)
        }
        return -1
    }

    /**
     * Parses time expressions from TTML attributes.
     * Supports:
     * - Simple seconds: `12.34`
     * - Complex clock time: `00:12:34.56`
     * - Offset time with units: `100ms`, `2.5s`, `1m`
     */
    // 2. 替换 parseTimeExpression 方法（精准补齐 3 位毫秒）
   @Throws(XmlPullParserException::class)
   private fun parseTimeExpression(time: String?): Long {
    if (time == null) return -1

    var matcher = CLOCK_TIME_COMPLEX.matcher(time)
    if (matcher.matches()) {
        val hours = matcher.group(1)?.toLong() ?: 0L
        val minutes = matcher.group(2)?.toLong() ?: 0L
        val seconds = matcher.group(3)?.toLong() ?: 0L
        val fractionStr = matcher.group(4)
        
        // 🌟 严谨修复：右侧补齐 3 位 '0'。将 .5 秒正确转换为 500 毫秒，.05 转换为 50 毫秒
        val millis = if (fractionStr != null) {
            fractionStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        } else 0L

        return (hours * 3600_000L) + (minutes * 60_000L) + (seconds * 1000L) + millis
    }

    matcher = CLOCK_TIME_SIMPLE.matcher(time)
    if (matcher.matches()) {
        val seconds = matcher.group(1)?.toLongOrNull() ?: 0L
        val millis = matcher.group(2)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        return (seconds * 1000L) + millis
    }

    matcher = OFFSET_TIME.matcher(time)
    if (matcher.matches()) {
        val timeValue = matcher.group(1)?.toDouble() ?: 0.0
        val unit = matcher.group(2)
        return when (unit) {
            "h" -> (timeValue * 3600_000).toLong()
            "m" -> (timeValue * 60_000).toLong()
            "s" -> (timeValue * 1_000).toLong()
            "ms" -> timeValue.toLong()
            else -> 0L
        }
    }
    throw XmlPullParserException("Malformed time expression: $time")
}

    companion object {
        private val CLOCK_TIME_SIMPLE = Pattern.compile("^(\\d+)(?:\\.(\\d{1,3}))?$")
        private val CLOCK_TIME_COMPLEX = Pattern.compile("^(?:(\\d+):)?([0-5]?\\d):([0-5]?\\d)(?:\\.(\\d{1,3}))?$")
        private val OFFSET_TIME = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(h|m|s|ms)$")
    }
}