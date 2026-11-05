package com.oguricap.efb.data

import java.io.File

// 与 naip_index.py 保持同一套解析规则：
// 一级目录形如 ZBAA_北京_首都 或 00_ENR通用资料；
// 二级目录以 01_ ~ 10_ 开头，对应十种图型。
// 00_ENR通用资料 下的 PDF 直接放在一级目录里，统一归为 ENR 图型。
// 目录命名不符合规则时记入 issues，绝不猜测。
object ChartScanner {

    private val AIRPORT_DIR = Regex("^(Z[A-Z]{3})_(.+)$")
    private val RWY = Regex("RWY\\s*([0-9]{2}[LRC]?)", RegexOption.IGNORE_CASE)
    private const val ENR_PREFIX = "00_"

    private val TYPE_BY_PREFIX = listOf(
        "01_" to "ADC",
        "02_" to "GMC",
        "03_" to "APDC",
        "04_" to "SID",
        "05_" to "IAC",
        "06_" to "STAR",
        "07_" to "AOC",
        "08_" to "ATCSMAC",
        "09_" to "CODING",
        "10_" to "OTHER",
    )

    data class Issue(val path: String, val reason: String)

    data class Result(
        val charts: List<Chart>,
        val airports: List<Airport>,
        val issues: List<Issue>,
    ) {
        val isEmpty: Boolean get() = charts.isEmpty()
    }

    fun scan(root: File, onProgress: (scanned: Int) -> Unit = {}): Result {
        // 未授予所有文件访问权限时 listFiles() 返回 null。
        // 必须在这里明确报错，否则会被当成「目录里没有航图」，
        // 用户会去反复调整目录层级，而真正的问题是权限。
        if (!root.isDirectory) {
            throw IllegalArgumentException("目录不存在或不可读：" + root.absolutePath)
        }
        if (root.listFiles() == null) {
            throw SecurityException(
                "读不到这个目录的内容。请先在系统设置里给本应用打开「所有文件访问权限」。",
            )
        }

        val charts = mutableListOf<Chart>()
        val issues = mutableListOf<Issue>()
        val counts = mutableMapOf<String, Int>()
        val names = mutableMapOf<String, String>()
        var scanned = 0

        fun accept(pdf: File, icao: String, airportName: String, chartType: String) {
            val title = pdf.nameWithoutExtension
            val rel = relative(root, pdf)
            charts += Chart(
                id = rel,
                icao = icao,
                airport = airportName,
                chartType = chartType,
                title = title,
                file = rel,
                rwys = RWY.findAll(title).map { it.groupValues[1] }.toList(),
            )
            counts[icao] = (counts[icao] ?: 0) + 1
            names.putIfAbsent(icao, airportName)
        }

        // 根目录下直接放的 PDF 不属于任何一级目录，必须逐个记下来。
        // 否则用户选了一个平铺的目录，会看到「0 张 / 0 个机场 / 0 个失败」，无从判断错在哪。
        for (pdf in root.listFiles().orEmpty().sortedBy { it.name }.filter { it.isFile && it.isPdf() }) {
            issues += Issue(relative(root, pdf), "PDF 直接放在所选目录下，没有按 机场/图型/ 的目录结构存放")
        }

        val firstLevel = root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        for (airportDir in firstLevel) {
            val (icao, airportName) = parseAirportDir(airportDir.name)
            val entries = airportDir.listFiles().orEmpty().sortedBy { it.name }

            // 直接放在一级目录里的 PDF：只有 00_ENR通用资料 这种才成立，统一按 ENR 处理
            for (pdf in entries.filter { it.isFile && it.isPdf() }) {
                scanned++
                if (scanned % 200 == 0) onProgress(scanned)
                when {
                    icao == null ->
                        issues += Issue(relative(root, pdf), "一级目录既不是机场目录也不是 00_ 通用资料")
                    icao == "ENR" -> accept(pdf, "ENR", "通用资料", "ENR")
                    else ->
                        issues += Issue(relative(root, pdf), "PDF 直接放在机场目录下，缺少图型子目录")
                }
            }

            for (typeDir in entries.filter { it.isDirectory }) {
                val chartType = chartTypeOf(typeDir.name)
                for (pdf in typeDir.listFiles().orEmpty().sortedBy { it.name }.filter { it.isFile && it.isPdf() }) {
                    scanned++
                    if (scanned % 200 == 0) onProgress(scanned)
                    when {
                        icao == null ->
                            issues += Issue(relative(root, pdf), "一级目录既不是机场目录也不是 00_ 通用资料")
                        chartType == null ->
                            issues += Issue(relative(root, pdf), "二级目录名不在图型枚举内")
                        else -> accept(pdf, icao, airportName.orEmpty(), chartType)
                    }
                }
            }
        }

        onProgress(scanned)
        val airports = counts.keys.sorted().map { key ->
            Airport(key, names[key].orEmpty(), counts.getValue(key))
        }
        return Result(charts, airports, issues)
    }

    private fun File.isPdf(): Boolean = name.endsWith(".pdf", ignoreCase = true)

    fun parseAirportDir(name: String): Pair<String?, String?> {
        AIRPORT_DIR.matchEntire(name)?.let { m ->
            return m.groupValues[1] to m.groupValues[2].replace("_", "")
        }
        if (name.startsWith(ENR_PREFIX)) return "ENR" to "通用资料"
        return null to null
    }

    fun chartTypeOf(name: String): String? =
        TYPE_BY_PREFIX.firstOrNull { name.startsWith(it.first) }?.second

    private fun relative(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
}