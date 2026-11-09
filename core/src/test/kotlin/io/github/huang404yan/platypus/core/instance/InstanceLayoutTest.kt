package io.github.huang404yan.platypus.core.instance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstanceLayoutTest {

    private fun instance(dir: String, id: String = "1.20.1"): Instance = Instance(
        id = id,
        name = id,
        gameVersion = "1.20.1",
        directory = dir,
    )

    @Test
    fun `版本目录就是实例目录`() {
        val layout = InstanceLayouts.of(File("/mc/versions/1.20.1"))
        assertEquals(File("/mc/versions/1.20.1").absolutePath, layout.versionDir.path)
        assertEquals(File("/mc/versions/1.20.1").absolutePath, layout.gameDir.path)
    }

    @Test
    fun `游戏根目录是版本目录的上两级`() {
        val layout = InstanceLayouts.of(File("/mc/versions/1.20.1"))
        assertEquals(File("/mc").absolutePath, layout.gameRoot.path)
    }

    @Test
    fun `共享目录在游戏根目录下`() {
        val layout = InstanceLayouts.of(File("/mc/versions/1.20.1"))
        assertEquals(File("/mc/libraries").absolutePath, layout.librariesDir.path)
        assertEquals(File("/mc/assets").absolutePath, layout.assetsDir.path)
    }

    @Test
    fun `原生库目录按实例分开`() {
        val a = InstanceLayouts.of(File("/mc/versions/1.20.1"))
        val b = InstanceLayouts.of(File("/mc/versions/1.21.1"))
        assertEquals(File("/mc/versions/1.20.1/natives/1.20.1").absolutePath, a.nativesDir.path)
        assertEquals(File("/mc/versions/1.21.1/natives/1.21.1").absolutePath, b.nativesDir.path)
        assertTrue(a.nativesDir.path != b.nativesDir.path, "两个实例的原生库目录必须不同")
    }

    @Test
    fun `父目录不叫 versions 时不上溯`() {
        // 有人手填了 <gameRoot>/instances/xx 这种目录：没有 versions 这一层，不能瞎猜根目录。
        val layout = InstanceLayouts.of(File("/mc/instances/xx"))
        assertEquals(File("/mc/instances/xx").absolutePath, layout.gameRoot.path)
        assertEquals(File("/mc/instances/xx/libraries").absolutePath, layout.librariesDir.path)
    }

    @Test
    fun `顶层目录没有父目录时不崩`() {
        // Windows 下 File("/").parentFile 是 null：这时原样返回，不往上跳，也不抛异常。
        val root = File("/")
        assertEquals(root, InstanceLayouts.gameRootOf(root))
    }

    @Test
    fun `versions 大小写不敏感`() {
        val layout = InstanceLayouts.of(File("/mc/Versions/1.20.1"))
        assertEquals(File("/mc").absolutePath, layout.gameRoot.path)
    }

    @Test
    fun `从实例取布局等价于从目录取`() {
        val dir = File("/mc/versions/1.20.1")
        assertEquals(
            InstanceLayouts.of(dir),
            InstanceLayouts.of(instance(dir.path)),
        )
    }

    @Test
    fun `目录字符串带引号也能认`() {
        // 用户从资源管理器复制路径时常带一对双引号。
        val layout = InstanceLayouts.of(instance("\"/mc/versions/1.20.1\""))
        assertEquals(File("/mc/versions/1.20.1").absolutePath, layout.versionDir.path)
    }

    @Test
    fun `按实例分开的目录清单包含存档模组配置`() {
        val names = InstanceLayouts.perInstanceDirs(File("/mc/versions/1.20.1")).map { it.name }
        assertTrue(names.contains("mods"))
        assertTrue(names.contains("saves"))
        assertTrue(names.contains("config"))
    }
}
