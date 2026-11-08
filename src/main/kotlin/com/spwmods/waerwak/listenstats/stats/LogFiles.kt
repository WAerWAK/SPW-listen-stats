package com.spwmods.waerwak.listenstats.stats

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * 带轮转的日志追加
 *
 * 诊断日志本来只写启动行与注入结果，但宿主升级频繁时 hook-diag 会持续增长；
 * 这里统一按 1 MB 轮转、保留 3 份历史，写失败永远不抛异常（诊断不能影响主流程）。
 */
object LogFiles {

    private const val MAX_BYTES = 1024L * 1024L
    private const val KEEP = 3

    fun append(file: File, line: String) {
        try {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            if (file.isFile && file.length() > MAX_BYTES) rotate(file)
            Files.write(
                file.toPath(),
                line.toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 写日志失败: " + t.message)
        }
    }

    /** 从最旧的开始往后挪：.2→.3、.1→.2、当前→.1 */
    private fun rotate(file: File) {
        val parent = file.parentFile ?: return
        var index = KEEP
        while (index >= 1) {
            val source = if (index == 1) file else File(parent, file.name + "." + (index - 1))
            if (source.exists()) {
                val target = File(parent, file.name + "." + index)
                try {
                    if (target.exists()) target.delete()
                    source.renameTo(target)
                } catch (t: Throwable) {
                    // 轮转失败就继续往当前文件写，不影响功能
                }
            }
            index--
        }
    }
}
