package com.oguricap.efb.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oguricap.efb.ui.tools.PlanSnapshot
import com.oguricap.efb.ui.tools.PlannedRoute
import com.oguricap.efb.ui.tools.SavedRoute

/**
 * 航路库面板。
 *
 * 网页侧不持久化任何东西，已存航路全在 [RouteStore] 里，面板只负责展示与转发动作，
 * 状态仍由 ViewModel 持有（改完会重新 list 一次，面板不做本地副本，避免两份数据打架）。
 */
@Composable
fun RoutesPanel(
    saved: List<SavedRoute>,
    activeId: String?,
    onSelect: (String?) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 改名和删除各自要一层确认。状态放在面板里，面板一关就自然清掉，不会串到下次打开
    var renameTarget by remember { mutableStateOf<SavedRoute?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedRoute?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("航路库") },
        text = {
            if (saved.isEmpty()) {
                Text("还没有保存的航路。生成后点『保存』才会存到本机。")
            } else {
                // 一趟长航线可能存好几条备选，限制高度让对话框不越过屏幕
                // 不用 id 当 key：数据万一被手工改出重复 id，LazyColumn 会直接抛异常
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(saved) { route ->
                        SavedRouteRow(
                            route = route,
                            active = route.id == activeId,
                            onSelect = { onSelect(if (route.id == activeId) null else route.id) },
                            onRename = { renameTarget = route },
                            onDelete = { deleteTarget = route },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    renameTarget?.let { target ->
        RenameRouteDialog(
            initial = target.name,
            onConfirm = { name ->
                onRename(target.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除航路") },
            text = { Text("确定删除「${target.name}」？删除后不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.id)
                        deleteTarget = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SavedRouteRow(
    route: SavedRoute,
    active: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = route.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (active) ActiveBadge()
        }
        Text(
            text = "${route.from} → ${route.to}   ${route.distNM} NM",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        // 三个动作挤在对话框宽度里，按钮文字用小一号并收窄内边距，防止换行、截断
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onSelect,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (active) "取消当前" else "设为当前",
                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                )
            }
            TextButton(
                onClick = onRename,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("改名", fontSize = MaterialTheme.typography.labelMedium.fontSize)
            }
            TextButton(
                onClick = onDelete,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("删除", fontSize = MaterialTheme.typography.labelMedium.fontSize)
            }
        }
        HorizontalDivider()
    }
}

/** 「当前」小标签。用 primary 系配色，和列表里其它灰字区分开。 */
@Composable
private fun ActiveBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = "当前",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun RenameRouteDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名航路") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("方案名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            // 空名字不提交：存进去只会得到一条无法辨认的记录
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 保存航路对话框。
 *
 * @param onConfirm 只回传方案名，保存哪一条由调用方按「距离最小」兜底。
 * @param onConfirmPlan 新版入口：同时回传用户选中的方案，选中项才真正生效。
 *   两个都传时优先 [onConfirmPlan]。
 */
@Composable
fun SaveRouteDialog(
    snapshot: PlanSnapshot?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirmPlan: ((String, PlannedRoute) -> Unit)? = null,
) {
    // 先落到局部 val：下面在 lambda 里还要读起降机场，避免依赖对参数的 smart cast
    val snap = snapshot
    val plans = snap?.plans.orEmpty()
    // 默认选中距离最短的那条：点「保存」时用户通常要的就是最省的那份
    val defaultPlan = plans.minByOrNull { it.distNM }
    var selectedKey by remember(snapshot) { mutableStateOf(defaultPlan?.key) }
    var name by remember(snapshot) { mutableStateOf("") }

    val selected = plans.firstOrNull { it.key == selectedKey } ?: defaultPlan
    val canSave = snap != null && selected != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存航路") },
        text = {
            if (snap == null || plans.isEmpty()) {
                Text("当前页面没有可保存的方案，先去航路页生成一次。")
            } else {
                Column {
                    Text(
                        text = "${snap.from} → ${snap.to} · 共 ${plans.size} 个方案",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .padding(top = 8.dp),
                    ) {
                        items(plans) { plan ->
                            PlanChoiceRow(
                                plan = plan,
                                selected = plan.key == selected?.key,
                                onClick = { selectedKey = plan.key },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("方案名") },
                        // 留空由 ViewModel 兜底成「起机场-降机场」，这里只做提示，不预先填值
                        placeholder = { Text("${snap.from}-${snap.to}") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val plan = selected ?: return@TextButton
                    if (onConfirmPlan != null) onConfirmPlan(name, plan) else onConfirm(name)
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PlanChoiceRow(
    plan: PlannedRoute,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 整行可点，单选按钮只做视觉指示，避免小圆点成为唯一热区
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = kindLabel(plan.kind),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else null,
            )
            Text(
                text = "${plan.key} · ${plan.distNM} NM",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    "official" -> "官方"
    "engine" -> "引擎"
    else -> kind
}
