package com.oguricap.efb.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// 检查单与 QRH 都是随 APK 分发的只读 JSON，首次用到时解析一次并常驻内存。
class ReferenceLibrary private constructor(
    val checklists: List<AircraftChecklist>,
    val qrh: QrhBook,
) {
    companion object {
        private const val CHECKLIST_ASSET = "checklists.json"
        // 用户自备检查单（PDF 转出来的文字版），结构与 checklists.json 相同
        private const val CHECKLIST_CN_ASSET = "checklists_cn.json"
        private const val QRH_ASSET = "qrh.json"

        @Volatile
        private var cached: ReferenceLibrary? = null

        /**
         * 同步取库。**只给后台线程与测试用**：首次调用要读 204 KB JSON 并解析，
         * 放主线程就是一次 DiskReadViolation。界面侧走 [load]。
         *
         * 并发安全靠这里的双重检查：第一个线程在 synchronized 里解析，
         * 其余线程等锁之后直接拿 [cached]，所以整份数据只会解析一次。
         */
        fun get(context: Context): ReferenceLibrary =
            cached ?: synchronized(this) {
                cached ?: load(context).also { cached = it }
            }

        /**
         * 在 IO 线程取库，给 ViewModel 用。
         *
         * 复用 [get] 的双重检查缓存，所以并发调用（包括与同步调用混着来）都只解析一次。
         */
        suspend fun loadAsync(context: Context): ReferenceLibrary =
            withContext(Dispatchers.IO) { get(context) }

        private fun load(context: Context): ReferenceLibrary {
            // 随附的文字版检查单单独一个文件：它来自用户自己的 PDF，
            // 和 simchecklists 那批（MIT）来源不同，混在一个文件里不好交代出处。
            val own = readOptionalAsset(context, CHECKLIST_CN_ASSET)
                ?.let { parseChecklists(it) }
                .orEmpty()
            return ReferenceLibrary(
                checklists = (parseChecklists(readAsset(context, CHECKLIST_ASSET)) + own)
                    .sortedWith(compareBy({ AircraftCategory.rank(it.category) }, { it.name })),
                qrh = parseQrh(readAsset(context, QRH_ASSET)),
            )
        }

        // 随附检查单缺失时不应该让整个库打不开，缺就少这一份
        private fun readOptionalAsset(context: Context, name: String): JSONObject? =
            runCatching { readAsset(context, name) }.getOrNull()

        private fun readAsset(context: Context, name: String): JSONObject =
            JSONObject(context.assets.open(name).bufferedReader().use { it.readText() })

        private fun parseChecklists(root: JSONObject): List<AircraftChecklist> {
            val array = root.getJSONArray("aircraft")
            return (0 until array.length()).map { index ->
                val ac = array.getJSONObject(index)
                AircraftChecklist(
                    id = ac.getString("id"),
                    category = ac.getString("category"),
                    name = ac.getString("name"),
                    note = ac.optString("note"),
                    source = ac.getString("source"),
                    license = ac.getString("license"),
                    phases = ac.getJSONArray("phases").mapObjects { phase ->
                        ChecklistPhase(
                            name = phase.getString("name"),
                            gloss = phase.optString("gloss"),
                            items = phase.getJSONArray("items").mapObjects { item ->
                                ChecklistItem(
                                    item = item.getString("item"),
                                    state = item.getString("state"),
                                )
                            },
                        )
                    },
                )
            }.sortedWith(compareBy({ AircraftCategory.rank(it.category) }, { it.name }))
        }

        private fun parseQrh(root: JSONObject): QrhBook =
            QrhBook(
                notice = root.optString("notice"),
                sections = root.getJSONArray("sections").mapObjects { section ->
                    QrhSection(
                        id = section.getString("id"),
                        aircraft = section.getString("aircraft"),
                        category = section.getString("category"),
                        title = section.getString("title"),
                        note = section.optString("note"),
                        entries = section.getJSONArray("entries").mapObjects { entry ->
                            QrhEntry(
                                ata = entry.getString("ata"),
                                title = entry.getString("title"),
                                condition = entry.getString("condition"),
                                memory = entry.getBoolean("memory"),
                                actions = entry.getJSONArray("actions").mapStrings(),
                                inop = entry.optString("inop"),
                            )
                        },
                    )
                },
            )
    }
}

private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

private fun JSONArray.mapStrings(): List<String> = (0 until length()).map { getString(it) }
