package com.oguricap.efb.perf

import android.os.SystemClock
import android.util.Log

object Perf {
    const val TAG = "PERF"

    inline fun <T> timed(name: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            Log.i(TAG, name + "=" + String.format("%.1f", ms) + "ms")
        }
    }

    fun mark(name: String, ms: Double) {
        Log.i(TAG, name + "=" + String.format("%.1f", ms) + "ms")
    }
}
