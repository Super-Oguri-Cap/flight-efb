package com.oguricap.efb.ui.tools

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.oguricap.efb.diag.AppLog

/**
 * 数据文件在导入流程里的角色。
 *
 * 为什么单独抽一个**纯 Kotlin** 的枚举：网页侧的 `NavParser.classify()` 是按文件名认文件的，
 * App 这边要在「用户只选了一个文件夹」的情况下把同名同后缀的一堆 .dat 分出三六九等，
 * 规则必须和网页一致；而规则本身不该依赖 Android，才好单测。
 */
enum class DataRole {
    /** earth_fix.dat —— 航路点 */
    NAV_FIX,

    /** earth_awy.dat —— 航段 */
    NAV_AWY,

    /** earth_nav.dat —— VOR / NDB */
    NAV_NAV,

    /** apt.dat —— 机场（Global Scenery 里那份，坐标最准） */
    NAV_APT,

    /** earth_aptmeta.dat —— X-Plane 12 导航包自带的机场元数据，没有 apt.dat 时靠它 */
    NAV_APTMETA,

    /** AIP 航路表 CSV（CAAC 四张表之一） */
    AIP_CSV,

    /** 本工具导出的 caac_official.json */
    AIP_JSON,

    /** 压缩包：里面的条目按名字再分类 */
    ZIP,

    /** 用不到（earth_hold / mora / msa、PDF、图片等），静默忽略 */
    IGNORED;

    val isNav: Boolean
        get() = this == NAV_FIX || this == NAV_AWY || this == NAV_NAV ||
            this == NAV_APT || this == NAV_APTMETA

    val isAip: Boolean
        get() = this == AIP_CSV || this == AIP_JSON
}

/**
 * 目录扫描器：把一个 SAF 目录树里「用得上的」文件挑出来。
 *
 * 背景：X-Plane 12 的导航数据包（Navigraph / Aerosoft）解压后是一整个目录，
 * 里面除了 earth_fix/awy/nav/aptmeta，还有 CIFP、airspaces、atc data 这些我们用不到的；
 * 而 apt.dat 根本不在这个包里，它在 Global Scenery 下。让用户逐个文件去记「要哪几个、在哪个目录」
 * 是这一版最容易出错的一步（少选 aptmeta 就没有机场坐标，输入 ZBAA 直接报「数据库里没有」）。
 *
 * 所以这里只做一件事：**递归列出候选文件 + 按名字定角色**，真正读流仍然走
 * ImportRepository 的分块管线，App 侧依旧不解析内容。
 */
object DataFolder {

    private const val TAG = "EFB/FOLDER"

    /** 扫描上限：防止用户选中整个内部存储时把手机拖死。 */
    private const val MAX_DEPTH = 6
    private const val MAX_FILES = 4000

    /**
     * 一个候选文件。
     *
     * @param uri 真实文件；若来自压缩包，这里是**压缩包本身**的 Uri
     * @param zipEntry 非空表示这是压缩包里的条目，读的时候要按条目名取
     */
    data class Found(
        val name: String,
        val uri: Uri,
        val path: String,
        val role: DataRole,
        val zipEntry: String? = null,
    ) {
        /** 显示给用户看的一行字。 */
        val label: String
            get() = if (zipEntry != null) "$path!$zipEntry" else path
    }

