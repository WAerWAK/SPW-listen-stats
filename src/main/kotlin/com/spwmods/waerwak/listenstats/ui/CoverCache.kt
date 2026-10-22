package com.spwmods.waerwak.listenstats.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.spwmods.waerwak.listenstats.stats.MissingStore
import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 曲目封面缓存
 *
 * 宿主提供 `WorkshopApi.library.getCoverById(id)`（返回封面图片字节，需 library-read 权限）。
 *
 * 四级读取顺序：
 * 1. **内存**：LRU，按字节限容（12 MB），命中即返回
 * 2. **磁盘**：把解码好的 128px 缩略图存成 PNG（8 MB 上限，按访问时间淘汰）。
 *    宿主 `getCoverById` 每次都要重读并解码原图（可能 1000×1000），而 128px PNG
 *    只有 ~25 KB，读盘解码是 1–3 ms 级别——这是第二次打开面板变快的根本原因。
 * 3. **宿主**：同 id 请求合并 + 6 路并发限流
 * 4. **失败冷却**：5 秒内不重试；面板每次打开时清空冷却，让失败的封面重新尝试
 *
 * 加载必须在 LaunchedEffect 里：组合期启动的异步回调写状态容易被本轮组合覆盖，
 * 表现为「首次打开不显示、切页后才出现」。
 */
object CoverCache {

    /** 失败后多久允许重试（毫秒）。短冷却 + 面板重开时清空，避免"一直加载不出来" */
    private const val RETRY_AFTER_MS = 5_000L

    /** 内存缓存上限（字节）。128×128×4 ≈ 64 KB/张，12 MB ≈ 180 张 */
    private const val MAX_BYTES = 12L * 1024L * 1024L

    /** 磁盘缓存上限（字节）。128px PNG 约 25 KB/张，8 MB ≈ 300+ 张 */
    private const val DISK_MAX_BYTES = 8L * 1024L * 1024L

    /** 缩略图边长（列表里最大显示 36dp，128px 足够清晰） */
    private const val THUMB_SIZE = 128

    /** 单张缩略图的理论字节数（用于容量统计） */
    private const val THUMB_BYTES = THUMB_SIZE.toLong() * THUMB_SIZE.toLong() * 4L

    /** 同时进行中的解码/请求数 */
    private const val MAX_PARALLEL = 8

    /**
     * 内存缓存
     *
     * 用 `mutableStateMapOf`：读取 `bitmaps[id]` 就是一次快照读，只有**这一首**
     * 封面写入才会让对应行重组。同一首歌可能在「最近播放」和「最常听」两处
     * 同时渲染，一处加载成功另一处也立即跟着显示，不用等切页重建。
     */
    private val bitmaps = androidx.compose.runtime.mutableStateMapOf<String, ImageBitmap>()

    /** 只看 LRU 顺序（accessOrder = true，迭代顺序就是最久未使用在前） */
    private val order = LinkedHashMap<String, Boolean>(64, 0.75f, true)

    /** 按条数限容（128×128×4 ≈ 64 KB/张，12 MB ≈ 192 张） */
    private val maxItems = (MAX_BYTES / THUMB_BYTES).toInt()

    private val failedAt = HashMap<String, Long>()

    /** 正在进行的请求：同 id 的后来者直接等这个结果 */
    private val inflight = HashMap<String, CompletableFuture<ImageBitmap?>>()

    private val gate = Semaphore(MAX_PARALLEL)

    /** 每次面板打开 +1，让失败的封面重新尝试（成功的走内存/磁盘，瞬间命中） */
    val generation = mutableStateOf(0)

    /** 诊断计数 */
    private var failures = 0L

