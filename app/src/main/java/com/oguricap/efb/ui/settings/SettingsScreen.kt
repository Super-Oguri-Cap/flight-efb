package com.oguricap.efb.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oguricap.efb.prefs.Settings
import com.oguricap.efb.ui.ChartViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: ChartViewModel,
    exclusivePhases: Boolean,
    onToggleExclusive: (Boolean) -> Unit,
    storageGranted: Boolean,
    onOpenSystemSettings: () -> Unit,
    onRerunOnboarding: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingBlock(
                title = "航图库目录",
                value = vm.chartRoot ?: "未设置",
                error = vm.rootError,
                action = null,
                onAction = null,
                monospace = true,
            )
            HorizontalDivider()
            SettingBlock(
                title = "索引来源",
                value = if (vm.indexOrigin == Settings.ORIGIN_SCAN)
                    "本机扫描生成，共 " + (vm.lastScan?.charts?.size ?: vm.index?.chartCount() ?: 0) + " 张"
                else "APK 预置（620 KB）",
                error = null,
                action = null,
                onAction = null,
            )
            HorizontalDivider()
            SettingBlock(
                title = "所有文件访问权限",
                value = if (storageGranted) "已授权" else "未授权",
                error = null,
                action = if (storageGranted) null else "去系统设置",
                onAction = onOpenSystemSettings,
            )
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("阅读页反色", fontWeight = FontWeight.Bold)
                    Text(
                        text = "深色背景下看白底航图时使用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = vm.invertReader,
                    onCheckedChange = { vm.applyInvert(it) },
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("检查单分组互斥", fontWeight = FontWeight.Bold)
                    Text(
                        text = "打开后一次只展开一个阶段：点开「起飞前」，其它阶段自动收起",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = exclusivePhases,
                    onCheckedChange = onToggleExclusive,
                )
            }
            HorizontalDivider()
            SettingBlock(
                title = "航图库结构规范",
                value = vm.specExportPath
                    ?: "整理新航图库时对照这份文档；点下面按钮生成到手机的 Download 目录",
                error = vm.specExportError,
                action = "生成规范文档",
                onAction = { vm.exportSpec() },
                monospace = vm.specExportPath != null,
            )
            HorizontalDivider()
            SettingBlock(
                title = "重新运行引导",
                value = "换手机、换航图库、或想重新指定目录时使用",
                error = null,
                action = "重新开始引导",
                onAction = onRerunOnboarding,
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SettingBlock(
    title: String,
    value: String,
    error: String?,
    action: String?,
    onAction: (() -> Unit)?,
    monospace: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (action != null && onAction != null) {
            Text(
                text = action,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp).clickable(onClick = onAction),
            )
        }
    }
}