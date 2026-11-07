package com.oguricap.efb.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// 航图「选中」后的去处：离场区、进场区。
// 这两个分组是机场页顶部的置顶区，用来放本次飞行真正要用的几张图，
// 免得在几十张 SID/STAR 里反复找。
enum class PinKind(val key: String, val label: String) {
    DEPARTURE("departure", "离场"),
    ARRIVAL("arrival", "进场"),
    ;

    companion object {
        fun of(key: String): PinKind? = entries.firstOrNull { it.key == key }
    }
}

// 选中结果落盘在 SharedPreferences 里，跟航图库目录无关。
// 只存展示与打开需要的字段：换库或换机场后，打不开的条目仍是可见的，
// 用户可以自己把它移出去，而不是整组凭空消失。
class PinStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(kind: PinKind): List<Chart> {
        val raw = prefs.getString(kind.key, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                val file = row.optString("file")
                if (file.isBlank()) return@mapNotNull null
                Chart(
                    id = row.optString("id", file),
                    icao = row.optString("icao"),
                    airport = row.optString("airport"),
                    chartType = row.optString("chartType"),
                    title = row.optString("title"),
                    file = file,
                    rwys = row.optString("rwys").split(SEP).filter { it.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(kind: PinKind, charts: List<Chart>) {
        val array = JSONArray()
        charts.forEach { chart ->
            array.put(
                JSONObject().apply {
                    put("id", chart.id)
                    put("icao", chart.icao)
                    put("airport", chart.airport)
                    put("chartType", chart.chartType)
                    put("title", chart.title)
                    put("file", chart.file)
                    put("rwys", chart.rwys.joinToString(SEP))
                },
            )
        }
        prefs.edit().putString(kind.key, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "efb_pins"

        // 跑道号里没有竖线，用它当分隔符不会跟数据撞
        const val SEP = "|"
    }
}
