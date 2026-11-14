package io.github.huang404yan.platypus.core.install

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerInstallerTest {

    @Test
    fun `eula 文件里写的是同意而不是空文件`() {
        val text = ServerInstaller.eulaText()
        assertTrue(text.contains("eula=true"), "没有 eula=true 服务端照样会退出")
        assertTrue(text.contains("EULA"), "注释里的许可协议地址要留着，用户得知道自己在同意什么")
    }

    @Test
    fun `读到 eula=true 就算已经同意`() {
        assertTrue(ServerInstaller.eulaAgreed(ServerInstaller.eulaText()))
    }

    @Test
    fun `大小写与空格都不影响同意判定`() {
        assertTrue(ServerInstaller.eulaAgreed("EULA = TRUE"))
        assertTrue(ServerInstaller.eulaAgreed("  eula=true  "))
        assertTrue(ServerInstaller.eulaAgreed("level-name=world\neula = True"))
    }

    @Test
    fun `没同意或者只有注释行都算没同意`() {
        assertFalse(ServerInstaller.eulaAgreed("eula=false"))
        assertFalse(ServerInstaller.eulaAgreed("# eula=true"))
        assertFalse(ServerInstaller.eulaAgreed("#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA)."))
        assertFalse(ServerInstaller.eulaAgreed(""))
    }

    @Test
    fun `开服脚本会先切到自己所在的目录`() {
        // 服务端把存档写在工作目录：双击 bat 时工作目录不一定是脚本目录
        val bat = ServerInstaller.startScriptText(isWindows = true, memoryMb = 4096)
        assertTrue(bat.contains("cd /d %~dp0"))
        val sh = ServerInstaller.startScriptText(isWindows = false, memoryMb = 4096)
        assertTrue(sh.contains("dirname"))
    }

    @Test
    fun `开服脚本里的内存就是给的那一档`() {
        val bat = ServerInstaller.startScriptText(isWindows = true, memoryMb = 4096)
        assertTrue(bat.contains("-Xmx4096M"), bat)
        assertTrue(bat.contains("-Xms2048M"), "最小堆取一半，别让服务端一上来就吃掉整份内存")
    }

    @Test
    fun `内存给得太小或太大都会被夹到能用的范围`() {
        assertTrue(ServerInstaller.startScriptText(true, memoryMb = 1).contains("-Xmx512M"))
        assertTrue(ServerInstaller.startScriptText(true, memoryMb = 999_999).contains("-Xmx32768M"))
    }

    @Test
    fun `开服脚本一定是 jar 加 nogui`() {
        val bat = ServerInstaller.startScriptText(isWindows = true, memoryMb = 2048)
        assertTrue(bat.contains("server.jar"))
        assertTrue(bat.contains("nogui"), "不加 nogui 会多弹一个图形界面")
    }

    @Test
    fun `bat 用 CRLF 行尾而 sh 用 LF`() {
        // Windows 的 cmd 读不了纯 LF 的 bat，会把整行拼错
        val bat = ServerInstaller.startScriptText(isWindows = true, memoryMb = 2048)
        assertTrue(bat.contains("\r\n"))
        assertTrue(bat.endsWith("\r\n"))
        val sh = ServerInstaller.startScriptText(isWindows = false, memoryMb = 2048)
        assertFalse(sh.contains("\r"))
        assertTrue(sh.startsWith("#!/bin/sh"))
    }

    @Test
    fun `结束时会停住窗口而不是一闪而过`() {
        val bat = ServerInstaller.startScriptText(isWindows = true, memoryMb = 2048)
        assertTrue(bat.contains("pause"), "双击开服崩了要能看见报错")
    }
}
