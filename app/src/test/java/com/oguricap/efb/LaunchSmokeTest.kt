package com.oguricap.efb

import android.content.Intent
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

// 启动冒烟测试：在 JVM 上完整走一遍进程创建 → Application.onCreate →
// Activity 创建 → Compose 首帧组合。闪退类问题在这里同样会暴露。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchSmokeTest {

    @Test
    fun applicationStarts() {
        val app = ApplicationProvider.getApplicationContext<EfbApplication>()
        assertNotNull(app.settings)
    }

    @Test
    fun activityLaunchesWithoutCrash() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        assertNotNull(controller.get())
    }

    // HyperOS 手机分身等环境下 Environment.isExternalStorageManager()
    // 内部会抛 ArrayIndexOutOfBoundsException，Activity 不能被它带崩
    @Test
    @Config(shadows = [BrokenEnvironmentShadow::class])
    fun activitySurvivesBrokenStorageManagerProbe() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        assertNotNull(controller.get())
    }
}

@Implements(Environment::class)
class BrokenEnvironmentShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun isExternalStorageManager(): Boolean {
            throw ArrayIndexOutOfBoundsException("Index 0 out of bounds for length 0")
        }
    }
}
