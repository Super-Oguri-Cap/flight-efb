package com.oguricap.efb.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.oguricap.efb.data.AircraftChecklist
import com.oguricap.efb.data.QrhBook
import com.oguricap.efb.data.QrhSection
import com.oguricap.efb.data.ReferenceLibrary

class ReferenceViewModel(app: Application) : AndroidViewModel(app) {

    private val library = ReferenceLibrary.get(app)

    val checklists: List<AircraftChecklist> get() = library.checklists
    val qrh: QrhBook get() = library.qrh

    var expandedPhases: Set<String> by mutableStateOf(emptySet())
        private set

    var expandedEntries: Set<String> by mutableStateOf(emptySet())
        private set

    fun aircraft(id: String): AircraftChecklist? = checklists.firstOrNull { it.id == id }

    fun section(id: String): QrhSection? = qrh.sections.firstOrNull { it.id == id }

    fun togglePhase(name: String) {
        expandedPhases = if (name in expandedPhases) expandedPhases - name else expandedPhases + name
    }

    fun toggleEntry(key: String) {
        expandedEntries = if (key in expandedEntries) expandedEntries - key else expandedEntries + key
    }
}
