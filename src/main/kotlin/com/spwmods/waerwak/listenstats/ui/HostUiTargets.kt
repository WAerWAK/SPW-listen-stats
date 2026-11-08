package com.spwmods.waerwak.listenstats.ui

/**
 * 宿主 UI 注入目标
 *
 * SPW 每次更新都会重排混淆名（连 patch 版本也会变），所以这里按宿主版本给出目标，
 * 不再把类名写死在调用点。新增宿主版本时在这里加一条即可；
 * 未登记的版本不注入（见 [forVersion]），避免用旧目标盲猜。
 *
 * 参考 Jethuit/SPW-Listen-Stats 的 HostUiTargets 做法。
 */
data class HostUiTargets(
    /** AppNavigation 调用点：面板 + 材质桥接层的 inClass */
    val pageCaller: String,
    /** 标题栏 composable 的调用点：标题栏按钮的 inClass */
    val titlebarCaller: String,
    /** 标题栏里的 DebugCaptionButton 模板：标题栏按钮的 className */
    val debugCaption: String,
    /**
     * 渲染标题栏按钮时是否复用宿主自己的 caption button 组件
     *
     * 1.19.1 起宿主标题栏容器只接受原生 caption button（拼接自绘组件会被排到行首），
     * 所以必须反射调用宿主组件；1.19.0 的自绘方案在原位置可用，保持老路径。
     */
    val reuseHostCaption: Boolean
) {
    companion object {

        private val V1190 = HostUiTargets(
            pageCaller = "androidx.compose.ui.\u06c4",
            titlebarCaller = "androidx.compose.ui.\u0205",
            debugCaption = "androidx.compose.ui.\u04f4",
            reuseHostCaption = false
        )

        private val V1191 = HostUiTargets(
            pageCaller = "androidx.compose.ui.\u0443",
            titlebarCaller = "androidx.compose.ui.\u09f1",
            debugCaption = "androidx.compose.ui.\u063a",
            reuseHostCaption = true
        )

        /**
         * SPW 1.19.3：宿主再次重排混淆名，1.19.1 的三个目标全部失效
         *
         * 三者均由宿主 jar 的字节码实测得到（javap）：
         * - pageCaller：AppNavigationKt.AppNavigation 的唯一外部调用类（类内调用点计数 = 1）
         * - titlebarCaller：标题栏里 `ݕ` 的唯一调用类
         * - debugCaption：标题栏 Quick 前一个零参 composable
         */
        private val V1193 = HostUiTargets(
            pageCaller = "androidx.compose.ui.\u024e",
            titlebarCaller = "androidx.compose.ui.\u0a88",
            debugCaption = "androidx.compose.ui.\u0755",
            reuseHostCaption = true
        )

        /**
         * 取对应版本的注入目标；未适配的版本返回 null
         *
         * 刻意**不回退**到最近已知的一组：混淆名在宿主里会指向完全不同的类，
         * 盲猜的结果是控制台里一句难懂的 ambiguous / 0 calls，甚至注入到错误的位置。
         * 返回 null 时调用方跳过 UI 注入，并在信息页/日志里给出明确提示——采集与统计不受影响。
         */
        fun forVersion(version: String): HostUiTargets? = when {
            version.startsWith("1.19.0") -> V1190
            version.startsWith("1.19.1") -> V1191
            version.startsWith("1.19.3") -> V1193
            else -> null
        }
    }
}
