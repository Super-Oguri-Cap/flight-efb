package com.oguricap.efb.data

data class Airport(
    val icao: String,
    val name: String,
    val chartCount: Int,
)

data class Chart(
    val id: String,
    val icao: String,
    val airport: String,
    val chartType: String,
    val title: String,
    val file: String,
    val rwys: List<String>,
)

object ChartType {
    // 分组顺序按飞行流程排列，OTHER 兜底
    val order = listOf("ADC", "GMC", "APDC", "SID", "STAR", "IAC", "AOC", "ATCSMAC", "CODING", "ENR", "OTHER")

    private val labels = mapOf(
        "ADC" to "机场图",
        "GMC" to "滑行道图",
        "APDC" to "机位图",
        "SID" to "离场图",
        "STAR" to "进场图",
        "IAC" to "进近图",
        "AOC" to "障碍物图",
        "ATCSMAC" to "最低监视高度图",
        "CODING" to "编码表",
        "ENR" to "通用资料",
        "OTHER" to "其它",
    )

    fun rank(type: String): Int = order.indexOf(type).let { if (it < 0) order.size else it }

    fun label(type: String): String = labels[type] ?: type
}

// uid 只在用户自己创建的检查单（id 以 custom_ 开头）里有值，用来给勾选进度做稳定键：
// 用户可以随便重排阶段和条目，序号会变，uid 不变。
// 内置数据（assets 里的 JSON）没有 uid，留空串，仍然用「阶段序号/条目序号」当键。
data class ChecklistItem(
    val item: String,
    val state: String,
    val uid: String = "",
)

data class ChecklistPhase(
    val name: String,
    val gloss: String,
    val items: List<ChecklistItem>,
    val uid: String = "",
)

data class AircraftChecklist(
    val id: String,
    val category: String,
    val name: String,
    val note: String,
    val source: String,
    val license: String,
    val phases: List<ChecklistPhase>,
    val uid: String = "",
) {
    val itemCount: Int get() = phases.sumOf { it.items.size }
}

object AircraftCategory {
    val order = listOf("airbus", "boeing", "other")

    private val labels = mapOf(
        "airbus" to "空客",
        "boeing" to "波音",
        "other" to "其他",
    )

    fun rank(id: String): Int = order.indexOf(id).let { if (it < 0) order.size else it }

    fun label(id: String): String = labels[id] ?: id
}

data class QrhEntry(
    val ata: String,
    val title: String,
    val condition: String,
    val memory: Boolean,
    val actions: List<String>,
    val inop: String,
)

data class QrhSection(
    val id: String,
    val aircraft: String,
    val category: String,
    val title: String,
    val note: String,
    val entries: List<QrhEntry>,
)

data class QrhBook(
    val notice: String,
    val sections: List<QrhSection>,
)
