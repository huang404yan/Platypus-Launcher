package io.github.huang404yan.platypus.core.lan

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 陶瓦协议层的离线测试：房间码、状态解析、文案、目录识别。
 *
 * 真实链路（起进程、打洞、真连上）没法在单测里跑 —— 那要两个人和两个网络。
 * 所以这里只钉住「错一个字符的码必须被挡下」「认不出的阶段不能崩」这类"能离线证明"的部分。
 */
class TerracottaTest {

    private val root: File = Files.createTempDirectory("platypus-terracotta-test").toFile()

    @AfterTest
    fun tearDown() {
        runCatching { root.deleteRecursively() }
    }

    // ── 房间码 ─────────────────────────────────────────────────────────────────

    @Test
    fun `房间码归一化把空格与大小写与易混字符都处理掉`() {
        val code = "U/ABCD-EFGH-JKLM-NPQR"
        assertEquals(code, Terracotta.normalizeCode(code))
        assertEquals(code, Terracotta.normalizeCode("  u/abcd-efgh-jklm-npqr  "))
        assertEquals(code, Terracotta.normalizeCode("U/ ABCD-EFGH-JKLM-NPQR"))
    }

    @Test
    fun `抄错 0 与 O、1 与 I 也能认回来`() {
        val wrong = "U/OABC-DEFG-HJKL-MNPQ"
        val fixed = Terracotta.normalizeCode(wrong)
        assertTrue(fixed.contains("0"), "O 应该被当成 0：" + fixed)
        val wrongOne = "U/IABC-DEFG-HJKL-MNPQ"
        assertTrue(Terracotta.normalizeCode(wrongOne).contains("1"))
    }

    @Test
    fun `码里夹着别的字时从 U 斜杠开始截`() {
        val code = Terracotta.normalizeCode("房间码：U/ABCD-EFGH-JKLM-NPQR 谢谢")
        assertEquals(21, code.length)
        assertTrue(code.startsWith("U/"))
    }

    @Test
    fun `认不出的码返回空串而不是抛`() {
        assertEquals("", Terracotta.normalizeCode(null))
        assertEquals("", Terracotta.normalizeCode(""))
        assertEquals("", Terracotta.normalizeCode("hello"))
        assertEquals("", Terracotta.normalizeCode("U/ABC"))
    }

    @Test
    fun `校验位不对的码会被挡下`() {
        // 逐位改一个字符，34 进制取模 7 几乎一定变，这条能挡住「抄错一个字符」。
        val body = "ABCDEFGHJKLMNPQR"
        var accepted = 0
        for (i in body.indices) {
            val chars = body.toCharArray()
            chars[i] = if (chars[i] == '0') '1' else '0'
            val candidate = "U/" + String(chars).chunked(4).joinToString("-")
            if (Terracotta.checkCode(candidate)) accepted++
        }
        // 34 进制里换一位，命中概率约 1/7；17 位全换还全中的概率低到可以当"实现错了"处理。
        assertTrue(accepted <= 8, "改一位还能全过的太多了（" + accepted + " 个），校验位多半没起作用")
    }

    @Test
    fun `格式错与校验错的提示不一样`() {
        assertTrue(Terracotta.codeFormatProblem().contains("21"))
        assertTrue(Terracotta.codeChecksumProblem().contains("校验位"))
    }

    // ── 状态解析 ───────────────────────────────────────────────────────────────

    @Test
    fun `解析建房成功的状态`() {
        val text = "{\"state\":\"host-ok\",\"room\":\"U/ABCD-EFGH-JKLM-NPQR\"}"
        val state = Terracotta.parseState(text)
        assertTrue(state.ok)
        assertEquals(TerracottaStages.HOST_OK, state.stage)
        assertEquals("U/ABCD-EFGH-JKLM-NPQR", state.room)
        assertTrue(state.isHost)
        assertTrue(state.hasHandshake)
    }

