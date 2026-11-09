package io.github.huang404yan.platypus.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OfflineUsernameTest {

    @Test
    fun validNamesPass() {
        listOf("Dev", "Steve", "Player_1", "____", "abc", "a".repeat(16)).forEach { name ->
            assertNull(usernameProblem(name), "应通过：$name")
        }
    }

    @Test
    fun tooShortOrTooLongIsRejected() {
        assertNotNull(usernameProblem("ab"))
        assertNotNull(usernameProblem("a".repeat(17)))
        // 长度按去空白后算
        assertNotNull(usernameProblem("  ab  "))
    }

    @Test
    fun illegalCharactersAreRejected() {
        // 首尾空白不算非法：校验前会 trim，存下去的就是去掉空白后的名字
        assertNull(usernameProblem("Dev "))
        assertNotNull(usernameProblem("Dev Player"))
        assertNotNull(usernameProblem("玩家"))
        assertNotNull(usernameProblem("steve-1"))
        assertNotNull(usernameProblem("steve.1"))
    }

    @Test
    fun emptyFallsBackToDefaultName() {
        assertEquals("Dev", normalizeOfflineUsername(""))
        assertEquals("Dev", normalizeOfflineUsername("   "))
        assertEquals("Steve", normalizeOfflineUsername("  Steve  "))
        assertEquals("用户名不能为空。", usernameProblem(""))
    }
}
