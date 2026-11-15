package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.server.PlayerLookup
import io.github.huang404yan.platypus.core.server.PlayerLookupKind
import io.github.huang404yan.platypus.core.server.PropertyField
import io.github.huang404yan.platypus.core.server.PropertyKind
import io.github.huang404yan.platypus.core.server.ServerPlayer
import io.github.huang404yan.platypus.core.server.ServerPlayers
import io.github.huang404yan.platypus.core.server.ServerProperties
import io.github.huang404yan.platypus.core.server.ServerPropertiesView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一个服务端目录的配置：`server.properties` 的表单，加上白名单与管理员。
 *
 * 为什么单独做一个子页而不是塞进 [ServerPage]：那一页已经装了两千多行（选版本、选加载器、
 * 进度、登记列表、控制台），再叠十几项配置会变成一堵墙。而配置是「装好之后才要做」的事，
 * 两者不该在同一个视口里抢位置。
 *
 * 三条与后端有关的规矩写在实现里，不只写在这里：
 * ① **服务端正在跑时只读**。原版服务端启动时把 properties 读进内存，之后会自己写回；
 *    我们这时候改盘上的文件，它一保存就被覆盖，用户会以为「改了没生效」。
 * ② 白名单与 OP 优先走控制台命令（`whitelist add` / `op`），只有服务端没在跑时才直接改 json ——
 *    同样是因为服务端会拿内存状态覆盖盘上的文件。
 * ③ 改完 properties 要提示「重启服务端才生效」。原版不读热更新，这一点不写清楚就是坑。
 */
