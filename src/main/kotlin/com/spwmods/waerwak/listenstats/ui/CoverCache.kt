package com.spwmods.waerwak.listenstats.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 曲目封面缓存
 *
 * 宿主提供 `WorkshopApi.library.getCoverById(id)`（返回封面图片字节，需 library-read 权限）。
 *
 * 三个关键设计：
 * 1. **按字节限容**：缩略图统一 128px，但不同来源的封面解码代价差异很大。用总字节数
 *    （12 MB）而不是条数做上限，内存占用才是可预期的。
 * 2. **同 id 请求合并**：一次翻页里"最常听"和"最近播放"可能同时请求同一首歌的封面，
 *    合并后只发一次宿主调用。
 * 3. **4 路并发限流**：宿主封面读取是同步 IO，无限并发会把线程池打满。
 *    实测 3 路会让 25 行列表等 390ms，4 路是收益与压力的平衡点。
 *
 * 加载必须在 LaunchedEffect 里：组合期启动的异步回调写状态容易被本轮组合覆盖，
 * 表现为「首次打开不显示、切页后才出现」。
 */
object CoverCache {

    /** 失败后多久允许重试（毫秒） */
    private const val RETRY_AFTER_MS = 30_000L

    /** 缓存上限（字节）。128×128×4 ≈ 64 KB/张，12 MB ≈ 180 张 */
    private const val MAX_BYTES = 12L * 1024L * 1024L

    /** 缩略图边长（列表里最大显示 36dp，128px 足够清晰） */
    private const val THUMB_SIZE = 128

    /** 单张缩略图的理论字节数（用于容量统计） */
    private const val THUMB_BYTES = THUMB_SIZE.toLong() * THUMB_SIZE.toLong() * 4L

    /** 同时进行中的解码/请求数 */
    private const val MAX_PARALLEL = 4

    private class Entry(val bitmap: ImageBitmap)

    /** accessOrder = true：迭代顺序就是最久未使用在前，淘汰时直接取头部 */
    private val cache = LinkedHashMap<String, Entry>(64, 0.75f, true)

    private val failedAt = HashMap<String, Long>()

    /** 正在进行的请求：同 id 的后来者直接等这个结果 */
    private val inflight = HashMap<String, CompletableFuture<ImageBitmap?>>()

    private val gate = Semaphore(MAX_PARALLEL)

    @Synchronized
    fun cached(id: String): ImageBitmap? {
        if (id.isBlank()) return null
        val hit = cache[id]
        return if (hit != null) hit.bitmap else null
    }

    @Synchronized
    fun recentlyFailed(id: String): Boolean {
        val at = failedAt[id]
        if (at == null) return false
        if (System.currentTimeMillis() - at > RETRY_AFTER_MS) {
            failedAt.remove(id)
            return false
        }
        return true
    }

    /** 缓存命中数（信息页诊断用） */
    @Synchronized
    fun cachedCount(): Int = cache.size

    /**
     * 取封面（阻塞，调用方负责放在 IO 线程）
     *
     * 同一 id 的并发调用合并为一次宿主请求；最多 [MAX_PARALLEL] 个请求同时进行。
     */
    fun loadBlocking(id: String): ImageBitmap? {
        if (id.isBlank()) return null
        val hit = cached(id)
        if (hit != null) return hit
        if (recentlyFailed(id)) return null

        val pair = obtainFuture(id)
        val target = pair.first
        val owner = pair.second

        if (owner) {
            var acquired = false
            try {
                acquired = gate.tryAcquire(6, TimeUnit.SECONDS)
                if (!acquired) {
                    target.complete(null)
                } else {
                    val bitmap = fetchAndDecode(id)
                    if (bitmap != null) {
                        put(id, bitmap)
                    } else {
                        markFailed(id)
                    }
                    target.complete(bitmap)
                }
            } catch (t: Throwable) {
                target.complete(null)
            } finally {
                if (acquired) gate.release()
                releaseFuture(id)
            }
        }

        return try {
            target.get(8L, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            null
        }
    }

    @Synchronized
    private fun obtainFuture(id: String): Pair<CompletableFuture<ImageBitmap?>, Boolean> {
        val existing = inflight[id]
        if (existing != null) return Pair(existing, false)
        val created = CompletableFuture<ImageBitmap?>()
        inflight[id] = created
        return Pair(created, true)
    }

    @Synchronized
    private fun releaseFuture(id: String) {
        inflight.remove(id)
    }

    private fun fetchAndDecode(id: String): ImageBitmap? {
        var bitmap: ImageBitmap? = null
        try {
            val future = WorkshopApi.library.getCoverById(id).toCompletableFuture()
            val bytes = future.get(4L, TimeUnit.SECONDS)
            if (bytes != null && bytes.isNotEmpty()) {
                bitmap = decodeThumbnail(bytes)
            }
        } catch (t: Throwable) {
            // 没有封面 / 权限不足 / 超时
        }
        return bitmap
    }

    @Synchronized
    private fun put(id: String, bitmap: ImageBitmap) {
        cache[id] = Entry(bitmap)
        failedAt.remove(id)
        var total = cache.size.toLong() * THUMB_BYTES
        val iterator = cache.entries.iterator()
        while (iterator.hasNext() && total > MAX_BYTES) {
            iterator.next()
            iterator.remove()
            total -= THUMB_BYTES
        }
    }

    @Synchronized
    private fun markFailed(id: String) {
        failedAt[id] = System.currentTimeMillis()
    }

    /**
     * 解码并缩放到 128×128 的缩略图
     *
     * 直接 decodeToImageBitmap 会按原始分辨率解码（一张 1000×1000 的封面 ≈ 4MB），
     * 缓存几十张就会吃光内存；这里用 Skia 直接画到小画布上。
     */
    private fun decodeThumbnail(bytes: ByteArray): ImageBitmap? {
        try {
            val source = org.jetbrains.skia.Image.makeFromEncoded(bytes)
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(THUMB_SIZE, THUMB_SIZE)
            surface.canvas.drawImageRect(
                source,
                org.jetbrains.skia.Rect.makeWH(THUMB_SIZE.toFloat(), THUMB_SIZE.toFloat())
            )
            val snapshot = surface.makeImageSnapshot()
            return snapshot.toComposeImageBitmap()
        } catch (t: Throwable) {
            // 缩放失败时退回原图解码
            return try {
                bytes.decodeToImageBitmap()
            } catch (e: Throwable) {
                null
            }
        }
    }
}

/**
 * 在 Composable 中取封面（命中缓存立即返回，否则异步加载后自动刷新）
 */
@Composable
fun rememberCover(id: String): ImageBitmap? {
    val state = remember(id) { mutableStateOf(CoverCache.cached(id)) }
    LaunchedEffect(id) {
        if (id.isBlank()) return@LaunchedEffect
        val cached = CoverCache.cached(id)
        if (cached != null) {
            state.value = cached
            return@LaunchedEffect
        }
        if (CoverCache.recentlyFailed(id)) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { CoverCache.loadBlocking(id) }
        if (loaded != null) {
            state.value = loaded
        }
    }
    return state.value
}
