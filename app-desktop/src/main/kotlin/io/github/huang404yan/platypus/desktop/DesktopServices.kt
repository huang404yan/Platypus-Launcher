package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.auth.AccountAuth
import io.github.huang404yan.platypus.core.auth.AccountRepository
import io.github.huang404yan.platypus.core.auth.JsonAccountRepository
import io.github.huang404yan.platypus.core.auth.ClientIds
import io.github.huang404yan.platypus.core.auth.MicrosoftAccountAuth
import io.github.huang404yan.platypus.core.auth.YggdrasilAccountAuth
import io.github.huang404yan.platypus.core.install.JsonServerRepository
import io.github.huang404yan.platypus.core.install.ServerRepository
import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.launch.GameRuntime
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeService
import io.github.huang404yan.platypus.core.settings.JsonSettingsRepository
import io.github.huang404yan.platypus.core.settings.SettingsRepository
import io.github.huang404yan.platypus.desktop.runtime.DesktopGameRuntime
import io.github.huang404yan.platypus.desktop.runtime.DesktopJavaRuntimeService
import io.github.huang404yan.platypus.ui.LauncherBackend
import java.io.File

/**
 * 桌面侧的服务装配（组合根）。
 *
 * ui 模块只认 core 里的接口，具体实现由这里创建；整个程序只有 [create] 一处装配点。
 * 刻意不在 Composable 里 new：DesktopJavaRuntimeService 持有探测缓存、DesktopGameRuntime 持有
 * 读日志用的 CoroutineScope，跟着重组重建会漏进程、漏协程。
 */
