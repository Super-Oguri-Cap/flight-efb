package com.oguricap.efb.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

// 把扫描结果写成与 assets/charts.db 完全相同结构的 SQLite，
// 这样扫描得到的索引和预置索引走同一条只读读取路径。
object IndexBuilder {

    const val SCHEMA_VERSION = 2

    // 预置索引与扫描生成的索引各用一个文件名。
    // 两者共用同一个名字时，正在被只读打开的那个文件无法被覆盖替换。
    const val ASSET_DB_NAME = "charts.db"
    const val SCAN_DB_NAME = "charts-scan.db"

    private val SCHEMA_STATEMENTS = listOf(
        "CREATE TABLE charts(" +
            "id TEXT PRIMARY KEY, icao TEXT, airport TEXT, chartType TEXT, " +
            "title TEXT, file TEXT, rwys TEXT)",
        "CREATE INDEX idx_charts_icao ON charts(icao)",
        "CREATE INDEX idx_charts_type ON charts(chartType)",
        "CREATE TABLE airports(" +
            "icao TEXT PRIMARY KEY, name TEXT NOT NULL, chartCount INTEGER NOT NULL)",
        "CREATE INDEX idx_airports_name ON airports(name)",
    )

    // 扫描结果落库到应用私有目录，返回可打开的 ChartIndex
    fun writeToPrivate(context: Context, result: ChartScanner.Result): File {
        val dir = File(context.filesDir, "index").apply { mkdirs() }
        val target = File(dir, SCAN_DB_NAME)
        val temp = File(dir, SCAN_DB_NAME + ".tmp")
        if (temp.exists()) temp.delete()

        SQLiteDatabase.openOrCreateDatabase(temp, null).use { db ->
            SCHEMA_STATEMENTS.forEach(db::execSQL)
            db.beginTransaction()
            val chartStmt = db.compileStatement(
                "INSERT OR REPLACE INTO charts(id, icao, airport, chartType, title, file, rwys) " +
                    "VALUES (?,?,?,?,?,?,?)",
            )
            for (chart in result.charts) {
                chartStmt.clearBindings()
                chartStmt.bindString(1, chart.id)
                chartStmt.bindString(2, chart.icao)
                chartStmt.bindString(3, chart.airport)
                chartStmt.bindString(4, chart.chartType)
                chartStmt.bindString(5, chart.title)
                chartStmt.bindString(6, chart.file)
                chartStmt.bindString(7, chart.rwys.joinToString(","))
                chartStmt.executeInsert()
            }
            val airportStmt = db.compileStatement(
                "INSERT OR REPLACE INTO airports(icao, name, chartCount) VALUES (?,?,?)",
            )
            for (airport in result.airports) {
                airportStmt.clearBindings()
                airportStmt.bindString(1, airport.icao)
                airportStmt.bindString(2, airport.name)
                airportStmt.bindLong(3, airport.chartCount.toLong())
                airportStmt.executeInsert()
            }
            db.setTransactionSuccessful()
            db.endTransaction()
            db.execSQL("VACUUM")
        }

        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "索引文件替换失败: " + target.absolutePath }
        File(dir, "version.txt").writeText(SCHEMA_VERSION.toString())
        return target
    }

    // 把 assets 里的预置索引拷到私有目录；版本号不一致时重新覆盖
    fun installAsset(context: Context): File {
        val dir = File(context.filesDir, "index").apply { mkdirs() }
        val target = File(dir, ASSET_DB_NAME)
        val stamp = File(dir, "version.txt")
        val installed = stamp.takeIf { it.exists() }?.readText()?.trim()
        if (!target.exists() || installed != SCHEMA_VERSION.toString()) {
            context.assets.open(ASSET_DB_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            stamp.writeText(SCHEMA_VERSION.toString())
        }
        return target
    }
}