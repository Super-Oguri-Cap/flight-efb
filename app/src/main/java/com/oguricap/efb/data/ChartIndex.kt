package com.oguricap.efb.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

class ChartIndex private constructor(
    private val db: SQLiteDatabase,
    val file: File,
) : Closeable {

    fun airports(): List<Airport> =
        db.rawQuery(
            "SELECT icao, name, chartCount FROM airports ORDER BY icao",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Airport(c.getString(0), c.getString(1), c.getInt(2)))
                }
            }
        }

    fun airport(icao: String): Airport? =
        db.rawQuery(
            "SELECT icao, name, chartCount FROM airports WHERE icao = ?",
            arrayOf(icao),
        ).use { c -> if (c.moveToFirst()) Airport(c.getString(0), c.getString(1), c.getInt(2)) else null }

    fun charts(icao: String): List<Chart> =
        db.rawQuery(
            "SELECT id, icao, airport, chartType, title, file, rwys FROM charts WHERE icao = ?",
            arrayOf(icao),
        ).use { c -> readCharts(c) }
            .sortedWith(compareBy({ ChartType.rank(it.chartType) }, { it.title }))

    fun searchAirports(query: String): List<Airport> {
        val q = query.trim()
        if (q.isEmpty()) return airports()
        val like = "%" + escape(q) + "%"
        val prefix = escape(q) + "%"
        return db.rawQuery(
            "SELECT icao, name, chartCount FROM airports " +
                "WHERE icao LIKE ? ESCAPE '\\' OR name LIKE ? ESCAPE '\\' " +
                "ORDER BY (name LIKE ? ESCAPE '\\') DESC, icao",
            arrayOf(prefix, like, like),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Airport(c.getString(0), c.getString(1), c.getInt(2)))
                }
            }
        }
    }

    // 图名搜索只按标题匹配。
    // 旧版还带 `OR icao = ?`：搜 ZGGG 会把该机场上百张图全灌进搜索结果，
    // 而用户此刻要找的是「ZGGG 这个机场」，具体航图进机场页按图型挑即可。
    fun searchCharts(query: String, limit: Int = 80): List<Chart> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val like = "%" + escape(q) + "%"
        return db.rawQuery(
            "SELECT id, icao, airport, chartType, title, file, rwys FROM charts " +
                "WHERE title LIKE ? ESCAPE '\\' " +
                "ORDER BY icao, chartType, title LIMIT ?",
            arrayOf(like, limit.toString()),
        ).use { c -> readCharts(c) }
    }

    fun chartByFile(path: String): Chart? =
        db.rawQuery(
            "SELECT id, icao, airport, chartType, title, file, rwys FROM charts WHERE file = ? LIMIT 1",
            arrayOf(path),
        ).use { c -> readCharts(c).firstOrNull() }

    fun chartCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM charts", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun readCharts(c: android.database.Cursor): List<Chart> = buildList {
        while (c.moveToNext()) {
            add(
                Chart(
                    id = c.getString(0),
                    icao = c.getString(1),
                    airport = c.getString(2),
                    chartType = c.getString(3),
                    title = c.getString(4),
                    file = c.getString(5),
                    rwys = c.getString(6).split(",").filter { it.isNotBlank() },
                ),
            )
        }
    }

    override fun close() = db.close()

    companion object {
        const val SCHEMA_VERSION = IndexBuilder.SCHEMA_VERSION

        fun open(context: Context): ChartIndex = open(IndexBuilder.installAsset(context))

        fun open(file: File): ChartIndex {
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            return ChartIndex(db, file)
        }
    }
}

private fun escape(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")