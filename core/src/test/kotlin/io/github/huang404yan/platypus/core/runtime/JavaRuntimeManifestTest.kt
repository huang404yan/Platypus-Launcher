package io.github.huang404yan.platypus.core.runtime

import io.github.huang404yan.platypus.core.version.OsFamily
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaRuntimeManifestTest {

    private val indexJson = """
        {
          "gamecore": {"java-runtime-gamma": [{"manifest": {"sha1": "aaaa", "size": 1, "url": "https://example.invalid/gamecore.json"}, "version": {"name": "17.0.15", "released": "2024-05-14"}}]},
          "windows-x64": {
            "java-runtime-gamma": [
              {"manifest": {"sha1": "89ce85ccb518c62e18b4b58d63399ba2d9611426", "size": 148571, "url": "https://launchermeta.mojang.com/v1/products/java-runtime/x/windows-x64/gamma/all.json"}, "version": {"name": "17.0.15", "released": "2024-05-14"}}
            ],
            "jre-legacy": [
              {"manifest": {"sha1": "03828bb8455f3cef94869286a0d941dd16933239", "size": 100, "url": "https://launchermeta.mojang.com/v1/products/java-runtime/x/windows-x64/jre-legacy/all.json"}, "version": {"name": "8u51-cacert462b08", "released": "2020-05-14"}}
            ]
          },
          "linux": {}
        }
    """.trimIndent()

    @Test
    fun parsesIndexAndSkipsEmptyOrOddEntries() {
        val index = parseRuntimeIndex(indexJson)
        assertEquals(setOf("gamecore", "windows-x64"), index.platforms.keys)
        val entries = index.entries("windows-x64")
        assertEquals(2, entries.size)
        val gamma = index.find("windows-x64", "java-runtime-gamma")!!
        assertEquals("java-runtime-gamma", gamma.component)
        assertEquals("17.0.15", gamma.versionName)
        assertEquals(148571L, gamma.manifestSize)
        assertEquals("89ce85ccb518c62e18b4b58d63399ba2d9611426", gamma.manifestSha1)
        // 空的 linux 平台整块丢掉，不产生「平台存在但一个组件都没有」的假象
        assertTrue(index.entries("linux").isEmpty())
        assertNull(index.find("windows-x64", "java-runtime-delta"))
    }

    @Test
    fun mapsPlatformKeys() {
        assertEquals("windows-x64", runtimePlatform(OsFamily.WINDOWS, "amd64"))
        assertEquals("windows-x64", runtimePlatform(OsFamily.WINDOWS, "x86_64"))
        assertEquals("windows-x86", runtimePlatform(OsFamily.WINDOWS, "x86"))
        assertEquals("windows-arm64", runtimePlatform(OsFamily.WINDOWS, "aarch64"))
        assertEquals("mac-os-arm64", runtimePlatform(OsFamily.MACOS, "aarch64"))
        assertEquals("mac-os", runtimePlatform(OsFamily.MACOS, "x86_64"))
        assertEquals("linux", runtimePlatform(OsFamily.LINUX, "amd64"))
        assertEquals("linux-i386", runtimePlatform(OsFamily.LINUX, "i386"))
    }

    @Test
    fun mapsMajorToOfficialComponent() {
        assertEquals("jre-legacy", JavaRuntimeComponents.componentForMajor(8))
        assertEquals("java-runtime-alpha", JavaRuntimeComponents.componentForMajor(16))
        assertEquals("java-runtime-gamma", JavaRuntimeComponents.componentForMajor(17))
        assertEquals("java-runtime-delta", JavaRuntimeComponents.componentForMajor(21))
        assertEquals("java-runtime-epsilon", JavaRuntimeComponents.componentForMajor(25))
        // 9–15 之间 Mojang 没有对应运行时，必须明确返回 null 而不是瞎猜一个
        assertNull(JavaRuntimeComponents.componentForMajor(11))
        assertNull(JavaRuntimeComponents.componentForMajor(15))
    }

    private val manifestJson = """
        {
          "files": {
            "bin": {"type": "directory"},
            "bin/java.exe": {
              "type": "file",
              "downloads": {
                "raw": {"sha1": "1111", "size": 100, "url": "https://example.invalid/java.exe"},
                "lzma": {"sha1": "2222", "size": 50, "url": "https://example.invalid/java.exe.lzma"}
              },
              "executable": true
            },
            "lib/modules": {
              "type": "file",
              "downloads": {
                "raw": {"sha1": "3333", "size": 900, "url": "https://example.invalid/modules"}
              }
            },
            "lib/lzma-only": {
              "type": "file",
              "downloads": {
                "lzma": {"sha1": "4444", "size": 10, "url": "https://example.invalid/lzma-only.lzma"}
              }
            },
            "release": {"type": "file", "downloads": {"raw": {"sha1": "5555", "size": 12, "url": "https://example.invalid/release"}}}
          }
        }
    """.trimIndent()

    @Test
    fun plansRuntimeFromRawDownloadsOnly() {
        val runtimeDir = File("C:/temp/platypus-runtime/gamma")
        val plan = planRuntimeManifest(manifestJson, "java-runtime-gamma", runtimeDir, platform = "windows-x64")

        assertEquals("java-runtime-gamma", plan.component)
        assertEquals("windows-x64", plan.platform)
        // 3 个 raw 文件；只有 lzma 的那条必须被跳过（实测 gamma 清单里确实有这种条目）
        assertEquals(3, plan.files.size)
        assertTrue(plan.files.none { it.target.endsWith("lzma-only") })
        assertEquals(1012L, plan.totalBytes)
        assertEquals(File(File(runtimeDir, "bin"), "java.exe").path, plan.javaExecutable)
        assertTrue(plan.directories.contains(File(runtimeDir, "bin").path))
        // 每个下载项都要带 sha1，否则重下时没法校验
        assertTrue(plan.files.all { !it.sha1.isNullOrBlank() })
    }

    @Test
    fun fallsBackToExecutableFlagWhenJavaPathIsUnusual() {
        val manifest = """
            {"files": {
              "jre/bin/java.exe": {"type": "file", "downloads": {"raw": {"sha1": "1", "size": 1, "url": "https://example.invalid/j"}}, "executable": true}
            }}
        """.trimIndent()
        val runtimeDir = File("C:/temp/platypus-runtime/legacy")
        val plan = planRuntimeManifest(manifest, "jre-legacy", runtimeDir, platform = "windows-x64")
        assertEquals(File(File(File(runtimeDir, "jre"), "bin"), "java.exe").path, plan.javaExecutable)
    }

    // ── 清单结构变了：解析必须明确报错，不能返回一个空规划让上层以为「下完了」 ──

    @Test
    fun rejectsManifestThatIsNotAnObject() {
        val error = assertFailsWith<IllegalArgumentException> {
            planRuntimeManifest("[]", "java-runtime-gamma", File("C:/temp/gamma"), platform = "windows-x64")
        }
        assertEquals("运行时 manifest 不是 JSON 对象", error.message)
    }

    @Test
    fun rejectsManifestWithoutFilesField() {
        // 真实的 manifest 顶层一定有 files；少了它说明结构变了（或者拿到的是别的 json）。
        val error = assertFailsWith<IllegalArgumentException> {
            planRuntimeManifest("""{"downloads": {}}""", "java-runtime-gamma", File("C:/temp/gamma"), platform = "windows-x64")
        }
        assertEquals("运行时 manifest 缺少 files 字段", error.message)
    }

    @Test
    fun rejectsIndexThatIsNotAnObject() {
        val error = assertFailsWith<IllegalArgumentException> { parseRuntimeIndex("[]") }
        assertEquals("运行时索引不是 JSON 对象", error.message)
    }

    @Test
    fun reportsEmptyPlanAsStructuralChange() {
        // 所有条目都只有 lzma、没有 raw 时，规划是空的：这必须被当成清单结构变化挡下来，
        // 否则 installer 会直接跳到「下完了但没找到 java」，用户看到的原因就偏了。
        val onlyLzma = """
            {"files": {"bin/java.exe": {"type": "file", "downloads": {"lzma": {"sha1": "1", "size": 1}}}}}
        """.trimIndent()
        val plan = planRuntimeManifest(onlyLzma, "java-runtime-gamma", File("C:/temp/gamma"), platform = "windows-x64")
        assertTrue(plan.files.isEmpty())
        assertEquals(
            "java-runtime-gamma 的文件清单是空的，清单结构可能变了。",
            runtimePlanProblem("java-runtime-gamma", plan),
        )
        // 规划非空时没有问题可报
        val normal = planRuntimeManifest(manifestJson, "java-runtime-gamma", File("C:/temp/gamma"), platform = "windows-x64")
        assertNull(runtimePlanProblem("java-runtime-gamma", normal))
    }

    @Test
    fun reportsMissingJavaExecutableAfterDownload() {
        val root = Files.createTempDirectory("platypus-runtime-check").toFile()
        try {
            val bin = File(root, "bin")
            bin.mkdirs()
            val javaExe = File(bin, "java.exe")

            // 目录建好了但 java 不在：下完了不等于能启动，必须报出来
            assertEquals(
                "下完了，但没找到 java 可执行文件：" + javaExe.path,
                runtimeJavaProblem(javaExe),
            )

            // 位置不对的 java 不算数：只认规划出来的那一个
            File(root, "java.exe").writeText("not a real java")
            assertEquals(
                "下完了，但没找到 java 可执行文件：" + javaExe.path,
                runtimeJavaProblem(javaExe),
            )

            // java 真的落地了才算装好
            javaExe.writeText("stub")
            assertNull(runtimeJavaProblem(javaExe))
        } finally {
            root.deleteRecursively()
        }
    }
}