    @Synchronized
    fun cached(id: String): ImageBitmap? {
        if (id.isBlank()) return null
        val hit = bitmaps[id] ?: return null
        // 刷新 LRU 顺序：order 是 accessOrder=true 的 LinkedHashMap，重新 put 会移到队尾
        order[id] = true
        return hit
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

    /** 面板打开时调用：清空失败冷却并触发重新加载 */
    @Synchronized
    fun resetFailures() {
        failedAt.clear()
        generation.value = generation.value + 1
    }

    /** 诊断文本缓存：信息页每 1.5 秒重组一次，不能每次都去 listFiles */
    private var statsCachedAt = 0L
    private var statsCache = ""

    /** 信息页诊断文本 */
    @Synchronized
    fun statsText(): String {
        val now = System.currentTimeMillis()
        if (statsCache.isNotEmpty() && now - statsCachedAt < 10_000L) return statsCache
        val diskCount = try {
            diskDir()?.listFiles()?.size ?: 0
        } catch (t: Throwable) {
            0
        }
        val text = "内存 " + bitmaps.size.toString() + " 张 / 磁盘 " + diskCount.toString() +
            " 张 / 失败 " + failures.toString()
        statsCache = text
        statsCachedAt = now
        return text
    }

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

        // 磁盘缓存：比再问一次宿主快一个量级
        val fromDisk = readDisk(id)
        if (fromDisk != null) {
            put(id, fromDisk)
            return fromDisk
        }

        // 已判定缺失的曲目文件都不在了，宿主取图必然失败：别再白等 4 秒超时，
        // 也别把「取不到」写进失败冷却（已缓存的封面照旧显示，所以放在磁盘之后）
        if (MissingStore.isMissingId(id)) return null

        val pair = obtainFuture(id)
        val target = pair.first
        val owner = pair.second

        if (owner) {
            var acquired = false
            try {
                acquired = gate.tryAcquire(3, TimeUnit.SECONDS)
                if (!acquired) {
                    markFailed(id)
                    target.complete(null)
                } else {
                    val bitmap = fetchAndDecode(id)
                    if (bitmap != null) {
                        put(id, bitmap)
                        writeDisk(id, bitmap)
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
        order[id] = true
        bitmaps[id] = bitmap
        failedAt.remove(id)
        val iterator = order.keys.iterator()
        while (iterator.hasNext() && order.size > maxItems) {
            val victim = iterator.next()
            iterator.remove()
            bitmaps.remove(victim)
        }
    }

    @Synchronized
    private fun markFailed(id: String) {
        failedAt[id] = System.currentTimeMillis()
        failures++
    }

    // ------------------------------------------------------------ 磁盘缓存

    private fun diskDir(): File? {
        val base = StatsStore.dataDirOrNull() ?: return null
        val dir = File(base, "covers")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return dir
    }

    private fun diskFile(id: String): File? {
        val dir = diskDir() ?: return null
        return File(dir, digest(id) + ".png")
    }

    private fun digest(text: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v < 16) sb.append('0')
                sb.append(Integer.toHexString(v))
            }
            sb.toString()
        } catch (t: Throwable) {
            Integer.toHexString(text.hashCode())
        }
    }

    private fun readDisk(id: String): ImageBitmap? {
        val file = diskFile(id) ?: return null
        if (!file.isFile) return null
        var image: org.jetbrains.skia.Image? = null
        try {
            image = org.jetbrains.skia.Image.makeFromEncoded(file.readBytes())
            val bitmap = image.toComposeImageBitmap()
            // 刷新访问时间，供容量淘汰时优先保留常用封面
            file.setLastModified(System.currentTimeMillis())
            return bitmap
        } catch (t: Throwable) {
            try {
                file.delete()
            } catch (e: Throwable) {
                // 删不掉也无所谓
            }
            return null
        } finally {
            // Skia 的 Image 是本地资源，必须显式释放（与 decodeThumbnail 保持一致）
            try {
                image?.close()
            } catch (t: Throwable) {
                // 忽略
            }
        }
    }

    private fun writeDisk(id: String, bitmap: ImageBitmap) {
        val file = diskFile(id) ?: return
        try {
            val image = org.jetbrains.skia.Image.makeFromBitmap(bitmap.asSkiaBitmap())
            val data = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG) ?: return
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(data.bytes)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            trimDisk(file.parentFile)
        } catch (t: Throwable) {
            // 磁盘缓存失败不影响本次显示
        }
    }

