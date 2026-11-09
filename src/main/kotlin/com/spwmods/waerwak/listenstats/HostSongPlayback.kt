@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.spwmods.waerwak.listenstats

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import kotlin.Metadata

/**
 * 从统计面板直接播放某首歌（双击曲目行）
 *
 * Workshop 官方 API 只能拿到曲目信息（[WorkshopApi.library.getTrackById]），没有"播放指定曲目"的接口；
 * 这里按 Jethuit 已验证的方式，用反射调用宿主内部的单曲播放链路：
 *
 *   PiscesMediaItem(6 个 String 构造) → PlaybackController.addToPlay → prepare → play
 *
 * 安全约束（沿用项目"结构判据 + 失败拒绝执行"原则）：
 * - 先校验宿主类结构（Metadata data2 字段顺序、构造器、方法签名）；不匹配就报"不支持"，绝不盲试；
 * - 必须在主线程（Swing EDT）执行宿主播放动作；
 * - 文件不存在、曲目 id 不一致、超时都直接拒绝；
 * - 不缓存任何宿主对象。
 */
internal object HostSongPlayback {

    private const val ITEM_CLASS = "com.xuncorp.pisces.PiscesMediaItem"
    private const val CONTROLLER_CLASS = "com.xuncorp.voxzen.service.PlaybackController"

    /** 宿主 PiscesMediaItem 的 6 个 String 构造参数顺序（用 Metadata data2 校验） */
    private val ITEM_PROPERTIES = listOf("id", "title", "artist", "album", "albumArtist", "path")

    private const val LOOKUP_TIMEOUT_SEC = 10L

    @Volatile
    private var busy = false

    internal var loaderOverride: ClassLoader? = null

    /** 双击曲目行调用：异步查询 + 主线程播放，失败弹提示 */
    fun playById(trackId: String) {
        if (trackId.isBlank()) {
            toast("这首歌曲缺少标识，无法播放", false)
            return
        }
        if (busy) return
        busy = true
        val worker = Thread({
            try {
                doPlay(trackId)
            } catch (t: Throwable) {
                toast("播放失败：" + friendly(t), false)
            } finally {
                busy = false
            }
        }, "listen-stats-play")
        worker.isDaemon = true
        worker.start()
    }

    private fun doPlay(trackId: String) {
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
            throw IllegalStateException("未获得修改应用行为权限，无法播放")
        }
        val stage = try {
            WorkshopApi.library.getTrackById(trackId)
        } catch (t: Throwable) {
            throw IllegalStateException("当前播放器的曲库接口不可用")
        }
        val song = try {
            stage.toCompletableFuture().get(LOOKUP_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (t: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("歌曲查询超时，请稍后重试")
        } catch (t: Throwable) {
            throw IllegalStateException("歌曲查询失败")
        }
        if (song == null) throw IllegalStateException("这首歌曲已不在当前曲库中")
        if (song.id != trackId) throw IllegalStateException("歌曲身份不一致，已取消播放")
        if (song.path.isBlank() || !isReadable(song.path)) {
            throw IllegalStateException("歌曲文件不可用，无法播放")
        }
        val action = prepare(song)
        // 宿主播放动作必须在主线程（Swing EDT）执行
        javax.swing.SwingUtilities.invokeLater {
            try {
                action()
            } catch (t: Throwable) {
                toast("播放失败：" + friendly(t), false)
            }
        }
    }

    private fun isReadable(path: String): Boolean {
        return try {
            val f = File(path)
            f.isFile && f.canRead()
        } catch (t: Throwable) {
            false
        }
    }

    private fun friendly(t: Throwable): String {
        if (t is InvocationTargetException && t.targetException != null) {
            return t.targetException.message ?: t.targetException.javaClass.simpleName
        }
        return t.message ?: t.javaClass.simpleName
    }

    /** 反射校验宿主结构；不通过抛 IllegalStateException，不在调用时才失败 */
    internal fun prepare(song: PlaybackExtensionPoint.MediaItem): () -> Unit {
        val loader = loaderOverride ?: WorkshopApi::class.java.classLoader
        val itemType = Class.forName(ITEM_CLASS, false, loader)
        val controllerType = Class.forName(CONTROLLER_CLASS, false, loader)

        // 1) 构造参数顺序必须与 Metadata data2 完全一致（宿主版本/混淆变化就拒绝）。
        //    data2 里可能还含 duration/year 等其它字段，按 Jethuit 的做法先过滤出这 6 个再比顺序。
        val declared = itemType.getAnnotation(Metadata::class.java)?.data2
        val filtered = ArrayList<String>(ITEM_PROPERTIES.size)
        if (declared != null) {
            var di = 0
            while (di < declared.size) {
                if (ITEM_PROPERTIES.contains(declared[di])) filtered.add(declared[di])
                di++
            }
        }
        if (filtered != ITEM_PROPERTIES) {
            throw IllegalStateException("当前播放器的歌曲结构不受支持（构造参数顺序不符）")
        }
        val constructor = itemType.getConstructor(*Array(6) { String::class.java })

        // 2) 播放控制器结构
        val instanceField = controllerType.getField("INSTANCE")
        val addToPlay = controllerType.getMethod("addToPlay", itemType)
        val prepareMethod = controllerType.getMethod("prepare")
        val playMethod = controllerType.getMethod("play")
        if (!Modifier.isStatic(instanceField.modifiers) || instanceField.type != controllerType) {
            throw IllegalStateException("当前播放器的播放接口不受支持")
        }
        val methods = listOf(addToPlay, prepareMethod, playMethod)
        var mi = 0
        while (mi < methods.size) {
            val m = methods[mi]
            if (Modifier.isStatic(m.modifiers) || m.returnType != Void.TYPE) {
                throw IllegalStateException("当前播放器的播放接口不受支持")
            }
            mi++
        }

        return {
            try {
                val item = constructor.newInstance(
                    song.id, song.title, song.artist, song.album, song.albumArtist, song.path
                )
                val controller = instanceField.get(null)
                // 与宿主原生"单曲播放"一致：addToPlay 只更新队列，prepare+play 才真正出声
                addToPlay.invoke(controller, item)
                prepareMethod.invoke(controller)
                playMethod.invoke(controller)
            } catch (t: InvocationTargetException) {
                throw t.targetException ?: t
            }
        }
    }
}
