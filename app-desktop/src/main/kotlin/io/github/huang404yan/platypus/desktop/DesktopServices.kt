package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.auth.AccountAuth
import io.github.huang404yan.platypus.core.auth.AccountRepository
import io.github.huang404yan.platypus.core.auth.JsonAccountRepository
import io.github.huang404yan.platypus.core.auth.MicrosoftAccountAuth
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
) : LauncherBackend {
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
        fun create(gameRoot: File?, instanceIndex: File?): DesktopServices {
            // 账号仓库要被登录流程和界面共用一份：两边各 new 一个会让「刚登录的账号
            // 没出现在列表里」这类问题变成偶发。
            val accountRepository = JsonAccountRepository(defaultAccountIndex(gameRoot))
            return assemble(gameRoot, instanceIndex, accountRepository)
        }

        private fun assemble(
            gameRoot: File?,
            instanceIndex: File?,
            accountRepository: AccountRepository,
        ): DesktopServices = DesktopServices(
            javaRuntimes = DesktopJavaRuntimeService(gameRoot = gameRoot),
            gameRuntime = DesktopGameRuntime(),
            instances = JsonInstanceRepository(instanceIndex ?: defaultInstanceIndex(gameRoot)),
            settings = JsonSettingsRepository(settingsFile(gameRoot)),
            servers = JsonServerRepository(defaultServerIndex(gameRoot)),
            accounts = accountRepository,
            auth = MicrosoftAccountAuth(repository = accountRepository),
        )

        /** 账号索引与实例索引同层：有游戏目录时 <gameRoot>/platypus/accounts.json。 */
        private fun defaultAccountIndex(gameRoot: File?): File {
            if (gameRoot != null) return JsonAccountRepository.defaultIndexFile(gameRoot)
            return JsonAccountRepository.defaultIndexFile(fallbackHome())
        }

        /** 服务端索引与实例索引同层：有游戏目录时 <gameRoot>/platypus/servers.json。 */
        private fun defaultServerIndex(gameRoot: File?): File {
            if (gameRoot != null) return JsonServerRepository.defaultIndexFile(gameRoot)
            return JsonServerRepository.defaultIndexFile(fallbackHome())
        }

        private fun defaultInstanceIndex(gameRoot: File?): File {
            if (gameRoot != null) return JsonInstanceRepository.defaultIndexFile(gameRoot)
            return JsonInstanceRepository.defaultIndexFile(fallbackHome())
        }

        /**
         * 设置文件与实例索引同层：有游戏目录时放 <gameRoot>/platypus/settings.json，
         * 没有时放用户目录下的 .platypus/settings.json。
         *
         * 刻意不跟着「用户当前选中的目录」漂移：设置本身记的就是那个目录，
         * 若设置文件也跟着目录走，换目录就看不到上次的选择了。
         */
        private fun settingsFile(gameRoot: File?): File {
            if (gameRoot != null) return JsonSettingsRepository.defaultFile(gameRoot)
            return JsonSettingsRepository.defaultFile(fallbackHome())
        }

        private fun fallbackHome(): File {
            val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
            return File(home, ".platypus")
        }
    }
}
