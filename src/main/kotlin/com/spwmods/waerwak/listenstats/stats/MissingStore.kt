package com.spwmods.waerwak.listenstats.stats

import java.io.File
import java.nio.file.Files

/**
 * 缺失曲目记录
 *
 * 「缺失」= 曲目字典里记过、但宿主当前音乐库里已经没有（文件被删除 / 移动）。
 * 检测只在用户点按钮时执行（宿主库可能上万首，不自动跑），结果落盘记住：
 * missing.txt = 一行检测时间戳 + 一行缺失 id + 一行缺失 hex key。
 */
internal object MissingStore {

    private const val FILE_NAME = "missing.txt"

    @Volatile
    private var keys: Set<String> = emptySet()

    @Volatile
    private var ids: Set<String> = emptySet()

    @Volatile
    private var checkedTime: Long = 0L

    /** 榜单里是否隐藏缺失曲目（开关由面板启动时从 ui-state.json 同步） */
    @Volatile
    var hidden: Boolean = false

    @Volatile
    private var loaded = false

    private var file: File? = null

    private fun targetFile(): File? {
        val known = file
        if (known != null) return known
        val dir = StatsStore.dataDirOrNull() ?: return null
        val f = File(dir, FILE_NAME)
        file = f
        return f
    }

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = targetFile() ?: return
        if (!f.isFile) return
        try {
            val lines = Files.readAllLines(f.toPath(), Charsets.UTF_8)
            if (lines.isNotEmpty()) checkedTime = lines[0].trim().toLongOrNull() ?: 0L
            if (lines.size > 1) ids = splitSet(lines[1])
            if (lines.size > 2) keys = splitSet(lines[2])
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 读取缺失曲目记录失败: " + t.message)
        }
    }

    private fun splitSet(line: String): Set<String> {
        val out = HashSet<String>()
        val parts = line.split(' ')
        var i = 0
        while (i < parts.size) {
            if (parts[i].isNotEmpty()) out.add(parts[i])
            i++
        }
        return out
    }

    /** 该曲目（hex key）是否缺失 */
    fun isMissingKey(key: String): Boolean {
        ensureLoaded()
        if (keys.isEmpty()) return false
        return keys.contains(key)
    }

    /** 该曲目 id 是否缺失（封面缓存用来跳过宿主请求） */
    fun isMissingId(id: String): Boolean {
        ensureLoaded()
        if (ids.isEmpty()) return false
        return ids.contains(id)
    }

    /** 缺失曲目数 */
    fun count(): Int {
        ensureLoaded()
        return keys.size
    }

    /** 上次检测时间（毫秒；0 = 从未检测） */
    fun checkedAt(): Long {
        ensureLoaded()
        return checkedTime
    }

    /** 换数据目录（测试用）：丢掉内存与缓存的文件句柄 */
    @Synchronized
    fun attach(dir: File?) {
        file = if (dir == null) null else File(dir, FILE_NAME)
        loaded = false
        keys = emptySet()
        ids = emptySet()
        checkedTime = 0L
    }

    /** 写入一次检测结果（内存 + 落盘） */
    fun setResult(missingKeys: Set<String>, missingIds: Set<String>, now: Long) {
        keys = missingKeys
        ids = missingIds
        checkedTime = now
        loaded = true
        write()
    }

    private fun write() {
        val f = targetFile() ?: return
        try {
            val parent = f.parentFile
            if (parent != null) parent.mkdirs()
            val body = ArrayList<String>(3)
            body.add(checkedTime.toString())
            body.add(joinSet(ids))
            body.add(joinSet(keys))
            Files.write(f.toPath(), body, Charsets.UTF_8)
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 写入缺失曲目记录失败: " + t.message)
        }
    }

    private fun joinSet(set: Set<String>): String {
        val sb = StringBuilder(set.size * 37)
        var first = true
        for (v in set) {
            if (!first) sb.append(' ')
            sb.append(v)
            first = false
        }
        return sb.toString()
    }

    /** 清空记录（清空统计时一起清） */
    fun clear() {
        keys = emptySet()
        ids = emptySet()
        checkedTime = 0L
        loaded = true
        val f = targetFile()
        if (f != null && f.isFile) {
            try {
                f.delete()
            } catch (t: Throwable) {
                System.err.println("[listen-stats] 删除缺失曲目记录失败: " + t.message)
            }
        }
    }
}
