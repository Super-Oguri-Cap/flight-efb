package com.oguricap.efb

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.oguricap.efb.data.CustomChecklistStore
import com.oguricap.efb.data.ReferenceLibrary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自定义检查单的分享：导出成一份 JSON，别人导入后应当得到一份**独立的**副本。
 *
 * 最容易出错的地方是 id / uid：「能导进来」和「导进来以后不串进度」是两件事。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomChecklistShareTest {

    private lateinit var app: Application
    private lateinit var store: CustomChecklistStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        store = CustomChecklistStore(app)
    }

    private fun builtin() = ReferenceLibrary.get(app).checklists.first()

    private fun checklistJson(name: String) = JSONObject().apply {
        put("id", "shared-1")
        put("name", name)
        put("phases", JSONArray().apply {
            put(JSONObject().apply {
                put("name", "起飞前")
                put("items", JSONArray().apply {
                    put(JSONObject().apply {
                        put("item", "襟翼")
                        put("state", "FLAPS 1")
                    })
                })
            })
        })
    }

    @Test
    fun exportIsSelfDescribingEnvelope() {
        store.create(builtin())
        store.create(builtin())

        val root = JSONObject(store.exportJson())
        assertEquals(CustomChecklistStore.EXPORT_KIND, root.getString("kind"))
        assertEquals(CustomChecklistStore.EXPORT_VERSION, root.getInt("version"))
        assertEquals("两份自定义检查单都应当导出", 2, root.getJSONArray("checklists").length())
        assertTrue("应当带导出时间", root.getLong("exportedAt") > 0)
    }

    @Test
    fun emptyStoreExportsEmptyArray() {
        val root = JSONObject(store.exportJson())
        assertEquals(0, root.getJSONArray("checklists").length())
    }

    @Test
    fun importingSharedCopyKeepsTextButRenewsIds() {
        val created = store.create(builtin())
        val json = store.exportJson()

        val report = store.importJson(json)

        assertEquals(1, report.added)
        assertEquals(0, report.skipped)
        assertEquals(2, store.list().size)

        val imported = store.list().last()
        assertNotEquals("id 必须重新分配，否则会和自己那份撞车", created.id, imported.id)
        assertEquals("导入的东西归到「其他」分类", "other", imported.category)
        assertEquals("名称原样保留", created.name, imported.name)
        assertEquals(
            "条目文字原样保留",
            created.phases.first().items.first().item,
            imported.phases.first().items.first().item,
        )
        assertNotEquals(
            "阶段 uid 必须重新分配，否则两份副本共用一份勾选进度",
            created.phases.first().uid,
            imported.phases.first().uid,
        )
        assertNotEquals(
            "条目 uid 必须重新分配",
            created.phases.first().items.first().uid,
            imported.phases.first().items.first().uid,
        )
    }

    @Test
    fun acceptsBareArrayAndSingleObject() {
        val array = JSONArray().put(checklistJson("裸数组里的"))
        assertEquals(1, store.importJson(array.toString()).added)

        val single = checklistJson("单个对象")
        assertEquals(1, store.importJson(single.toString()).added)

        assertEquals(2, store.list().size)
    }

    @Test
    fun malformedEntriesAreSkippedNotFatal() {
        val array = JSONArray()
        // 没有 phases 的记录留着只会是一个打不开的空壳
        array.put(JSONObject().put("id", "empty"))
        array.put(checklistJson("能用的"))

        val report = store.importJson(array.toString())

        assertEquals(1, report.added)
        assertEquals(1, report.skipped)
        assertEquals("能用的", store.list().single().name)
    }

    @Test
    fun garbageIsRejectedLoudly() {
        val failed = runCatching { store.importJson("这不是 JSON") }.isFailure
        assertTrue("无法解析的内容必须抛出来，由调用方提示用户", failed)
        assertEquals("失败时不能写坏已有数据", 0, store.list().size)
    }
}