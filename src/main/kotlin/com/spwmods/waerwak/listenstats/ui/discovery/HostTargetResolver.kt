package com.spwmods.waerwak.listenstats.ui.discovery

import com.spwmods.waerwak.listenstats.stats.StatsStore
import com.spwmods.waerwak.listenstats.ui.HostUiTargets
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** 解析结果：目标 + 来源（cache / scan / table），便于诊断 */
data class ResolvedHostTargets(
    val targets: HostUiTargets,
    val layer: HostLayerSite?,
    val source: String,
    val detail: String
)

/**
 * 注入目标解析器：缓存 → 结构化发现 → 版本表回退
 *
 * 缓存 key = 宿主 jar 的绝对路径 + SHA-256 + 插件版本，宿主一升级就自动失效重扫。
 * 缓存只存"描述字符串"（类名/方法名/描述符），不反序列化任何可执行对象。
 */
object HostTargetResolver {

    private const val CACHE_FILE = "host-targets.txt"

    /**
     * 缓存格式版本
     *
     * 2 → 3：标题栏成为可选组（发现失败时写 `caption.*=none`），旧缓存一律失效重扫。
     */
    private const val FORMAT = 3

    private var memory: ResolvedHostTargets? = null
    private var memoryVersion: String = ""

    @Synchronized
    fun resolve(pluginVersion: String, hostVersion: String, cacheDir: File?): ResolvedHostTargets? {
        val cached = memory
        if (cached != null && memoryVersion == pluginVersion) return cached
        val result = compute(pluginVersion, hostVersion, cacheDir)
        if (result != null) {
            memory = result
            memoryVersion = pluginVersion
        }
        return result
    }

    /** 清空内存缓存（测试/重新启用插件时用） */
    @Synchronized
    fun forget() {
        memory = null
        memoryVersion = ""
    }

    private fun compute(pluginVersion: String, hostVersion: String, cacheDir: File?): ResolvedHostTargets? {
        var source = "table"
        var detail = ""
        var discovered: DiscoveredUiTargets? = null
        var identitySha: String? = null
        var identityPath: String? = null
        try {
            val sourcePath = HostBytecodeIndex.source()
            identityPath = sourcePath.toRealPath().toString()
            identitySha = sha256(sourcePath)
            val fromCache = readCache(cacheDir, pluginVersion, identityPath, identitySha)
            if (fromCache != null) {
                return ResolvedHostTargets(fromCache.targets, fromCache.layer, "cache", identityPath)
            }
            val index = HostBytecodeIndex.read(sourcePath)
            discovered = HostUiDiscovery.discover(index)
            source = "scan"
            detail = "classes=" + index.methods.size + " path=" + identityPath
            writeCache(cacheDir, pluginVersion, identityPath, identitySha, discovered)
            return ResolvedHostTargets(discovered.targets, discovered.layer, source, detail)
        } catch (t: Throwable) {
            detail = t.javaClass.simpleName + ": " + (t.message ?: "")
        }
        // 结构化发现不可用（ASM 不可见 / 宿主结构不认识）：退回按版本登记的已知目标
        val fallback = HostUiTargets.forVersion(hostVersion)
        if (fallback == null) return null
        return ResolvedHostTargets(fallback, null, "table", detail)
    }

    // ------------------------------------------------------------ 缓存

    private fun cacheFile(cacheDir: File?): File? {
        if (cacheDir == null) return null
        if (!cacheDir.isDirectory) cacheDir.mkdirs()
        if (!cacheDir.isDirectory) return null
        return File(cacheDir, CACHE_FILE)
    }

    /** internal 仅供回归测试构造/校验缓存文本（不对外暴露） */
    internal fun readCache(
        dir: File?,
        pluginVersion: String,
        path: String,
        sha: String?
    ): DiscoveredUiTargets? {
        val file = cacheFile(dir) ?: return null
        if (!file.isFile) return null
        if (file.length() > 64 * 1024L) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            return null
        }
        val map = HashMap<String, String>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            var line = lines[i].trim()
            i++
            if (line.isEmpty()) continue
            var eq = line.indexOf('=')
            if (eq <= 0) continue
            map[line.substring(0, eq)] = line.substring(eq + 1)
        }
        if (map["format"] != FORMAT.toString()) return null
        if (map["plugin"] != pluginVersion) return null
        if (map["path"] != path) return null
        if (sha != null && map["sha256"] != sha) return null
        val page = parseMethod(map["page"]) ?: return null
        // 标题栏为可选组：缓存里可能是 none（上一轮发现失败时的降级结果）
        val titlebarAvailable = map["caption.entry"] != null && map["caption.entry"] != "none"
        var entry: SiteRef? = null
        var control: SiteRef? = null
        if (titlebarAvailable) {
            entry = parseMethod(map["caption.entry"]) ?: return null
            control = parseMethod(map["caption.control"]) ?: return null
        }
        var layer: HostLayerSite? = null
        val layerText = map["layer"]
        if (layerText != null && layerText != "none") {
            val parts = layerText.split('|')
            // 写入的是 4 段（owner|name|descriptor|caller）；1.5.1 读时要求 5 段，
            // 导致缓存命中时必然丢掉 layer/content（又回到旧点位）。1.5.1.1 修正。
            if (parts.size == 4) {
                layer = HostLayerSite(HostMethod(parts[0], parts[1], parts[2]), parts[3])
            }
        }
        var content: HostLayerSite? = null
        val contentText = map["content"]
        if (contentText != null && contentText != "none") {
            val parts = contentText.split('|')
            if (parts.size == 4) {
                content = HostLayerSite(HostMethod(parts[0], parts[1], parts[2]), parts[3])
            }
        }
        val warnings = ArrayList<String>(2)
        if (!titlebarAvailable) {
            warnings.add("titlebar: 缓存记录为不可用（上次发现失败）")
        }
        if (content == null) {
            warnings.add("material: 找不到内层材质点，背景将回退为不透明")
        }
        val caption = entry
        val captionControl = control
        val targets = HostUiTargets(
            pageCaller = page.caller.replace('/', '.'),
            titlebarCaller = if (titlebarAvailable) caption!!.caller.replace('/', '.') else "",
            debugCaption = if (titlebarAvailable) caption!!.method.dottedOwner() else "",
            reuseHostCaption = titlebarAvailable,
            captionClass = if (titlebarAvailable) captionControl!!.method.dottedOwner() else "",
            debugCaptionMethod = if (titlebarAvailable) caption!!.method.name else "",
            captionMethodName = if (titlebarAvailable) captionControl!!.method.name else "",
            contentOwner = if (content == null) "" else content.target.owner.replace('/', '.'),
            contentMethod = if (content == null) "" else content.target.name,
            contentDescriptor = if (content == null) "" else content.target.descriptor,
            contentCaller = if (content == null) "" else content.caller.replace('/', '.'),
            titlebarAvailable = titlebarAvailable,
            warnings = warnings
        )
        return DiscoveredUiTargets(targets, layer, content)
    }

    private class SiteRef(val method: HostMethod, val caller: String)

    /**
     * 缓存行解析
     *
     * 4 段：`owner|name|descriptor|caller`（调用点）；3 段：`owner|name|descriptor`
     * （caption.control 只描述方法本身，没有调用点）。
     * 1.5.1 只支持 4 段，而 caption.control 写的是 3 段，导致**缓存永远不会命中**、
     * 每次启动都重扫宿主（约 1.5 s）。1.5.1.1 修正。
     */
    private fun parseMethod(value: String?): SiteRef? {
        if (value == null) return null
        val parts = value.split('|')
        if (parts.size == 4) return SiteRef(HostMethod(parts[0], parts[1], parts[2]), parts[3])
        if (parts.size == 3) return SiteRef(HostMethod(parts[0], parts[1], parts[2]), "")
        return null
    }

    private fun writeCache(
        dir: File?,
        pluginVersion: String,
        path: String,
        sha: String?,
        discovered: DiscoveredUiTargets
    ) {
        val file = cacheFile(dir) ?: return
        val t = discovered.targets
        val sb = StringBuilder(512)
        sb.append("format=").append(FORMAT).append('\n')
        sb.append("plugin=").append(pluginVersion).append('\n')
        sb.append("path=").append(path).append('\n')
        sb.append("sha256=").append(sha ?: "-").append('\n')
        sb.append("page=").append("com/xuncorp/voxzen/ui/navigation/AppNavigationKt").append('|')
            .append("AppNavigation").append('|')
            .append("(Landroidx/compose/runtime/Composer;I)V").append('|')
            .append(t.pageCaller.replace('.', '/')).append('\n')
        // 标题栏为可选组：不可用时写 none，避免下次启动重复扫描
        if (t.titlebarAvailable && t.debugCaption.isNotEmpty() && t.titlebarCaller.isNotEmpty()) {
            sb.append("caption.entry=").append(t.debugCaption.replace('.', '/')).append('|')
                .append(t.debugCaptionMethod).append('|')
                .append("(Landroidx/compose/runtime/Composer;I)V").append('|')
                .append(t.titlebarCaller.replace('.', '/')).append('\n')
            sb.append("caption.control=").append(t.captionClass.replace('.', '/')).append('|')
                .append(t.captionMethodName).append('|')
                .append("(Lkotlin/jvm/functions/Function0;Ljava/lang/String;Landroidx/compose/ui/Modifier;ZLjava/lang/String;F" +
                    "Lkotlin/jvm/functions/Function3;Landroidx/compose/runtime/Composer;II)V").append('\n')
        } else {
            sb.append("caption.entry=none\n")
            sb.append("caption.control=none\n")
        }
        val layer = discovered.layer
        if (layer == null) {
            sb.append("layer=none\n")
        } else {
            sb.append("layer=").append(layer.target.owner).append('|').append(layer.target.name).append('|')
                .append(layer.target.descriptor).append('|').append(layer.caller).append('\n')
        }
        val content = discovered.content
        if (content == null) {
            sb.append("content=none\n")
        } else {
            sb.append("content=").append(content.target.owner).append('|').append(content.target.name).append('|')
                .append(content.target.descriptor).append('|').append(content.caller).append('\n')
        }
        val tmp = File(file.parentFile, CACHE_FILE + ".tmp")
        try {
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (t: Throwable) {
                file.writeText(sb.toString(), Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            // 缓存写不进去不影响本次注入，下次重新扫描即可
            tmp.delete()
        }
    }

    // ------------------------------------------------------------ 工具

    private fun sha256(path: java.nio.file.Path): String? {
        if (Files.isDirectory(path)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val input = Files.newInputStream(path)
        try {
            val buffer = ByteArray(256 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) return null
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } finally {
            input.close()
        }
        val bytes = digest.digest()
        val sb = StringBuilder(bytes.size * 2)
        var i = 0
        while (i < bytes.size) {
            var v = bytes[i].toInt() and 0xff
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
            i++
        }
        return sb.toString()
    }
}
