package com.spwmods.waerwak.listenstats.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.concurrent.Executors

/**
 * 后台文件对话框 + 磁盘 I/O
 *
 * 宿主的界面是 Compose Desktop（事件循环就是 EDT）。模态文件框与备份/恢复/CSV 的读写
 * 都不能跑在这条循环上：zip 打包上万行数据时会卡住整个窗口，嵌套模态还会让 Compose 掉帧。
 *
 * 约定：
 * - 对话框本身仍回到 EDT 上显示（Swing/AWT 不是线程安全的），后台线程只负责等待与后续 I/O；
 * - 同一时刻只允许一个文件任务，避免连点弹出多个模态框；
 * - 任务结果通过回调回投，回调里只做 toast / 状态更新。
 */
object BackgroundDialogs {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "listen-stats-files").apply { isDaemon = true }
    }

    @Volatile
    private var busy = false

    fun isBusy(): Boolean = busy

    /** 在后台线程执行（可弹模态对话框 + 做磁盘 I/O），完成后回调；[task] 返回给用户看的文案 */
    fun run(task: () -> String?, onDone: (String?) -> Unit) {
        if (busy) {
            onDone("正在处理上一个文件操作，请稍候")
            return
        }
        busy = true
        executor.execute {
            var result: String?
            try {
                result = task()
            } catch (t: Throwable) {
                result = "失败：" + t.javaClass.simpleName + "：" + (t.message ?: "")
            } finally {
                busy = false
            }
            try {
                onDone(result)
            } catch (t: Throwable) {
                System.err.println("[listen-stats] 文件操作回调失败: " + t.message)
            }
        }
    }

    /** 保存对话框（阻塞后台线程）；取消返回 null */
    fun saveFile(title: String, defaultName: String): File? {
        return showFile(title, defaultName, true)
    }

    /** 打开对话框（阻塞后台线程）；取消返回 null */
    fun openFile(title: String, defaultName: String = ""): File? {
        return showFile(title, defaultName, false)
    }

    private fun showFile(title: String, defaultName: String, save: Boolean): File? {
        var result: File? = null
        // 对话框必须在 EDT 上创建/显示：后台线程用 invokeAndWait 等它关掉
        javax.swing.SwingUtilities.invokeAndWait {
            val dialog = FileDialog(null as Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
            if (defaultName.isNotEmpty()) dialog.file = defaultName
            try {
                dialog.isVisible = true
                val dir = dialog.directory
                val name = dialog.file
                if (dir != null && name != null) result = File(dir, name)
            } finally {
                dialog.dispose()
            }
        }
        return result
    }

    /** 确认框（EDT 上显示，后台线程等待） */
    fun confirm(title: String, message: String): Boolean {
        var ok = false
        javax.swing.SwingUtilities.invokeAndWait {
            val choice = javax.swing.JOptionPane.showConfirmDialog(
                null,
                message,
                title,
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE
            )
            ok = choice == javax.swing.JOptionPane.YES_OPTION
        }
        return ok
    }
}
