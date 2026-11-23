package com.oguricap.efb.perf

import android.os.SystemClock
import com.oguricap.efb.diag.AppLog
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object Perf {
    const val TAG = "PERF"

    /**
     * 冷启动起点，在 `Application.onCreate` 的第一行取。
     *
     * **这是一个近似值，不是真正的进程创建时刻**：`Application.onCreate` 已经在
     * 进程 fork、`LoadedApk` 装配、`ContentProvider` 初始化之后才被调用，
     * 那一段通常有几十毫秒（低端机更多），这里量不到。
     *
     * 之所以不退到更早：Android 没有给应用任何「进程创建」回调，
     * `SystemClock.elapsedRealtime()` 的零点也是开机时刻而不是进程起点。
     * 想拿到真正的冷启动时间只能靠 `adb shell am start -W` 的 `TotalTime`
     * （见 docs/性能目标.md 第 6.2 节），那是系统侧量的，不是应用内埋点。
     *
     * 结论：这个数用于**版本之间横向比较**（同一台机器、同一个口径），
     * 不能直接和 `TotalTime` 对齐。
     */
    @Volatile
    private var appStartUptimeMs: Long = -1L

    /** 首帧是否已经记过。见 [markFirstFrame]。 */
    private val appStartConsumed = AtomicBoolean(false)

    /** 由 `Application.onCreate` 调用一次。重复调用只记第一次。 */
    fun markAppStart() {
        if (appStartUptimeMs >= 0L) return
        appStartUptimeMs = SystemClock.elapsedRealtime()
    }

    /**
     * 首帧上屏时记一条 `cold_start_to_first_frame`。
     *
     * 只记第一次：用 `compareAndSet` 把起点消费掉，之后的重组再调也不会重复写日志。
     * `doOnPreDraw` 每帧都会回调，没有这一层保护就会刷出一串越来越大的数字。
     *
     * 没有先调 [markAppStart] 时（例如直接从 ViewModel 起的测试）什么也不记，
     * 不会写出一条 -1 的假数字。
     */
    fun markFirstFrame() {
        val start = appStartUptimeMs
        if (start < 0L) return
        // 两个线程同时进来的概率很低（只有主线程画帧），但 CAS 的代价一样低
        if (!appStartConsumed.compareAndSet(false, true)) return
        val ms = (SystemClock.elapsedRealtime() - start).toDouble()
        AppLog.i(TAG, "cold_start_to_first_frame=" + String.format(Locale.US, "%.1f", ms) + "ms")
    }

    inline fun <T> timed(name: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            AppLog.i(TAG, name + "=" + String.format(Locale.US, "%.1f", ms) + "ms")
        }
    }

    fun mark(name: String, ms: Double) {
        AppLog.i(TAG, name + "=" + String.format(Locale.US, "%.1f", ms) + "ms")
    }
}
