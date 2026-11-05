package com.oguricap.efb

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.data.AircraftCategory
import com.oguricap.efb.data.ReferenceLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReferenceLibraryTest {

    private fun library(): ReferenceLibrary =
        ReferenceLibrary.get(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun loadsEveryAircraft() {
        val lib = library()
        assertEquals(21, lib.checklists.size)
    }

    @Test
    fun categoriesAreOnlyTheThreeDeclaredOnes() {
        val lib = library()
        val categories = lib.checklists.map { it.category }.distinct().sorted()
        assertEquals(listOf("airbus", "boeing", "other"), categories)
        assertTrue(lib.checklists.none { it.id.startsWith("su") })
    }

    @Test
    fun aircraftSortedByCategoryThenName() {
        val lib = library()
        val ranks = lib.checklists.map { AircraftCategory.rank(it.category) }
        assertEquals(ranks.sorted(), ranks)
        val airbus = lib.checklists.filter { it.category == "airbus" }.map { it.name }
        assertEquals(airbus.sorted(), airbus)
    }

    @Test
    fun a320neoHasFourteenPhases() {
        val a320 = library().checklists.first { it.id == "a320neo" }
        assertEquals(14, a320.phases.size)
        assertEquals(180, a320.itemCount)
        assertEquals("Pre Start", a320.phases.first().name)
        assertEquals("启动前", a320.phases.first().gloss)
    }

    @Test
    fun everyPhaseHasChineseGloss() {
        val missing = library().checklists.flatMap { ac ->
            ac.phases.filter { it.gloss.isBlank() }.map { ac.id + "/" + it.name }
        }
        assertTrue("阶段名缺中文解释: " + missing, missing.isEmpty())
    }

    @Test
    fun everyPhaseHasItems() {
        val empty = library().checklists.flatMap { ac ->
            ac.phases.filter { it.items.isEmpty() }.map { ac.id + "/" + it.name }
        }
        assertTrue("空阶段: " + empty, empty.isEmpty())
    }

    @Test
    fun itemsSplitIntoItemAndState() {
        val a320 = library().checklists.first { it.id == "a320neo" }
        val parkingBrake = a320.phases.first().items.first()
        assertEquals("PARKING BRAKE", parkingBrake.item)
        assertEquals("SET", parkingBrake.state)
    }

    @Test
    fun checklistProvenanceIsRecorded() {
        library().checklists.forEach { ac ->
            assertTrue(ac.source.contains("simchecklists"))
            assertEquals("MIT", ac.license)
        }
    }

    @Test
    fun qrhHasThreeSections() {
        val qrh = library().qrh
        assertEquals(3, qrh.sections.size)
        assertEquals(16, qrh.sections.sumOf { it.entries.size })
        assertTrue(qrh.notice.contains("for simulation only"))
    }

    @Test
    fun qrhMemoryEntriesAreFlagged() {
        val qrh = library().qrh
        val memory = qrh.sections.flatMap { it.entries }.filter { it.memory }
        assertTrue("记忆项目应当存在", memory.isNotEmpty())
        assertTrue(memory.any { it.title == "ENG 1(2) FIRE" })
        assertTrue(memory.any { it.title == "TCAS RA" })
    }

    @Test
    fun qrhEntriesCarryActions() {
        val entries = library().qrh.sections.flatMap { it.entries }
        assertTrue(entries.all { it.actions.isNotEmpty() })
        assertTrue(entries.all { it.ata.isNotBlank() })
        assertTrue(entries.all { it.condition.isNotBlank() })
    }
}
