package com.oguricap.efb.ui.checklist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oguricap.efb.data.AircraftChecklist
import com.oguricap.efb.data.ChecklistItem
import com.oguricap.efb.data.ChecklistPhase
import com.oguricap.efb.data.CustomChecklistStore
import com.oguricap.efb.ui.ReferenceViewModel
import com.oguricap.efb.ui.TwoLineTopBar
import kotlinx.coroutines.delay

// 输入停了才落盘的等待时长：太短会每个字符写一次盘，太长用户会觉得「没保存」
private const val DEBOUNCE_MS = 300L

// 自定义检查单的图形化编辑器：改哪存哪，没有「保存」按钮。
//
// 为什么不直接在详情页里就地编辑：详情页整行可点是为了打勾（戴手套、颠簸时好点中），
// 混进输入框以后同一个手势会有两种含义，容易误勾。编辑与执行分开两页最省心。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomChecklistEditorScreen(
    vm: ReferenceViewModel,
    checklistId: String,
    onBack: () -> Unit,
) {
    val checklist = vm.aircraft(checklistId)
    if (checklist == null) {
        // 从别处删掉了或者 id 失效，别留一个空白页
        Scaffold(
            // 系统栏留白由外层 EfbApp 的 Scaffold 统一负责
            contentWindowInsets = WindowInsets(0),
            topBar = { TwoLineTopBar(title = "自定义检查单", subtitle = "", onBack = onBack) },
        ) { padding ->
            Text("这份检查单已不存在", modifier = Modifier.padding(padding).padding(16.dp))
        }
        return
    }

    // 待确认删除的阶段 uid：存 uid 而不是序号，确认前列表被别人改过也不会删错
    var pendingPhaseDelete by remember { mutableStateOf<String?>(null) }

    // 所有改动都从这里走：先取当前最新版本再改，避免拿着旧快照把别处的改动覆盖掉
    val mutate: ((AircraftChecklist) -> AircraftChecklist) -> Unit = { block ->
        vm.aircraft(checklistId)?.let { current -> vm.updateCustom(block(current)) }
    }

    Scaffold(
        // 系统栏留白由外层 EfbApp 的 Scaffold 统一负责
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TwoLineTopBar(
                title = checklist.name.ifBlank { "自定义检查单" },
                subtitle = "自定义检查单 · 即时保存",
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "name") {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    DebouncedTextField(
                        value = checklist.name,
                        label = "机型名",
                        modifier = Modifier.fillMaxWidth(),
                        onCommit = { text -> mutate { it.copy(name = text) } },
                    )
                    Text(
                        text = "改动即时保存，返回即可",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (checklist.source.isNotBlank()) {
                        Text(
                            text = checklist.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "phases-header") {
                Text(
                    text = "阶段 · " + checklist.phases.size,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            if (checklist.phases.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "还没有阶段，点下面的「+ 添加阶段」开始。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            itemsIndexed(
                items = checklist.phases,
                key = { _, phase -> "phase-" + phase.uid.ifBlank { phase.name } },
            ) { phaseIndex, phase ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        DebouncedTextField(
                            value = phase.name,
                            label = "阶段名称",
                            modifier = Modifier.fillMaxWidth(),
                            onCommit = { text ->
                                mutate { it.updatePhase(phase.uid) { p -> p.copy(name = text) } }
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { mutate { it.movePhase(phase.uid, -1) } },
                                enabled = phaseIndex > 0,
                            ) {
                                Text("▲")
                            }
                            TextButton(
                                onClick = { mutate { it.movePhase(phase.uid, 1) } },
                                enabled = phaseIndex < checklist.phases.lastIndex,
                            ) {
                                Text("▼")
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = { pendingPhaseDelete = phase.uid }) {
                                Text("删除阶段")
                            }
                        }
                        DebouncedTextField(
                            value = phase.gloss,
                            label = "阶段说明（可留空）",
                            modifier = Modifier.fillMaxWidth(),
                            onCommit = { text ->
                                mutate { it.updatePhase(phase.uid) { p -> p.copy(gloss = text) } }
                            },
                        )

                        phase.items.forEachIndexed { itemIndex, item ->
                            key(item.uid.ifBlank { "item-" + phaseIndex + "-" + itemIndex }) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    DebouncedTextField(
                                        value = item.item,
                                        label = "项目",
                                        modifier = Modifier.weight(1f),
                                        onCommit = { text ->
                                            mutate {
                                                it.updateItem(phase.uid, item.uid) { x -> x.copy(item = text) }
                                            }
                                        },
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    DebouncedTextField(
                                        value = item.state,
                                        label = "应答",
                                        modifier = Modifier.width(96.dp),
                                        onCommit = { text ->
                                            mutate {
                                                it.updateItem(phase.uid, item.uid) { x -> x.copy(state = text) }
                                            }
                                        },
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = { mutate { it.moveItem(phase.uid, item.uid, -1) } },
                                        enabled = itemIndex > 0,
                                    ) {
                                        Text("▲")
                                    }
                                    TextButton(
                                        onClick = { mutate { it.moveItem(phase.uid, item.uid, 1) } },
                                        enabled = itemIndex < phase.items.lastIndex,
                                    ) {
                                        Text("▼")
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    // 条目直接删，不再二次确认：敲错一个字比多点一次更烦
                                    TextButton(onClick = { mutate { it.deleteItem(phase.uid, item.uid) } }) {
                                        Text("删除")
                                    }
                                }
                            }
                        }

                        TextButton(onClick = { mutate { it.addItem(phase.uid) } }) {
                            Text("+ 添加条目")
                        }
                    }
                }
            }

            item(key = "add-phase") {
                TextButton(
                    onClick = { mutate { it.addPhase() } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("+ 添加阶段")
                }
            }
        }
    }

    val toDelete = pendingPhaseDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingPhaseDelete = null },
            title = { Text("删除阶段") },
            text = { Text("这个阶段和它下面的条目会一起删掉，已勾的进度也随之失效。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingPhaseDelete = null
                    mutate { it.deletePhase(toDelete) }
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPhaseDelete = null }) { Text("取消") }
            },
        )
    }
}

