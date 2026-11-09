package com.oguricap.efb.perf

import android.os.SystemClock
import com.oguricap.efb.diag.AppLog

object Perf {
    const val TAG = "PERF"

    inline fun <T> timed(name: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            AppLog.i(TAG, name + "=" + String.format("%.1f", ms) + "ms")
        }
    }

    fun mark(name: String, ms: Double) {
        AppLog.i(TAG, name + "=" + String.format("%.1f", ms) + "ms")
    }
}
