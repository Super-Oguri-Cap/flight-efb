package com.oguricap.efb.data

import android.content.Context
import android.content.SharedPreferences

// 检查单勾选进度：按机型各存一份，键是「阶段序号/条目序号」。
//
// 为什么用序号而不是条目正文：同一阶段里会出现同名条目（比如两个「高度表指示」），
// 用文本当键会一起勾上；序号在同一份数据里是稳定的，转换脚本重跑后顺序不变就仍然对得上。
class ChecklistProgress(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("efb_checklist_progress", Context.MODE_PRIVATE)

    fun load(aircraftId: String): Set<String> {
        val raw = prefs.getString(key(aircraftId), null)
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(SEP).filter { it.isNotBlank() }.toSet()
    }

    fun save(aircraftId: String, keys: Set<String>) {
        if (keys.isEmpty()) {
            prefs.edit().remove(key(aircraftId)).apply()
            return
        }
        prefs.edit().putString(key(aircraftId), keys.joinToString(SEP)).apply()
    }

    private fun key(aircraftId: String) = "p_" + aircraftId

    companion object {
        const val SEP = ","

        // 条目在界面上的稳定标识
        fun itemKey(phaseIndex: Int, itemIndex: Int): String = phaseIndex.toString() + "/" + itemIndex
    }
}
