package io.github.huang404yan.platypus.core.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 安卓 Java 运行时服务这块的单测。
 *
 * 全程不联网：盘的形状用临时目录造，下载那一层换成假实现。
 * 真要下一份 FCL 的 JRE 是整机上的事，单测里不该依赖 GitHub。
 */
class AndroidJavaRuntimeServiceTest {

    private val root: File = Files.createTempDirectory("platypus-android-jre").toFile()

    /** 造一套「看着能用」的 JRE：bin/java、release、lib/server/libjvm.so 三样齐。 */
    private fun makeJre(
        dir: File,
        version: String = "17.0.9",
        arch: String = "aarch64",
        withLibJvm: Boolean = true,
    ): File {
        File(dir, "bin").mkdirs()
        File(dir, "bin/java").writeText("#!/bin/sh\n", Charsets.UTF_8)
        File(dir, "lib/server").mkdirs()
        if (withLibJvm) File(dir, "lib/server/libjvm.so").writeBytes(ByteArray(8))
        File(dir, "release").writeText(
            "IMPLEMENTOR=\"Eclipse Adoptium\"\n" +
                "JAVA_VERSION=\"" + version + "\"\n" +
                "OS_ARCH=\"" + arch + "\"\n" +
                "OS_NAME=\"Linux\"\n",
            Charsets.UTF_8,
        )
        return dir
    }

    private val service = AndroidJavaRuntimeService(root, abi = "arm64-v8a")

    // ---------- release 文件 ----------

    @Test
    fun `release 文件里的等号与引号都按格式处理`() {
        val parsed = JavaReleaseFile.parse(
            "JAVA_VERSION=\"17.0.9\"\n" +
                "OS_ARCH=aarch64\n" +
                "# 注释行不算\n" +
                "\n" +
                "IMPLEMENTOR=\"Eclipse Adoptium\"\n",
        )
        assertEquals("17.0.9", parsed["JAVA_VERSION"])
        assertEquals("aarch64", parsed["OS_ARCH"])
        assertEquals("Eclipse Adoptium", parsed["IMPLEMENTOR"])
        assertEquals(3, parsed.size)
    }

    @Test
    fun `release 文件缺失或读不到时返回空表而不是抛异常`() {
        val missing = File(root, "no-such-jre")
        assertTrue(JavaReleaseFile.read(missing).isEmpty())
        assertEquals(null, JavaReleaseFile.majorOf(JavaReleaseFile.read(missing)))
    }

    @Test
    fun `主版本从 JAVA_VERSION 里认出来`() {
        assertEquals(17, JavaReleaseFile.majorOf(mapOf("JAVA_VERSION" to "17.0.9")))
        assertEquals(8, JavaReleaseFile.majorOf(mapOf("JAVA_VERSION" to "1.8.0_402")))
        assertEquals(25, JavaReleaseFile.majorOf(mapOf("JAVA_VERSION" to "25.0.5.0.3")))
        assertNull(JavaReleaseFile.majorOf(mapOf("JAVA_VERSION" to "not-a-version")))
        assertNull(JavaReleaseFile.majorOf(emptyMap()))
    }

    // ---------- 盘上认版本 ----------

    @Test
    fun `认出盘上那套 JRE 的版本与架构`() {
        val home = makeJre(File(root, "jre17-arm64-v8a"))
        val runtime = assertNotNull(service.runtimeOf(home))
        assertEquals(17, runtime.majorVersion)
        assertEquals(home.absolutePath, runtime.path)
        assertEquals("aarch64", runtime.arch)
        assertEquals(64, runtime.bits)
        assertEquals("Eclipse Adoptium", runtime.vendor)
        assertTrue(runtime.managed)
    }

    @Test
    fun `少了 libjvm 的目录不算能用`() {
        val broken = makeJre(File(root, "jre21-arm64-v8a"), version = "21.0.1", withLibJvm = false)
        // bin/java 与 release 都在，但虚拟机本体没解出来。
        assertTrue(AndroidJavaInstaller.isInstalled(broken))
        assertFalse(AndroidJavaRuntimeService.canLaunch(broken))
        assertNull(service.runtimeOf(broken))
    }

    @Test
    fun `release 没写版本号时退回目录名`() {
        val home = makeJre(File(root, "jre25-arm64-v8a"))
        File(home, "release").writeText("OS_ARCH=\"aarch64\"\n", Charsets.UTF_8)
        val runtime = assertNotNull(service.runtimeOf(home))
        assertEquals(25, runtime.majorVersion)
    }

    @Test
    fun `目录名解析只认 jre 前缀加数字`() {
        assertEquals(17, AndroidJavaRuntimeService.majorFromDirName("jre17-arm64-v8a"))
        assertEquals(8, AndroidJavaRuntimeService.majorFromDirName("jre8-x86"))
        assertNull(AndroidJavaRuntimeService.majorFromDirName("jdk17"))
        assertNull(AndroidJavaRuntimeService.majorFromDirName("jre"))
        assertNull(AndroidJavaRuntimeService.majorFromDirName("17"))
    }

