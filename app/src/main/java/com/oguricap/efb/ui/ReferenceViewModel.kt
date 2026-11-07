package com.oguricap.efb.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.oguricap.efb.data.AircraftChecklist
import com.oguricap.efb.data.QrhBook
import com.oguricap.efb.data.QrhSection
import com.oguricap.efb.data.ReferenceDoc
import com.oguricap.efb.data.ReferenceDocs
import com.oguricap.efb.data.ReferenceLibrary

class ReferenceViewModel(app: Application) : AndroidViewModel(app) {

    private val library = ReferenceLibrary.get(app)

    val checklists: List<AircraftChecklist> get() = library.checklists
    val qrh: QrhBook get() = library.qrh
    val docs: List<ReferenceDoc> get() = ReferenceDocs.all

    var expandedPhases: Set<String> by mutableStateOf(emptySet())
        private set

    var expandedEntries: Set<String> by mutableStateOf(emptySet())
        private set

    fun aircraft(id: String): AircraftChecklist? = checklists.firstOrNull { it.id == id }

    fun section(id: String): QrhSection? = qrh.sections.firstOrNull { it.id == id }

    fun doc(id: String): ReferenceDoc? = ReferenceDocs.get(id)

    fun togglePhase(name: String) {
        expandedPhases = if (name in expandedPhases) expandedPhases - name else expandedPhases + name
    }

    fun toggleEntry(key: String) {
        expandedEntries = if (key in expandedEntries) expandedEntries - key else expandedEntries + key
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