    @Test
    fun `解析游客连上后的直连地址与难度`() {
        val text = "{\"state\":\"guest-ok\",\"url\":\"127.0.0.1:25565\",\"difficulty\":\"MEDIUM\"}"
        val state = Terracotta.parseState(text)
        assertTrue(state.ok)
        assertEquals("127.0.0.1:25565", state.url)
        assertEquals("MEDIUM", state.difficulty)
        assertFalse(state.isHost)
    }

    @Test
    fun `解析失败状态里的错误类型`() {
        val text = "{\"state\":\"exception\",\"room\":\"U/ABCD-EFGH-JKLM-NPQR\",\"type\":4}"
        val state = Terracotta.parseState(text)
        assertEquals(4, state.exceptionType)
        assertTrue(Terracotta.statusText(state).contains("游戏已关闭"))
    }

    @Test
    fun `不是 JSON 或字段缺失时返回空状态而不是抛`() {
        assertFalse(Terracotta.parseState("").ok)
        assertFalse(Terracotta.parseState("not json").ok)
        assertFalse(Terracotta.parseState("[]").ok)
        val partial = Terracotta.parseState("{\"state\":\"waiting\"}")
        assertTrue(partial.ok)
        assertEquals("", partial.room)
        assertEquals(TerracottaState.UNKNOWN_TYPE, partial.exceptionType)
    }

    @Test
    fun `空异常不算真失败`() {
        // 守护进程刚被拉起时会先报一个既没房间码也没地址的 exception，那不是失败。
        val state = TerracottaState(ok = true, stage = TerracottaStages.EXCEPTION)
        val text = Terracotta.statusText(state)
        assertTrue(text.contains("还没有房间"), "实际：" + text)
        assertFalse(text.contains("失败"), "实际：" + text)
    }

    @Test
    fun `没启动时说的是未运行而不是失败`() {
        val text = Terracotta.statusText(TerracottaState())
        assertTrue(text.contains("未运行"))
    }

    @Test
    fun `断网时房间已就绪会多一句提醒`() {
        val state = TerracottaState(ok = true, stage = TerracottaStages.HOST_OK, room = "U/ABCD-EFGH-JKLM-NPQR")
        val online = Terracotta.statusText(state, networkAvailable = true)
        val offline = Terracotta.statusText(state, networkAvailable = false)
        assertFalse(online.contains("网络不可用"))
        assertTrue(offline.contains("网络不可用"))
        assertTrue(offline.contains("U/ABCD-EFGH-JKLM-NPQR"))
    }

    // ── 端口文件 ───────────────────────────────────────────────────────────────

    @Test
    fun `从端口文件里读出端口`() {
        assertEquals(12345, Terracotta.parsePort("{\"port\":12345}"))
        assertEquals(12345, Terracotta.parsePort("{\"port\": 12345, \"x\":1}"))
        assertNull(Terracotta.parsePort(""))
        assertNull(Terracotta.parsePort("{}"))
        assertNull(Terracotta.parsePort("{\"port\":0}"))
        assertNull(Terracotta.parsePort("{\"port\":70000}"))
    }

    // ── 文案对照 ───────────────────────────────────────────────────────────────

    @Test
    fun `八个阶段都有中文说法`() {
        assertEquals("空闲", Terracotta.stageText(TerracottaStages.WAITING))
        assertEquals("房间已就绪", Terracotta.stageText(TerracottaStages.HOST_OK))
        assertEquals("已连上", Terracotta.stageText(TerracottaStages.GUEST_OK))
        assertEquals("失败", Terracotta.stageText(TerracottaStages.EXCEPTION))
        assertEquals("未运行", Terracotta.stageText(""))
        // 陶瓦以后加了新阶段，界面要原样显示而不是崩
        assertEquals("brand-new-stage", Terracotta.stageText("brand-new-stage"))
    }

