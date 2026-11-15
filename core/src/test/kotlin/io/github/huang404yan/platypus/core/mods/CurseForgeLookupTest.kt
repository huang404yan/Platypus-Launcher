package io.github.huang404yan.platypus.core.mods

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurseForgeLookupTest {

    @Test
    fun `请求体里的指纹是数字不是字符串`() {
        val body = CurseForgeLookup.requestBody(listOf(1540447798L, 626045324L))
        assertEquals("{\"fingerprints\":[1540447798,626045324]}", body)
    }

    @Test
    fun `画出三个字段就把结果对回本地指纹`() {
        val text = response(
            "{\"modId\":394468,\"id\":5123456,\"file\":{\"fileFingerprint\":3376380438}}",
            "{\"modId\":238222,\"id\":998877,\"file\":{\"fileFingerprint\":626045324}}",
        )
        val parsed = CurseForgeLookup.parseMatches(text)
        assertEquals(2, parsed.size, parsed.toString())
        assertEquals(394468 to 5123456, parsed[3376380438L])
        assertEquals(238222 to 998877, parsed[626045324L])
    }

    @Test
    fun `少字段的条目直接跳过`() {
        val text = response(
            "{\"id\":5123456,\"file\":{\"fileFingerprint\":1}}",
            "{\"modId\":1,\"file\":{\"fileFingerprint\":2}}",
            "{\"modId\":1,\"id\":2}",
            "{\"modId\":1,\"id\":2,\"file\":{\"fileFingerprint\":777}}",
        )
        val parsed = CurseForgeLookup.parseMatches(text)
        assertEquals(setOf(777L), parsed.keys, parsed.toString())
    }

    @Test
    fun `一个都没认出来是空表而不是报错`() {
        assertEquals(emptyMap(), CurseForgeLookup.parseMatches(response()))
    }

    @Test
    fun `响应不是 JSON 或不是对象时返回空表`() {
        assertEquals(emptyMap(), CurseForgeLookup.parseMatches("not json"))
        assertEquals(emptyMap(), CurseForgeLookup.parseMatches("[]"))
        assertEquals(emptyMap(), CurseForgeLookup.parseMatches("{\"data\":[]}"))
    }

    @Test
    fun `没填 Key 时不发请求而是直接说清楚`() {
        val error = runCatching {
            runBlocking { CurseForgeLookup.lookup("", listOf(123L)) }
        }.exceptionOrNull()
        assertTrue(error is IOException, error.toString())
        assertTrue(error!!.message!!.contains("API Key"), error.message!!)
    }

    @Test
    fun `指纹是空的就不用发请求`() = runBlocking {
        assertEquals(emptyMap(), CurseForgeLookup.lookup("a-valid-looking-api-key-0000", emptyList()))
    }

    @Test
    fun `接口地址与游戏 ID 与官方一致`() {
        assertEquals(432, CurseForgeLookup.GAME_ID)
        assertTrue(CurseForgeLookup.FINGERPRINTS_URL.endsWith("/fingerprints/432"), CurseForgeLookup.FINGERPRINTS_URL)
        assertEquals("x-api-key", CurseForgeLookup.API_KEY_HEADER)
    }

    @Test
    fun `Key 的格式提示只拦明显不对的`() {
        assertNull(CurseForgeLookup.apiKeyProblem(""))
        assertNull(CurseForgeLookup.apiKeyProblem("abcdefghijklmnopqrstuvwxyz012345"))
        assertTrue(CurseForgeLookup.apiKeyProblem("abc")!!.contains("太短"))
        assertTrue(CurseForgeLookup.apiKeyProblem("has space in the middle 1234")!!.contains("空格"))
    }

    private fun response(vararg matches: String): String =
        "{\"data\":{\"exactMatches\":[" + matches.joinToString(",") + "]}}"
}