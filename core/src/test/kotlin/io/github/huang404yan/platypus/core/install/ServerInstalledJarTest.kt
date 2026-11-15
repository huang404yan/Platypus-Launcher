package io.github.huang404yan.platypus.core.install

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * installer 装完之后去哪儿找服务端 jar。
 *
 * 这一段以前是漏的：注释写「新版本在 libraries/ 下」，代码只查了根目录。于是装了
 * 1.17+ 的 Forge 服务端后，界面上既看不到 jar、脚本也没生成，看起来像装失败。
 */
class ServerInstalledJarTest {

    private val dir: File = Files.createTempDirectory("platypus-forge-jar-test").toFile()

    @AfterTest
    fun tearDown() {
        runCatching { dir.deleteRecursively() }
    }

    private fun touch(relative: String): File {
        val file = File(dir, relative)
        file.parentFile?.mkdirs()
        file.writeText("jar", Charsets.UTF_8)
        return file
    }

    @Test
    fun `老版本 Forge 的 jar 在目录根上`() {
        val jar = touch("forge-1.16.5-36.2.34.jar")
        assertEquals(
            jar.canonicalFile,
            ServerInstaller.findInstalledServerJar(dir, "1.16.5", "36.2.34", isForge = true)?.canonicalFile,
        )
    }

    @Test
    fun `新版本 Forge 的 jar 在 libraries 下也能找到`() {
        // 1.20.1 装完长这样：根目录只有 run.bat 与 libraries/，jar 埋在很深的包路径里。
        val jar = touch("libraries/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-server.jar")
        assertEquals(
            jar.canonicalFile,
            ServerInstaller.findInstalledServerJar(dir, "1.20.1", "47.2.0", isForge = true)?.canonicalFile,
        )
    }

    @Test
    fun `installer 自己的 jar 不算服务端 jar`() {
        touch("forge-1.20.1-47.2.0-installer.jar")
        assertNull(ServerInstaller.findInstalledServerJar(dir, "1.20.1", "47.2.0", isForge = true))
    }

    @Test
    fun `别的版本的 jar 不会被认成本版本的`() {
        touch("forge-1.19.2-43.2.0.jar")
        assertNull(ServerInstaller.findInstalledServerJar(dir, "1.20.1", "47.2.0", isForge = true))
    }

    @Test
    fun `NeoForge 走自己的前缀`() {
        val jar = touch("libraries/net/neoforged/neoforge/21.1.72/neoforge-21.1.72-server.jar")
        assertEquals(
            jar.canonicalFile,
            ServerInstaller.findInstalledServerJar(dir, "1.21.1", "21.1.72", isForge = false)?.canonicalFile,
        )
    }

    @Test
    fun `什么都找不到时返回 null 而不是抛`() {
        assertNull(ServerInstaller.findInstalledServerJar(dir, "1.20.1", "47.2.0", isForge = true))
    }
}
