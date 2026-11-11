package com.spwmods.waerwak.listenstats.ui

/**
 * hook 句柄所有权
 *
 * 1.5.1 之前每个 hook 都是单独字段 + 卸载时各自 try/catch 忽略失败：一旦某个句柄
 * 释放失败，字段仍被清空，代码就以为"已经卸干净了"，下次启用会在同一调用点再注册一遍。
 * 宿主对同一个 call site 重复注册 replaceComposable 不报错、afterComposable 会重复执行，
 * 于是出现"面板叠一层、按钮画两个"这类难以复现的问题。
 *
 * 这里的规则很简单：**只有 close 成功返回的句柄才算释放**，失败的留在集合里，
 * 调用方据此拒绝后续安装，并在诊断里明确提示"需重启 SPW"。
 *
 * 线程约定：只在安装/卸载线程（当前是注入线程或宿主界面线程）内使用，不加锁。
 */
class HookHandles : AutoCloseable {

    private val handles = ArrayList<AutoCloseable>(8)

    val size: Int get() = handles.size

    val isEmpty: Boolean get() = handles.isEmpty()

    operator fun plusAssign(handle: AutoCloseable) {
        handles.add(handle)
    }

    /** 逆序关闭；成功的移除，失败的留下并把异常抛给调用方（多个失败合并为 suppressed） */
    override fun close() {
        var failure: Throwable? = null
        var i = handles.size - 1
        while (i >= 0) {
            val handle = handles[i]
            try {
                handle.close()
                handles.removeAt(i)
            } catch (t: Throwable) {
                failure = mergeFailure(failure, t)
            }
            i--
        }
        if (failure != null) throw failure
    }

    /**
     * 注册中途失败：先尽力回滚本层已装的句柄，再把"仍未释放"的状态交给上层
     *
     * 返回的 [HookRegistrationFailure] 里 remaining 就是本对象（只含关闭失败的句柄）。
     */
    fun registrationFailed(cause: Throwable): HookRegistrationFailure {
        try {
            close()
        } catch (cleanup: Throwable) {
            if (cleanup !== cause) cause.addSuppressed(cleanup)
        }
        val nestedIncomplete = cause is HookRegistrationFailure && !cause.rollbackComplete
        return HookRegistrationFailure(cause, this, nestedIncomplete)
    }

    companion object {
        private fun mergeFailure(current: Throwable?, next: Throwable): Throwable {
            if (current == null) return next
            if (current !== next) current.addSuppressed(next)
            return current
        }
    }
}

/**
 * 注册失败
 *
 * [rollbackComplete] 为 false 表示宿主里还留着没释放的 hook：不能继续装，
 * 只能提示用户重启 SPW（重启后宿主侧 hook 随类加载器一起消失）。
 */
class HookRegistrationFailure(
    cause: Throwable,
    val remaining: HookHandles,
    private val nestedIncomplete: Boolean = false
) : RuntimeException(
    "宿主 hook 注册失败（回滚" + (if (nestedIncomplete || !remaining.isEmpty) "不完整" else "完整") + "）",
    cause
) {
    val rollbackComplete: Boolean get() = !nestedIncomplete && remaining.isEmpty
}

/**
 * 一次成功（或部分成功）的安装
 *
 * - [cacheable]：本次安装是否所有计划内的组都装上了（缺顶栏/材质时为 false，不写注入点缓存）
 * - [incompleteCause]：缺项原因，写进诊断与信息页
 * - [onClosed]：句柄全部处理完后回调（传释放失败时的异常），调用方用它复位 UI 状态
 */
class HostHookInstallation(
    val handles: HookHandles,
    val cacheable: Boolean,
    val incompleteCause: Throwable? = null,
    private val onClosed: (Throwable?) -> Unit = {}
) : AutoCloseable {

    override fun close() {
        var failure: Throwable? = null
        try {
            handles.close()
        } catch (t: Throwable) {
            failure = t
        }
        onClosed(failure)
        if (failure != null) throw failure
    }
}

/**
 * 重试策略
 *
 * 权限被收回、线程被中断、任务被取消这几种情况重试没有意义（要么一直失败，
 * 要么本次启用的会话已经作废），必须直接向上抛，避免反复扫描宿主字节码。
 */
object HookFailurePolicy {

    fun noRetry(error: Throwable): Boolean {
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        return inspect(error, seen)
    }

    private fun inspect(error: Throwable, seen: MutableSet<Throwable>): Boolean {
        if (!seen.add(error)) return false
        if (error is InterruptedException) return true
        if (error is java.util.concurrent.CancellationException) return true
        if (error is SecurityException) return true
        if (error.javaClass.simpleName.contains("PermissionDenied")) return true
        val cause = error.cause
        if (cause != null && inspect(cause, seen)) return true
        val suppressed = error.suppressed
        var i = 0
        while (i < suppressed.size) {
            if (inspect(suppressed[i], seen)) return true
            i++
        }
        return false
    }
}
