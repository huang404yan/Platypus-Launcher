package io.github.huang404yan.platypus.core.instance

import io.github.huang404yan.platypus.core.version.OsFamily
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceIntegrityTest {

    private val jarBytes = "client-jar-bytes".toByteArray()
    private val libBytes = "library-bytes".toByteArray()
    private val assetBytes = "asset-object-bytes".toByteArray()
    private val indexBytes = assetIndexJson().toByteArray()

    private fun sha1(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private fun assetIndexJson(): String {
        val hash = sha1(assetBytes)
        return """{"objects":{"minecraft/sounds/a.ogg":{"hash":"$hash","size":${assetBytes.size}}}}"""
    }

    private fun tempRoot(): File = Files.createTempDirectory("platypus-integrity").toFile()

    /**
     * 造一个最小可校验的版本目录：json + client jar + 资源索引 + 一个库 + 一个资源对象。
     *
     * [withFiles] 为 false 时只写 json，用来测「什么都没下」的情形。
     */
    private fun buildVersion(
        root: File,
        id: String = "1.21.1",
        withFiles: Boolean = true,
        extraLibraries: String = "",
    ): File {
        val versionDir = File(root, "versions/$id")
        versionDir.mkdirs()

        if (withFiles) {
            File(versionDir, "$id.jar").writeBytes(jarBytes)
            val lib = File(root, "libraries/org/example/lib/1.0/lib-1.0.jar")
            lib.parentFile.mkdirs()
            lib.writeBytes(libBytes)
            val index = File(root, "assets/indexes/17.json")
            index.parentFile.mkdirs()
            index.writeBytes(indexBytes)
            val hash = sha1(assetBytes)
            val obj = File(root, "assets/objects/${hash.substring(0, 2)}/$hash")
            obj.parentFile.mkdirs()
            obj.writeBytes(assetBytes)
        }

        val json = """
            {
              "id": "$id",
              "type": "release",
              "mainClass": "net.minecraft.client.main.Main",
              "assets": "17",
              "assetIndex": {
                "id": "17",
                "url": "https://example.invalid/17.json",
                "sha1": "${sha1(indexBytes)}",
                "size": ${indexBytes.size}
              },
              "downloads": {
                "client": {
                  "url": "https://example.invalid/$id.jar",
                  "sha1": "${sha1(jarBytes)}",
                  "size": ${jarBytes.size}
                }
              },
              "libraries": [
                {
                  "name": "org.example:lib:1.0",
                  "downloads": {
                    "artifact": {
                      "url": "https://example.invalid/lib-1.0.jar",
                      "path": "org/example/lib/1.0/lib-1.0.jar",
                      "sha1": "${sha1(libBytes)}",
                      "size": ${libBytes.size}
                    }
                  }
                }$extraLibraries
              ]
            }
        """.trimIndent()
        File(versionDir, "$id.json").writeText(json, Charsets.UTF_8)
        return versionDir
    }

    private fun instanceOf(versionDir: File, id: String = "1.21.1"): Instance = Instance(
        id = id,
        name = id,
        gameVersion = id,
        directory = versionDir.path,
    )

    @Test
    fun reportsHealthyWhenEverythingMatches() {
        val root = tempRoot()
        val versionDir = buildVersion(root)
        val report = InstanceIntegrity.inspect(instanceOf(versionDir))

        assertTrue(report.healthy, report.summary)
        assertEquals(0, report.problemFiles)
        // client jar + 资源索引 + 一个库 + 一个资源对象
        assertEquals(4, report.totalFiles)
        assertEquals(4, report.okFiles)
        assertTrue(report.hashesChecked)
    }

    @Test
    fun missingClientJarIsReported() {
        val root = tempRoot()
        val versionDir = buildVersion(root)
        File(versionDir, "1.21.1.jar").delete()

        val report = InstanceIntegrity.inspect(instanceOf(versionDir))
        assertFalse(report.healthy)
        assertEquals(1, report.missing.size)
        assertEquals(1, report.problemFiles)
        assertTrue(report.missing.single().target.path.endsWith("1.21.1.jar"))
        // 缺一个文件不会把整个清单判死，其余三个照样是好的
        assertEquals(3, report.okFiles)
    }

    @Test
    fun libraryWithWrongContentIsBrokenWhenHashesChecked() {
        val root = tempRoot()
        val versionDir = buildVersion(root)
        val lib = File(root, "libraries/org/example/lib/1.0/lib-1.0.jar")
        // 同样长度、不同内容：只比大小是发现不了的，这正是为什么要算 sha1
        lib.writeBytes(ByteArray(libBytes.size) { 0x41 })

        val strict = InstanceIntegrity.inspect(instanceOf(versionDir), verifyHashes = true)
        assertEquals(1, strict.broken.size)
        assertTrue(strict.broken.single().target.path.endsWith("lib-1.0.jar"))
        assertFalse(strict.healthy)

        val loose = InstanceIntegrity.inspect(instanceOf(versionDir), verifyHashes = false)
        assertTrue(loose.healthy)
        assertFalse(loose.hashesChecked)
    }

    @Test
    fun wrongSizeIsBrokenEvenWithoutHashes() {
        val root = tempRoot()
        val versionDir = buildVersion(root)
        File(root, "libraries/org/example/lib/1.0/lib-1.0.jar").writeBytes(ByteArray(3))

        val report = InstanceIntegrity.inspect(instanceOf(versionDir), verifyHashes = false)
        assertEquals(1, report.broken.size)
        assertEquals(3L, report.broken.single().actualBytes)
    }

    @Test
    fun missingAssetIndexOnlyWarns() {
        val root = tempRoot()
        val versionDir = buildVersion(root)
        File(root, "assets/indexes/17.json").delete()

        val report = InstanceIntegrity.inspect(instanceOf(versionDir))
        // 索引本身算一条缺失，但 objects 里的东西一条都不会被误报成缺失
        assertEquals(1, report.missing.size)
        assertTrue(report.missing.single().target.path.endsWith("17.json"))
        assertTrue(report.warnings.any { it.contains("资源索引缺失") }, report.warnings.toString())
        // client jar + 索引 + 库，没有资源对象
        assertEquals(3, report.totalFiles)
    }

    @Test
    fun brokenVersionJsonGivesOneWarning() {
        val root = tempRoot()
        val versionDir = File(root, "versions/1.21.1")
        versionDir.mkdirs()
        File(versionDir, "1.21.1.json").writeText("{ 这不是 json", Charsets.UTF_8)

        val report = InstanceIntegrity.inspect(instanceOf(versionDir))
        assertEquals(0, report.totalFiles)
        assertEquals(1, report.warnings.size)
        assertTrue(report.warnings.single().contains("找不到版本 json"))
        assertEquals("没有可对照的清单", report.summary)
    }

    @Test
    fun librariesForOtherPlatformsAreIgnored() {
        val root = tempRoot()
        // 挑一个当前平台之外的名字，避免把用例钉死在某一个系统上
        val otherOs = listOf("osx", "windows", "linux").first { it != OsFamily.current().ruleName }
        val extra = """,
                {
                  "name": "org.example:other:1.0",
                  "rules": [{"action": "allow", "os": {"name": "$otherOs"}}],
                  "downloads": {
                    "artifact": {
                      "url": "https://example.invalid/other-1.0.jar",
                      "path": "org/example/other/1.0/other-1.0.jar",
                      "sha1": "0000000000000000000000000000000000000000",
                      "size": 10
                    }
                  }
                }"""
        val versionDir = buildVersion(root, extraLibraries = extra)

        val report = InstanceIntegrity.inspect(instanceOf(versionDir))
        assertTrue(report.healthy, report.summary)
        assertFalse(report.files.any { it.target.path.endsWith("other-1.0.jar") })
    }

    @Test
    fun inheritedVariantUsesParentFilesAndStaysHealthy() {
        val root = tempRoot()
        // 父版本：完整的原版
        buildVersion(root, id = "1.21.1")
        // 子版本：只有 json，inheritsFrom 指父版本（Forge/Fabric 的常见形状）
        val childDir = File(root, "versions/1.21.1-fabric-0.16.0")
        childDir.mkdirs()
        File(childDir, "1.21.1-fabric-0.16.0.json").writeText(
            """{"id":"1.21.1-fabric-0.16.0","inheritsFrom":"1.21.1"}""",
            Charsets.UTF_8,
        )

        val report = InstanceIntegrity.inspect(instanceOf(childDir, id = "1.21.1-fabric-0.16.0"))
        assertTrue(report.healthy, report.summary)
        // 父版本的 client jar 就是子版本要的那份，不该报缺
        assertTrue(report.files.any { it.target.path.endsWith("1.21.1.jar") })
        assertTrue(report.warnings.any { it.contains("使用父版本") }, report.warnings.toString())
    }

    @Test
    fun missingBytesCountsOnlyProblemFiles() {
        val root = tempRoot()
        val versionDir = buildVersion(root)
        File(versionDir, "1.21.1.jar").delete()
        File(root, "libraries/org/example/lib/1.0/lib-1.0.jar").delete()

        val report = InstanceIntegrity.inspect(instanceOf(versionDir))
        assertEquals(2, report.problemFiles)
        assertEquals((jarBytes.size + libBytes.size).toLong(), report.missingBytes)
    }
}
