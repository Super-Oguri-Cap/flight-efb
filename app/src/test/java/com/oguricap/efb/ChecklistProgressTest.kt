package com.oguricap.efb

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.data.ChecklistProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// 勾选进度按机型分开存：一份检查单的勾不能串到另一份上
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChecklistProgressTest {

    private fun store(): ChecklistProgress =
        ChecklistProgress(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun saveAndReloadKeepsOnlyThatAircraft() {
        val store = store()
        val keys = setOf(
            ChecklistProgress.itemKey(0, 0),
            ChecklistProgress.itemKey(0, 1),
            ChecklistProgress.itemKey(3, 5),
        )
        store.save("a320neo", keys)

        assertEquals(keys, store.load("a320neo"))
        assertTrue(store.load("b738x").isEmpty())
    }

    @Test
    fun samePositionInDifferentChecklistsDoNotCollide() {
        val store = store()
        val key = ChecklistProgress.itemKey(1, 1)
        store.save("a320neo", setOf(key))

        // 同样是「第 1 阶段第 1 条」，但属于另一份检查单
        assertTrue(store.load("cn_a320_normal").isEmpty())
        assertTrue(store.load("a320neo").contains(key))
    }

    @Test
    fun savingEmptySetClearsTheRecord() {
        val store = store()
        store.save("a320neo", setOf(ChecklistProgress.itemKey(2, 2)))
        store.save("a320neo", emptySet())
        assertTrue(store.load("a320neo").isEmpty())
    }
}