    @Test
    fun `错误类型 0 到 5 都有说法`() {
        for (type in 0..5) {
            val text = Terracotta.exceptionText(type)
            assertTrue(text.isNotBlank())
            assertTrue(text.contains("。"), "第 " + type + " 条不像一句完整的话：" + text)
        }
        assertTrue(Terracotta.exceptionText(99).contains("未知"))
    }

    @Test
    fun `打洞难度翻成人话`() {
        assertEquals("网络极佳", Terracotta.difficultyText("EASIEST"))
        assertTrue(Terracotta.difficultyText("TOUGH").contains("连不上"))
        assertTrue(Terracotta.difficultyText("").contains("评估"))
    }

    // ── 目录与安装判据 ─────────────────────────────────────────────────────────

    @Test
    fun `目录里没有 exe 就是没装`() {
        assertFalse(Terracotta.isInstalled(root))
        assertNull(Terracotta.exeOf(root))
        File(root, "readme.txt").writeText("x")
        assertFalse(Terracotta.isInstalled(root))
    }

    @Test
    fun `认得出目录里的 terracotta 可执行文件`() {
        File(root, "terracotta.exe").writeText("x")
        assertTrue(Terracotta.isInstalled(root))
        assertEquals("terracotta.exe", Terracotta.exeOf(root)?.name)
    }

    @Test
    fun `带版本号的文件名也认`() {
        File(root, "terracotta-0.4.2-windows-x86_64.exe").writeText("x")
        assertTrue(Terracotta.isInstalled(root))
    }

    @Test
    fun `程序目录挂在游戏根目录的 platypus 下`() {
        assertEquals(File(File(root, "platypus"), "terracotta"), Terracotta.dirOf(root))
    }

    @Test
    fun `下载地址只剩官方一条，指向内置版本`() {
        val urls = Terracotta.downloadUrls()
        assertEquals(1, urls.size, "2026-11-16 起去掉 cnb.cool / gitee 两个转存，只留官方：$urls")
        val url = urls.single()
        assertTrue(url.startsWith("https://github.com/burningtnt/Terracotta/releases/download/"), url)
        assertTrue(url.contains(Terracotta.VERSION), "版本号不在地址里：" + url)
        assertTrue(url.endsWith(Terracotta.packageName()), "文件名不对：" + url)
    }

    @Test
    fun `可以按别的版本号拼下载地址`() {
        val url = Terracotta.downloadUrls("9.9.9").single()
        assertTrue(url.contains("v9.9.9/"), url)
        assertTrue(url.endsWith("terracotta-9.9.9-windows-x86_64-pkg.tar.gz"), url)
    }

    @Test
    fun `packageName 跟着版本号走`() {
        assertEquals("terracotta-" + Terracotta.VERSION + "-windows-x86_64-pkg.tar.gz", Terracotta.packageName())
    }

    @Test
    fun `校验值是 128 位十六进制`() {
        assertEquals(128, Terracotta.PACKAGE_SHA512.length)
        assertTrue(Terracotta.PACKAGE_SHA512.all { it in "0123456789abcdef" })
    }

    // ── 建房 / 加入的请求路径（2026-11-15 真实链路实测后补） ──────────────

    @Test
    fun `加入房间时房间码里的斜杠必须转义`() {
        val client = TerracottaClient(File(root, "terracotta"))
        val path = client.guestPathOf("U/ABCD-EFGH-JKLM-NPQR")
        assertEquals("/state/guesting?room=U%2FABCD-EFGH-JKLM-NPQR", path)
        assertFalse(path.contains("U/"), "斜杠没转义，陶瓦会回 400：")
    }

    @Test
    fun `建房请求在没给房间码时不带查询串`() {
        val client = TerracottaClient(File(root, "terracotta"))
        assertEquals("/state/scanning", client.hostPathOf(""))
        assertEquals("/state/scanning", client.hostPathOf("   "))
        assertEquals("/state/scanning?room=U%2FABCD-EFGH-JKLM-NPQR", client.hostPathOf("U/ABCD-EFGH-JKLM-NPQR"))
    }
}