package com.oguricap.efb

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.oguricap.efb.diag.AppLog
import com.oguricap.efb.diag.CrashGuard
import com.oguricap.efb.prefs.Settings

class EfbApplication : Application() {

    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        // 诊断设施必须先装好，后面任何一步炸了才有堆栈可看。
        // 整段容错：这个工程已经两次因为 onCreate 里的设备特异行为首启闪退
        // （isExternalStorageManager、ViewModel 构造器），日志设施本身绝不能成为第三个。
        // 装不上就退回只有 logcat，应用照常启动。
        runCatching {
            CrashGuard.install(this)
            AppLog.init(this, settings.logEnabled)
        }.onFailure { Log.e("EFB/LOG", "诊断设施初始化失败，本次只记 logcat", it) }

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
}
