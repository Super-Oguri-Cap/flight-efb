package com.oguricap.efb.ui.settings

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oguricap.efb.diag.AppLog
import com.oguricap.efb.diag.CrashGuard
import com.oguricap.efb.diag.LogExporter
import com.oguricap.efb.prefs.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页「诊断与日志」卡片的状态。
 *
 * 单独一个 ViewModel 而不是塞进 [com.oguricap.efb.ui.ChartViewModel]：
 * 日志与航图索引没有任何关系，混在一起会让航图那边的测试被迫关心日志文件。
 * 工程里已经是「一个领域一个 ViewModel」（Chart / Reference / Tools），这里照旧。
 *
 * 构造器必须只有 (Application) 一个参数：默认工厂靠反射找这个签名，
 * 多一个带默认值的参数就不会生成它，进设置页即闪退（ChartViewModel 上踩过）。
 */
class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)

    @JvmSynthetic
    internal var io: CoroutineDispatcher = Dispatchers.IO

    var logEnabled by mutableStateOf(settings.logEnabled)
        private set

    var logBytes by mutableStateOf(0L)
        private set

    var logFileCount by mutableStateOf(0)
        private set

    var crashCount by mutableStateOf(0)
        private set

    /** 最近一次崩溃的时间与异常首行；没有记录时为 null。 */
    var lastCrash by mutableStateOf<String?>(null)
        private set

    var exporting by mutableStateOf(false)
        private set

    /** 导出成功后的文件名。SAF 只给 Uri，拿不到可靠的绝对路径，所以显示用户自己起的名字。 */
    var exportedName by mutableStateOf<String?>(null)
        private set

    var exportError by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun consumeMessage() {
        message = null
    }

    // 名字不能叫 setLogEnabled：属性 logEnabled 的私有 setter 已经占了这个 JVM 签名，
    // 同名会编译期报 Platform declaration clash（ToolsBridge 上踩过同一类坑）
    fun applyLogEnabled(value: Boolean) {
        logEnabled = value
        settings.logEnabled = value
        AppLog.enabled = value
        AppLog.i(TAG, if (value) "运行日志已开启" else "运行日志已关闭")
        refresh()
    }

    /** 选择器里预填的名字，每次点导出都重新生成，避免两次导出撞同一个文件名。 */
    fun suggestedFileName(): String = LogExporter.suggestedName()

    /**
     * 写到用户在选择器里指定的位置。取消选择时 SettingsScreen 根本不会调到这里。
     */
    fun exportTo(uri: Uri) {
        if (exporting) return
        exporting = true
        exportError = null
        viewModelScope.launch {
            val result = withContext(io) {
                runCatching { LogExporter.export(getApplication(), uri) }
            }
            exporting = false
            result.onSuccess { name ->
                exportedName = name
                message = "已导出 $name"
            }.onFailure {
                exportedName = null
                exportError = it.message ?: it.toString()
                AppLog.e(TAG, "日志导出失败", it)
                message = "导出失败：" + (it.message ?: it.javaClass.simpleName)
            }
            refresh()
        }
    }

    /** 清掉本机的运行日志与崩溃记录；已经导出的文件不受影响。 */
    fun clearLogs() {
        viewModelScope.launch {
            withContext(io) {
                CrashGuard.clear(getApplication())
                AppLog.clear()
            }
            AppLog.i(TAG, "本机日志与崩溃记录已清空")
            exportedName = null
            exportError = null
            message = "已清空本机日志"
            refresh()
        }
    }

    /**
     * 重新统计体积与崩溃记录。
     *
     * 先 flush：日志是排队写盘的，不等一下就会少算最后几条，
     * 用户刚复现完问题来看体积却发现是 0，会以为日志根本没记。
     */
    fun refresh() {
        viewModelScope.launch {
            val stats = withContext(io) {
                AppLog.flush()
                val app = getApplication<Application>()
                Stats(
                    bytes = AppLog.sizeBytes(app),
                    files = AppLog.logFiles(app).size,
                    crashes = CrashGuard.crashFiles(app).size,
                    lastCrash = CrashGuard.lastCrash(app),
                )
            }
            logBytes = stats.bytes
            logFileCount = stats.files
            crashCount = stats.crashes
            lastCrash = stats.lastCrash
        }
    }

    private data class Stats(
        val bytes: Long,
        val files: Int,
        val crashes: Int,
        val lastCrash: String?,
    )

    companion object {
        private const val TAG = "EFB/LOG"
    }
}