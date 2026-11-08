package io.github.huang0yan4.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang0yan4.platypus.core.Brand

/**
 * 关于页。免责声明必须在这里完整出现一次，
 * 措辞取自 core 的 Brand，不在界面里另写一份。
 */
@Composable
fun AboutPage() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("关于", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        Text(Brand.NAME_ZH, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            Brand.NAME_EN,
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
        InfoRow("通道", if (Brand.isPreview) "孵化版（Platypus Preview）" else "正式版（Platypus Launcher）")
        InfoRow("协议", "Apache-2.0")
        InfoRow("仓库", Brand.HOMEPAGE)

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