    /**
     * 文件名 → 角色。规则与网页侧 `NavParser.classify()` 保持一致：
     * 网页认 `earth_fix` / `_fix.dat` / `earth_awy` / `_awy.dat` / `earth_nav` / `_nav.dat` / `apt.dat`，
     * 这里额外把 `earth_aptmeta.dat` 认出来 —— 网页把它当 skip，但 App 的 shim 会用它补机场坐标。
     */
    fun roleOf(name: String): DataRole {
        val n = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (n.isEmpty()) return DataRole.IGNORED
        if (n.endsWith(".zip")) return DataRole.ZIP
        if (n == "apt.dat") return DataRole.NAV_APT
        if (n == "earth_aptmeta.dat") return DataRole.NAV_APTMETA
        if (n == "earth_fix.dat" || n == "fix.dat" || n.endsWith("_fix.dat")) return DataRole.NAV_FIX
        if (n == "earth_awy.dat" || n == "awy.dat" || n.endsWith("_awy.dat")) return DataRole.NAV_AWY
        if (n == "earth_nav.dat" || n == "nav.dat" || n.endsWith("_nav.dat")) return DataRole.NAV_NAV
        if (n.endsWith(".csv")) return DataRole.AIP_CSV
        if (n.endsWith(".json")) {
            // 目录里可能混着别家的 json；只认名字像 AIP 表的
            val keys = listOf("caac", "aip", "official", "route", "routes")
            return if (keys.any { n.contains(it) }) DataRole.AIP_JSON else DataRole.IGNORED
        }
        return DataRole.IGNORED
    }

    /** 同一角色有多份时先要哪一份：Custom Data 优于其他，default data 最后。 */
    fun preference(path: String): Int = when {
        path.contains("custom data", ignoreCase = true) -> 0
        path.contains("default data", ignoreCase = true) -> 3
        path.contains("apt.dat", ignoreCase = true) -> 1
        else -> 2
    }

    /**
     * 递归扫描目录树。只返回 [roleOf] 认识的普通文件（以及压缩包）。
     * 任何一层读不动都只记日志、不抛 —— 用户选错目录时应当得到「没找到」而不是崩溃。
     */
    fun scan(context: Context, treeUri: Uri): List<Found> {
        val out = ArrayList<Found>()
        val resolver = context.contentResolver

        fun walk(dirUri: Uri, path: String, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
            val children = runCatching {
                val docId = DocumentsContract.getDocumentId(dirUri)
                DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, docId)
            }.getOrNull() ?: return

            val cursor = runCatching {
                resolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null,
                )
            }.onFailure { AppLog.w(TAG, "列目录失败：$path", it) }.getOrNull() ?: return

            cursor.use { c ->
                while (c.moveToNext() && out.size < MAX_FILES) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val childPath = if (path.isEmpty()) name else "$path/$name"

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(childUri, childPath, depth + 1)
                        continue
                    }
                    val role = roleOf(name)
                    if (role == DataRole.IGNORED) continue
                    out += Found(name = name, uri = childUri, path = childPath, role = role)
                }
            }
        }

        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        walk(root, "", 0)
        AppLog.i(TAG, "扫描完成：${out.size} 个候选（${out.count { it.role.isNav }} 导航 / ${out.count { it.role.isAip }} AIP / ${out.count { it.role == DataRole.ZIP }} 压缩包）")
        return out
    }

    /**
     * 从候选里挑出一套导航数据：每个角色最多一份。
     *
     * 优先级：目录里的原件 > 压缩包里的同名条目；同角色多份时按 [preference] + 路径长度。
     * 压缩包只在**缺角色**时才启用 —— 目录里已经有 earth_fix.dat 就没必要再从 zip 里读一遍。
     */
    fun pickNav(found: List<Found>): List<Found> {
        val direct = found.filter { it.role.isNav }
        val picked = LinkedHashMap<DataRole, Found>()
        for (f in direct.sortedWith(compareBy({ preference(it.path) }, { it.path.length }))) {
            picked.putIfAbsent(f.role, f)
        }
        return picked.values.toList()
    }

    /** AIP 表：目录里的 csv/json 全要（四张表是分开的）；没有则交给压缩包。 */
    fun pickAip(found: List<Found>): List<Found> {
        val direct = found.filter { it.role.isAip }
        if (direct.isNotEmpty()) return direct
        return emptyList()
    }

    /** 压缩包是否值得先打开：名字像导航数据 / AIP 的优先，其余留作兜底。 */
    fun zipLooksRelevant(name: String): Boolean {
        val n = name.lowercase()
        val keys = listOf(
            "navdata", "nav data", "xplane", "x-plane", "navigraph", "aerosoft",
            "aip", "caac", "2611", "2610", "2501", "2401", "2301",
        )
        return keys.any { n.contains(it) }
    }
}
