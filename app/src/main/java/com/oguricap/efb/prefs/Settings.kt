package com.oguricap.efb.prefs

import android.content.Context
import android.content.SharedPreferences

class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("efb", Context.MODE_PRIVATE)

    var chartRoot: String?
        get() = prefs.getString(KEY_ROOT, null)
        set(value) {
            prefs.edit().putString(KEY_ROOT, value).apply()
        }

    var invertInReader: Boolean
        get() = prefs.getBoolean(KEY_INVERT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_INVERT, value).apply()
        }

    var lastQuery: String
        get() = prefs.getString(KEY_QUERY, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_QUERY, value).apply()
        }

    // 引导是否走完。只有走完并且目录可用，才会进主界面。
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()
        }

    // 检查单分组互斥：开一次只展开一个阶段，避免一屏里摊开好几份
    var exclusiveChecklistPhases: Boolean
        get() = prefs.getBoolean(KEY_EXCLUSIVE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EXCLUSIVE, value).apply()
        }

    // 索引来源：asset 表示用 APK 预置的，scan 表示在手机上扫描目录生成的
    var indexOrigin: String
        get() = prefs.getString(KEY_ORIGIN, ORIGIN_ASSET).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ORIGIN, value).apply()
        }

    companion object {
        const val ORIGIN_ASSET = "asset"
        const val ORIGIN_SCAN = "scan"

        private const val KEY_ROOT = "chart_root"
        private const val KEY_INVERT = "invert_reader"
        private const val KEY_QUERY = "last_query"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val KEY_ORIGIN = "index_origin"
        private const val KEY_EXCLUSIVE = "exclusive_checklist_phases"
    }
}