class DesktopServices private constructor(
    /** Java 运行时探测。 */
    override val javaRuntimes: JavaRuntimeService,
    /** 起游戏进程。 */
    override val gameRuntime: GameRuntime,
    /** 实例登记。 */
    override val instances: InstanceRepository,
    /** 启动器设置。 */
    override val settings: SettingsRepository,
    /** 服务端登记（与实例索引平级）。 */
    override val servers: ServerRepository,
    /** 正版账号登记（同样与实例索引平级）。 */
    override val accounts: AccountRepository,
    /** 正版登录。 */
    override val auth: AccountAuth,
    /** 外置登录（Yggdrasil）。 */
    override val yggdrasil: YggdrasilAccountAuth,
    /**
     * 便携模式的数据目录（<启动器目录>/data）。常规模式下为 null。
     *
     * 它非空就说明这一份启动器不往系统用户目录写东西：设置、账号、实例、服务端索引
     * 全在这下面，与游戏目录彼此独立。界面拿它显示状态。
     */
    override val portableDataDir: File?,
) : LauncherBackend {

    /**
     * 启动器自己的目录（安装版 = 安装目录，便携版 = 解压出来的文件夹）。
     *
     * 界面拿它判断「这份托管运行时归不归我删」——运行时就在 `<launcherDir>/java/<组件>` 下。
     * 拿不到时为空，界面就不会画删除按钮（宁可少一个按钮，不能删错目录）。
     */
    override val launcherDir: File? = (javaRuntimes as? DesktopJavaRuntimeService)?.launcherDir

    companion object {
        /**
         * @param gameRoot 当前游戏目录，可为 null。探测 Java 时会连带扫它的 runtime/ 子目录
         *   （PCL、HMCL、官方启动器把托管运行时都放在这里）；实例登记也存在它的 platypus/ 下。
         */
        fun create(gameRoot: File?): DesktopServices = create(gameRoot, null)

        /**
         * @param instanceIndex 实例索引文件；null 时按 gameRoot 推导，gameRoot 也为 null 时
         *   退到用户目录下的 .platypus/instances.json（先能存下来，之后用户指了目录再迁移）。
         */
        fun create(gameRoot: File?, instanceIndex: File?): DesktopServices =
            create(gameRoot, instanceIndex, launcherDir = null)

        /**
         * @param launcherDir 启动器自己的目录（安装版 = 安装目录，便携版 = 解压出来的文件夹）。
         *   托管运行时（自动补的 Java）落在这里的 java/ 下，而不是游戏目录 —— 见
         *   [DesktopJavaRuntimeService]。null 时退回游戏目录，方便无头冒烟与单测。
         */
        fun create(gameRoot: File?, instanceIndex: File?, launcherDir: File?): DesktopServices =
            create(gameRoot, instanceIndex, launcherDir, homeDir = null)

        /**
         * @param homeDir 便携模式的数据目录（<启动器目录>/data）。给了就把设置、账号、
         *   实例、服务端索引全放它下面，与游戏目录无关；常规模式传 null。
         *
         * 之所以让它压过 gameRoot：便携模式下游戏目录可能被用户指到 U 盘里已有的那份
         * .minecraft，而启动器自己的数据必须留在启动器目录里 —— 拔了 U 盘游戏没了，
         * 账号与设置不该跟着一起消失。
         */
        fun create(
            gameRoot: File?,
            instanceIndex: File?,
            launcherDir: File?,
            homeDir: File?,
        ): DesktopServices {
            // 账号仓库要被登录流程和界面共用一份：两边各 new 一个会让「刚登录的账号
            // 没出现在列表里」这类问题变成偶发。
            val accountRepository = JsonAccountRepository(defaultAccountIndex(gameRoot, homeDir))
            return assemble(
                gameRoot = gameRoot,
                instanceIndex = instanceIndex,
                accountRepository = accountRepository,
                launcherDir = launcherDir?.takeIf { it.isDirectory },
                homeDir = homeDir,
            )
        }

        private fun assemble(
            gameRoot: File?,
            instanceIndex: File?,
            accountRepository: AccountRepository,
            launcherDir: File?,
            homeDir: File?,
        ): DesktopServices {
            // 设置仓库与登录共用同一份：Client ID 存在设置里，登录时现读，
            // 两个实例指向同一个文件也问题不大，但共用一份能少一次「为什么刚填的没生效」。
            val settingsRepository = JsonSettingsRepository(settingsFile(gameRoot, homeDir))
            return DesktopServices(
                javaRuntimes = DesktopJavaRuntimeService(gameRoot = gameRoot, launcherDir = launcherDir),
                gameRuntime = DesktopGameRuntime(),
                instances = JsonInstanceRepository(instanceIndex ?: defaultInstanceIndex(gameRoot, homeDir)),
                settings = settingsRepository,
                servers = JsonServerRepository(defaultServerIndex(gameRoot, homeDir)),
                accounts = accountRepository,
                // 应用 ID 的取舍在 ClientIds 里：这个包自带的优先（正式版由构建方注入，
                // 下载的人不用做任何事），没有才用用户自己填的（孵化版）。两者都是现读，
                // 用户注册完填进去，下一次点登录就该生效。
                auth = MicrosoftAccountAuth(
                    repository = accountRepository,
                    clientIdProvider = {
                        ClientIds.resolve(settingsRepository.load().microsoftClientId)
                    },
                ),
                // authlib-injector 落在 <gameRoot>/platypus/authlib-injector/ 下面，是这份
                // 游戏目录的附属物，所以跟着 gameRoot 走；没有游戏目录时退到用户目录。
                yggdrasil = YggdrasilAccountAuth(
                    gameRoot = dataRoot(gameRoot, homeDir),
                    repository = accountRepository,
                ),
                portableDataDir = homeDir,
            )
        }

        /** 账号索引与实例索引同层：有游戏目录时 <gameRoot>/platypus/accounts.json。 */
        private fun defaultAccountIndex(gameRoot: File?, homeDir: File?): File =
            JsonAccountRepository.defaultIndexFile(dataRoot(gameRoot, homeDir))

        /** 服务端索引与实例索引同层：有游戏目录时 <gameRoot>/platypus/servers.json。 */
        private fun defaultServerIndex(gameRoot: File?, homeDir: File?): File =
            JsonServerRepository.defaultIndexFile(dataRoot(gameRoot, homeDir))

        private fun defaultInstanceIndex(gameRoot: File?, homeDir: File?): File =
            JsonInstanceRepository.defaultIndexFile(dataRoot(gameRoot, homeDir))

        /**
         * 设置文件与实例索引同层：有游戏目录时放 <gameRoot>/platypus/settings.json，
         * 没有时放用户目录下的 .platypus/settings.json。
         *
         * 刻意不跟着「用户当前选中的目录」漂移：设置本身记的就是那个目录，
         * 若设置文件也跟着目录走，换目录就看不到上次的选择了。
         */
        private fun settingsFile(gameRoot: File?, homeDir: File?): File =
            JsonSettingsRepository.defaultFile(dataRoot(gameRoot, homeDir))

        /**
         * 启动器自己的数据该落在哪个根上，优先级：便携目录 > 游戏目录 > 用户目录。
         *
         * 便携模式排第一的理由见 [create] 的 homeDir 说明：启动器数据要跟着启动器走，
         * 而不是跟着游戏目录走。三者都可能为 null 之外的场合才用到 [fallbackHome]。
         */
        private fun dataRoot(gameRoot: File?, homeDir: File?): File =
            homeDir ?: gameRoot ?: fallbackHome()

        private fun fallbackHome(): File {
            val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
            return File(home, ".platypus")
        }
    }
}
