import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 宿主类是用 Kotlin metadata 2.4.0 编译的，编译器版本不能低于它
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

group = "com.spwmods.waerwak.listenstats"
version = "1.4.6"

val pluginId = "com.spwmods.waerwak.listenstats"
val pluginVersion = version.toString()
val mainClassName = "com.spwmods.waerwak.listenstats.ListenStatsPlugin"
val pluginDisplayName = "听歌统计"
// 保持简短：manifest 按 72 字节折行，过长的中文描述可能被切在多字节字符中间
val pluginSummary = "Play counts, listening time, activity heatmap and rankings for Salt Player (local only)"
val openSourceUrl = "https://github.com/WAerWAK/SPW-listen-stats"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

// ---------------------------------------------------------------- 宿主编译桩
val spwHostJarPath = providers.gradleProperty("spwHostJar").get()
val hostJarFile = file(spwHostJarPath)
require(hostJarFile.isFile) {
    "找不到宿主 jar：$spwHostJarPath\n请在 gradle.properties 里把 spwHostJar 指向 SPW 安装目录下的 app/ffmpeg-x64.dll"
}

// 复制成 .jar 再上 classpath：部分工具链只把 .jar/.zip 结尾的文件当归档
val hostStubJar = layout.buildDirectory.file("host/spw-host.jar")
val prepareHostStub = tasks.register<Copy>("prepareHostStub") {
    description = "把宿主 fat jar 复制成 spw-host.jar 作为 compileOnly 编译桩"
    from(hostJarFile)
    into(layout.buildDirectory.dir("host"))
    rename { "spw-host.jar" }
}

// classpath 必须是明确的 jar 文件（不能是目录），任务依赖单独声明
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(prepareHostStub)
}
tasks.withType<JavaCompile>().configureEach {
    dependsOn(prepareHostStub)
}

dependencies {
    // 宿主已提供：Kotlin stdlib、Compose、Workshop API、PF4J 运行时
    compileOnly(files(hostStubJar))
    // @Extension 注解（编译期）+ 宿主运行时自带
    compileOnly("org.pf4j:pf4j:3.14.1")
    // 测试需要构造宿主的 MediaItem，运行时也要这份类
    testImplementation(files(hostStubJar))
}

/**
 * 数据层回归测试
 *
 * 零依赖：测试是一个带 main() 的 Kotlin 文件，自行断言并输出 PASS/FAIL。
 * 不引入 JUnit，避免额外依赖与网络拉取。
 */
tasks.register<JavaExec>("runDataTests") {
    group = "verification"
    description = "运行数据层回归测试（采集/跨天/门槛/崩溃恢复）"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.spwmods.waerwak.listenstats.test.DataRegressionTestKt")
    workingDir = layout.buildDirectory.dir("test-run").get().asFile
    // 宿主类是 class file 69（JDK 25）编译的：运行时必须用 JDK 25，否则加载 MediaItem 会失败
    val jdk25Home = providers.gradleProperty("jdk25Home").getOrElse("D:/Projects/.tools/jdk25")
    executable = "$jdk25Home/bin/java.exe"
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

// ---------------------------------------------------------------- 打包
tasks.named<Jar>("jar") {
    archiveBaseName.set("$pluginId-core")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/spmod"))
    manifest {
        attributes(
            linkedMapOf(
                "Plugin-Class" to mainClassName,
                "Plugin-Id" to pluginId,
                "Plugin-Version" to pluginVersion,
                "Plugin-Provider" to "spwmods",
                "Plugin-Name" to pluginDisplayName,
                "Plugin-Description" to pluginSummary,
                "Plugin-Has-Config" to "true",
                "Plugin-Open-Source-Url" to openSourceUrl,
                // 逗号分隔；与上游 spmod.PluginPermissions 生成格式一致
                "Plugin-Permissions" to "key-bindings,library-read,class-transform"
            )
        )
    }
}

// 与官方 WorkshopPluginTask 相同的布局：classes/ <- jar 内容，lib/ <- runtimeClasspath 的 jar
val plugin by tasks.registering(Zip::class) {
    group = "build"
    description = "打包为 .spmod 分发包"
    dependsOn(tasks.named("jar"))

    archiveBaseName.set("plugin-$pluginId")
    archiveVersion.set(pluginVersion)
    archiveExtension.set("spmod")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))

    into("classes") {
        from(zipTree(tasks.named<Jar>("jar").flatMap { it.archiveFile }))
    }
    into("lib") {
        from(configurations.named("runtimeClasspath").map { config ->
            config.filter { it.name.endsWith(".jar") }
        })
    }
}

// 部分宿主版本只扫描 .zip/.jar，额外产出一份内容相同的 zip
val pluginZip by tasks.registering(Copy::class) {
    group = "build"
    description = "复制一份 .zip 分发包（兼容只认 .zip 的宿主加载器）"
    from(plugin.map { it.archiveFile })
    into(layout.buildDirectory.dir("libs"))
    rename { name -> name.removeSuffix(".spmod") + ".zip" }
}

// 让 build 直接产出分发包
tasks.named("assemble") {
    dependsOn(plugin, pluginZip)
}

// ---------------------------------------------------------------- 宿主 API 校验
// 宿主 fat jar 被 R8 裁剪过，部分 Compose API 在元数据里已经不存在。
// 插件编译期能看到的一定存在，但为了防 $default / 合成调用在运行时炸，
// 构建后跑一遍“引用 vs 宿主”的存在性校验。
// 需要 Java 25 的 javap：宿主自己的类编译为 class file 69。
val spwJavapPath = providers.gradleProperty("spwJavap").getOrElse(
    "D:/Projects/.tools/jdk25/bin/javap.exe"
)

val verifyHostApi by tasks.registering(Exec::class) {
    group = "verification"
    description = "校验插件引用的宿主导成员是否真实存在（需要 Java 25 的 javap）"
    dependsOn(tasks.named("classes"))
    doFirst {
        val script = layout.projectDirectory.file("tools/verify-host-api.ps1").asFile
        val classesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        commandLine(
            "pwsh", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath,
            "-PluginDir", classesDir.absolutePath,
            "-HostJar", hostJarFile.absolutePath,
            "-Javap", spwJavapPath
        )
    }
}
