package io.github.huang404yan.platypus.core.install

import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quilt 安装器的离线部分：扫已装版本、登记实例、进度换算。
 *
 * 联网那半（拉 loader 列表、拉 profile、下库）没有单测 —— 它和 Fabric 走的是同一批
 * HTTP 与下载代码，为一个新加载器再造一套假服务器换不来多少保障，真实链路在发版前的
 * 无头冒烟里跑。这里只测「不联网也该对」的那几件事。
 */
class QuiltInstallerTest {

    private fun tempDir(): File = Files.createTempDirectory("platypus-quilt-test").toFile()

    /** 造一个已装的版本目录；三个参数决定它像不像 Quilt。 */
    private fun versionDir(root: File, id: String, inheritsFrom: String, mainClass: String): File {
        val dir = File(File(root, "versions"), id)
        dir.mkdirs()
        val json = "{\"id\":\"" + id + "\",\"inheritsFrom\":\"" + inheritsFrom +
            "\",\"mainClass\":\"" + mainClass + "\",\"libraries\":[]}"
        File(dir, id + ".json").writeText(json, Charsets.UTF_8)
        return dir
    }

    @Test
    fun `扫得出本启动器自己装的 Quilt 版本`() = runBlocking {
        val root = tempDir()
        try {
            versionDir(root, "1.20.1-quilt-0.24.0", "1.20.1", "org.quiltmc.loader.impl.launch.knot.KnotClient")
            val found = QuiltInstaller.installedLoaders(root, "1.20.1")
            assertEquals(1, found.size)
            assertEquals("1.20.1-quilt-0.24.0", found.single().versionId)
            // 关键：id 里没有 quilt-loader 前缀，也照样要反解出 loader 版本
            assertEquals("0.24.0", found.single().loaderVersion)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `扫得出 Quilt 官方 profile 的命名`() = runBlocking {
        val root = tempDir()
        try {
            versionDir(root, "quilt-loader-0.24.0-1.20.1", "1.20.1", "org.quiltmc.loader.impl.launch.knot.KnotClient")
            val found = QuiltInstaller.installedLoaders(root, "1.20.1")
            assertEquals("0.24.0", found.single().loaderVersion)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `别的游戏版本与别的加载器不会被算进来`() = runBlocking {
        val root = tempDir()
        try {
            versionDir(root, "1.20.1-quilt-0.24.0", "1.20.1", "org.quiltmc.loader.impl.launch.knot.KnotClient")
            // 父版本不是 1.20.1
            versionDir(root, "1.21.4-quilt-0.27.0", "1.21.4", "org.quiltmc.loader.impl.launch.knot.KnotClient")
            // 父版本对上但主类不是 Quilt
            versionDir(root, "1.20.1-fabric-0.16.0", "1.20.1", "net.fabricmc.loader.impl.launch.knot.KnotClient")
            val found = QuiltInstaller.installedLoaders(root, "1.20.1")
            assertEquals(listOf("1.20.1-quilt-0.24.0"), found.map { it.versionId })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `扫出来的列表按 loader 版本从旧到新`() = runBlocking {
        val root = tempDir()
        try {
            versionDir(root, "1.20.1-quilt-0.30.2", "1.20.1", "org.quiltmc.loader.impl.launch.knot.KnotClient")
            versionDir(root, "1.20.1-quilt-0.24.0", "1.20.1", "org.quiltmc.loader.impl.launch.knot.KnotClient")
            versionDir(root, "1.20.1-quilt-0.27.0", "1.20.1", "org.quiltmc.loader.impl.launch.knot.KnotClient")
            val found = QuiltInstaller.installedLoaders(root, "1.20.1")
            assertEquals(listOf("0.24.0", "0.27.0", "0.30.2"), found.map { it.loaderVersion })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `没有 versions 目录时返回空表而不是抛异常`() {
        val root = tempDir()
        try {
            assertTrue(QuiltInstaller.installedLoaders(root, "1.20.1").isEmpty())
            assertTrue(QuiltInstaller.installedLoaders(File(root, "nope"), "1.20.1").isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `登记实例时 loader 标成 QUILT、游戏版本仍是原版号`() = runBlocking {
        val root = tempDir()
        try {
            val repo: InstanceRepository = JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(root))
            val instance = QuiltInstaller.register(
                instances = repo,
                gameRoot = root,
                versionId = "1.20.1-quilt-0.24.0",
                loaderVersion = "0.24.0",
                gameVersion = "1.20.1",
                javaPath = null,
                memoryMb = 2048,
            )
            assertEquals(LoaderKind.QUILT, instance.loader)
            assertEquals("0.24.0", instance.loaderVersion)
            assertEquals("1.20.1", instance.gameVersion)
            assertEquals("Quilt 0.24.0 / 1.20.1", instance.name)
            assertEquals(File(root, "versions/1.20.1-quilt-0.24.0").absolutePath, instance.directory)
            assertTrue(instance.registered)
            assertEquals(1, repo.list().size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `登记时给了实例名就用给的`() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(root))
            val instance = QuiltInstaller.register(
                instances = repo,
                gameRoot = root,
                versionId = "1.20.1-quilt-0.24.0",
                loaderVersion = "0.24.0",
                gameVersion = "1.20.1",
                javaPath = "C:/java/bin/java.exe",
                memoryMb = 4096,
                instanceName = "我的 Quilt 存档",
            )
            assertEquals("我的 Quilt 存档", instance.name)
            assertEquals("C:/java/bin/java.exe", instance.javaPath)
            assertEquals(4096, instance.memoryMb)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `内存会被夹到允许范围里`() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(root))
            val instance = QuiltInstaller.register(
                instances = repo,
                gameRoot = root,
                versionId = "1.20.1-quilt-0.24.0",
                loaderVersion = "0.24.0",
                gameVersion = "1.20.1",
                javaPath = null,
                memoryMb = 1,
            )
            assertEquals(Instance.clampMemoryMb(1), instance.memoryMb)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `进度换算在总数为零时不炸也不乱跳`() {
        assertEquals(0f, QuiltProgress(QuiltStep.LIBRARIES).fraction)
        assertEquals(0.5f, QuiltProgress(QuiltStep.LIBRARIES, done = 50, total = 100).fraction)
        // 进度只会往前走，超过总数也只到 1
        assertEquals(1f, QuiltProgress(QuiltStep.LIBRARIES, done = 120, total = 100).fraction)
    }

    @Test
    fun `每个步骤都有中文名，失败时能说清卡在哪`() {
        QuiltStep.entries.forEach { assertTrue(it.label.isNotBlank(), it.name + " 没有中文名") }
        val error = QuiltInstallException(QuiltStep.PROFILE, "拉不到 profile")
        assertEquals(QuiltStep.PROFILE, error.step)
        assertEquals("拉不到 profile", error.message)
        assertNull(error.cause)
    }
}