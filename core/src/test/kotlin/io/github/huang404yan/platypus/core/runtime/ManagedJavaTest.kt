package io.github.huang404yan.platypus.core.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「这份 Java 是不是启动器自己下的、删不删得掉」的判定。
 *
 * 这里错一次的代价是删掉用户 `C:\Program Files\Java\jdk-17`，所以每条否定分支都单独测：
 * 只看 [JavaRuntime.managed] 标记是不够的，路径必须真的落在托管根里。
 */
class ManagedJavaTest {

    private fun runtime(path: String, managed: Boolean = true) =
        JavaRuntime(path = path, majorVersion = 17, managed = managed)

    /** 在 [dir] 下按给定相对路径造一份「运行时」并返回它的 java.exe。 */
    private fun fakeRuntime(dir: File, relativeHome: String): File {
        val home = File(dir, relativeHome)
        File(home, "bin").mkdirs()
        val java = File(home, "bin/java.exe")
        java.writeText("")
        return java
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("platypus-java").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `托管根就是启动器目录下的 java`() {
        assertEquals(
            File("D:/xiazai/Platypus/java").path,
            ManagedJava.rootOf(File("D:/xiazai/Platypus")).path,
        )
    }

    @Test
    fun `托管根不叫 runtime 免得和 jpackage 自带的 JRE 撞车`() {
        // jpackage 的 app-image 里有自己的 runtime/（启动器 JRE，里面没有 java.exe）。
        // 下载的运行时要是也落在那儿，用户数据和程序文件就混成一层了。
        val launcher = File("D:/xiazai/Platypus")
        assertEquals(File(launcher, "java").path, ManagedJava.rootOf(launcher).path)
        assertTrue(ManagedJava.rootOf(launcher).path != File(launcher, "runtime").path)
    }

    @Test
    fun `老位置是游戏目录下的 runtime`() {
        assertEquals(
            File("D:/minecraft/runtime").path,
            ManagedJava.legacyRootOf(File("D:/minecraft")).path,
        )
    }

    @Test
    fun `认出 java 组件目录里的 java`() {
        val root = File("D:/xiazai/Platypus/java")
        val home = ManagedJava.homeOf("D:/xiazai/Platypus/java/java-runtime-gamma/bin/java.exe", root)
        assertEquals(File("D:/xiazai/Platypus/java/java-runtime-gamma").path, home?.path)
    }

    @Test
    fun `bin 目录名大小写不影响判定`() {
        val root = File("D:/xiazai/Platypus/java")
        val home = ManagedJava.homeOf("D:/xiazai/Platypus/java/java-runtime-gamma/BIN/java.exe", root)
        assertEquals(File("D:/xiazai/Platypus/java/java-runtime-gamma").path, home?.path)
    }

    @Test
    fun `系统 JDK 推不出托管目录`() {
        val root = File("D:/xiazai/Platypus/java")
        assertNull(ManagedJava.homeOf("C:/Program Files/Java/jdk-17/bin/java.exe", root))
    }

    @Test
    fun `可执行文件不在 bin 下就不认`() {
        val root = File("D:/xiazai/Platypus/java")
        assertNull(ManagedJava.homeOf("D:/xiazai/Platypus/java/java-runtime-gamma/java.exe", root))
    }

    @Test
    fun `组件名不能是双点`() {
        val root = File("D:/xiazai/Platypus/java")
        assertNull(ManagedJava.homeOf("D:/xiazai/Platypus/java/../bin/java.exe", root))
    }

    @Test
    fun `多代托管根都能认`() {
        val launcherRoot = File("D:/xiazai/Platypus/java")
        val legacyRoot = File("D:/minecraft/runtime")
        val roots = listOf(launcherRoot, legacyRoot)
        assertEquals(
            File("D:/minecraft/runtime/java-runtime-gamma").path,
            ManagedJava.homeIn("D:/minecraft/runtime/java-runtime-gamma/bin/java.exe", roots)?.path,
        )
        assertEquals(
            File("D:/xiazai/Platypus/java/jre-legacy").path,
            ManagedJava.homeIn("D:/xiazai/Platypus/java/jre-legacy/bin/java.exe", roots)?.path,
        )
        assertNull(ManagedJava.homeIn("C:/Program Files/Java/jdk-17/bin/java.exe", roots))
    }

    @Test
    fun `不是启动器下的不给删`() = withTempDir { dir ->
        val java = fakeRuntime(dir, "java/java-runtime-gamma")
        val roots = listOf(ManagedJava.rootOf(dir))
        assertFalse(ManagedJava.removable(runtime(java.path, managed = false), roots))
    }

    @Test
    fun `托管且路径在根里才给删`() = withTempDir { dir ->
        val java = fakeRuntime(dir, "java/java-runtime-gamma")
        val roots = listOf(ManagedJava.rootOf(dir))
        assertTrue(ManagedJava.removable(runtime(java.path), roots))
    }

    @Test
    fun `老位置那份也删得掉`() = withTempDir { dir ->
        val java = fakeRuntime(dir, "runtime/java-runtime-gamma")
        val roots = listOf(ManagedJava.rootOf(dir), ManagedJava.legacyRootOf(dir))
        assertTrue(ManagedJava.removable(runtime(java.path), roots))
    }

    @Test
    fun `目录已经没了就画不出删除按钮`() = withTempDir { dir ->
        val roots = listOf(ManagedJava.rootOf(dir))
        val gone = File(dir, "java/java-runtime-gamma/bin/java.exe").path
        assertFalse(ManagedJava.removable(runtime(gone), roots))
    }

    @Test
    fun `没有托管根就不给删`() {
        assertFalse(ManagedJava.removable(runtime("D:/x/java/java-runtime-gamma/bin/java.exe"), emptyList()))
    }
}
