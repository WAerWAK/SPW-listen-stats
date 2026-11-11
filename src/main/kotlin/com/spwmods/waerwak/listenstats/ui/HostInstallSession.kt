package com.spwmods.waerwak.listenstats.ui

/**
 * 界面注入会话
 *
 * 宿主停用插件走的是后台 FutureTask（SPW 1.19.3 有 3 秒超时等待），而首次注入要先扫描
 * 宿主字节码（约 1.5 秒）。1.5.1 之前这两件事没有关联：停用已经开始，后台还在扫，
 * 扫完照样把 hook 注册进去 —— 句柄没人持有，下一次启用就出现重复注册。
 *
 * 这里给每次启用分配一个代号：`stop()` 一递增，之前所有在途扫描/准备结果立即作废，
 * 注册前必须再确认一次"我还属于当前会话"。
 *
 * 只做代数与状态，不持有任何句柄，也不做线程调度。
 *
 * 注意：本文件刻意不用 `synchronized(lock) { ... }` 内联函数（本项目在 in-process 编译下
 * 曾因 inline 函数 + 后置自增触发后端 codegen 崩溃），统一用 `@Synchronized` 方法。
 */
object HostInstallSession {

    @Volatile
    private var generation = 0L

    @Volatile
    private var stopped = true

    /** 开始一次新会话（每次启用/手动安装都算），返回本次代号 */
    @Synchronized
    fun begin(): Long {
        generation += 1
        stopped = false
        return generation
    }

    /** 当前代号（诊断用） */
    fun current(): Long = generation

    /**
     * 本会话是否还有效
     *
     * 注册前必须调用：过期意味着插件已停用或被下一次启用取代。
     */
    @Synchronized
    fun isCurrent(session: Long): Boolean {
        return !stopped && session == generation
    }

    /** 停用：使在途结果作废，返回新的代号（诊断用） */
    @Synchronized
    fun stop(): Long {
        stopped = true
        generation += 1
        return generation
    }
}
