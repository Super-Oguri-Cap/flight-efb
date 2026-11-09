package com.oguricap.efb.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oguricap.efb.data.AircraftChecklist
import com.oguricap.efb.data.ChecklistProgress
import com.oguricap.efb.data.CustomChecklistStore
import com.oguricap.efb.data.QrhBook
import com.oguricap.efb.data.QrhSection
import com.oguricap.efb.data.ReferenceDoc
import com.oguricap.efb.data.ReferenceDocs
import com.oguricap.efb.data.ReferenceLibrary
import com.oguricap.efb.diag.AppLog
import com.oguricap.efb.prefs.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class ReferenceViewModel(app: Application) : AndroidViewModel(app) {

    private val tag = "EFB/CHECKLIST"
    private val library = ReferenceLibrary.get(app)
    private val settings = Settings(app)
    private val progressStore = ChecklistProgress(app)
    private val customStore = CustomChecklistStore(app)

    // 自定义检查单改动后要立刻刷新这个状态，界面才会跟着变
    var customChecklists: List<AircraftChecklist> by mutableStateOf(customStore.list())
        private set

    // 内置在前、自定义在后；自定义的 id 都是 custom_ 开头，靠它分流
    val checklists: List<AircraftChecklist> get() = library.checklists + customChecklists
    val qrh: QrhBook get() = library.qrh
    val docs: List<ReferenceDoc> get() = ReferenceDocs.all

    var expandedPhases: Set<String> by mutableStateOf(emptySet())
        private set

    var expandedEntries: Set<String> by mutableStateOf(emptySet())
        private set

    // 分组互斥：开了以后一次只展开一个阶段
    var exclusivePhases: Boolean by mutableStateOf(settings.exclusiveChecklistPhases)
        private set

    /**
     * 列表页分类分组（空客 / 波音 / 其他）里被收起的那些。
     *
     * 存「收起的」而不是「展开的」：默认全展开，空集就是默认状态，
     * 新增分类不用再维护一份初始展开列表。
     */
    var collapsedCategories: Set<String> by mutableStateOf(emptySet())
        private set

    /** 一次性提示（Snackbar），界面读走以后调 [consumeMessage] 清掉。 */
    var message: String? by mutableStateOf(null)
        private set

    fun consumeMessage() {
        message = null
    }

    fun toggleCategory(category: String) {
        collapsedCategories = if (category in collapsedCategories) {
            collapsedCategories - category
        } else {
            collapsedCategories + category
        }
    }

    fun aircraft(id: String): AircraftChecklist? = checklists.firstOrNull { it.id == id }

    fun section(id: String): QrhSection? = qrh.sections.firstOrNull { it.id == id }

    fun doc(id: String): ReferenceDoc? = ReferenceDocs.get(id)

    // ---- 自定义检查单 ----

    // 内置数据随 APK 分发，只读；用户想改只能先复制出一份可编辑的副本
    fun isCustom(id: String): Boolean = id.startsWith(CUSTOM_PREFIX)

    fun refreshCustom() {
        customChecklists = customStore.list()
    }

    fun duplicateBuiltin(id: String): AircraftChecklist? {
        val source = library.checklists.firstOrNull { it.id == id } ?: return null
        val copy = customStore.create(source)
        refreshCustom()
        return copy
    }

    fun updateCustom(checklist: AircraftChecklist) {
        customStore.update(checklist)
        refreshCustom()
    }

    fun deleteCustom(id: String) {
        customStore.delete(id)
        // 内存里那份进度缓存也一起丢掉，别留着占地方
        progress.remove(id)
        refreshCustom()
    }

    /**
     * 把全部自定义检查单导出成一份 JSON，写到用户选定的位置。
     *
     * 用途是分享：对方拿到文件后走「导入」即可，不必再手工抄一遍条目。
     * 格式带一层信封（`kind` / `version`），以后加字段时老版本还能认得出来。
     */
    fun exportCustom(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = customStore.exportJson()
                    val out = getApplication<Application>().contentResolver
                        .openOutputStream(uri, "wt")
                        ?: throw IOException("打不开所选位置，请换一个目录再试")
                    out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    json.length
                }
            }
            result.onSuccess { chars ->
                AppLog.i(tag, "自定义检查单已导出：${customChecklists.size} 份 / $chars 字符")
                message = "已导出 ${customChecklists.size} 份自定义检查单"
            }.onFailure {
                AppLog.e(tag, "导出自定义检查单失败", it)
                message = "导出失败：" + (it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /**
     * 从用户选定的文件导入自定义检查单。
     *
     * 导入记录一律重新发 id 与 uid（见 [CustomChecklistStore.importJson]）：
     * 沿用对方的 id 会和自己已有的副本撞车，沿用 uid 会让两份副本共用勾选进度。
     */
    fun importCustom(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        ?.use { it.reader(Charsets.UTF_8).readText() }
                        ?: throw IOException("打不开这个文件")
                    customStore.importJson(text)
                }
            }
            result.onSuccess { report ->
                refreshCustom()
                AppLog.i(tag, "自定义检查单导入：新增 ${report.added}，跳过 ${report.skipped}")
                message = when {
                    report.added == 0 -> "没有可导入的检查单（跳过 ${report.skipped} 条）"
                    report.skipped == 0 -> "已导入 ${report.added} 份自定义检查单"
                    else -> "已导入 ${report.added} 份，跳过 ${report.skipped} 条损坏记录"
                }
            }.onFailure {
                AppLog.e(tag, "导入自定义检查单失败", it)
                message = "导入失败：" + (it.message ?: it.javaClass.simpleName)
            }
        }
    }

    fun togglePhase(name: String) {
        val open = name in expandedPhases
        expandedPhases = when {
            exclusivePhases -> if (open) emptySet() else setOf(name)
            open -> expandedPhases - name
            else -> expandedPhases + name
        }
    }

    // 名字不能叫 setExclusivePhases：var 属性自己就会生成同签名的 setter，JVM 层撞车
    fun applyExclusivePhases(value: Boolean) {
        exclusivePhases = value
        settings.exclusiveChecklistPhases = value
        // 打开互斥时把当前摊开的阶段收成一个，避免看起来「设置没生效」
        if (value && expandedPhases.size > 1) {
            expandedPhases = setOf(expandedPhases.first())
        }
    }

    fun toggleEntry(key: String) {
        expandedEntries = if (key in expandedEntries) expandedEntries - key else expandedEntries + key
    }

    // ---- 勾选进度 ----
    //
    // 内置检查单的键是「阶段序号/条目序号」，落在 ChecklistProgress；
    // 自定义检查单可编辑可重排，序号会变，键改用条目的 uid，落在 CustomChecklistStore。
    // 下面所有方法都按 id 前缀分流，内置那一路的行为一个字都没改。

    // 每个机型一份，第一次进详情页时才从磁盘读，避免启动时把 22 份全读一遍
    private val progress = mutableStateMapOf<String, Set<String>>()

    fun ensureProgress(aircraftId: String) {
        if (aircraftId in progress) return
        progress[aircraftId] =
            if (isCustom(aircraftId)) customStore.loadProgress(aircraftId)
            else progressStore.load(aircraftId)
    }

    fun isChecked(aircraftId: String, itemKey: String): Boolean =
        progress[aircraftId]?.contains(itemKey) == true

    fun checkedCount(aircraftId: String): Int {
        val current = progress[aircraftId].orEmpty()
        if (!isCustom(aircraftId)) return current.size
        // 条目被删掉后它的 uid 还留在集合里，不计入总数，否则会出现「已勾 5 / 4」
        val alive = aircraft(aircraftId)?.phases
            ?.flatMap { it.items }
            ?.map { it.uid }
            ?.toSet()
            .orEmpty()
        return if (alive.isEmpty()) 0 else current.count { it in alive }
    }

    // 点一下打勾，再点一下撤销
    fun toggleItem(aircraftId: String, itemKey: String) {
        val current = progress[aircraftId].orEmpty()
        val next = if (itemKey in current) current - itemKey else current + itemKey
        progress[aircraftId] = next
        saveProgress(aircraftId, next)
    }

    // 只清某一个阶段
    fun resetPhase(aircraftId: String, phaseIndex: Int) {
        val current = progress[aircraftId].orEmpty()
        val next = if (isCustom(aircraftId)) {
            // 自定义的键是 uid，算不出前缀，只能把这一阶段的 uid 全找出来做差集
            current - phaseUidSet(aircraftId, phaseIndex)
        } else {
            current.filterNot { it.startsWith(phasePrefix(phaseIndex)) }.toSet()
        }
        if (next.size == current.size) return
        progress[aircraftId] = next
        saveProgress(aircraftId, next)
    }

    fun checkedInPhase(aircraftId: String, phaseIndex: Int): Int {
        val current = progress[aircraftId].orEmpty()
        if (isCustom(aircraftId)) {
            val uids = phaseUidSet(aircraftId, phaseIndex)
            return if (uids.isEmpty()) 0 else current.count { it in uids }
        }
        val prefix = phasePrefix(phaseIndex)
        return current.count { it.startsWith(prefix) }
    }

    // 每份检查单自己的复位
    fun resetChecklist(aircraftId: String) {
        progress[aircraftId] = emptySet()
        saveProgress(aircraftId, emptySet())
    }

    private fun saveProgress(aircraftId: String, keys: Set<String>) {
        if (isCustom(aircraftId)) customStore.saveProgress(aircraftId, keys)
        else progressStore.save(aircraftId, keys)
    }

    // 自定义检查单里一个阶段的全部条目 uid
    private fun phaseUidSet(aircraftId: String, phaseIndex: Int): Set<String> =
        aircraft(aircraftId)?.phases?.getOrNull(phaseIndex)?.items
            ?.mapNotNull { it.uid.takeIf { uid -> uid.isNotBlank() } }
            ?.toSet()
            .orEmpty()

    private fun phasePrefix(phaseIndex: Int) = phaseIndex.toString() + "/"

    // ---- 列表滚动位置 ----
    // 三个页面栈（检查单列表、检查单详情、QRH 索引、QRH 分组）共用一张表。
    // 存 ViewModel 而不是 rememberSaveable：切到「航图」或「QRH」再切回来时，
    // Composable 会被重新组合，只有 ViewModel 还活着，位置才不会丢。
    private val scrolls = mutableMapOf<String, Pair<Int, Int>>()

    fun savedScroll(key: String): Pair<Int, Int>? = scrolls[key]

    fun rememberScroll(key: String, index: Int, offset: Int) {
        scrolls[key] = index to offset
    }

    companion object {
        // 自定义检查单的 id 前缀，也是分流依据（CustomChecklistStore.create 生成）
        const val CUSTOM_PREFIX = "custom_"
    }
}
