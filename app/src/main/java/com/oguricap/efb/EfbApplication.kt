package com.oguricap.efb

import android.app.Application
import android.os.StrictMode
import com.oguricap.efb.prefs.Settings

class EfbApplication : Application() {

    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
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
