package com.oguricap.efb

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oguricap.efb.ui.ChartViewModel
import com.oguricap.efb.ui.EfbApp
import com.oguricap.efb.ui.onboarding.OnboardingScreen
import com.oguricap.efb.ui.theme.EfbTheme

class MainActivity : ComponentActivity() {

    private var storageGranted by mutableStateOf(false)
    private var pendingRoot: ((String) -> Unit)? = null
    private var pickFailure by mutableStateOf<String?>(null)

    private val treePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val callback = pendingRoot
            pendingRoot = null
            // 用户取消时什么都不做，不能把上一次的失败提示留在界面上
            if (uri == null) return@registerForActivityResult
            when (val resolved = TreePaths.resolve(uri)) {
                is TreePaths.Result.Ok -> {
                    pickFailure = null
                    callback?.invoke(resolved.path)
                }
                is TreePaths.Result.UnsupportedProvider ->
                    pickFailure = "请从「内部存储」进入并选中 AIP航图库。" +
                        "刚刚选中的位置来自其它来源，无法还原成文件路径。"
                is TreePaths.Result.Unparseable ->
                    pickFailure = "无法从这个位置解析出文件路径，请改选内部存储里的目录。"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        storageGranted = hasStorageAccess()
        setContent {
            EfbTheme {
                val vm: ChartViewModel = viewModel()
                var ready by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    vm.load()
                    ready = true
                }
                val pickRoot: (onPath: (String) -> Unit) -> Unit = { onPath ->
                    pickFailure = null
                    pendingRoot = onPath
                    treePicker.launch(null)
                }
                if (!vm.onboardingDone) {
                    OnboardingScreen(
                        vm = vm,
                        storageGranted = storageGranted,
                        onRequestStorage = ::requestStorageAccess,
                        onPickRoot = pickRoot,
                        pickFailure = pickFailure,
                        onFinish = { },
                    )
                } else {
                    EfbApp(
                        vm = vm,
                        ready = ready,
                        storageGranted = storageGranted,
                        onRequestStorage = ::requestStorageAccess,
                        onPickRoot = pickRoot,
                        onRestartOnboarding = { vm.resetOnboarding() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        storageGranted = hasStorageAccess()
    }

    // HyperOS 等定制系统在某些运行环境（手机分身、平行空间）里，
    // Environment.isExternalStorageManager() 内部会读到空的用户数组，
    // 直接抛 ArrayIndexOutOfBoundsException；首次启动就在 onCreate 里闪退。
    // 这个探测只用于推进引导界面，不是功能开关：
    // 抛异常时按「未授权」处理，扫描步骤会拿真实目录读取做最终判定，
    // 授权确实已开时用户点「已授权，继续」也能往下走。
    private fun hasStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    }

    // 部分定制系统没有实现应用级的「所有文件访问权限」页面，
    // 依次回退到全局页面与应用详情页，避免直接抛 ActivityNotFoundException。
    private fun requestStorageAccess() {
        val packageUri = Uri.parse("package:" + packageName)
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
        for (intent in candidates) {
            val opened = runCatching { startActivity(intent) }.isSuccess
            if (opened) return
        }
        throw IllegalStateException("这台设备上找不到可以打开文件权限设置的页面")
    }
}