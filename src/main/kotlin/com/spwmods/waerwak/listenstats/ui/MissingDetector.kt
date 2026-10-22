package com.spwmods.waerwak.listenstats.ui

import androidx.compose.runtime.mutableStateOf
import com.spwmods.waerwak.listenstats.stats.MissingStore
import com.spwmods.waerwak.listenstats.stats.TrackDict
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.util.concurrent.TimeUnit

/**
 * 缺失检测的界面状态
 *
 * 用 Compose 状态包一层：检测在后台线程跑，结束后 +1 让所有榜单重建
 * （徽章要跟着变、隐藏开关下还要重算分页）。
 */
object MissingState {

    /** 结果版本：变化后榜单/概览/弹窗都要重建 */
    val version = mutableStateOf(0)

    /** 是否正在检测（按钮禁用「检测中…」） */
    val scanning = mutableStateOf(false)

    /** 最近一次检测的结果文案 */
    val lastMessage = mutableStateOf("")
}

/**
 * 缺失曲目检测
 *
 * 判定方式：宿主 `getAllTracks()` 的 id 集合 vs 曲目字典里记过的 id。
 * 不在可读集合里的（含宿主自己标记 readable=false 的）即缺失。
 *
 * 有意做成手动触发：宿主库大时 getAllTracks 要几百毫秒到数秒，
 * 每次打开面板都跑会拖慢首屏；用户也可能只是不想查。
 */
object MissingDetector {

    fun detect() {
        if (MissingState.scanning.value) return
        MissingState.scanning.value = true
        val thread = Thread({
            var message: String
            try {
                message = scan()
            } catch (t: Throwable) {
                message = "检测失败：" + (t.message ?: t.javaClass.simpleName)
            }
            MissingState.lastMessage.value = message
            MissingState.scanning.value = false
        }, "listen-stats-missing")
        thread.isDaemon = true
        thread.start()
    }

    private fun scan(): String {
        val dict = TrackDict.snapshotKeys()
        if (dict.isEmpty()) return "没有可检测的曲目（曲目字典为空）"

        val future = WorkshopApi.library.getAllTracks().toCompletableFuture()
        val list = future.get(20L, TimeUnit.SECONDS)
        val readable = HashSet<String>()
        var total = 0
        if (list != null) {
            for (item in list) {
                val id = item.id
                if (id.isBlank()) continue
                total++
                // 宿主对「文件找不到」的记录会保留但标记不可读
                if (item.readable) readable.add(id)
            }
        }
        // 空列表既可能是真空库，也可能是权限/宿主异常：不做写盘，避免把全部曲目标成缺失
        if (total == 0) return "检测失败：宿主音乐库没有返回曲目（可能是权限或库为空）"

        val missingKeys = HashSet<String>()
        val missingIds = HashSet<String>()
        for (entry in dict.entries) {
            val id = entry.value
            if (readable.contains(id)) continue
            missingKeys.add(entry.key)
            missingIds.add(id)
        }
        MissingStore.setResult(missingKeys, missingIds, System.currentTimeMillis())
        // 判定变了：清掉封面失败冷却并让列表重新尝试（之前取不到图的曲目可能还在缓存目录里）
        CoverCache.resetFailures()
        CoverCache.generation.value = CoverCache.generation.value + 1
        MissingState.version.value = MissingState.version.value + 1
        if (missingKeys.isEmpty()) return "检测完成：没有缺失曲目"
        return "检测完成：缺失 " + missingKeys.size + " 首"
    }
}
