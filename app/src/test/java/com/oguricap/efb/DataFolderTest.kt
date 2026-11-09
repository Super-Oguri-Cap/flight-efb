package com.oguricap.efb

import com.oguricap.efb.ui.tools.DataFolder
import com.oguricap.efb.ui.tools.DataRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「选文件夹」靠文件名认文件，认错就等于没导入。
 *
 * 这里的用例全部来自真实数据：
 *  - X-Plane 12 导航包（Navigraph 2611）里的实际文件名；
 *  - CAAC 2611V2 的 AIP 表文件名 —— 注意 overflying 那张表用的是 **不换行空格（\u00A0）**，
 *    在资源管理器里看着和普通空格一样，按空格切分就会漏掉。
 */
class DataFolderTest {

    @Test
    fun navDataNamesAreClassified() {
        assertEquals(DataRole.NAV_FIX, DataFolder.roleOf("earth_fix.dat"))
        assertEquals(DataRole.NAV_AWY, DataFolder.roleOf("earth_awy.dat"))
        assertEquals(DataRole.NAV_NAV, DataFolder.roleOf("earth_nav.dat"))
        assertEquals(DataRole.NAV_APTMETA, DataFolder.roleOf("earth_aptmeta.dat"))
        assertEquals(DataRole.NAV_APT, DataFolder.roleOf("apt.dat"))
    }

    @Test
    fun fileNameIsTakenFromPathNotTheWholeString() {
        // SAF 给的 displayName 可能带完整路径；网页侧 classify 也是取最后一段
        assertEquals(DataRole.NAV_FIX, DataFolder.roleOf("Custom Data/earth_fix.dat"))
        assertEquals(DataRole.NAV_FIX, DataFolder.roleOf("Custom Data\\earth_fix.dat"))
        assertEquals(DataRole.NAV_APTMETA, DataFolder.roleOf("xplane12-native-2611-r1/earth_aptmeta.dat"))
    }

    @Test
    fun unusedNavFilesAreIgnored() {
        assertEquals(DataRole.IGNORED, DataFolder.roleOf("earth_hold.dat"))
        assertEquals(DataRole.IGNORED, DataFolder.roleOf("earth_mora.dat"))
        assertEquals(DataRole.IGNORED, DataFolder.roleOf("earth_msa.dat"))
        assertEquals(DataRole.IGNORED, DataFolder.roleOf("cycle_info.txt"))
        assertEquals(DataRole.IGNORED, DataFolder.roleOf(""))
    }

    @Test
    fun aipTablesAreClassified() {
        assertEquals(DataRole.AIP_CSV, DataFolder.roleOf("International Flights from Mainland of China Nr2611.csv"))
        assertEquals(DataRole.AIP_CSV, DataFolder.roleOf("Regional Flight Routes Nr2611.csv"))
        // 真名里是不换行空格，不能按普通空格切
        assertEquals(
            DataRole.AIP_CSV,
            DataFolder.roleOf("Overflying\u00A0Flight\u00A0Routes\u00A0From\u00A0Operation\u00A0Center\u00A0Nr2611.csv"),
        )
        assertEquals(DataRole.AIP_JSON, DataFolder.roleOf("caac_official.json"))
        assertEquals(DataRole.ZIP, DataFolder.roleOf("2611V2.zip"))
    }

    @Test
    fun unrelatedFilesAreIgnored() {
        assertEquals(DataRole.IGNORED, DataFolder.roleOf("International Flights to Mainland of China Nr2611.pdf"))
        assertEquals(DataRole.IGNORED, DataFolder.roleOf("package.json"))
        assertEquals(DataRole.IGNORED, DataFolder.roleOf("readme.md"))
    }

    @Test
    fun rolesReportWhatTheyAreFor() {
        assertTrue(DataRole.NAV_APTMETA.isNav)
        assertTrue(DataRole.AIP_CSV.isAip)
        assertFalse(DataRole.ZIP.isNav)
        assertFalse(DataRole.ZIP.isAip)
    }

    @Test
    fun customDataBeatsDefaultData() {
        val custom = DataFolder.preference("Custom Data/earth_fix.dat")
        val plain = DataFolder.preference("navdata/earth_fix.dat")
        val factory = DataFolder.preference("Resources/default data/earth_fix.dat")
        assertTrue("Custom Data 应当优先于普通目录", custom < plain)
        assertTrue("游戏自带数据应当排最后", factory > plain)
    }
}
