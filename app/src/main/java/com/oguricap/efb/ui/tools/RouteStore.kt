package com.oguricap.efb.ui.tools

import android.content.Context
import android.content.SharedPreferences
import com.oguricap.efb.diag.AppLog
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 本机航路库（不含任何导航数据，只存用户主动保存的方案）。
 *
 * 为什么只有点「保存」才写盘：航路生成器每次改动输入都会重算一遍，屏幕上的方案只是
 * 一次浏览结果。若生成即落盘，一次完整规划会留下十几条中间结果，反而把真正要用的
 * 航线淹掉；所以这里只暴露 [add]，没有任何「自动同步当前方案」的入口。
 */
class RouteStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 全部已存航路，最近保存的在前。
     *
     * 单条解析失败就跳过：航路库是用户自己攒的数据，宁可少显示一条，
     * 也不能因为一条坏 JSON 让整个工具页打不开。
     */
    fun list(): List<SavedRoute> {
        val arr = readArray()
        val out = ArrayList<SavedRoute>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += fromJson(o) ?: continue
        }
        return out
    }

    /** 保存一条方案。返回落盘后的记录，界面直接拿去提示「已保存 xxx」。 */
    fun add(name: String, from: String, to: String, plan: PlannedRoute): SavedRoute {
        val record = SavedRoute(
            id = newId(),
            name = name,
            from = from,
            to = to,
            distNM = plan.distNM,
            route = plan.route,
            kind = plan.kind,
            savedAt = System.currentTimeMillis(),
        )
        val arr = JSONArray()
        // 新记录插到头部：打开航路库第一眼看到的就是刚存的那条，不用往下翻
        arr.put(toJson(record))
        val old = readArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            arr.put(o)
        }
        writeArray(arr)
        return record
    }

    /**
     * 改名。空名字会让列表行变成一条没有标题的横线，之后再也分不清是哪条，直接忽略。
     */
    fun rename(id: String, name: String) {
        val newName = name.trim()
        if (newName.isEmpty()) return

        val arr = readArray()
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id", "") != id) continue
            runCatching { o.put("name", newName) }
            changed = true
        }
        if (changed) writeArray(arr)
    }

    fun delete(id: String) {
        val arr = readArray()
        val kept = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id", "") == id) {
                removed = true
                continue
            }
            kept.put(o)
        }
        if (!removed) return
        writeArray(kept)

        // 删掉的正好是当前航路时必须一并清 active：否则油量页会继续按一个已不存在的
        // 距离去填「航段距离」，等于把上一份计划的数字悄悄带进下一次计算。
        if (activeIdRaw() == id) prefs.edit().remove(KEY_ACTIVE).apply()
    }

    /**
     * 当前航路 id。
     *
     * 会回头校验实体是否存在：数据可能被清掉或手工改坏，指不到实体的 active
     * 一律当作「没有」，免得界面一直显示「当前」却找不到对应航路。
     */
    fun activeId(): String? {
        val id = activeIdRaw() ?: return null
        if (list().none { it.id == id }) {
            prefs.edit().remove(KEY_ACTIVE).apply()
            return null
        }
        return id
    }

    /** 设为当前；传 null 表示取消。不存在的 id 同样按取消处理。 */
    fun setActive(id: String?) {
        if (id == null || list().none { it.id == id }) {
            prefs.edit().remove(KEY_ACTIVE).apply()
            return
        }
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    // ---------------------------------------------------------------- 存储细节

    private fun readArray(): JSONArray {
        val raw = prefs.getString(KEY_ROUTES, null)
        if (raw.isNullOrBlank()) return JSONArray()
        return runCatching { JSONArray(raw) }
            .onFailure { AppLog.w(TAG, "航路库 JSON 解析失败，按空处理（${raw.length} 字符）", it) }
            .getOrElse { JSONArray() }
    }

    private fun writeArray(arr: JSONArray) {
        prefs.edit().putString(KEY_ROUTES, arr.toString()).apply()
    }

    private fun activeIdRaw(): String? = prefs.getString(KEY_ACTIVE, null)

    // 毫秒级时间戳 + 短随机后缀：同一毫秒内连点两次保存也不会生成同一个 id
    private fun newId(): String =
        System.currentTimeMillis().toString() + "-" + Random.nextInt(0x1000, 0x10000).toString(16)

    private fun toJson(r: SavedRoute): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("name", r.name)
        put("from", r.from)
        put("to", r.to)
        put("distNM", r.distNM)
        // 航路点串可能很长，SharedPreferences 存字符串没有长度限制，按原样保存
        put("route", r.route)
        put("kind", r.kind)
        put("savedAt", r.savedAt)
    }

    private fun fromJson(o: JSONObject): SavedRoute? = runCatching {
        val id = o.optString("id", "")
        // 没有 id 的记录再也定位不到，留着只会占位，直接跳过
        if (id.isBlank()) return@runCatching null
        SavedRoute(
            id = id,
            name = o.optString("name", "").ifBlank { "未命名航路" },
            from = o.optString("from", ""),
            to = o.optString("to", ""),
            distNM = o.optInt("distNM", 0),
            route = o.optString("route", ""),
            kind = o.optString("kind", "engine"),
            savedAt = o.optLong("savedAt", 0L),
        )
    }.onFailure { AppLog.w(TAG, "单条航路数据损坏，已跳过", it) }.getOrNull()

    companion object {
        private const val TAG = "EFB/ROUTES"
        private const val PREF = "efb_tool_routes"
        private const val KEY_ROUTES = "routes"
        private const val KEY_ACTIVE = "active"
    }
}
