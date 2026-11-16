package io.github.huang404yan.platypus.core.runtime

import java.io.File

/**
 * 托管运行时的落盘位置，以及「删这一份会不会伤到别人」的判定。
 *
 * 启动器自己下的 Java 在 `<启动器目录>/java/<组件名>/` 下（里面是 bin/、lib/、conf/），
 * 一整个目录就是一份运行时。**「启动器目录」不是游戏目录**：安装版是安装目录（exe 所在的那层），
 * 便携版是解压出来的那个文件夹。装在游戏目录里会有两个毛病：换一个游戏目录就找不到自己下过的
 * Java，卸载启动器时那份 150 MB 的运行时反而留在用户盘上。
 *
 * 为什么是 `java/` 而不是 `runtime/`：jpackage 打出来的 app-image 自带一个 `runtime/`
 * （启动器自己的 JRE，25.0.5，模块裁剪过、里面没有 java.exe）。要是把下载的运行时也塞进
 * 那个目录，用户的 150 MB 会和程序自己的 bin/、lib/、release 混成一层，覆盖升级与卸载时
 * 谁也说不清哪些能删。分成两个目录，一眼就能看出哪个是程序的、哪个是用户的。
 *
 * 删它必须同时满足两条：
 *
 * 1. 这份 Java 是启动器下的（[JavaRuntime.managed] 为真）；
 * 2. 它的可执行文件确实落在某个托管根的 `<组件名>/bin/` 里面。
 *
 * 第二条是硬保险，不是重复检查：[DesktopJavaRuntimeService.detect] 会把 JAVA_HOME、PATH、
 * 注册表、别的启动器的 runtime 全都扫进来，只信 managed 标记的话，一次标记错误就会去删
 * 用户 `C:\Program Files\Java\jdk-17\bin` 的上级目录。
 *
 * 托管根是**一组**而不是一个：老版本把运行时下在游戏目录的 runtime/ 下（[legacyRootOf]），
 * preview.20 起改到启动器目录（[rootOf]）；升级上来的用户两种都有，得都能认出来才删得掉。
 *
 * 这里全是纯路径运算、不碰磁盘内容，桌面端与安卓端都能用，也方便单测。
 */
object ManagedJava {

    /** 托管运行时的根：`<启动器目录>/java`。与 [MojangJavaRuntimeInstaller] 用的是同一个位置。 */
    fun rootOf(launcherDir: File): File = File(launcherDir, "java")

    /**
     * 老位置的托管根：`<游戏目录>/runtime`。
     *
     * preview.20 及以前把自动补的 Java 下在游戏目录里。升级上来的用户那份还在盘上，
     * 不认它就等于「刚下的 150 MB 突然删不掉了」。新版不再往这里写，只读。
     */
    fun legacyRootOf(gameRoot: File): File = File(gameRoot, "runtime")

    /**
     * 从 java 可执行文件的路径推出它所属的那份运行时目录；推不出来返回 null。
     *
     * 认的形状只有一种：`<runtimeRoot>/<组件名>/bin/java(.exe)`。
     * 别的形状（系统 JDK、PCL 的 runtime、用户手动指定的 java.exe）一律返回 null ——
     * 拿不准的就不该删。
     */
    fun homeOf(javaPath: String, runtimeRoot: File): File? {
        val java = File(javaPath).absoluteFile.normalize()
        val binDir = java.parentFile ?: return null
        if (!binDir.name.equals("bin", ignoreCase = true)) return null
        val home = binDir.parentFile ?: return null
        // 组件名不能为空、不能是「.」或「..」那种还能再往上跳一层的写法。
        if (home.name.isBlank() || home.name == "." || home.name == "..") return null
        val root = runtimeRoot.absoluteFile.normalize()
        if (home.parentFile?.path != root.path) return null
        return home
    }

    /** 在几个托管根里找这份 java 属于哪一份运行时；都不属于返回 null。 */
    fun homeIn(javaPath: String, runtimeRoots: List<File>): File? {
        for (root in runtimeRoots) {
            homeOf(javaPath, root)?.let { return it }
        }
        return null
    }

    /**
     * 这一份运行时能不能删：托管 + 路径认识 + 目录确实存在。
     *
     * 界面据此决定「删除」按钮画不画，别让用户点了才收到「没法删」。
     */
    fun removable(runtime: JavaRuntime, runtimeRoots: List<File>): Boolean {
        if (!runtime.managed) return false
        if (runtimeRoots.isEmpty()) return false
        val home = homeIn(runtime.path, runtimeRoots) ?: return false
        return home.isDirectory
    }
}
