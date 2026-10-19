package com.spwmods.waerwak.listenstats.stats

/**
 * 极简 JSON 读写
 *
 * 不引 kotlinx.serialization：插件体积、宿主依赖、版本对齐都不值得。
 * 事件日志是固定 schema 的单行 JSON，正则解析足够安全（字符串值已转义，
 * 不会出现裸引号把字段切错）。
 */
internal object JsonLite {

    fun escape(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length + 8)
        for (c in raw) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c.code < 0x20) {
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
            }
        }
        return sb.toString()
    }

    fun unescape(raw: String): String {
        if (raw.indexOf('\\') < 0) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val n = raw[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' ->
                        if (i + 5 < raw.length) {
                            val hex = raw.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                sb.append(n); i += 2
                            }
                        } else {
                            sb.append(n); i += 2
                        }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun string(line: String, key: String, def: String = ""): String {
        val m = Regex("\"$key\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(line) ?: return def
        return unescape(m.groupValues[1])
    }

    fun long(line: String, key: String, def: Long = 0L): Long {
        val m = Regex("\"$key\":(-?\\d+)").find(line) ?: return def
        return m.groupValues[1].toLongOrNull() ?: def
    }

    fun bool(line: String, key: String, def: Boolean = false): Boolean {
        val m = Regex("\"$key\":(true|false)").find(line) ?: return def
        return m.groupValues[1] == "true"
    }
}
