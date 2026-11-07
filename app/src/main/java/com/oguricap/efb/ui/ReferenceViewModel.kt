package com.oguricap.efb.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.oguricap.efb.data.AircraftChecklist
import com.oguricap.efb.data.ChecklistProgress
import com.oguricap.efb.data.QrhBook
import com.oguricap.efb.data.QrhSection
import com.oguricap.efb.data.ReferenceDoc
import com.oguricap.efb.data.ReferenceDocs
import com.oguricap.efb.data.ReferenceLibrary
import com.oguricap.efb.prefs.Settings

class ReferenceViewModel(app: Application) : AndroidViewModel(app) {

    private val library = ReferenceLibrary.get(app)
    private val settings = Settings(app)
    private val progressStore = ChecklistProgress(app)

    val checklists: List<AircraftChecklist> get() = library.checklists
    val qrh: QrhBook get() = library.qrh
    val docs: List<ReferenceDoc> get() = ReferenceDocs.all

    var expandedPhases: Set<String> by mutableStateOf(emptySet())
        private set

    var expandedEntries: Set<String> by mutableStateOf(emptySet())
        private set

    // 分组互斥：开了以后一次只展开一个阶段
    var exclusivePhases: Boolean by mutableStateOf(settings.exclusiveChecklistPhases)
        private set

    fun aircraft(id: String): AircraftChecklist? = checklists.firstOrNull { it.id == id }

    fun section(id: String): QrhSection? = qrh.sections.firstOrNull { it.id == id }

    fun doc(id: String): ReferenceDoc? = ReferenceDocs.get(id)

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

    // 每个机型一份，第一次进详情页时才从磁盘读，避免启动时把 22 份全读一遍
    private val progress = mutableStateMapOf<String, Set<String>>()

    fun ensureProgress(aircraftId: String) {
        if (aircraftId !in progress) progress[aircraftId] = progressStore.load(aircraftId)
    }

    fun isChecked(aircraftId: String, itemKey: String): Boolean =
        progress[aircraftId]?.contains(itemKey) == true

    fun checkedCount(aircraftId: String): Int = progress[aircraftId]?.size ?: 0

    // 点一下打勾，再点一下撤销
    fun toggleItem(aircraftId: String, itemKey: String) {
        val current = progress[aircraftId].orEmpty()
        val next = if (itemKey in current) current - itemKey else current + itemKey
        progress[aircraftId] = next
        progressStore.save(aircraftId, next)
    }

    // 只清某一个阶段：键是「阶段序号/条目序号」，按前缀筛即可
    fun resetPhase(aircraftId: String, phaseIndex: Int) {
        val prefix = phaseIndex.toString() + "/"
        val current = progress[aircraftId].orEmpty()
        val next = current.filterNot { it.startsWith(prefix) }.toSet()
        if (next.size == current.size) return
        progress[aircraftId] = next
        progressStore.save(aircraftId, next)
    }

    fun checkedInPhase(aircraftId: String, phaseIndex: Int): Int {
        val prefix = phaseIndex.toString() + "/"
        return progress[aircraftId].orEmpty().count { it.startsWith(prefix) }
    }

    // 每份检查单自己的复位
    fun resetChecklist(aircraftId: String) {
        progress[aircraftId] = emptySet()
        progressStore.save(aircraftId, emptySet())
    }

    // ---- 列表滚动位置 ----
    // 三个页面栈（检查单列表、检查单详情、QRH 索引、QRH 分组）共用一张表。
    // 存 ViewModel 而不是 rememberSaveable：切到「航图」或「QRH」再切回来时，
    // Composable 会被重新组合，只有 ViewModel 还活着，位置才不会丢。
    private val scrolls = mutableMapOf<String, Pair<Int, Int>>()

    fun savedScroll(key: String): Pair<Int, Int>? = scrolls[key]

    fun rememberScroll(key: String, index: Int, offset: Int) {
        scrolls[key] = index to offset
    }
}
