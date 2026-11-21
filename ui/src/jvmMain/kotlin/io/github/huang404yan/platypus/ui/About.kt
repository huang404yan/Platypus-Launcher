package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.Credit
import io.github.huang404yan.platypus.core.CreditKind
import io.github.huang404yan.platypus.core.Credits
import io.github.huang404yan.platypus.core.Endpoints
import java.awt.Desktop

/**
 * 关于页。免责声明必须在这里完整出现一次，
 * 措辞取自 core 的 Brand，不在界面里另写一份。
 *
 * 致谢段与 NOTICE 同源（都在 core 的 Credits 里）：有项目真的被搬了代码时，
 * 改一处就够，不会出现「关于页说没抄、NOTICE 里却写着抄了」这种自相矛盾。
 */
@Composable
fun AboutPage() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("关于", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        Text(
            if (Brand.isPreview) Brand.PREVIEW_NAME_ZH else Brand.NAME_ZH,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (Brand.isPreview) Brand.PREVIEW_NAME_EN else Brand.NAME_EN,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (Brand.isPreview) Brand.PREVIEW_SLOGAN else Brand.SLOGAN,
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()

        InfoRow("版本", Brand.version)
        InfoRow(
            "通道",
            when {
                // 空壳包要单独说出来：它看着和完整版一模一样，只有下载页是空的，
                // 不在这里写明「这个包不带服务地址」，用户只会以为它坏了。
                Endpoints.shell -> "孵化版 · 开源版（空壳，不含下载与搜索服务地址）"
                Brand.isPreview -> "孵化版（Platypus Preview）"
                else -> "正式版（Platypus Launcher）"
            },
        )
        if (Endpoints.shell) {
            Text(
                Endpoints.SHELL_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InfoRow("协议", "Apache-2.0")
        InfoRow("仓库", Brand.HOMEPAGE)

        Spacer(Modifier.height(4.dp))
        DownloadGuide()

        Spacer(Modifier.height(4.dp))
        UpdateSection()

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()

        CreditsSection()

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()

        Text("免责声明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    Brand.DISCLAIMER_ZH,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Brand.DISCLAIMER_EN,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 怎么下载。
 *
 * 编译好的包只在发布页，仓库里只有源码 —— 这件事必须写在关于页里，
 * 因为拿到包的人不一定回过仓库，而「想换个版本 / 想给朋友的电脑装一份」的人一定会来这一页找。
 *
 * 两条产品线各讲一句就够，展开讲放在 README 与发布说明里：
 * 正式版是完整包（内置应用 ID 与服务地址），孵化版发布出去的是空壳（两样都得自己填）。
 * 安卓的孵化版（开源版）不发产物、只公开源码，所以这里只提一句怎么拿源码，不指向发布页。
 */
@Composable
private fun DownloadGuide() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("怎么下载", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "编译好的包只放在发布页，仓库里只有源码。桌面端（Windows / Linux）与安卓端都在同一页。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "桌面端分两条线。正式版是完整包，内置应用 ID 与服务地址，装完就能联网。" +
                    "孵化版发布出去的那一份是空壳，应用 ID 与下载地址都得自己填；" +
                    "填过一次就一直记住，程序升级不会冲掉。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "安卓端以正式版为主，另一条线也是孵化版（也就是开源版），只公开源码、不发安装包，想要就自己编一份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { openBrowser(Brand.RELEASES) }) {
                Text("打开发布页")
            }
        }
    }
}

/** 用系统默认浏览器打开。打不开就什么都不做 —— 上面「仓库」那一行的地址仍可复制。 */
private fun openBrowser(url: String) {
    if (url.isBlank()) return
    runCatching {
        if (!Desktop.isDesktopSupported()) return@runCatching
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runCatching
        desktop.browse(java.net.URI(url))
    }
}

/**
 * 开源致谢。按 CreditKind 分组（真的用了代码 / 设计参考 / 格式与协议 / 运行时依赖与资产），
 * 每组都带一句「这一类是什么」，否则用户看到 GPL-3.0 会以为自己装了个 GPL 启动器。
 */
@Composable
private fun CreditsSection() {
    Text("开源致谢", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
        Credits.INTRO_ZH,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    for (kind in CreditKind.entries) {
        val group = Credits.of(kind)
        if (group.isEmpty()) continue
        Text(
            "${kind.label} · ${group.size}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            kindExplanation(kind),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (item in group) {
                    CreditCard(item)
                }
            }
        }
    }
}

@Composable
private fun CreditCard(item: Credit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    item.license,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.note.isNotBlank()) {
                Text(
                    item.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.url.isNotBlank()) {
                Text(
                    item.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 每一类致谢到底意味着什么 —— 尤其要让用户分清「GPL 代码没进这个项目」。 */
private fun kindExplanation(kind: CreditKind): String = when (kind) {
    CreditKind.CODE -> "这些项目的实现被用进了本项目，版权归原作者，按各自的许可使用；本项目的修改同样以 Apache-2.0 发布。"
    CreditKind.REFERENCE -> "只看过它们的文档、issue 与源码来理解问题怎么解，代码是自己重写的；上面这些项目的 GPL 或专有代码没有进入本项目。"
    CreditKind.SPEC -> "用的是公开的接口、数据格式或社区约定，没有链接也没有修改对方的代码。"
    CreditKind.LIBRARY -> "运行时要用的第三方库与资产，各自保留原版权与许可；其中由用户自己触发的下载不进安装包。"
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
