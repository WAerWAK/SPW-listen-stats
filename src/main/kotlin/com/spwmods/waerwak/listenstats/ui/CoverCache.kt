@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.spwmods.waerwak.listenstats.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 曲目封面缓存
 *
 * 宿主提供 `WorkshopApi.library.getCoverById(id)`（返回封面图片字节，需 library-read 权限）。
 *
 * 注意两点：
 * 1. **必须限容**：解码后是原始分辨率的位图（一张 1000×1000 的封面约 4MB），
 *    无上限缓存会在长时间使用后吃掉几百 MB 内存。
 * 2. **加载必须在 LaunchedEffect 里**：组合期启动的异步回调写状态容易被本轮组合覆盖，
 *    表现为「首次打开不显示、切页后才出现」。
 */
object CoverCache {

    /** 失败后多久允许重试（毫秒） */
    private const val RETRY_AFTER_MS = 30_000L

    /** 最多缓存多少张封面（LRU，按最近使用淘汰） */
    private const val MAX_CACHED = 48

    /** 缩略图边长（列表里最大也只显示 36dp，128px 足够清晰） */
    private const val THUMB_SIZE = 128

    private val lockKey = Any()

    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MAX_CACHED
        }
    }

    private val failedAt = HashMap<String, Long>()

    @Synchronized
    fun cached(id: String): ImageBitmap? {
        if (id.isBlank()) return null
        return cache[id]
    }

    @Synchronized
    fun recentlyFailed(id: String): Boolean {
        val at = failedAt[id] ?: return false
        if (System.currentTimeMillis() - at > RETRY_AFTER_MS) {
            failedAt.remove(id)
            return false
        }
        return true
    }

    @Synchronized
    private fun put(id: String, bitmap: ImageBitmap) {
        cache[id] = bitmap
        failedAt.remove(id)
    }

    @Synchronized
    private fun markFailed(id: String) {
        failedAt[id] = System.currentTimeMillis()
    }

    /** 同步加载（调用方负责放到 IO 线程） */
    fun loadBlocking(id: String): ImageBitmap? {
        if (id.isBlank()) return null
        val hit = cached(id)
        if (hit != null) return hit
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
        if (bitmap != null) {
            put(id, bitmap)
        } else {
            markFailed(id)
        }
        return bitmap
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

    // lockKey 仅用于表达「这里需要互斥」的语义，实际互斥由 @Synchronized 提供
    @Suppress("unused")
    private fun describeLock(): Any = lockKey
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
