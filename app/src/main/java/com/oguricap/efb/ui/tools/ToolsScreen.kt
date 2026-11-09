package com.oguricap.efb.ui.tools

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * 工具页：两个内嵌网页工具（航路生成器 / 油量计算器）。
 *
 * 两个 WebView 同时挂在树上，只切可见性 —— 航路页导入一次导航数据要读几十兆文本，
 * 切到油量页看一眼再切回来必须还是原结果，重载等于让用户重做一遍。
 *
 * 数据流全部由 [ToolsViewModel] 持有，这里只做「按钮 → vm 方法」和「vm 状态 → 界面」。
 */
@Composable
fun ToolsScreen(vm: ToolsViewModel = viewModel()) {
    var showSave by remember { mutableStateOf(false) }
    var showRoutes by remember { mutableStateOf(false) }
    // 右下角那颗胶囊：收起时只有一个圆形按钮，展开后浮出「保存」与「航路库」
    var capsuleOpen by remember { mutableStateOf(false) }

    // 切子页时收起，免得胶囊飘在油量页上
    LaunchedEffect(vm.page) { capsuleOpen = false }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 这一次选择是给哪个入口用的：nav / aip / pack。
    // 网页里三个「点击选择文件」分别对应这三条，选完由 ViewModel 按 kind 分流。
    var pendingKind by remember { mutableStateOf("nav") }

    // SAF 多选：只拿到只读 Uri，文件内容由 ViewModel 分块读出来送进网页
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(pendingKind, uris)
    }

    // 底部按钮和网页里的「点击选择文件」共用这一个入口。
    // launch 失败时不能静默：用户只会看到「点了没反应」，根本无从判断。
    val pickFiles: () -> Unit = {
        runCatching { picker.launch(arrayOf("*/*")) }
            .onFailure { e ->
                Log.e("EFB/TOOL", "打开文件选择器失败", e)
                scope.launch {
                    snackbar.showSnackbar("打不开文件选择器：" + (e.message ?: e.javaClass.simpleName))
                }
            }
    }

    // 展示完再清：先清会让 key 立刻变 null，本协程被取消，Snackbar 根本来不及显示
    LaunchedEffect(vm.message) {
        val text = vm.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.consumeMessage()
    }

    val blocked = vm.loadError != null

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // 航路页不要底部操作条了：导入走网页自己的「数据管理」，
            // 方案是自动读取的，保存与航路库收进右下角胶囊
            if (vm.page == ToolsPage.Fuel) FuelActions(vm = vm, blocked = blocked)
        },
        // 系统栏由外层 EfbApp 的 Scaffold 统一留白，这里再加一次会出现双倍上边距
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToolsPage.entries.forEach { target ->
                    FilterChip(
                        selected = vm.page == target,
                        onClick = { vm.selectPage(target) },
                        label = { Text(target.label) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }

            if (vm.importBusy) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = vm.importStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // 只挂一个宿主：同一时刻屏幕上只有一个 WebView。
                // 两个并排放会互相抢触摸 —— 上层那个即使内部不可见，也会吃掉所有点击。
                ToolsWebView(
                    vm = vm,
                    onEvent = { text -> scope.launch { snackbar.showSnackbar(text) } },
                    // 网页里三个「点击选择文件」都走同一个 SAF 选择器：
                    // WebView 没有 FileChooser 回调，不接管的话点它毫无反应
                    onPickFiles = { kind -> pendingKind = kind; pickFiles() },
                )

                // 资源坏了就别让用户对着半渲染的页面点按钮，直接盖住
                vm.loadError?.let { LoadErrorOverlay(it) }

                if (vm.page == ToolsPage.Route && !blocked) {
                    RouteCapsule(
                        expanded = capsuleOpen,
                        onToggle = { capsuleOpen = !capsuleOpen },
                        savedCount = vm.saved.size,
                        planCount = vm.snapshot?.plans?.size ?: 0,
                        onSave = { capsuleOpen = false; showSave = true },
                        onOpenRoutes = { capsuleOpen = false; showRoutes = true },
                    )
                }
            }
        }
    }

    if (showSave) {
        SaveRouteDialog(
            snapshot = vm.snapshot,
            // onConfirmPlan 优先，这里只是「不传选中方案」那条老路径的兜底
            onConfirm = { name ->
                vm.snapshot?.plans?.minByOrNull { it.distNM }?.let { vm.savePlan(it, name) }
                showSave = false
            },
            onDismiss = { showSave = false },
            // 用户在对话框里点的哪一条，存的就是哪一条
            onConfirmPlan = { name, plan ->
                vm.savePlan(plan, name)
                showSave = false
            },
        )
    }

    if (showRoutes) {
        RoutesPanel(
            saved = vm.saved,
            activeId = vm.activeId,
            onSelect = { vm.setActive(it) },
            onRename = { id, name -> vm.renameSaved(id, name) },
            onDelete = { id -> vm.deleteSaved(id) },
            onDismiss = { showRoutes = false },
        )
    }
}

/**
 * 右下角的可展开胶囊：收起时是一颗圆形按钮，展开后浮出「保存当前方案」与「航路库」。
 *
 * 为什么不放底部操作条：航路页下半屏本来就是网页自己的结果区，底部再压一条按钮栏
 * 会把方案卡片挤到看不见的地方；而且导入、读方案这两个动作现在都不需要按钮了。
 */
@Composable
private fun RouteCapsule(
    expanded: Boolean,
    onToggle: () -> Unit,
    savedCount: Int,
    planCount: Int,
    onSave: () -> Unit,
    onOpenRoutes: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 展开时点空白处收起，避免误触网页
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .pointerInput(Unit) { detectTapGestures { onToggle() } },
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ExtendedFloatingActionButton(
                        onClick = onSave,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(if (planCount > 0) "保存当前方案" else "暂无方案可保存")
                    }
                    ExtendedFloatingActionButton(
                        onClick = onOpenRoutes,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Text("航路库 ($savedCount)")
                    }
                }
            }
            Spacer(modifier = Modifier.height(if (expanded) 10.dp else 0.dp))
            FloatingActionButton(
                onClick = onToggle,
                containerColor = if (expanded) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (expanded) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.Close else Icons.Filled.Add,
                    contentDescription = if (expanded) "收起" else "保存与航路库",
                )
            }
        }
    }
}

/** 油量页的操作条：显示当前航路距离，并可把距离重新填进网页的 #dist。 */
@Composable
private fun FuelActions(
    vm: ToolsViewModel,
    blocked: Boolean,
) {
    val distNM = vm.currentDistNM()
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (distNM != null) "当前航路 $distNM NM" else "当前航路未设定",
                style = MaterialTheme.typography.bodyMedium,
                color = if (distNM != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { vm.fillFuelDistance() },
                enabled = !blocked && vm.fuelReady && distNM != null,
            ) {
                Text("重新填入")
            }
        }
    }
}

/** 网页资源缺失 / 锚点漂移时的整块覆盖层。 */
@Composable
private fun LoadErrorOverlay(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
