package com.spwmods.waerwak.listenstats.stats

/**
 * 排行榜分组键的规范化
 *
 * 背景：宿主的 `MediaItem.artist` 是一个"署名串"，而不是结构化的艺术家列表。
 * 直接用它当分组键会产生两类错误：
 *  1. 合作曲目 `Aimer/TWINKLE TWINKLE LITTLE STAR` 会变成一个不存在的"艺术家"
 *  2. 不同歌手的同名专辑（例如两张都叫《Best》）会被合并成一张
 *
 * 这里的规则与参考实现保持一致：艺术家按常见分隔符拆分（保护 `AC/DC` 这类乐队名），
 * 专辑用 `专辑名 + 专辑艺术家` 双键，未标注专辑的曲目不与其它曲目合并。
 *
 * 刻意不用 Kotlin inline 集合函数：Kotlin 2.4.21 + Compose 插件 in-process 编译下会崩。
 */
object GroupKeys {

    /** 占位符：保护含斜杠的乐队名 */
    private const val SLASH_GUARD: Char = '\u0001'

    /** 未标注专辑时的分隔前缀，避免和真实专辑名冲突 */
    private const val NO_ALBUM_PREFIX = "\u0002"

    /** 已经被占位符替换的乐队名 */
    private val PROTECTED_BANDS = arrayOf(
        "AC/DC", "ac/dc", "Ac/Dc", "aC/dC",
        "H/E/R", "M/A/R/R/S"
    )

    /**
     * 分隔符只有斜杠、分号、中文顿号
     *
     * 刻意**不包含** `&` 和逗号：`Simon & Garfunkel`、`Earth, Wind & Fire`
     * 都是完整乐队名，拆开就错了。
     */
    private fun isSeparator(c: Char): Boolean {
        return c == '/' || c == ';' || c == '；' || c == '、'
    }

    /**
     * 拆分艺术家署名
     *
     * `"Aimer/TWINKLE TWINKLE LITTLE STAR"` → `["Aimer", "TWINKLE TWINKLE LITTLE STAR"]`
     * `"AC/DC"` → `["AC/DC"]`
     * 空串或全是分隔符 → 返回 `["未知歌手"]`
     */
    fun splitArtists(credits: String): List<String> {
        val out = ArrayList<String>(2)
        val raw = credits.trim()
        if (raw.isEmpty()) {
            out.add("未知歌手")
            return out
        }
        var guarded = raw
        var g = 0
        while (g < PROTECTED_BANDS.size) {
            guarded = guarded.replace(PROTECTED_BANDS[g], SLASH_GUARD.toString())
            g++
        }
        val sb = StringBuilder()
        var i = 0
        while (i < guarded.length) {
            val c = guarded[i]
            if (isSeparator(c)) {
                addArtist(out, sb)
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        addArtist(out, sb)
        if (out.isEmpty()) out.add("未知歌手")
        return out
    }

    private fun addArtist(out: MutableList<String>, sb: StringBuilder) {
        if (sb.length == 0) return
        var name = sb.toString()
        if (name.indexOf(SLASH_GUARD) >= 0) name = name.replace(SLASH_GUARD.toString(), "/")
        name = name.trim()
        if (name.isEmpty()) return
        var i = 0
        while (i < out.size) {
            if (out[i].equals(name, ignoreCase = true)) return
            i++
        }
        out.add(name)
    }

    /**
     * 专辑分组键
     *
     * - 有专辑名：`专辑名 + 专辑艺术家`（忽略大小写）；专辑艺术家为空时回退到曲目艺术家
     * - 无专辑名：每首曲目独立（用 `key` 区分），避免把所有"未知专辑"合成一张
     */
    fun albumGroupKey(album: String, albumArtist: String, artist: String, key: String): String {
        val name = album.trim()
        if (name.isEmpty()) return NO_ALBUM_PREFIX + key
        var owner = albumArtist.trim()
        if (owner.isEmpty()) owner = artist.trim()
        return if (owner.isEmpty()) {
            name.lowercase()
        } else {
            name.lowercase() + "\u0000" + owner.lowercase()
        }
    }

    /** 专辑榜显示名 */
    fun albumDisplayName(album: String): String {
        val name = album.trim()
        return if (name.isEmpty()) "未知专辑" else name
    }

    /** 专辑榜副标题（专辑艺术家） */
    fun albumDisplayArtist(albumArtist: String, artist: String): String {
        var owner = albumArtist.trim()
        if (owner.isEmpty()) owner = artist.trim()
        return if (owner.isEmpty()) "未知歌手" else owner
    }
}
