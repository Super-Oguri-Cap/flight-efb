package com.oguricap.efb.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// 用户自己「复制一份」出来的检查单。
//
// 为什么不复用 ChecklistProgress 的「阶段序号/条目序号」当进度键：
// 副本可编辑，用户重排一条，所有序号都会平移，进度就会串到别的条目上；
// 所以自定义检查单在创建时给每个阶段、每个条目发一个短 uid，进度一律存 uid 集合。
//
// 为什么 create 必须重新发 uid 而不是沿用原检查单的：
// 同一份内置数据可能被复制多次，若沿用同一批 uid，几个副本会共用一份进度，
// 勾了 A 副本的条目 B 副本也跟着打勾。
class CustomChecklistStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("efb_custom_checklists", Context.MODE_PRIVATE)

    fun list(): List<AircraftChecklist> {
        val raw = prefs.getString(KEY_ITEMS, null)
        if (raw.isNullOrBlank()) return emptyList()
        // 整份 JSON 坏掉时不如给空列表，也不要让检查单页打不开
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                // 单条坏数据只跳过它自己
                runCatching { fromJson(array.getJSONObject(index)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun create(from: AircraftChecklist): AircraftChecklist {
        val copy = AircraftChecklist(
            id = newId(),
            // 副本归到「其他」分类：它已经不是原厂数据了
            category = "other",
            name = from.name + " 副本",
            note = from.note,
            source = "复制自 " + from.source.ifBlank { from.name },
            license = from.license,
            phases = from.phases.map { phase ->
                ChecklistPhase(
                    name = phase.name,
                    gloss = phase.gloss,
                    items = phase.items.map { it.copy(uid = newUid()) },
                    uid = newUid(),
                )
            },
            uid = newUid(),
        )
        val all = list() + copy
        save(all)
        return copy
    }

    fun update(checklist: AircraftChecklist) {
        val all = list()
        val index = all.indexOfFirst { it.id == checklist.id }
        save(if (index >= 0) all.toMutableList().also { it[index] = checklist } else all + checklist)
    }

    fun delete(id: String) {
        save(list().filterNot { it.id == id })
        // 进度跟着检查单一起清，避免留下孤儿键
        prefs.edit().remove(progressKey(id)).apply()
    }

    // ---- 勾选进度 ----

    fun loadProgress(id: String): Set<String> {
        val raw = prefs.getString(progressKey(id), null)
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(ChecklistProgress.SEP).filter { it.isNotBlank() }.toSet()
    }

    fun saveProgress(id: String, keys: Set<String>) {
        // 与 ChecklistProgress 一致：空集合直接删键，不存空串
        if (keys.isEmpty()) {
            prefs.edit().remove(progressKey(id)).apply()
            return
        }
        prefs.edit().putString(progressKey(id), keys.joinToString(ChecklistProgress.SEP)).apply()
    }

    private fun progressKey(id: String) = "p_" + id

    private fun save(checklists: List<AircraftChecklist>) {
        if (checklists.isEmpty()) {
            prefs.edit().remove(KEY_ITEMS).apply()
            return
        }
        val array = JSONArray()
        checklists.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun toJson(checklist: AircraftChecklist): JSONObject = JSONObject().apply {
        put("id", checklist.id)
        put("category", checklist.category)
        put("name", checklist.name)
        put("note", checklist.note)
        put("source", checklist.source)
        put("license", checklist.license)
        put("uid", checklist.uid)
        put("phases", JSONArray().apply {
            checklist.phases.forEach { phase ->
                put(JSONObject().apply {
                    put("uid", phase.uid)
                    put("name", phase.name)
                    put("gloss", phase.gloss)
                    put("items", JSONArray().apply {
                        phase.items.forEach { item ->
                            put(JSONObject().apply {
                                put("uid", item.uid)
                                put("item", item.item)
                                put("state", item.state)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun fromJson(obj: JSONObject): AircraftChecklist = AircraftChecklist(
        id = obj.getString("id"),
        category = obj.optString("category", "other"),
        name = obj.optString("name"),
        note = obj.optString("note"),
        source = obj.optString("source"),
        license = obj.optString("license"),
        phases = obj.optJSONArray("phases")?.objects { phase ->
            ChecklistPhase(
                name = phase.optString("name"),
                gloss = phase.optString("gloss"),
                items = phase.optJSONArray("items")?.objects { item ->
                    ChecklistItem(
                        item = item.optString("item"),
                        state = item.optString("state"),
                        uid = item.optString("uid"),
                    )
                }.orEmpty(),
                uid = phase.optString("uid"),
            )
        }.orEmpty(),
        uid = obj.optString("uid"),
    )

    companion object {
        private const val KEY_ITEMS = "items"

        // 短 uid：8 位十六进制，够用且不占存储。编辑页新增阶段/条目也调这里，
        // 保证整个工程只有一套 uid 生成规则。
        fun newUid(): String = UUID.randomUUID().toString().take(8)

        // id 前缀是 ViewModel 分流的依据：custom_ 开头走这份存储，其余走内置数据
        private fun newId(): String =
            "custom_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(4)
    }
}

private fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }
