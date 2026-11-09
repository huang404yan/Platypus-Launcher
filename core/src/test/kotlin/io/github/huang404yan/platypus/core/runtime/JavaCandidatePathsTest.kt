package io.github.huang404yan.platypus.core.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 候选路径枚举的纯逻辑测试：全部在临时目录里造假 JDK，不碰真机环境。
 */
class JavaCandidatePathsTest {

    private fun tempDir(): File = Files.createTempDirectory("platypus-java-paths-test").toFile()

    /** 造一个 <root>/bin/java.exe 的假 JDK，返回 JDK 根目录。 */
    private fun fakeJdk(root: File, name: String): File {
        val home = File(root, name)
        val bin = File(home, "bin")
        bin.mkdirs()
        File(bin, "java.exe").writeText("fake")
        return home
    }

    @Test
    fun `javaExecutableIn 接受 JDK 根目录 bin 目录与可执行文件本身`() {
        assertEquals("D:/jdk-17/bin/java.exe", JavaCandidatePaths.javaExecutableIn("D:/jdk-17"))
        assertEquals("D:/jdk-17/bin/java.exe", JavaCandidatePaths.javaExecutableIn("D:\\jdk-17"))
        assertEquals("D:/jdk-17/bin/java.exe", JavaCandidatePaths.javaExecutableIn("D:/jdk-17/bin"))
        assertEquals("D:/jdk-17/bin/java.exe", JavaCandidatePaths.javaExecutableIn("D:/jdk-17/bin/java.exe"))
        // 带引号的环境变量值（JAVA_HOME 常见脏数据）
        assertEquals(
            "C:/Program Files/Java/jdk-17/bin/java.exe",
            JavaCandidatePaths.javaExecutableIn("\"C:\\Program Files\\Java\\jdk-17\""),
        )
        assertNull(JavaCandidatePaths.javaExecutableIn("   "))
        assertNull(JavaCandidatePaths.javaExecutableIn(""))
    }