    @Test
    fun `detect 只报能用的那几套并按版本升序`() {
        makeJre(File(root, "jre21-arm64-v8a"), version = "21.0.1")
        makeJre(File(root, "jre8-arm64-v8a"), version = "1.8.0_402")
        makeJre(File(root, "jre17-arm64-v8a"), version = "17.0.9", withLibJvm = false)
        val majors = kotlinx.coroutines.runBlocking { service.detect() }.map { it.majorVersion }
        assertEquals(listOf(8, 21), majors)
    }

    // ---------- component 名字 ----------

    @Test
    fun `component 名字能写也能读回来`() {
        val name = AndroidJavaRuntimeService.componentOf(17, "arm64-v8a")
        assertEquals("android-jre17-arm64-v8a", name)
        assertEquals(17 to "arm64-v8a", AndroidJavaRuntimeService.parseComponent(name))
        assertEquals(21 to null, AndroidJavaRuntimeService.parseComponent("android-jre21"))
        assertNull(AndroidJavaRuntimeService.parseComponent("jre-legacy"))
        assertNull(AndroidJavaRuntimeService.parseComponent("android-jreabc"))
    }

    // ---------- catalog ----------

    @Test
    fun `catalog 列出这个 ABI 能下的四档并标出已装`() {
        makeJre(File(root, "jre17-arm64-v8a"))
        val offers = kotlinx.coroutines.runBlocking { service.catalog() }
        assertEquals(listOf(8, 17, 21, 25), offers.map { it.majorVersion })
        val installed = offers.filter { it.installed }
        assertEquals(1, installed.size)
        assertEquals(17, installed.first().majorVersion)
        assertEquals(AndroidJavaRuntimeService.componentOf(17, "arm64-v8a"), installed.first().component)
        assertTrue(offers.first { it.majorVersion == 8 }.installedPath == null)
    }

    @Test
    fun `架构不认识时 catalog 是空表而不报错`() {
        val none = AndroidJavaRuntimeService(root, abi = null)
        assertEquals(emptyList(), kotlinx.coroutines.runBlocking { none.catalog() })
    }

    // ---------- ensure / download ----------

    @Test
    fun `ensure 命中盘上已有时不去下载`() {
        makeJre(File(root, "jre17-arm64-v8a"))
        var installs = 0
        val counting = AndroidJavaRuntimeService(
            root,
            abi = "arm64-v8a",
            installer = { _, _, _ -> installs++; File(root, "unused") },
        )
        val runtime = kotlinx.coroutines.runBlocking { counting.ensure(17) }
        assertEquals(17, runtime.majorVersion)
        assertEquals(0, installs)
    }

    @Test
    fun `ensure 缺那一版时交给下载那一层`() {
        val installed = mutableListOf<Int>()
        val fake = AndroidJavaRuntimeService(
            root,
            abi = "arm64-v8a",
            installer = { target, offer, onProgress ->
                installed += offer.majorVersion
                onProgress(AndroidJavaProgress("完成", "fake", 1L, 1L))
                makeJre(File(target, "jre" + offer.majorVersion + "-" + offer.abi), version = "21.0.1")
            },
        )
        val runtime = kotlinx.coroutines.runBlocking { fake.ensure(21) }
        assertEquals(listOf(21), installed)
        assertEquals(21, runtime.majorVersion)
    }

    @Test
    fun `没有可用 ABI 时下载直接说清楚`() {
        val none = AndroidJavaRuntimeService(root, abi = null)
        val error = assertFailsWith<JavaRuntimeUnavailableException> {
            kotlinx.coroutines.runBlocking { none.download(17, null) }
        }
        assertTrue(error.message.orEmpty().contains("架构"), error.message.orEmpty())
    }

    @Test
    fun `要一个上游没发过的版本时说清能下哪些`() {
        val fake = AndroidJavaRuntimeService(root, abi = "x86", installer = { _, _, _ -> File(root, "unused") })
        // jre25 没有 32 位 x86 的包。
        val error = assertFailsWith<JavaRuntimeUnavailableException> {
            kotlinx.coroutines.runBlocking { fake.download(25, null) }
        }
        assertTrue(error.message.orEmpty().contains("25"), error.message.orEmpty())
    }

    @Test
    fun `下载这一层失败时包成可读的话术`() {
        val fake = AndroidJavaRuntimeService(
            root,
            abi = "arm64-v8a",
            installer = { _, _, _ -> throw java.io.IOException("连不上") },
        )
        val error = assertFailsWith<JavaRuntimeUnavailableException> {
            kotlinx.coroutines.runBlocking { fake.download(17, null) }
        }
        assertTrue(error.message.orEmpty().contains("连不上"), error.message.orEmpty())
    }
}
