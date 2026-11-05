package com.oguricap.efb.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// 检查单与 QRH 都是随 APK 分发的只读 JSON，首次用到时解析一次并常驻内存。
class ReferenceLibrary private constructor(
    val checklists: List<AircraftChecklist>,
    val qrh: QrhBook,
) {
    companion object {
        private const val CHECKLIST_ASSET = "checklists.json"
        private const val QRH_ASSET = "qrh.json"

        @Volatile
        private var cached: ReferenceLibrary? = null

        fun get(context: Context): ReferenceLibrary =
            cached ?: synchronized(this) {
                cached ?: load(context).also { cached = it }
            }

        private fun load(context: Context): ReferenceLibrary =
            ReferenceLibrary(
                checklists = parseChecklists(readAsset(context, CHECKLIST_ASSET)),
                qrh = parseQrh(readAsset(context, QRH_ASSET)),
            )

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
