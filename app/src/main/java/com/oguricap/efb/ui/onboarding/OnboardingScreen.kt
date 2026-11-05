package com.oguricap.efb.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oguricap.efb.ui.ChartViewModel

private const val TOTAL_STEPS = 4

// 首次进入的引导。四步：欢迎、授权、选目录并扫描、结果确认。
// 权限授予要离开应用，onResume 回来后 storageGranted 变化会推动这一步自动前进。
@Composable
fun OnboardingScreen(
    vm: ChartViewModel,
    storageGranted: Boolean,
    onRequestStorage: () -> Unit,
    onPickRoot: (onPath: (String) -> Unit) -> Unit,
    pickFailure: String?,
    onFinish: () -> Unit,
) {
    // 只有「从未走过引导」时才允许跳过欢迎页；重新运行引导固定从第一屏开始，
    // 那里的目录结构说明正是重新选目录时最需要看的。
    val firstRun = remember { !vm.hasCompletedOnboardingBefore }
    var step by remember(vm.onboardingDone) {
        mutableIntStateOf(if (firstRun && storageGranted) 1 else 0)
    }

    // 权限在系统设置里被授予后自动进入下一步
    LaunchedEffect(storageGranted) {
        if (storageGranted && step == 1) step = 2
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        ) {
            StepIndicator(step)
            Spacer(Modifier.height(20.dp))
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when (step) {
                    0 -> WelcomeStep(onNext = { step = 1 })
                    1 -> PermissionStep(storageGranted) {
                        if (storageGranted) step = 2 else onRequestStorage()
                    }
                    2 -> RootStep(
                        vm = vm,
                        onPickRoot = onPickRoot,
                        pickFailure = pickFailure,
                        onScanned = { step = 3 },
                    )
                    else -> ResultStep(vm = vm, onFinish = onFinish, onBack = { step = 2 })
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(TOTAL_STEPS) { index ->
            val active = index <= step
            Box(
                modifier = Modifier
                    .size(if (index == step) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "第 " + (step + 1) + " / " + TOTAL_STEPS + " 步",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column {
        Text("飞行EFB", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "离线航图 · 检查单 · QRH",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "第一次使用需要做两件事：打开「所有文件访问权限」，然后指定手机上航图库所在的目录。" +
                "做完这一步，以后启动就直接进主界面，全程不需要网络。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("关于航图库", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "航图库是一批按固定命名的文件夹，形如：",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "AIP航图库/\n" +
                        "├─ ZBAA_北京_首都/\n" +
                        "│   ├─ 01_机场图ADC/\n" +
                        "│   ├─ 05_进近图IAC/\n" +
                        "│   └─ ...\n" +
                        "├─ ZBGG_广州_白云/\n" +
                        "└─ 00_ENR通用资料/",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "一级目录是机场（四字码_城市_机场名），二级目录是图型（01_ 到 10_）。" +
                        "下一步会在这台设备上扫一遍，把结果建成索引。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text("开始")
        }
    }
}

@Composable
private fun PermissionStep(storageGranted: Boolean, onAction: () -> Unit) {
    Column {
        Text("第 1 步 · 所有文件访问权限", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "航图放在手机的公共存储里，Android 只允许拿到这个权限的应用按路径直接读取。" +
                "点下面的按钮会跳到系统设置页，找到「飞行EFB」并把开关打开，然后按返回键回到这里。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("当前状态", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (storageGranted) "已授权" else "未授权",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (storageGranted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
            Text(if (storageGranted) "已授权，继续" else "去系统设置授权")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "如果按钮点了没有反应，可以手动前往：系统设置 → 应用 → 飞行EFB → 权限 → 所有文件访问权限。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RootStep(
    vm: ChartViewModel,
    onPickRoot: (onPath: (String) -> Unit) -> Unit,
    pickFailure: String?,
    onScanned: () -> Unit,
) {
    LaunchedEffect(vm.lastScan) {
        val scanned = vm.lastScan
        if (scanned != null && !scanned.isEmpty && !vm.scanning) onScanned()
    }

    Column {
        Text("第 2 步 · 选择航图库目录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "在系统选择器里选中 AIP航图库 这一层，也就是打开后能直接看到 ZBAA_北京_首都、" +
                "00_ENR通用资料 这些文件夹的位置。选外层或选进某个机场目录都会扫描不到。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("当前目录", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = vm.chartRoot ?: "尚未选择",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                if (vm.lastScan != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "上次扫描：" + vm.lastScan!!.charts.size + " 张航图 / " +
                            vm.lastScan!!.airports.size + " 个机场",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (pickFailure != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = pickFailure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (vm.rootError != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = vm.rootError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(20.dp))
        if (vm.scanning) {
            Text("正在扫描目录…", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                text = "已读取 " + vm.scanProgress + " 个文件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(
                onClick = { onPickRoot { path -> vm.scanInto(path) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (vm.chartRoot.isNullOrBlank()) "选择航图库目录" else "重新选择目录")
            }
        }
    }
}

@Composable
private fun ResultStep(vm: ChartViewModel, onFinish: () -> Unit, onBack: () -> Unit) {
    val scan = vm.lastScan
    Column {
        Text("第 3 步 · 扫描结果", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (scan == null) {
            Text("还没有扫描结果，请回到上一步选择目录。", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("返回选择目录")
            }
            return@Column
        }
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                ResultLine("航图", scan.charts.size.toString() + " 张")
                ResultLine("机场", scan.airports.size.toString() + " 个")
                ResultLine("解析失败", scan.issues.size.toString() + " 个")
                ResultLine("目录", vm.chartRoot ?: "")
            }
        }
        if (scan.issues.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "以下文件没能解析出机场或图型，未进入索引：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            scan.issues.take(10).forEach { issue ->
                Text(
                    text = issue.path + "  —  " + issue.reason,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (scan.issues.size > 10) {
                Text(
                    text = "…… 另有 " + (scan.issues.size - 10) + " 个",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { if (vm.completeOnboarding()) onFinish() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !scan.isEmpty,
        ) {
            Text("进入主界面")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("重新选择目录")
        }
    }
}

@Composable
private fun ResultLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}