@Composable
internal fun ServerConfigPanel(
    dir: File,
    running: Boolean,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var view by remember(dir.absolutePath) { mutableStateOf<ServerPropertiesView?>(null) }
    var fields by remember(dir.absolutePath) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var problems by remember(dir.absolutePath) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var whitelist by remember(dir.absolutePath) { mutableStateOf<List<ServerPlayer>>(emptyList()) }
    var ops by remember(dir.absolutePath) { mutableStateOf<List<ServerPlayer>>(emptyList()) }
    var notice by remember(dir.absolutePath) { mutableStateOf("") }
    var noticeIsError by remember(dir.absolutePath) { mutableStateOf(false) }
    var saving by remember(dir.absolutePath) { mutableStateOf(false) }
    var dirty by remember(dir.absolutePath) { mutableStateOf(false) }

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) { ServerProperties.read(dir) }
        view = loaded
        fields = ServerProperties.FIELDS.associate { it.key to ServerProperties.valueOf(loaded, it) }
        problems = emptyMap()
        dirty = false
        val (white, admins) = withContext(Dispatchers.IO) {
            ServerPlayers.whitelist(dir) to ServerPlayers.ops(dir)
        }
        whitelist = white
        ops = admins
    }

    LaunchedEffect(dir.absolutePath) { reload() }

    fun speak(text: String, error: Boolean = false) {
        notice = text
        noticeIsError = error
    }

    fun save() {
        val current = view ?: return
        val invalid = problems.filterValues { it.isNotBlank() }
        if (invalid.isNotEmpty()) {
            speak("有 " + invalid.size + " 项填得不对，先改掉再保存。", error = true)
            return
        }
        if (saving) return
        saving = true
        scope.launch {
            try {
                val written = withContext(Dispatchers.IO) {
                    val next = current.withValues(fields)
                    ServerProperties.write(dir, next) to next
                }
                view = written.second
                dirty = false
                speak(
                    if (running) {
                        "已写入 " + written.first.absolutePath + "。服务端还在跑，**重启之后才生效**。"
                    } else {
                        "已写入 " + written.first.absolutePath + "。"
                    },
                )
            } catch (e: Exception) {
                speak("写不进 server.properties：" + (e.message ?: e::class.simpleName.orEmpty()), error = true)
            } finally {
                saving = false
            }
        }
    }

    fun addPlayer(name: String, toWhitelist: Boolean, level: Int, done: () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val lookup: PlayerLookup = withContext(Dispatchers.IO) { ServerPlayers.lookup(name) }
                withContext(Dispatchers.IO) {
                    if (toWhitelist) {
                        ServerPlayers.addWhitelist(dir, ServerPlayer(lookup.uuid, lookup.name))
                    } else {
                        ServerPlayers.addOp(dir, ServerPlayer(lookup.uuid, lookup.name, level = level))
                    }
                }
                reload()
                val who = lookup.name + "（" + lookup.kind.label + "）"
                val command = if (toWhitelist) "whitelist add " + lookup.name else "op " + lookup.name
                speak(
                    if (running) {
                        "文件里已经加上了 " + who + "。服务端正在跑，它只认控制台里的 " +
                            command + " —— 回上一页，在控制台里发一次才立刻生效。"
                    } else {
                        "已加上 " + who + "。下次开服就生效。"
                    },
                )
                if (lookup.kind == PlayerLookupKind.ASSUMED_OFFLINE) {
                    speak(
                        "已加上 " + who + "，但**网络不通，UUID 是按离线规则算的**。" +
                            "如果这是个正版服，这个人很可能进不来 —— 联网后重加一次。",
                        error = true,
                    )
                }
            } catch (e: Exception) {
                speak("加不上：" + (e.message ?: e::class.simpleName.orEmpty()), error = true)
            } finally {
                saving = false
                done()
            }
        }
    }

    fun removeWhitelist(player: ServerPlayer) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ServerPlayers.removeWhitelist(dir, player.key) }
                reload()
                speak(
                    if (running) {
                        "已从白名单文件里删掉 " + player.name + "。服务端还在跑，" +
                            "它要到重启后才不认这个人；要立刻踢掉就在控制台里发 whitelist remove " +
                            player.name + "。"
                    } else {
                        "已从白名单里删掉 " + player.name + "。"
                    },
                )
            } catch (e: Exception) {
                speak("删不掉：" + (e.message ?: e::class.simpleName.orEmpty()), error = true)
            }
        }
    }

    var pendingAddWhitelist by remember { mutableStateOf(false) }
    var pendingAddOp by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("← 返回服务端") }
            Text(
                "服务端配置",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (dirty) {
                Text(
                    "有未保存的改动",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Button(onClick = { save() }, enabled = !saving && dirty) {
                Text(if (saving) "保存中…" else "保存")
            }
        }

        Text(
            dir.absolutePath,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (running) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "服务端正在跑，这里的改动要重启才生效",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        "原版服务端启动时把 server.properties 读进内存，之后会自己写回去；" +
                            "这时候只改磁盘上的文件，它一保存就盖掉了。白名单与管理员也一样 —— " +
                            "想要立刻生效，把控制台那条命令发一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        ConfigCard("服务器设置") {
            val loaded = view
            if (loaded == null) {
                Text("正在读 server.properties…", style = MaterialTheme.typography.bodySmall)
            } else {
                if (!ServerProperties.exists(dir)) {
                    Text(
                        "这个目录里还没有 server.properties：现在显示的是原版默认值，保存一次才会写出文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ServerProperties.FIELDS.forEach { field ->
                    PropertyRow(
                        field = field,
                        value = fields[field.key] ?: field.default,
                        problem = problems[field.key].orEmpty(),
                        enabled = true,
                        onChange = { raw ->
                            fields = fields + (field.key to raw)
                            problems = problems + (field.key to ServerProperties.problemOf(field, raw).orEmpty())
                            dirty = true
                        },
                    )
                }
            }
        }

        ConfigCard("白名单（" + whitelist.size + "）") {
            Text(
                "只有白名单里的人能进 —— 前提是上面「只允许白名单进」开着。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (whitelist.isEmpty()) {
                Text("还没有人。", style = MaterialTheme.typography.bodySmall)
            } else {
                whitelist.forEach { player ->
                    PlayerRow(
                        player = player,
                        trailing = "从白名单删掉",
                        enabled = !saving,
                        onRemove = { removeWhitelist(player) },
                    )
                }
            }
            OutlinedButton(onClick = { pendingAddWhitelist = true }, enabled = !saving) {
                Text("加一个白名单")
            }
        }

        ConfigCard("管理员（" + ops.size + "）") {
            Text(
                "管理员能执行 /gamemode、/tp、/ban 这些命令。权限等级 4 是最高。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ops.isEmpty()) {
                Text("还没有管理员。", style = MaterialTheme.typography.bodySmall)
            } else {
                ops.forEach { player ->
                    PlayerRow(
                        player = player,
                        trailing = ServerPlayers.levelLabel(player.level) + " · 撤掉",
                        enabled = !saving,
                        onRemove = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { ServerPlayers.removeOp(dir, player.key) }
                                    reload()
                                    speak("已撤掉 " + player.name + " 的管理员。")
                                } catch (e: Exception) {
                                    speak("撤不掉：" + (e.message ?: e::class.simpleName.orEmpty()), error = true)
                                }
                            }
                        },
                    )
                }
            }
            OutlinedButton(onClick = { pendingAddOp = true }, enabled = !saving) {
                Text("加一个管理员")
            }
        }

        if (notice.isNotBlank()) {
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = if (noticeIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Text(
            "以上名单存成 " + File(dir, ServerPlayers.WHITELIST_NAME).absolutePath + " 与 " +
                File(dir, ServerPlayers.OPS_NAME).absolutePath + "。服务端自己也会写这两个文件。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (pendingAddWhitelist) {
        AddPlayerDialog(
            title = "加一个白名单",
            allowLevel = false,
            onDismiss = { pendingAddWhitelist = false },
            onConfirm = { name, level ->
                addPlayer(name, toWhitelist = true, level = level) { pendingAddWhitelist = false }
            },
        )
    }

    if (pendingAddOp) {
        AddPlayerDialog(
            title = "加一个管理员",
            allowLevel = true,
            onDismiss = { pendingAddOp = false },
            onConfirm = { name, level ->
                addPlayer(name, toWhitelist = false, level = level) { pendingAddOp = false }
            },
        )
    }
}

/** 一行配置。类型决定控件：开关、下拉（用一组小按钮）、数字、文本框。 */
@Composable
private fun PropertyRow(
    field: PropertyField,
    value: String,
    problem: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (field.kind) {
            PropertyKind.SWITCH -> {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = value.equals("true", ignoreCase = true),
                        onCheckedChange = { onChange(if (it) "true" else "false") },
                        enabled = enabled,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(field.label, style = MaterialTheme.typography.bodyMedium)
                        if (field.note.isNotBlank()) {
                            Text(
                                field.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            PropertyKind.CHOICE -> {
                Text(field.label, style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    field.options.forEach { (raw, label) ->
                        val selected = raw.equals(value, ignoreCase = true)
                        if (selected) {
                            Button(onClick = { }, enabled = false) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { onChange(raw) }, enabled = enabled) { Text(label) }
                        }
                    }
                }
            }
            PropertyKind.NUMBER -> {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onChange,
                        modifier = Modifier.width(140.dp),
                        label = { Text(field.label) },
                        singleLine = true,
                        enabled = enabled,
                        isError = problem.isNotBlank(),
                    )
                    if (field.note.isNotBlank()) {
                        Text(
                            field.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            PropertyKind.TEXT -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(field.label) },
                    singleLine = true,
                    enabled = enabled,
                    isError = problem.isNotBlank(),
                    supportingText = {
                        Text(if (problem.isNotBlank()) problem else field.note)
                    },
                )
            }
        }
        if (problem.isNotBlank() && field.kind != PropertyKind.TEXT) {
            Text(
                problem,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PlayerRow(
    player: ServerPlayer,
    trailing: String,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(player.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                player.uuid,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove, enabled = enabled) { Text(trailing) }
    }
}

@Composable
private fun AddPlayerDialog(
    title: String,
    allowLevel: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, level: Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(4) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim().take(16) },
                    label = { Text("玩家的游戏名字") },
                    singleLine = true,
                    supportingText = {
                        Text("大小写要与游戏里一致。正版账号会去 Mojang 查 UUID，查不到就按离线规则算。")
                    },
                )
                if (allowLevel) {
                    Text("权限等级", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (4 downTo 1).forEach { item ->
                            val selected = item == level
                            if (selected) {
                                Button(onClick = { }, enabled = false) { Text(item.toString()) }
                            } else {
                                OutlinedButton(onClick = { level = item }) { Text(item.toString()) }
                            }
                        }
                    }
                    Text(
                        ServerPlayers.levelLabel(level),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), level) },
                enabled = name.trim().isNotEmpty(),
            ) { Text("加进去") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ConfigCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}
