package com.spwmods.waerwak.listenstats.stats

/**
 * 宿主当前播放倍速
 *
 * Workshop 官方 API 不暴露倍速；宿主公开类 com.xuncorp.voxzen.service.PlaybackController
 * 有 getPlaybackSpeed(): Float（1.19.3 实测非混淆）。这里用反射读取，宿主升级/改名时
 * 自动回退到 1.0（等效按 1 倍速判定），不影响计时主流程。
 *
 * 用途：位置-时间一致性判据、跳变容差、停摆钳制都要按真实倍速换算，
 * 否则 >4 倍速会被误判成跳转而少记，低速播放会被误判成停摆。
 */
object PlaybackSpeed {

    private const val REFRESH_MS = 1_000L

    /** 测试注入 */
    internal var overrideProvider: (() -> Float)? = null

    private var lastReadMs = 0L
    private var lastValue = 1.0f
    private var initialized = false
    private var controller: Any? = null
    private var method: java.lang.reflect.Method? = null

    @Synchronized
    internal fun resetForTesting() {
        overrideProvider = null
        lastReadMs = 0L
        lastValue = 1.0f
        initialized = false
        controller = null
        method = null
    }

    /** 当前倍速（>=0.1），读不到返回 1.0；每秒最多读一次 */
    fun current(monotonicMs: Long): Float {
        val injected = overrideProvider
        if (injected != null) {
            val v = try {
                injected()
            } catch (t: Throwable) {
                1.0f
            }
            if (v.isFinite() && v > 0.1f) lastValue = v
            return lastValue
        }
        if (monotonicMs - lastReadMs < REFRESH_MS) return lastValue
        lastReadMs = monotonicMs
        ensureInit()
        val m = method
        val target = controller
        if (m == null || target == null) return lastValue
        val v = try {
            val raw = m.invoke(target)
            if (raw is Float) raw else 1.0f
        } catch (t: Throwable) {
            // 宿主升级/方法消失：永久回退 1.0
            method = null
            1.0f
        }
        if (v.isFinite() && v > 0.1f) lastValue = v
        return lastValue
    }

    @Synchronized
    private fun ensureInit() {
        if (initialized) return
        initialized = true
        try {
            val cls = Class.forName("com.xuncorp.voxzen.service.PlaybackController")
            val inst = cls.getField("INSTANCE").get(null)
            val m = cls.getMethod("getPlaybackSpeed")
            controller = inst
            method = m
        } catch (t: Throwable) {
            controller = null
            method = null
        }
    }

    /** 是否读到了真实倍速（诊断用） */
    fun available(): Boolean = method != null || overrideProvider != null
}
