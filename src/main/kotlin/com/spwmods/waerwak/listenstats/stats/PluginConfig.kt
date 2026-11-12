package com.spwmods.waerwak.listenstats.stats

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper

/**
 * config.json 的统一读写
 *
 * 宿主配置页的控件保存时用的是**另一份** ConfigHelper 实例，插件若自持一份旧快照再
 * `set` + `save`，就把用户刚在配置页改的其它项整份覆盖回去了（1.5.1 各模块各建 helper，
 * 只是靠"每次都 reload"降低概率，并没有真正解决）。
 *
 * 规则（借鉴 Jethuit v0.5.0 的 Prefs）：
 *  - 读：先 `reload()` 再 `get`，失败回落默认值
 *  - 写：先 `reload()`，成功才 `set` + `save()`；reload 失败就放弃本次写入
 *  - 全部方法 `@Synchronized`：宿主配置页与面板可能同时改配置
 *
 * 刻意不用 Kotlin inline 集合/作用域函数（本项目 in-process 编译有过 codegen 崩溃）。
 */
@OptIn(UnstableSpwWorkshopApi::class)
object PluginConfig {

    const val FILE_NAME = "config.json"

    /** 浮钮是否显示（宿主配置页 switch） */
    const val SHOW_FLOATING_BUTTON = "showFloatingButton"

    /** 标题栏按钮是否显示（宿主配置页 switch） */
    const val SHOW_TITLEBAR_BUTTON = "showTitlebarButton"

    @Volatile
    private var helper: ConfigHelper? = null

    @Volatile
    private var helperTried = false

    @Synchronized
    private fun config(): ConfigHelper? {
        val existing = helper
        if (existing != null) return existing
        if (helperTried) return null
        helperTried = true
        helper = try {
            WorkshopApi.manager.createConfigManager().getConfig(FILE_NAME)
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 打开 config.json 失败: " + t.message)
            null
        }
        return helper
    }

    /** 配置是否可用（宿主配置组件不可用时不要用默认值覆盖内存里的值） */
    fun isAvailable(): Boolean = config() != null

    /** 清掉缓存的 helper（测试/重新启用时用） */
    @Synchronized
    fun reset() {
        helper = null
        helperTried = false
    }

    @Synchronized
    fun getBoolean(key: String, fallback: Boolean): Boolean {
        val h = config() ?: return fallback
        return try {
            h.reload()
            val value = h.get(key, fallback)
            value ?: fallback
        } catch (t: Throwable) {
            fallback
        }
    }

    @Synchronized
    fun getInt(key: String, fallback: Int, min: Int, max: Int): Int {
        val h = config() ?: return fallback
        return try {
            h.reload()
            val value = (h.get(key, fallback) as? Number)?.toInt() ?: fallback
            if (value < min) min else if (value > max) max else value
        } catch (t: Throwable) {
            fallback
        }
    }

    @Synchronized
    fun getString(key: String, fallback: String): String {
        val h = config() ?: return fallback
        return try {
            h.reload()
            (h.get(key, fallback) as? String) ?: fallback
        } catch (t: Throwable) {
            fallback
        }
    }

    /** 键是否存在（旧配置迁移用：不能把"用户没设过"当成"用户设成默认值"） */
    @Synchronized
    fun contains(key: String): Boolean {
        val h = config() ?: return false
        return try {
            h.reload()
            val probe: Any? = h.get<Any?>(key, null)
            probe != null
        } catch (t: Throwable) {
            false
        }
    }

    /** 读-改-写；返回是否保存成功 */
    @Synchronized
    fun edit(block: (ConfigHelper) -> Unit): Boolean {
        val h = config() ?: return false
        return try {
            if (!h.reload()) {
                System.err.println("[listen-stats] 保存配置前 reload 失败，已放弃本次写入")
                return false
            }
            block(h)
            h.save()
        } catch (t: Throwable) {
            System.err.println("[listen-stats] 保存配置失败: " + t.message)
            false
        }
    }

    fun setBoolean(key: String, value: Boolean) {
        edit { h -> h.set(key, value) }
    }
}
