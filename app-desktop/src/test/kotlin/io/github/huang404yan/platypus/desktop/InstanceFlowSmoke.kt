package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.instance.LocalInstance
import io.github.huang404yan.platypus.core.instance.mergeInstances
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * 实例页背后的数据链路无头冒烟。
 *
 * 界面本身跑不了无头，但实例页每个按钮背后其实只有四五步：认养/新建 → 写登记 → 扫描 → 合并
 * → 改内存 → 移除登记。这些都在 [DesktopServices] 装配出来的真实仓库上跑一遍，
 * 走的是与界面完全相同的那条路径（界面里也是调 backend.instances.save）。
 *
 * 只由 `gradle :app-desktop:instanceFlowSmoke` 调用，不进发行包。
 *
 * 参数：<临时游戏目录> [真实游戏目录]
 *
 * 给了第二个参数时，额外在那个目录上做一次「只读认养」：只调 LocalInstance.adopt，
 * 不写任何文件。用真实的 1.20.1 目录验证一次，比只用造出来的假 json 更有说服力。
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { error("缺少参数：临时游戏目录") })
    var exit = 0

    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS " else "FAIL ") + name + if (detail.isBlank()) "" else " :: " + detail)
        if (!ok) exit = 1
    }

    val indexFile = JsonInstanceRepository.defaultIndexFile(dir)

    /** 造一个最小可用版本：versions/<id>/<id>.json + <id>.jar。 */
    fun makeVersion(id: String, jarBytes: Int = 16) {
        val versionDir = File(File(dir, "versions"), id)
        versionDir.mkdirs()
        File(versionDir, id + ".json").writeText(
            """
            {
              "id": "$id",
              "type": "release",
              "mainClass": "net.minecraft.client.main.Main",
              "assets": "5",
              "downloads": { "client": { "sha1": "x", "size": $jarBytes, "url": "https://example.invalid/c.jar" } }
            }
            """.trimIndent(),
        )
        if (jarBytes > 0) File(versionDir, id + ".jar").writeBytes(ByteArray(jarBytes) { 1 })
    }

    dir.mkdirs()
    makeVersion("1.20.1")
    makeVersion("1.19.4", jarBytes = 0)

    runBlocking {
        val backend = DesktopServices.create(dir)

        // ① 「本地已有就别再下载」：认养出来的实例必须是已登记、内存按传入值、目录指回版本目录
        val adopted = LocalInstance.adopt(dir, "1.20.1", name = "我的 1.20.1", memoryMb = 3072)
        check("adopt existing version", adopted != null)
        if (adopted == null) {
            println("INSTANCE FLOW SMOKE FAILED")
            exitProcess(1)
        }
        check("adopted registered", adopted.registered)
        check("adopted memory", adopted.memoryMb == 3072, adopted.memoryMb.toString())
        check(
            "adopted directory",
            adopted.directory == File(File(dir, "versions"), "1.20.1").absolutePath,
            adopted.directory,
        )

        // ② 写登记落盘到 <gameRoot>/platypus/instances.json
        backend.instances.save(adopted)
        check("index file written", indexFile.isFile, indexFile.absolutePath)

        val listed = backend.instances.list()
        check("listed after save", listed.size == 1, listed.size.toString())
        check("listed name", listed.firstOrNull()?.name == "我的 1.20.1", listed.firstOrNull()?.name.orEmpty())

        // ③ 目录扫描能看见同一个版本，合并后只有一条且是「已登记」
        val scan = InstanceScanner.scan(dir)
        check("scan sees version", scan.instances.any { it.id == "1.20.1" }, scan.instances.joinToString { it.id })
        val merged = mergeInstances(listed, scan.instances)
        // 扫描会看见 1.20.1 与 1.19.4 两个版本；1.20.1 是同一条被合并掉，所以是 2 条不是 3 条。
        check("merge dedup by directory", merged.size == 2, merged.joinToString { it.name + "/" + it.registered })
        val mergedTarget = merged.firstOrNull { it.id == "1.20.1" }
        check("merge keeps registered", mergedTarget?.registered == true, mergedTarget?.name.orEmpty())
        check("merge keeps edited name", mergedTarget?.name == "我的 1.20.1", mergedTarget?.name.orEmpty())

        // ④ 改内存：与实例页「改内存」按钮同一次 save
        val target = merged.first { it.id == "1.20.1" }
        backend.instances.save(target.copy(memoryMb = Instance.clampMemoryMb(8192)))
        val afterEdit = DesktopServices.create(dir).instances.list().firstOrNull()
        check("reopen after memory edit", afterEdit?.memoryMb == 8192, afterEdit?.memoryMb?.toString().orEmpty())
        check("reopen keeps name", afterEdit?.name == "我的 1.20.1", afterEdit?.name.orEmpty())

        // ⑤ 越界内存被夹住，不会写成 999999
        backend.instances.save(target.copy(memoryMb = 999999))
        val clamped = DesktopServices.create(dir).instances.list().firstOrNull()
        check("clamp on save", clamped?.memoryMb == Instance.MAX_MEMORY_MB, clamped?.memoryMb?.toString().orEmpty())
        backend.instances.save(target.copy(memoryMb = 8192))

        // ⑥ 空 jar 的版本不许被认养（否则用户点启动才发现缺文件）
        val broken = LocalInstance.adopt(dir, "1.19.4")
        check("reject empty jar", broken == null, broken?.id.orEmpty())

        // ⑦ 移除登记：只删登记，不删文件，扫描仍看得见
        backend.instances.remove(adopted.id)
        check("remove drops registration", backend.instances.list().isEmpty())
        check(
            "remove keeps files",
            File(File(File(dir, "versions"), "1.20.1"), "1.20.1.jar").isFile,
            "版本文件必须留着，否则「移除登记」就是删除版本了",
        )
        val rescanned = InstanceScanner.scan(dir)
        check("still scannable after remove", rescanned.instances.any { it.id == "1.20.1" })
        val mergedAfter = mergeInstances(backend.instances.list(), rescanned.instances)
        check("merged as scanned-only", mergedAfter.all { !it.registered })
    }

    // ⑧ 真实目录只读验证：不写盘，只证明「本地已有就不该下载」在真版本上也成立
    val realRoot = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::File)
    if (realRoot != null) {
        val probe = File(File(realRoot, "versions"), "1.20.1")
        if (!probe.isDirectory) {
            println("SKIP real directory has no versions/1.20.1 :: " + realRoot.absolutePath)
        } else {
            val real = LocalInstance.adopt(realRoot, "1.20.1", name = "真实 1.20.1", memoryMb = 4096)
            check("adopt real 1.20.1", real != null, realRoot.absolutePath)
            if (real != null) {
                check("real gameVersion", real.gameVersion == "1.20.1", real.gameVersion)
                check("real directory", real.directory == probe.absolutePath, real.directory)
                check("real memory", real.memoryMb == 4096, real.memoryMb.toString())
                check(
                    "real client jar exists",
                    File(real.directory, "1.20.1.jar").isFile,
                    File(real.directory, "1.20.1.jar").absolutePath,
                )
            }
        }
    }

    println(if (exit == 0) "INSTANCE FLOW SMOKE OK" else "INSTANCE FLOW SMOKE FAILED")
    exitProcess(exit)
}