    /** 超出上限时按最后访问时间删最旧的 */
    private fun trimDisk(dir: File?) {
        if (dir == null) return
        val files = dir.listFiles() ?: return
        val total = sumBytes(files)
        if (total <= DISK_MAX_BYTES) return
        java.util.Arrays.sort(files, Comparator<File> { a, b ->
            a.lastModified().compareTo(b.lastModified())
        })
        var rest = total
        var j = 0
        while (j < files.size && rest > DISK_MAX_BYTES) {
            val len = files[j].length()
            if (files[j].delete()) rest -= len
            j++
        }
    }

    private fun sumBytes(files: Array<File>): Long {
        var total = 0L
        var i = 0
        while (i < files.size) {
            total += files[i].length()
            i++
        }
        return total
    }

    /**
     * 解码并缩放到 128×128 的缩略图
     *
     * 两点关键（对齐 Jethuit）：
     * 1. **保持宽高比 + 居中裁剪**（cover）：直接拉伸会压扁横/纵向的非正方形封面。
     * 2. **释放 Skia 资源**：Image / Surface 都是本地对象，不 close 会泄漏。
     */
    private fun decodeThumbnail(bytes: ByteArray): ImageBitmap? {
        var source: org.jetbrains.skia.Image? = null
        var surface: org.jetbrains.skia.Surface? = null
        try {
            val img = org.jetbrains.skia.Image.makeFromEncoded(bytes)
            source = img
            if (img.width <= 0 || img.height <= 0) return null
            val raster = org.jetbrains.skia.Surface.makeRasterN32Premul(THUMB_SIZE, THUMB_SIZE)
            surface = raster
            // cover：按较大比例缩放，保证填满整个缩略图，超出部分居中裁掉
            val scale = maxOf(
                THUMB_SIZE.toFloat() / img.width.toFloat(),
                THUMB_SIZE.toFloat() / img.height.toFloat()
            )
            val drawW = img.width * scale
            val drawH = img.height * scale
            raster.canvas.clear(0)
            raster.canvas.drawImageRect(
                img,
                org.jetbrains.skia.Rect.makeWH(img.width.toFloat(), img.height.toFloat()),
                org.jetbrains.skia.Rect.makeXYWH(
                    (THUMB_SIZE - drawW) / 2f,
                    (THUMB_SIZE - drawH) / 2f,
                    drawW,
                    drawH
                ),
                org.jetbrains.skia.SamplingMode.LINEAR,
                null,
                true
            )
            return raster.makeImageSnapshot().toComposeImageBitmap()
        } catch (t: Throwable) {
            // 缩放失败时退回原图解码
            return try {
                bytes.decodeToImageBitmap()
            } catch (e: Throwable) {
                null
            }
        } finally {
            try {
                surface?.close()
            } catch (t: Throwable) {
                // 忽略
            }
            try {
                source?.close()
            } catch (t: Throwable) {
                // 忽略
            }
        }
    }
}

/**
 * 在 Composable 中取封面
 *
 * 直接读缓存（订阅 [CoverCache.generation]），所以同一首歌在多处渲染时，
 * 任何一处加载成功都会让其他处立即显示。
 *
 * [CoverCache.generation] 变化（面板重新打开）时会让之前失败的封面再试一次。
 */
@Composable
fun rememberCover(id: String): ImageBitmap? {
    val gen = CoverCache.generation.value
    // 直接读快照映射：只有本 id 的封面写入才会让本行重组
    val hit = CoverCache.cached(id)
    LaunchedEffect(id, gen) {
        if (id.isBlank()) return@LaunchedEffect
        // 失败后自动重试（最多 3 轮，每轮间隔 6 秒），
        // 避免“宿主一时忙 → 这张封面永远不出现，除非切页/重开面板”
        var attempt = 0
        while (attempt < 3) {
            if (CoverCache.cached(id) != null) return@LaunchedEffect
            val loaded = withContext(Dispatchers.IO) { CoverCache.loadBlocking(id) }
            if (loaded != null) return@LaunchedEffect
            attempt++
            if (attempt < 3) delay(6_000L)
        }
    }
    return hit
}
