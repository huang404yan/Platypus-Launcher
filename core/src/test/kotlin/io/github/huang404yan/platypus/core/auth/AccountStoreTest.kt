package io.github.huang404yan.platypus.core.auth

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountStoreTest {

    private val dir: File = Files.createTempDirectory("platypus-accounts").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun repo() = JsonAccountRepository(File(dir, "accounts.json"))

    private fun account(uuid: String, name: String, token: String = "rt-" + name) = StoredAccount(
        uuid = uuid,
        name = name,
        refreshToken = token,
    )

    @Test
    fun 存一条能原样读回来() = runBlocking {
        val repo = repo()
        repo.save(account("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Notch"))
        val list = repo.list()
        assertEquals(1, list.size)
        assertEquals("Notch", list[0].name)
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", list[0].uuid)
        assertEquals("rt-Notch", list[0].refreshToken)
        assertTrue(list[0].createdAt > 0)
    }

    @Test
    fun 同一个账号再登录一次不会长出两条() = runBlocking {
        val repo = repo()
        repo.save(account("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Notch", "old"))
        repo.save(account("069a79f444e94726a5befca90e38aaf5", "Notch", "new"))
        val list = repo.list()
        assertEquals(1, list.size)
        assertEquals("new", list[0].refreshToken)
    }

    @Test
    fun 不同账号各存一份() = runBlocking {
        val repo = repo()
        repo.save(account("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Notch"))
        repo.save(account("853c80ef3c3749fdaa49938b674adae6", "jeb_"))
        assertEquals(listOf("Notch", "jeb_"), repo.list().map { it.name })
    }

    @Test
    fun 删除只影响指定的那条() = runBlocking {
        val repo = repo()
        repo.save(account("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Notch"))
        repo.save(account("853c80ef3c3749fdaa49938b674adae6", "jeb_"))
        repo.remove("069a79f444e94726a5befca90e38aaf5")
        assertEquals(listOf("jeb_"), repo.list().map { it.name })
    }

    @Test
    fun 删不存在的账号不报错也不动别的() = runBlocking {
        val repo = repo()
        repo.save(account("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Notch"))
        repo.remove("00000000000000000000000000000000")
        assertEquals(1, repo.list().size)
    }

    @Test
    fun 空的刷新令牌挡在写盘之前() = runBlocking {
        val repo = repo()
        assertFailsWith<IllegalArgumentException> {
            repo.save(StoredAccount(uuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5", name = "x", refreshToken = " "))
        }
        assertFalse(File(dir, "accounts.json").isFile)
    }

    @Test
    fun 空的UUID挡在写盘之前() = runBlocking {
        val repo = repo()
        assertFailsWith<IllegalArgumentException> {
            repo.save(StoredAccount(uuid = "", name = "x", refreshToken = "rt"))
        }
        Unit
    }

    @Test
    fun 索引文件坏了当没有而不是崩掉() = runBlocking {
        File(dir, "accounts.json").writeText("{这不是 JSON", Charsets.UTF_8)
        assertEquals(emptyList(), repo().list())
    }

    @Test
    fun 空文件也当没有() = runBlocking {
        File(dir, "accounts.json").writeText("   ", Charsets.UTF_8)
        assertEquals(emptyList(), repo().list())
    }

    @Test
    fun 默认索引跟实例与服务端放在一起() {
        val file = JsonAccountRepository.defaultIndexFile(File("C:/mc"))
        assertEquals(File("C:/mc/platypus/accounts.json").path, file.path)
    }

    @Test
    fun 列表按创建时间排() = runBlocking {
        val repo = repo()
        repo.save(account("069a79f4-44e9-4726-a5be-fca90e38aaf5", "first").copy(createdAt = 100L))
        repo.save(account("853c80ef3c3749fdaa49938b674adae6", "second").copy(createdAt = 50L))
        assertEquals(listOf("second", "first"), repo.list().map { it.name })
    }

    @Test
    fun 存进去的UUID统一成带横线的写法() = runBlocking {
        val repo = repo()
        repo.save(account("069a79f444e94726a5befca90e38aaf5", "Notch"))
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", repo.list()[0].uuid)
    }
}
