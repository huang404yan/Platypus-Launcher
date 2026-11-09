package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.instance.DeletionRefusal
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceDeleter
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.instance.formatBytes
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * 实例删除的无头冒烟。
 *
 * 实例页的「删除文件…」背后只有两步：InstanceDeleter.inspect 算计划、InstanceDeleter.delete 动盘。
 * 界面跑不了无头，但这两步都在这里用**真实磁盘**跑一遍，并且走与界面同一条路径
 * （登记用 DesktopServices 装配出来的仓库，计划用 instances.list() 的结果当 knownInstances）。
 *
 * 只由 `gradle :app-desktop:deleteSmoke` 调用，不进发行包。
 *
 * 参数：<临时游戏目录>（默认 dist/_delete-smoke，由 build.gradle.kts 的 -PdeleteDir 决定）
 *
 * 全程只在这个临时目录里造/删文件，不碰任何真实 .minecraft。
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { error("缺少参数：临时游戏目录") })
    var exit = 0

    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS " else "FAIL ") + name + if (detail.isBlank()) "" else " :: " + detail)
        if (!ok) exit = 1
    }

    /** 上一次运行留下的东西先清掉，保证每次都是干净起点（只清本冒烟自己的临时目录）。 */
    fun wipe(target: File) {
        if (!target.exists()) return
        target.walkBottomUp().forEach { it.delete() }
    }

    val jsonText = { id: String ->
        """
        {
          "id": "$id",
          "type": "release",
          "mainClass": "net.minecraft.client.main.Main",
          "assets": "5"
        }
        """.trimIndent()
    }

    /** 造一个「长得像版本目录」的目录：json + client jar + 两个存档文件。 */
    fun makeVersion(id: String, jarBytes: Int, savesBytes: Int): File {
        val versionDir = File(File(dir, "versions"), id)
        versionDir.mkdirs()
        File(versionDir, id + ".json").writeText(jsonText(id))
        if (jarBytes > 0) File(versionDir, id + ".jar").writeBytes(ByteArray(jarBytes) { 7 })
        if (savesBytes > 0) {
            val saves = File(versionDir, "saves")
            File(saves, "world").mkdirs()
            // 每个存档文件一半大小，两个文件拼出 savesBytes。
            File(saves, "level.dat").writeBytes(ByteArray(savesBytes / 2) { 3 })
            File(File(saves, "world"), "region.mca").writeBytes(ByteArray(savesBytes / 2) { 4 })
        }
        return versionDir
    }

    wipe(dir)
    dir.mkdirs()

    val indexFile = JsonInstanceRepository.defaultIndexFile(dir)
    println("delete smoke root = " + dir.absolutePath)
    println("index file        = " + indexFile.absolutePath)

    runBlocking {
        val backend = DesktopServices.create(dir)

        // ────────────────────────────────────────────────────────────────
        // ① 正常删除：造目录 → inspect 数字对 → delete（不删存档）→ 目录没了存档还在
        // ────────────────────────────────────────────────────────────────
        val jarBytes = 4096
        val savesBytes = 1024
        val versionDir = makeVersion("1.20.1", jarBytes = jarBytes, savesBytes = savesBytes)
        val expectedJson = File(versionDir, "1.20.1.json").length()
        val expectedFiles = 4 // json + jar + level.dat + region.mca
        val expectedBytes = expectedJson + jarBytes + savesBytes

        backend.instances.save(
            Instance(
                id = "1.20.1",
                name = "删除冒烟 1.20.1",
                gameVersion = "1.20.1",
                directory = versionDir.absolutePath,
                memoryMb = 2048,
                registered = true,
            ),
        )
        val registered = backend.instances.list()
        check("register instance", registered.size == 1, registered.joinToString { it.id })

        // 界面上传的就是这份列表，所以这里也传它，走的判断与界面一模一样。
        val plan = InstanceDeleter.inspect(dir, "1.20.1", knownInstances = registered)
        check("plan is deletable", plan.deletable, plan.refusalReason)
        check("plan path", plan.versionDir.absolutePath == versionDir.absolutePath, plan.versionDir.absolutePath)
        check("plan fileCount", plan.fileCount == expectedFiles, plan.fileCount.toString() + " vs " + expectedFiles)
        check("plan totalBytes", plan.totalBytes == expectedBytes, plan.totalBytes.toString() + " vs " + expectedBytes)
        check("plan hasSaves", plan.hasSaves, plan.savesFileCount.toString() + " files")
        check("plan savesBytes", plan.savesBytes == savesBytes.toLong(), plan.savesBytes.toString())
        check("plan no mods", !plan.hasMods)

        val outcome = InstanceDeleter.delete(plan, includeSaves = false)
        check("delete ok", outcome.ok, outcome.summary)
        check("version dir gone", !versionDir.exists(), versionDir.absolutePath)
        check("deletedFiles counted", outcome.deletedFiles == expectedFiles - 2, outcome.deletedFiles.toString())
        // jar + json 留下来才算「释放了字节」；存档被搬走，不算释放。
        check(
            "freedBytes = jar+json",
            outcome.freedBytes == expectedJson + jarBytes,
            outcome.freedBytes.toString() + " vs " + (expectedJson + jarBytes),
        )
        check("savesKept flag", outcome.savesKept)
        val movedSaves = outcome.savesMovedTo
        check("saves relocated", movedSaves != null && movedSaves.isDirectory, movedSaves?.absolutePath.orEmpty())
        check(
            "saves path under platypus/saves-backup",
            movedSaves != null &&
                movedSaves.absolutePath.replace('\\', '/').contains("/platypus/saves-backup/"),
            movedSaves?.absolutePath.orEmpty(),
        )
        check(
            "saves content intact",
            movedSaves != null &&
                File(movedSaves, "level.dat").length() == (savesBytes / 2).toLong() &&
                File(File(movedSaves, "world"), "region.mca").length() == (savesBytes / 2).toLong(),
            "level.dat=" + (movedSaves?.let { File(it, "level.dat").length() } ?: -1),
        )
        // 登记还在（删除动作本身不写 instances.json，界面负责移除登记），这一条只记录事实。
        check("registration untouched by core delete", backend.instances.list().size == 1)
        backend.instances.remove("1.20.1")
        check("registration removed", backend.instances.list().isEmpty())

        // ────────────────────────────────────────────────────────────────
        // ② 目录不存在
        // ────────────────────────────────────────────────────────────────
        val missing = InstanceDeleter.inspect(dir, "9.9.9")
        check(
            "missing dir refused",
            missing.refusal == DeletionRefusal.VERSION_DIR_MISSING,
            missing.refusal?.name.orEmpty(),
        )
        check("missing dir not deletable", !missing.deletable)
        val missingOutcome = InstanceDeleter.delete(missing)
        check("delete on refused plan fails", !missingOutcome.ok, missingOutcome.summary)

        // ────────────────────────────────────────────────────────────────
        // ③ 多实例共用同一目录：拒绝，且不动磁盘
        // ────────────────────────────────────────────────────────────────
        val sharedDir = makeVersion("1.19.4", jarBytes = 2048, savesBytes = 0)
        // 走仓库是写不进「两条同目录记录」的：JsonInstanceRepository 按 instanceKey（目录）去重，
        // 第二次 save 会覆盖第一条。这本身是对的，但也意味着共用只能来自手改过的 json 或旧数据，
        // 所以这里直接手写 instances.json 造出那个状态——inspect 读盘那条路径也顺带被覆盖到。
        backend.instances.save(
            Instance(
                id = "1.19.4",
                name = "原实例",
                gameVersion = "1.19.4",
                directory = sharedDir.absolutePath,
                memoryMb = 2048,
                registered = true,
            ),
        )
        backend.instances.save(
            Instance(
                id = "1.19.4-副本",
                name = "副本实例",
                gameVersion = "1.19.4",
                directory = sharedDir.absolutePath,
                memoryMb = 2048,
                registered = true,
            ),
        )
        check(
            "repo dedups same directory",
            backend.instances.list().size == 1,
            backend.instances.list().joinToString { it.id },
        )
        indexFile.parentFile?.mkdirs()
        // Windows 路径里的反斜杠直接写进 JSON 会被当成转义符，统一换成正斜杠再写。
        val sharedJsonPath = sharedDir.absolutePath.replace('\\', '/')
        indexFile.writeText(
            """
            [
              {"id":"1.19.4","name":"原实例","gameVersion":"1.19.4",
               "directory":"$sharedJsonPath","memoryMb":2048,"registered":true},
              {"id":"1.19.4-副本","name":"副本实例","gameVersion":"1.19.4",
               "directory":"$sharedJsonPath","memoryMb":2048,"registered":true}
            ]
            """.trimIndent(),
        )
        val sharedList = backend.instances.list()
        check("hand-written index has two records", sharedList.size == 2, sharedList.joinToString { it.id })

        // 不传 knownInstances，让 inspect 自己读盘（与界面不同的一条路径，也要覆盖）。
        val sharedPlan = InstanceDeleter.inspect(dir, "1.19.4")
        check(
            "shared refused",
            sharedPlan.refusal == DeletionRefusal.SHARED_BY_INSTANCES,
            sharedPlan.refusal?.name.orEmpty(),
        )
        check("shared lists the other one", sharedPlan.sharedWith.size == 1, sharedPlan.sharedWith.joinToString())
        check("shared reason mentions 移除", sharedPlan.refusalReason.contains("移除"), sharedPlan.refusalReason)
        val sharedOutcome = InstanceDeleter.delete(sharedPlan)
        check("shared delete refused", !sharedOutcome.ok, sharedOutcome.summary)
        check("shared dir still there", sharedDir.isDirectory, sharedDir.absolutePath)

        // 自己那条记录不算「共用」：只剩一条登记时必须能删。
        backend.instances.remove("1.19.4-副本")
        val soloList = backend.instances.list()
        val soloPlan = InstanceDeleter.inspect(dir, "1.19.4", knownInstances = soloList)
        check("self record is not sharing", soloPlan.deletable, soloPlan.refusalReason)
        val soloOutcome = InstanceDeleter.delete(soloPlan, includeSaves = true)
        check("solo delete ok", soloOutcome.ok, soloOutcome.summary)
        check("solo dir gone", !sharedDir.exists(), sharedDir.absolutePath)
        backend.instances.remove("1.19.4")

        // ────────────────────────────────────────────────────────────────
        // ④ 越界：id 里带 ../，绝不能删到 versions/ 之外
        // ────────────────────────────────────────────────────────────────
        val precious = File(dir, "precious.txt")
        precious.writeText("不许被删")
        val escape = InstanceDeleter.inspect(dir, "../precious.txt")
        check("traversal refused", escape.refusal == DeletionRefusal.OUTSIDE_GAME_ROOT, escape.refusal?.name.orEmpty())
        check("traversal not deletable", !escape.deletable)
        val escapeOutcome = InstanceDeleter.delete(escape)
        check("traversal delete refused", !escapeOutcome.ok)
        check("precious file untouched", precious.isFile && precious.readText() == "不许被删", precious.absolutePath)

        // 绝对路径当 id：Java 的 File(parent, child) 会把绝对 child 折回 parent 之下，
        // 于是落在 versions/ 里、指向一个不存在的目录，被当成「目录不存在」拒绝。
        // 拒绝的方式不同，但结论一致：谁都删不掉 versions/ 之外的东西。
        val absolute = InstanceDeleter.inspect(dir, precious.absolutePath)
        check("absolute id refused", !absolute.deletable, absolute.refusal?.name.orEmpty())
        check(
            "absolute id never escapes versions/",
            absolute.versionDir.absolutePath.startsWith(
                File(dir, "versions").absolutePath,
                ignoreCase = true,
            ),
            absolute.versionDir.absolutePath,
        )

        // ────────────────────────────────────────────────────────────────
        // ⑤ 连存档一起删：目录连 saves 一起没了
        // ────────────────────────────────────────────────────────────────
        val withSaves = makeVersion("1.18.2", jarBytes = 1024, savesBytes = 2048)
        val withSavesPlan = InstanceDeleter.inspect(dir, "1.18.2")
        check("second plan hasSaves", withSavesPlan.hasSaves)
        val withSavesOutcome = InstanceDeleter.delete(withSavesPlan, includeSaves = true)
        check(
            "delete with saves ok",
            withSavesOutcome.ok,
            withSavesOutcome.summary,
        )
        check("dir and saves gone", !withSaves.exists(), withSaves.absolutePath)
        check("no saves moved", withSavesOutcome.savesMovedTo == null && !withSavesOutcome.savesKept)

        // ────────────────────────────────────────────────────────────────
        // ⑥ 改名（本地回收站模式）：只改名，不递归删
        // ────────────────────────────────────────────────────────────────
        val recycled = makeVersion("1.17.1", jarBytes = 512, savesBytes = 0)
        val recyclePlan = InstanceDeleter.inspect(dir, "1.17.1")
        val recycleOutcome = InstanceDeleter.delete(recyclePlan, includeSaves = true, moveToRecycleBin = true)
        check("recycle ok", recycleOutcome.ok, recycleOutcome.summary)
        check("recycle renamed", !recycled.exists() && recycleOutcome.renamedTo?.isDirectory == true,
            recycleOutcome.renamedTo?.absolutePath.orEmpty())
        check(
            "recycle name prefix",
            recycleOutcome.renamedTo?.name?.startsWith("1.17.1.deleting-") == true,
            recycleOutcome.renamedTo?.name.orEmpty(),
        )
        check("recycle deleted nothing", recycleOutcome.deletedFiles == 0, recycleOutcome.deletedFiles.toString())

        // ────────────────────────────────────────────────────────────────
        // ⑦ 重新扫描：删掉的版本不再出现在实例列表里
        // ────────────────────────────────────────────────────────────────
        val after = io.github.huang404yan.platypus.core.instance.InstanceScanner.scan(dir)
        check(
            "scanner no longer sees deleted versions",
            after.instances.none { it.id == "1.20.1" || it.id == "1.18.2" },
            after.instances.joinToString { it.id },
        )
        println("formatBytes sample: " + formatBytes(5L * 1024 * 1024) + " / " + formatBytes(0L))
    }

    println(if (exit == 0) "DELETE SMOKE OK" else "DELETE SMOKE FAILED")
    exitProcess(exit)
}