// 带防抖的输入框：草稿留在本地，停手 300ms 才回写 ViewModel。
//
// 为什么不直接把 value 绑到 OutlinedTextField：每次按键都会整份 JSON 落盘，
// 条目多的检查单输入会明显掉帧；而且光标位置也会跟着重组跳。
@Composable
private fun DebouncedTextField(
    value: String,
    label: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf(value) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    // onCommit 每次都可能是新的闭包（捕获了新的 phase/item），用 rememberUpdatedState
    // 保证定时器不会因为这些闭包换新而重启，否则永远等不到那 300ms
    val commit by rememberUpdatedState(onCommit)

    // 外部值变了（切换检查单等）才同步；自己刚写出去的值不要盖掉正在输入的草稿
    LaunchedEffect(value) {
        if (!dirty) draft = value
    }
    LaunchedEffect(draft) {
        if (!dirty) return@LaunchedEffect
        delay(DEBOUNCE_MS)
        commit(draft)
        dirty = false
    }

    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it
            dirty = true
        },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
    )
}

// ---- 不可变数据的编辑辅助 ----
// 全部按 uid 定位：编辑器里随时可能重排/删除，闭包里捕获的序号会过期。

private fun AircraftChecklist.updatePhase(
    uid: String,
    block: (ChecklistPhase) -> ChecklistPhase,
): AircraftChecklist = copy(phases = phases.map { if (it.uid == uid) block(it) else it })

private fun AircraftChecklist.updateItem(
    phaseUid: String,
    itemUid: String,
    block: (ChecklistItem) -> ChecklistItem,
): AircraftChecklist = updatePhase(phaseUid) { phase ->
    phase.copy(items = phase.items.map { if (it.uid == itemUid) block(it) else it })
}

private fun AircraftChecklist.movePhase(uid: String, delta: Int): AircraftChecklist {
    val index = phases.indexOfFirst { it.uid == uid }
    return copy(phases = phases.moved(index, delta))
}

private fun AircraftChecklist.moveItem(phaseUid: String, itemUid: String, delta: Int): AircraftChecklist =
    updatePhase(phaseUid) { phase ->
        val index = phase.items.indexOfFirst { it.uid == itemUid }
        phase.copy(items = phase.items.moved(index, delta))
    }

private fun AircraftChecklist.deletePhase(uid: String): AircraftChecklist =
    copy(phases = phases.filterNot { it.uid == uid })

private fun AircraftChecklist.deleteItem(phaseUid: String, itemUid: String): AircraftChecklist =
    updatePhase(phaseUid) { phase -> phase.copy(items = phase.items.filterNot { it.uid == itemUid }) }

private fun AircraftChecklist.addItem(phaseUid: String): AircraftChecklist =
    updatePhase(phaseUid) { phase ->
        phase.copy(items = phase.items + ChecklistItem(item = "", state = "", uid = CustomChecklistStore.newUid()))
    }

private fun AircraftChecklist.addPhase(): AircraftChecklist =
    copy(
        phases = phases + ChecklistPhase(
            name = "新阶段",
            gloss = "",
            items = emptyList(),
            uid = CustomChecklistStore.newUid(),
        ),
    )

// 越界时原样返回，调用方不用自己判断
private fun <T> List<T>.moved(index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in indices || target !in indices) return this
    return toMutableList().also { it.add(target, it.removeAt(index)) }
}
