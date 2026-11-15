package io.github.huang404yan.platypus.core.auth

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 应用 ID 到底从哪儿来。
 *
 * 这里只测得到「编译期注入」与「系统属性」两条：环境变量没法在进程内改，
 * 只能靠手工 export 验证（见 docs/57）。这不是遗漏 —— 与其写一个永远测不出
 * 环境变量分支的假测试，不如把这一条局限写清楚。
 */
class ClientIdsTest {

    /** 每个用例前后都把属性清掉：它属于进程，漏一个就会让后面的用例读到上一个的值。 */
    @AfterTest
    fun clearProperty() {
        System.clearProperty(ClientIds.PROPERTY_NAME)
    }

    private fun useProperty(value: String) {
        System.setProperty(ClientIds.PROPERTY_NAME, value)
    }

    @Test
    fun `仓库里的构建不带应用 ID`() {
        assertEquals("", ClientIds.builtin(), "源码仓库里不能有 ID：带了就等于所有人共用一份别人的应用注册")
    }

    @Test
    fun `系统属性给了 ID 就会用上`() {
        useProperty("11111111-2222-3333-4444-555555555555")
        assertEquals("11111111-2222-3333-4444-555555555555", ClientIds.builtin())
    }

    @Test
    fun `属性里的空格与引号会被清掉`() {
        useProperty("  \"11111111-2222-3333-4444-555555555555\"  ")
        assertEquals("11111111-2222-3333-4444-555555555555", ClientIds.builtin(), "从命令行粘过来常带引号，不清掉会原样发给微软")
    }

    @Test
    fun `只有空白的属性等于没给`() {
        useProperty("   ")
        assertEquals("", ClientIds.builtin())
    }

    @Test
    fun `没有内置 ID 时用用户填的那个`() {
        assertEquals("user-id", ClientIds.resolve("  user-id  "))
    }

    @Test
    fun `内置 ID 优先于用户填的`() {
        useProperty("builtin-id")
        assertEquals("builtin-id", ClientIds.resolve("user-id"), "正式版没要求用户填过 ID，用他填的会让登录打到别人的应用上")
    }

    @Test
    fun `认得出 ID 是哪来的`() {
        assertEquals(ClientIds.Source.USER, ClientIds.source("user-id"))
        assertEquals(ClientIds.Source.MISSING, ClientIds.source(""))
        assertEquals(ClientIds.Source.MISSING, ClientIds.source("   "))
        useProperty("builtin-id")
        assertEquals(ClientIds.Source.BUILTIN, ClientIds.source("user-id"))
        assertEquals(ClientIds.Source.BUILTIN, ClientIds.source(""))
    }

    @Test
    fun `一份都没有时才提示缺 ID`() {
        assertNull(ClientIds.missingProblem("user-id"))
        useProperty("builtin-id")
        assertNull(ClientIds.missingProblem(""))
    }

    @Test
    fun `缺 ID 的提示指向设置页`() {
        val problem = ClientIds.missingProblem("")
        assertTrue(problem != null, "一份都没有时必须给出提示")
        assertTrue(problem!!.contains("客户端 ID") || problem.contains("Client ID"), "提示要说出缺的是什么：实际是 " + problem)
        assertTrue(problem.contains("设置"), "提示要告诉用户去哪儿填：实际是 " + problem)
    }

    @Test
    fun `环境变量名与属性名不要随手改`() {
        assertEquals("PLATYPUS_MS_CLIENT_ID", ClientIds.ENV_NAME, "改了之后已经 export 过的机器会突然登录不了")
        assertEquals("platypus.msClientId", ClientIds.PROPERTY_NAME, "Gradle 的 -P 名字与它保持一致，改一处要两处一起改")
    }
}