    @Test
    fun `JAVA_HOME 排在注册表与 PATH 之前`() {
        val home = tempDir()
        try {
            val fromJavaHome = fakeJdk(home, "jdk-home")
            val fromRegistry = fakeJdk(home, "jdk-registry")
            val fromPath = fakeJdk(home, "jdk-path")

            val list = JavaCandidatePaths.candidates(
                JavaSearchEnv(
                    javaHome = fromJavaHome.path,
                    registryJavaHomes = listOf(fromRegistry.path),
                    pathDirs = listOf(File(fromPath, "bin").path),
                ),
            )
            assertEquals(
                listOf(
                    JavaCandidatePaths.normalizeKey(File(fromJavaHome, "bin/java.exe").path),
                    JavaCandidatePaths.normalizeKey(File(fromRegistry, "bin/java.exe").path),
                    JavaCandidatePaths.normalizeKey(File(fromPath, "bin/java.exe").path),
                ),
                list.map { JavaCandidatePaths.normalizeKey(it) },
            )
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `手动指定的 java 优先级最高`() {
        val home = tempDir()
        try {
            val manual = fakeJdk(home, "manual")
            val javaHome = fakeJdk(home, "env")
            val list = JavaCandidatePaths.candidates(
                JavaSearchEnv(
                    javaHome = javaHome.path,
                    extraJavas = listOf(File(manual, "bin/java.exe").path),
                ),
            )
            assertEquals(
                JavaCandidatePaths.normalizeKey(File(manual, "bin/java.exe").path),
                JavaCandidatePaths.normalizeKey(list.first()),
            )
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `枚举 Program Files 下的厂商目录与散装 jdk 目录`() {
        val home = tempDir()
        try {
            val programFiles = File(home, "Program Files")
            programFiles.mkdirs()
            val adoptiumChild = fakeJdk(File(programFiles, "Eclipse Adoptium"), "jdk-17.0.10.7-hotspot")
            val zuluChild = fakeJdk(File(programFiles, "Zulu"), "zulu-21")
            val microsoftChild = fakeJdk(File(programFiles, "Microsoft"), "jdk-21.0.5.11-hotspot")
            val javaVendor = fakeJdk(File(programFiles, "Java"), "jdk1.8.0_401")
            // 直接铺在 Program Files 根下、名字像 JDK 的目录
            val bare = fakeJdk(programFiles, "jdk-22")
            // 名字不像 JDK 的目录不应被当成候选
            val notJdk = fakeJdk(programFiles, "Steam")

            val keys = JavaCandidatePaths.candidates(
                JavaSearchEnv(programFilesDirs = listOf(programFiles.path)),
            ).map { JavaCandidatePaths.normalizeKey(it) }

            assertTrue(keys.contains(JavaCandidatePaths.normalizeKey(File(adoptiumChild, "bin/java.exe").path)))
            assertTrue(keys.contains(JavaCandidatePaths.normalizeKey(File(zuluChild, "bin/java.exe").path)))
            assertTrue(keys.contains(JavaCandidatePaths.normalizeKey(File(microsoftChild, "bin/java.exe").path)))
            assertTrue(keys.contains(JavaCandidatePaths.normalizeKey(File(javaVendor, "bin/java.exe").path)))
            assertTrue(keys.contains(JavaCandidatePaths.normalizeKey(File(bare, "bin/java.exe").path)))
            assertFalse(keys.contains(JavaCandidatePaths.normalizeKey(File(notJdk, "bin/java.exe").path)))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `在启动器自带 runtime 目录里递归找到 jre`() {
        val home = tempDir()
        try {
            val runtime = File(home, ".minecraft/runtime")
            val java8 = fakeJdk(File(runtime, "java-runtime-beta"), "windows-x64/java-runtime-beta")
            val deep = fakeJdk(File(runtime, "java-runtime-gamma"), "windows-x64/java-runtime-gamma/jre-x64")

            val homes = JavaCandidatePaths.findJavaHomes(runtime.path, maxScanDepth = 4)
                .map { JavaCandidatePaths.normalizeKey(it) }
            assertTrue(homes.contains(JavaCandidatePaths.normalizeKey(java8.path)))
            assertTrue(homes.contains(JavaCandidatePaths.normalizeKey(deep.path)))
            // 返回的是 JDK 根目录本身，不是它的 bin
            assertFalse(homes.any { it.endsWith("/bin") })
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `递归深度受限 不会为了找一个 jre 扫穿整个磁盘`() {
        val home = tempDir()
        try {
            val runtime = File(home, "runtime")
            val veryDeep = File(runtime, "a/b/c/d/e/f")
            veryDeep.mkdirs()
            File(veryDeep, "bin").mkdirs()
            File(veryDeep, "bin/java.exe").writeText("fake")

            val shallow = JavaCandidatePaths.findJavaHomes(runtime.path, maxScanDepth = 2)
            assertTrue(shallow.isEmpty())

            val deep = JavaCandidatePaths.findJavaHomes(runtime.path, maxScanDepth = 8)
            assertEquals(1, deep.size)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `PATH 条目既当目录也当 JDK 根目录处理`() {
        val home = tempDir()
        try {
            val jdk = fakeJdk(home, "jdk-from-path")
            val keys = JavaCandidatePaths.candidates(
                JavaSearchEnv(pathDirs = listOf(File(jdk, "bin").path, jdk.path)),
            ).map { JavaCandidatePaths.normalizeKey(it) }
            // 两个条目指向同一个 java，去重后只剩一个
            assertEquals(
                listOf(JavaCandidatePaths.normalizeKey(File(jdk, "bin/java.exe").path)),
                keys,
            )
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `同一路径的大小写与分隔符差异只算一次`() {
        val home = tempDir()
        try {
            val jdk = fakeJdk(home, "Jdk")
            val list = JavaCandidatePaths.candidates(
                JavaSearchEnv(
                    javaHome = jdk.path,
                    pathDirs = listOf(File(jdk, "bin").path),
                    extraJavas = listOf(File(jdk, "bin/java.exe").path.replace('/', '\\')),
                ),
            )
            assertEquals(1, list.size)
        } finally {
            home.deleteRecursively()
        }
    }
}
