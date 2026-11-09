package com.oguricap.efb.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oguricap.efb.BuildConfig
import com.oguricap.efb.diag.AppLog
import com.oguricap.efb.diag.LogExporter
import com.oguricap.efb.prefs.Settings
import com.oguricap.efb.ui.ChartViewModel
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * 布局从「一列横条 + 分隔线」改成**分组卡片**：
 *  - 航图库（目录、状态、换目录）
 *  - 数据与索引（来源、权限）
 *  - 显示与交互（两个开关）
 *  - 规范与维护（导出规范、重跑引导）
 *  - 诊断与日志（记录开关、体积、崩溃记录、自选位置导出、清空）
 * 底部补版本号，出问题时用户能直接报出来。
 *
 * 交互上的三处改动：
 *  1. 路径长了会被截断成两行 —— 原来一行铺满、后半截被裁掉还看不出是路径；
 *  2. 路径可一键复制，排查「选错目录」时不用照着屏幕手敲；
 *  3. 开关整行可点，不用非去戳那个小滑块。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: ChartViewModel,
    exclusivePhases: Boolean,
    onToggleExclusive: (Boolean) -> Unit,
    storageGranted: Boolean,
    onOpenSystemSettings: () -> Unit,
    onPickRoot: (onPath: (String) -> Unit) -> Unit,
    onRerunOnboarding: () -> Unit,
    onBack: () -> Unit,
    diagVm: DiagnosticsViewModel = viewModel(),
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmClearLogs by remember { mutableStateOf(false) }

    // 每次进设置页都重新统计：ViewModel 挂在 Activity 上不会随页面销毁，
    // 只在 init 里读一次的话，用户复现完问题回来看体积还是旧数字
    LaunchedEffect(Unit) { diagVm.refresh() }

    LaunchedEffect(diagVm.message) {
        val text = diagVm.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        diagVm.consumeMessage()
    }

    // 「导出位置自行选择」就是这里：系统创建文档选择器，内部存储 / SD 卡 / 网盘都能选，
    // 文件名预填一个带时间戳的，用户想改当场就能改。取消时 uri 为 null，什么都不做。
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LogExporter.MIME_TYPE)
    ) { uri ->
        if (uri != null) diagVm.exportTo(uri)
    }

    // launch 失败不能静默：部分定制系统没有可用的文档选择器，
    // 不提示的话用户只会看到「点了没反应」（工具页的文件选择器踩过同一个坑）
    val pickExportTarget: () -> Unit = {
        runCatching { exportPicker.launch(diagVm.suggestedFileName()) }
            .onFailure { e ->
                AppLog.e(LOG_TAG, "打开日志导出选择器失败", e)
                scope.launch {
                    snackbar.showSnackbar("打不开保存位置选择器：" + (e.message ?: e.javaClass.simpleName))
                }
            }
    }

    Scaffold(
        // 系统栏留白由外层 EfbApp 的 Scaffold 统一负责
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("航图库")
            LibraryCard(
                path = vm.chartRoot,
                error = vm.rootError,
                scanning = vm.scanning,
                onPick = {
                    vm.clearRootError()
                    onPickRoot { path -> vm.scanInto(path) }
                },
                onCopy = { path ->
                    copyToClipboard(context, "航图库目录", path)
                    scope.launch { snackbar.showSnackbar("已复制航图库路径") }
                },
                onRescan = { path ->
                    vm.clearRootError()
                    vm.scanInto(path)
                },
            )

            SectionHeader("数据与索引")
            SettingsCard {
                InfoRow(
                    title = "索引来源",
                    desc = if (vm.indexOrigin == Settings.ORIGIN_SCAN) {
                        "本机扫描生成 · 共 ${chartCount(vm)} 张"
                    } else {
                        "APK 预置索引（620 KB）· 共 ${chartCount(vm)} 张"
                    },
                    trailing = {
                        StatusPill(
                            text = if (vm.indexOrigin == Settings.ORIGIN_SCAN) "本机扫描" else "预置",
                            tone = if (vm.indexOrigin == Settings.ORIGIN_SCAN) Tone.Ok else Tone.Neutral,
                        )
                    },
                )
                CardDivider()
                InfoRow(
                    title = "所有文件访问权限",
                    desc = if (storageGranted) {
                        "已授权，可读取航图目录里的 PDF"
                    } else {
                        "未授权，扫描与打开航图都会失败"
                    },
                    trailing = {
                        if (storageGranted) {
                            StatusPill("已授权", Tone.Ok)
                        } else {
                            SmallButton(text = "去授权", onClick = onOpenSystemSettings)
                        }
                    },
                )
            }

            SectionHeader("显示与交互")
            SettingsCard {
                SwitchRow(
                    title = "阅读页反色",
                    desc = "深色背景下看白底航图时使用，只影响显示",
                    checked = vm.invertReader,
                    onChange = { vm.applyInvert(it) },
                )
                CardDivider()
                SwitchRow(
                    title = "检查单分组互斥",
                    desc = "打开后一次只展开一个阶段：点开「起飞前」，其它阶段自动收起",
                    checked = exclusivePhases,
                    onChange = onToggleExclusive,
                )
            }

            SectionHeader("规范与维护")
            SettingsCard {
                ActionRow(
                    title = "航图库结构规范",
                    desc = "整理新航图库时对照这份文档，生成到手机 Download 目录",
                    action = if (vm.specExportPath == null) "生成规范文档" else "重新生成",
                    onAction = { vm.exportSpec() },
                ) {
                    if (vm.specExportPath != null) {
                        Text(
                            text = vm.specExportPath!!,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (vm.specExportError != null) {
                        Text(
                            text = vm.specExportError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                CardDivider()
                ActionRow(
                    title = "重新运行引导",
                    desc = "换手机、换航图库，或想重新指定目录时使用",
                    action = "重新开始引导",
                    onAction = onRerunOnboarding,
                )
            }

            SectionHeader("诊断与日志")
            DiagnosticsCard(
                vm = diagVm,
                onExport = pickExportTarget,
                onClear = { confirmClearLogs = true },
            )

            VersionFooter()
        }
    }

    if (confirmClearLogs) {
        AlertDialog(
            onDismissRequest = { confirmClearLogs = false },
            title = { Text("清空本机日志？") },
            text = {
                Text(
                    "运行日志与崩溃记录会被删除，且无法恢复。" +
                        "如果正准备反馈问题，请先导出再清空。已经导出的文件不受影响。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearLogs = false
                        diagVm.clearLogs()
                    },
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearLogs = false }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------- 卡片与行

/** 分组标题：小号、主色、带一点字距，用来切分卡片。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(content = content)
    }
}

/** 卡片内的分隔线：左右各缩 16dp，不切到圆角上。 */
@Composable
private fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun InfoRow(
    title: String,
    desc: String,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** 整行可点的开关行：点文字也能切，不必非去戳滑块。 */
@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        // onCheckedChange = null：点击交给整行处理，避免「行 + 滑块」各切一次
        Switch(
            checked = checked,
            onCheckedChange = null,
            // 关状态的默认轨道在这套深色底上几乎看不见，补一点对比
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/**
 * 带动作的行。
 *
 * `extra` 用来放动作产生的结果（比如导出的文件路径），放在按钮下方而不是替换说明文字 ——
 * 原来路径会直接顶掉说明，用户看不到「这个按钮是干什么的」。
 */
@Composable
private fun ActionRow(
    title: String,
    desc: String,
    action: String,
    onAction: () -> Unit,
    enabled: Boolean = true,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        OutlinedButton(
            onClick = onAction,
            enabled = enabled,
            modifier = Modifier.padding(top = 10.dp).height(36.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            Text(action, style = MaterialTheme.typography.labelLarge)
        }
        extra()
    }
}

// ---------------------------------------------------------------- 航图库卡片

@Composable
private fun LibraryCard(
    path: String?,
    error: String?,
    scanning: Boolean,
    onPick: () -> Unit,
    onCopy: (String) -> Unit,
    onRescan: (String) -> Unit,
) {
    SettingsCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "航图库目录",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                StatusPill(
                    text = when {
                        scanning -> "扫描中"
                        path == null -> "未设置"
                        error != null -> "有问题"
                        else -> "已就绪"
                    },
                    tone = when {
                        scanning -> Tone.Neutral
                        path == null -> Tone.Warn
                        error != null -> Tone.Bad
                        else -> Tone.Ok
                    },
                )
            }

            Text(
                text = path ?: "还没有选择航图库目录",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (path != null) FontFamily.Monospace else null,
                color = if (path != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (scanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(3.dp),
                )
            }

            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onPick,
                    enabled = !scanning,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Text(if (path == null) "选择目录" else "更换目录", style = MaterialTheme.typography.labelLarge)
                }
                if (path != null) {
                    TextButton(
                        onClick = { onRescan(path) },
                        enabled = !scanning,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Text("重新扫描", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(
                        onClick = { onCopy(path) },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Text("复制路径", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 诊断与日志卡片

@Composable
private fun DiagnosticsCard(
    vm: DiagnosticsViewModel,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    SettingsCard {
        SwitchRow(
            title = "记录运行日志",
            desc = "在本机保存运行日志与崩溃堆栈，出问题时可整份导出。关闭后不再写盘。",
            checked = vm.logEnabled,
            onChange = vm::applyLogEnabled,
        )
        CardDivider()
        InfoRow(
            title = "本机日志",
            desc = if (vm.logFileCount == 0) {
                "还没有日志"
            } else {
                "${vm.logFileCount} 个文件 · ${vm.logBytes / 1024} KB"
            },
            trailing = {
                StatusPill(
                    text = if (vm.crashCount == 0) "无崩溃" else "${vm.crashCount} 次崩溃",
                    tone = if (vm.crashCount == 0) Tone.Ok else Tone.Bad,
                )
            },
        )
        // 崩溃摘要单独用错误色摆出来：混在说明文字里会被当成普通描述划过去
        if (vm.lastCrash != null) {
            Text(
                text = "上次异常退出：" + vm.lastCrash!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            )
        }
        CardDivider()
        ActionRow(
            title = "导出日志",
            desc = "自己选保存位置：内部存储、SD 卡、网盘都行，文件名可当场改",
            action = if (vm.exporting) "导出中…" else "选择位置导出…",
            onAction = onExport,
            enabled = !vm.exporting,
        ) {
            if (vm.exportedName != null) {
                Text(
                    text = "已导出：" + vm.exportedName!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (vm.exportError != null) {
                Text(
                    text = vm.exportError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        CardDivider()
        ActionRow(
            title = "清空本机日志",
            desc = "删除运行日志与崩溃记录，不影响已导出的文件",
            action = "清空",
            onAction = onClear,
            enabled = vm.logFileCount > 0 || vm.crashCount > 0,
        )
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------- 小部件

private enum class Tone { Ok, Warn, Bad, Neutral }

@Composable
private fun StatusPill(text: String, tone: Tone) {
    val scheme = MaterialTheme.colorScheme
    val (bg: Color, fg: Color) = when (tone) {
        Tone.Ok -> scheme.tertiary.copy(alpha = 0.16f) to scheme.tertiary
        Tone.Warn -> scheme.inversePrimary.copy(alpha = 0.16f) to scheme.inversePrimary
        Tone.Bad -> scheme.error.copy(alpha = 0.18f) to scheme.error
        Tone.Neutral -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(percent = 50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
private fun VersionFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Flight EFB ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
        Text(
            text = "航图、检查单与导入的数据只保存在本机",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ---------------------------------------------------------------- 工具

private const val LOG_TAG = "EFB/SETTINGS"

private fun chartCount(vm: ChartViewModel): Int =
    vm.lastScan?.charts?.size ?: vm.index?.chartCount() ?: 0

private fun copyToClipboard(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}
