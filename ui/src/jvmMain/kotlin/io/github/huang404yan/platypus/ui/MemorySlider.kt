package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
import java.util.Locale

/**
 * 内存的几个「一键跳过去」的档位。滑条是连续的，这些档位只是省得一下一下挪。
 *
 * 为什么是这一组数：1G 是下限附近（再小 1.21 就跑不动），2G 是原版默认，
 * 4G/8G 是加了模组之后最常停的两格，再往上留给整合包与光影。
 */
internal val MEMORY_PRESETS_MB: List<Int> =
    listOf(1024, 2048, 3072, 4096, 6144, 8192, 12288, 16384, 24576, 32768)

/** 档位按钮上的字：整 G 的写成「4G」，不是整 G 的写成「3072M」—— 两种都不含糊。 */
internal fun memoryPresetLabel(mb: Int): String =
    if (mb % 1024 == 0) (mb / 1024).toString() + "G" else mb.toString() + "M"

/**
 * 滑条拖动的颗粒：128 MB。
 *
 * 为什么不是 1 MB：512–32768 之间按 1 MB 算，滑条得有三万像素才分得出一格；
 * 128 MB 在 1G 以下还剩四格（512/640/768/896），摸到 2G 以上也就每档十来个像素，
 * 手感上就是「连续」。PCL 的做法是分段线性（PageSetupLaunch.xaml.vb:336-346），
 * 这里是线性 + 128 取整，够用且不用维护四段区间。
 */
internal const val MEMORY_STEP_MB: Int = 128

/**
 * 滑条上限：本机物理内存减 1 GB，再夹进允许区间。
 *
 * 减 1 GB 是留给系统自己 —— 把 -Xmx 正好开到物理内存总量，启动那一刻就开始换页。
 * 探不到物理内存（Android，或运行时被裁掉 jdk.management）时退回 [Instance.MAX_MEMORY_MB]。
 */
internal fun memorySliderUpperMb(totalMb: Int = SystemMemory.totalMb): Int {
    if (totalMb <= 0) return Instance.MAX_MEMORY_MB
    val reserved = (totalMb - 1024).coerceAtLeast(Instance.MIN_MEMORY_MB)
    return reserved.coerceAtMost(Instance.MAX_MEMORY_MB)
}

/** 把任意 MB 吸附到 128 的整数倍并夹进 [Instance.MIN_MEMORY_MB]–[upper]。 */
private fun snapMemoryMb(rawMb: Int, upperMb: Int): Int {
    val snapped = Math.round(rawMb.toDouble() / MEMORY_STEP_MB).toInt() * MEMORY_STEP_MB
    return snapped.coerceIn(Instance.MIN_MEMORY_MB, upperMb)
}

/** MB → "2.0 GB"，给提示语用。 */
private fun formatGb(mb: Int): String = String.format(Locale.ROOT, "%.1f GB", mb / 1024.0)

/**
 * 「给了这么多内存，机器受不受得了」这一句；受得了就返回 null。
 *
 * 门槛取物理内存的八成：剩下两成留给系统、显存映射与其他程序，再往上就是换页。
 */
internal fun memoryPressureHint(valueMb: Int, totalMb: Int): String? {
    if (totalMb <= 0) return null
    if (valueMb.toLong() * 5L <= totalMb.toLong() * 4L) return null
    return "本机物理内存约 " + formatGb(totalMb) + "，给游戏 " + formatGb(valueMb) +
        " 已经超过八成 —— 系统会开始换页，进游戏后大概率卡顿。"
}

/**
 * 内存滑条：拖一下选大小，下面一排固定档位点一下就跳过去。
 *
 * 为什么不用输入框：内存只是「给多少」这一个数，区间又长，键盘输入既慢又容易打错
 * （32768 打成 3276 是要等到启动失败才发现的）。滑条 + 常用档位把这件事变成两下点击。
 *
 * 拖动手感和 PCL 的 MySlider 对齐：整条都能按下去拖（不是只有圆点），拖动中滑条跟手、
 * 数值按 [MEMORY_STEP_MB] 跳。传进来的 [valueMb] 可能不是 128 的倍数（老实例里存着任意数），
 * 只有真的拖了才会改值 —— 不会因为打开界面就被悄悄夹到某个档位。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemorySlider(
    valueMb: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
    /** 卡片里那种窄地方关掉：十个档位按钮在 336dp 宽的卡上要折三四行，把卡片撑得比邻居高。 */
    showPresets: Boolean = true,
    showHint: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val totalMb = SystemMemory.totalMb
    val upperMb = memorySliderUpperMb(totalMb)
    val span = (upperMb - Instance.MIN_MEMORY_MB).coerceAtLeast(1)
    val settled = ((valueMb - Instance.MIN_MEMORY_MB).toFloat() / span).coerceIn(0f, 1f)
    // 拖动中用手上这个连续位置画滑条、松手再回到由 valueMb 算出的位置。
    // 不这么做的话，每跳一个 128 都会把圆点重新吸到整格上，手一快就像在顿。
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Slider(
            value = dragFraction ?: settled,
            onValueChange = { raw ->
                dragFraction = raw
                val picked = snapMemoryMb(Instance.MIN_MEMORY_MB + (raw * span).toInt(), upperMb)
                if (picked != valueMb) onChange(picked)
            },
            onValueChangeFinished = { dragFraction = null },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "内存 " + valueMb + " MB（" + memoryPresetLabel(valueMb) + "）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        memoryPressureHint(valueMb, totalMb)?.let { hint ->
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (!showPresets) return@Column
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MEMORY_PRESETS_MB.forEach { preset ->
                val reachable = preset <= upperMb
                if (preset == valueMb) {
                    Button(
                        onClick = { onChange(preset) },
                        enabled = enabled && reachable,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) { Text(memoryPresetLabel(preset)) }
                } else {
                    OutlinedButton(
                        onClick = { onChange(preset) },
                        enabled = enabled && reachable,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) { Text(memoryPresetLabel(preset)) }
                }
            }
        }
        if (showHint) {
            Text(
                if (totalMb > 0) {
                    "滑条是连续的，上限按本机物理内存算（" + formatGb(upperMb) + "）；下面这排是常停的几档，点一下就跳过去。"
                } else {
                    "滑条是连续的，下面这排是常停的几档，点一下就跳过去。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
