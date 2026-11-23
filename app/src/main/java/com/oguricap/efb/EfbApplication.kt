package com.oguricap.efb

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.StrictMode
import android.util.Log
import com.oguricap.efb.diag.AppLog
import com.oguricap.efb.diag.CrashGuard
import com.oguricap.efb.perf.Perf
import com.oguricap.efb.prefs.Settings
import com.oguricap.efb.ui.reader.PdfDocument

class EfbApplication : Application() {

    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        // 冷启动计时起点。必须是 onCreate 的第一句：后面每多一行都会算进「启动耗时」，
        // 而这里量的是应用自己的开销（进程创建与 LoadedApk 装配量不到，见 Perf.markAppStart）。
        Perf.markAppStart()
        settings = Settings(this)
        // 诊断设施必须先装好，后面任何一步炸了才有堆栈可看。
        // 整段容错：这个工程已经两次因为 onCreate 里的设备特异行为首启闪退
        // （isExternalStorageManager、ViewModel 构造器），日志设施本身绝不能成为第三个。
        // 装不上就退回只有 logcat，应用照常启动。
        runCatching {
            CrashGuard.install(this)
            AppLog.init(this, settings.logEnabled)
        }.onFailure { Log.e("EFB/LOG", "诊断设施初始化失败，本次只记 logcat", it) }

        // 位图缓存的内存压力回调。性能目标 §2.4 要求用 onTrimMemory 主动清缓存：
        // 单页 1280 宽 ARGB_8888 是 9.1 MB，三档缓存叠起来能到几十兆，
        // 系统在后台压内存时不清就会先被系统干掉整个进程。
        registerComponentCallbacks(MemoryCallbacks)

        if (BuildConfig.DEBUG) {
            // 主线程任何磁盘读写都记一条，性能目标 3.3 的验收手段
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }
    }

    /**
     * 内存压力回调。
     *
     * 只接 `onTrimMemory`，不做 `onLowMemory`（后者是最后一道，通常来不及）；
     * 也不在这里做任何 IO —— 只把「清哪一层缓存」这个决定交给 [PdfDocument]，
     * 真正的位图释放由它在自己的渲染锁上异步完成，避免在系统回调里阻塞主线程。
     *
     * `TRIM_MEMORY_RUNNING_*` 与 `TRIM_MEMORY_COMPLETE` 在 SDK 36 上标了废弃，
     * 但没有替代的等价常量，而它们的等级语义正是这里要用的分档依据，
     * 所以局部抑制这条警告而不是换成一堆魔法数字。
     */
    @Suppress("DEPRECATION")
    private object MemoryCallbacks : ComponentCallbacks2 {

        override fun onTrimMemory(level: Int) {
            when {
                // 进程在后台且内存已经很低：连基准缓存一起清，回来时重新渲染当前页。
                // 这时用户看不到界面，重渲染的代价最小。
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                    PdfDocument.trimAllCaches(aggressive = true)

                // 前台运行但内存偏低：只腾预览与高清这两层，
                // 基准缓存留着，界面不会因为这次清理而白屏。
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                    PdfDocument.trimAllCaches(aggressive = false)

                else -> Unit
            }
        }

        // onLowMemory 在 SDK 36 上标了废弃（推荐改用 onTrimMemory），但它仍是
        // 「内存已经见底」这一档的唯一回调，而且没有替代品；这里保留实现，
        // 并把「覆盖废弃成员」这条诊断一并抑制掉。
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() {
            PdfDocument.trimAllCaches(aggressive = true)
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }
}
