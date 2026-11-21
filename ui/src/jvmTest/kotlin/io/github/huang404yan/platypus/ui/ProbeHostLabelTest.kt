package io.github.huang404yan.platypus.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ProbeHostLabelTest {

    @Test
    fun mirrorHostsAreCalledMirror() {
        assertEquals(
            "国内镜像",
            probeHostLabel("https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"),
        )
    }

    @Test
    fun mojangHostsAreCalledOfficial() {
        assertEquals("官方源", probeHostLabel("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    }

    @Test
    fun unknownHostsShowTheirName() {
        assertEquals("mirror.example.com", probeHostLabel("https://mirror.example.com/mc/game/version_manifest_v2.json"))
    }

    @Test
    fun blankUrlFallsBackToOfficial() {
        // 空壳包没填地址时这里会是空串；界面上写「官网」比写一个空标签强，
        // 至少让人看出「这一行本来该有个地址」。
        assertEquals("官网", probeHostLabel(""))
    }

    @Test
    fun hostLookalikeIsNotMirror() {
        // 后端用同一套边界判断：这不是镜像，那就照实显示它的主机名，别替它抬成「国内镜像」。
        assertEquals(
            "bmclapi2.bangbang93.com.evil.example.com",
            probeHostLabel("https://bmclapi2.bangbang93.com.evil.example.com/x"),
        )
    }

    @Test
    fun mojangLookalikeIsNotOfficial() {
        assertEquals("evilmojang.com", probeHostLabel("https://evilmojang.com/mc/game/version_manifest_v2.json"))
    }
}