package com.oguricap.efb.data

import android.content.Context
import android.content.SharedPreferences
import com.oguricap.efb.diag.AppLog
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
                runCatching { fromJson(array.getJSONObject(index)) }
                    .onFailure { AppLog.w(TAG, "第 $index 份自定义检查单损坏，已跳过", it) }
                    .getOrNull()
            }
        }.onFailure { AppLog.e(TAG, "自定义检查单整体解析失败，按空列表处理", it) }
            .getOrDefault(emptyList())
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

    // ---- 分享：整份导出 / 导入 ----

    /** 导入结果：新增几份、跳过几条（损坏或缺阶段）。 */
    data class ImportReport(val added: Int, val skipped: Int)

    /**
     * 导出全部自定义检查单。
     *
     * 外面包一层信封而不是裸数组：对方拿到文件时可以先看 `kind` 判断这是不是
     * 检查单分享文件，以后加字段也不会让老版本误读。
     */
    fun exportJson(): String {
        val array = JSONArray()
        for (checklist in list()) array.put(toJson(checklist))
        return JSONObject().apply {
            put("kind", EXPORT_KIND)
            put("version", EXPORT_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("checklists", array)
        }.toString()
    }

    /**
     * 导入别人分享的文件。三种形态都认：带信封的对象、裸数组、单个检查单对象 ——
     * 别人手工整理的文件不该因为少了几个外层字段就被拒之门外。
     *
     * **id 与 uid 一律重新分配**：沿用对方的 id 会和自己已有的副本撞车，
     * 沿用 uid 会让两份副本共用同一份勾选进度（勾了一份，另一份也打勾）。
     */
    fun importJson(text: String): ImportReport {
        val root = JSONTokener(text).nextValue()
        val objects: List<JSONObject> = when (root) {
            is JSONArray -> (0 until root.length()).mapNotNull { root.optJSONObject(it) }
            is JSONObject -> {
                val arr = root.optJSONArray("checklists") ?: root.optJSONArray("items")
                if (arr != null) {
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                } else {
                    listOf(root)
                }
            }
            else -> emptyList()
        }
        // JSONTokener 很宽松：随便一段文字也能解析成字符串而不报错。
        // 一条检查单对象都没拿到，就说明这压根不是分享文件 —— 明确报错，
        // 比返回「新增 0 跳过 0」让用户猜要强。
        if (objects.isEmpty()) throw IllegalArgumentException("文件里没有找到检查单")

        var added = 0
        var skipped = 0
        val all = list().toMutableList()
        for (obj in objects) {
            // 手写的分享文件可能没有 id；补一个再解析，免得整条被当成坏数据
            val normalized = if (obj.optString("id").isBlank()) {
                JSONObject(obj.toString()).put("id", newId())
            } else {
                obj
            }
            val parsed = runCatching { fromJson(normalized) }.getOrNull()
            if (parsed == null || parsed.phases.isEmpty()) {
                skipped++
                continue
            }
            all += reidentify(parsed)
            added++
        }
        if (added > 0) save(all)
        return ImportReport(added, skipped)
    }

    private fun reidentify(src: AircraftChecklist): AircraftChecklist = src.copy(
        id = newId(),
        // 导入的东西不是原厂数据，归到「其他」分类
        category = "other",
        uid = newUid(),
        phases = src.phases.map { phase ->
            phase.copy(
                uid = newUid(),
                items = phase.items.map { it.copy(uid = newUid()) },
            )
        },
    )

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
        private const val TAG = "EFB/CUSTOM"
        private const val KEY_ITEMS = "items"

        /** 分享文件的外层标识与版本，导入时用它判断「这是不是检查单」。 */
        const val EXPORT_KIND = "efb-custom-checklists"
        const val EXPORT_VERSION = 1

        /** 导出时预填的文件名，带时间戳，多次导出不会互相覆盖。 */
        fun suggestedFileName(at: Long = System.currentTimeMillis()): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
            return "EFB自定义检查单-$stamp.json"
        }

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
