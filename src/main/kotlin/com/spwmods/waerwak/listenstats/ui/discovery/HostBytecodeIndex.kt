package com.spwmods.waerwak.listenstats.ui.discovery

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * 只描述宿主方法/字段，不持有 Class / Method / 类加载器
 *
 * 发现过程必须**只读字节码**：初始化宿主类会触发播放器启动流程。
 */
data class HostMethod(val owner: String, val name: String, val descriptor: String) {
    /** 供 hook API 使用的点分类名 */
    fun dottedOwner(): String {
        return owner.replace('/', '.')
    }

    fun parameterTypes(): List<String> {
        var list = ArrayList<String>()
        var types = Type.getArgumentTypes(descriptor)
        var i = 0
        while (i < types.size) {
            list.add(types[i].className)
            i++
        }
        return list
    }

    /** 返回类型描述符（如 V / Lfoo/Bar;） */
    fun returnTypeDescriptor(): String {
        return Type.getReturnType(descriptor).descriptor
    }
}

data class HostField(val owner: String, val name: String, val descriptor: String)

class HostMethodBody(
    val method: HostMethod,
    val access: Int,
    val calls: ArrayList<HostMethod>,
    val fields: ArrayList<HostField>
) {
    fun isStatic(): Boolean = access and Opcodes.ACC_STATIC != 0
    fun isPublic(): Boolean = access and Opcodes.ACC_PUBLIC != 0
}

class HostBytecodeIndex(
    val methods: ArrayList<HostMethodBody>,
    val fields: ArrayList<HostField>
) {
    private val bodies = HashMap<HostMethod, HostMethodBody>()

    init {
        var i = 0
        while (i < methods.size) {
            bodies[methods[i].method] = methods[i]
            i++
        }
    }

    fun body(method: HostMethod): HostMethodBody? = bodies[method]

    companion object {
        /** 只索引 Compose 相关的类：宿主里 4 万多个 class，全量解析太慢也无必要 */
        private val MARKER = "androidx/compose/runtime/".toByteArray(Charsets.UTF_8)

        /**
         * 定位宿主字节码（jar 或 class 目录）
         *
         * 优先取宿主 API 类的 CodeSource；某些启动方式下 CodeSource 为空或不是文件，
         * 就退回"类资源 URL"（jar:file:/... 或 file:/...）反推根路径。
         */
        fun source(): Path {
            val host = com.xuncorp.spw.workshop.api.PlaybackExtensionPoint::class.java
            val location = host.protectionDomain?.codeSource?.location
            if (location != null && location.protocol == "file") {
                return Path.of(location.toURI())
            }
            val name = host.name.replace('.', '/') + ".class"
            val resource = host.getResource("/" + name) ?: error("找不到宿主字节码资源：" + name)
            if (resource.protocol == "jar") {
                val connection = resource.openConnection() as JarURLConnection
                // 只看 URL，不打开会缓存/占用文件的 JarFile（宿主升级时会被锁）
                connection.useCaches = false
                val archive = connection.jarFileURL
                if (archive.protocol == "file") {
                    return Path.of(archive.toURI())
                }
            }
            if (resource.protocol == "file") {
                var root = Path.of(resource.toURI())
                var depth = 0
                var i = 0
                while (i < name.length) {
                    if (name[i] == '/') depth++
                    i++
                }
                var up = 0
                while (up < depth) {
                    var parent = root.parent
                    checkNotNull(parent) { "宿主 class 资源没有 classpath 根：$resource" }
                    root = parent
                    up++
                }
                return root
            }
            error("无法定位宿主字节码：" + host.name + "（source=" + (location?.protocol ?: "none") + "，resource=" + resource.protocol + "）")
        }

        fun read(source: Path): HostBytecodeIndex {
            var methods = ArrayList<HostMethodBody>()
            var fields = ArrayList<HostField>()
            if (Files.isDirectory(source)) {
                var stream = Files.walk(source)
                try {
                    var iterator = stream.iterator()
                    while (iterator.hasNext()) {
                        var path = iterator.next()
                        var text = path.toString()
                        if (!text.endsWith(".class")) continue
                        if (Thread.currentThread().isInterrupted) error("宿主界面识别被取消")
                        var bytes = Files.readAllBytes(path)
                        parse(bytes, methods, fields)
                    }
                } finally {
                    stream.close()
                }
            } else {
                var jar = JarFile(source.toFile())
                try {
                    var entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        var entryName = entry.name
                        if (!entryName.endsWith(".class")) continue
                        if (entryName.startsWith("META-INF/versions/")) continue
                        if (Thread.currentThread().isInterrupted) error("宿主界面识别被取消")
                        var input = jar.getInputStream(entry)
                        var bytes: ByteArray
                        try {
                            bytes = input.readBytes()
                        } finally {
                            input.close()
                        }
                        parse(bytes, methods, fields)
                    }
                } finally {
                    jar.close()
                }
            }
            return HostBytecodeIndex(methods, fields)
        }

        private fun parse(bytes: ByteArray, methods: ArrayList<HostMethodBody>, fields: ArrayList<HostField>) {
            if (!contains(bytes, MARKER)) return
            var reader = ClassReader(bytes)
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                private var owner: String = ""

                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?
                ) {
                    owner = name
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?
                ): FieldVisitor? {
                    fields.add(HostField(owner, name, descriptor))
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor {
                    var calls = ArrayList<HostMethod>()
                    var reads = ArrayList<HostField>()
                    methods.add(HostMethodBody(HostMethod(owner, name, descriptor), access, calls, reads))
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            callOwner: String,
                            callName: String,
                            callDescriptor: String,
                            isInterface: Boolean
                        ) {
                            calls.add(HostMethod(callOwner, callName, callDescriptor))
                        }

                        override fun visitFieldInsn(
                            opcode: Int,
                            fieldOwner: String,
                            fieldName: String,
                            fieldDescriptor: String
                        ) {
                            if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
                                reads.add(HostField(fieldOwner, fieldName, fieldDescriptor))
                            }
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }

        private fun contains(bytes: ByteArray, marker: ByteArray): Boolean {
            var last = bytes.size - marker.size
            if (last < 0) return false
            var i = 0
            while (i <= last) {
                var j = 0
                while (j < marker.size && bytes[i + j] == marker[j]) {
                    j++
                }
                if (j == marker.size) return true
                i++
            }
            return false
        }
    }
}
