package com.thirdcoastsailing.app

import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal GeoPackage (SQLite) reader for the lake routing files (<lake>_hazards.gpkg):
 * spatial-index queries, geometry blobs read in pieces (Android cannot pass a single value over 2 MB
 * through a cursor; the approximate-depth polygons are bigger than that), and polygon parsing.
 */
object Gpkg {

    private const val CHUNK = 1_000_000

    fun open(f: File): SQLiteDatabase? =
        if (!f.exists()) null
        else runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()

    /** Feature ids (and an optional numeric column, null when empty) whose index box overlaps the given box. */
    fun query(db: SQLiteDatabase, table: String, valueCol: String?,
              minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, orderBy: String? = null): List<Pair<Long, Double?>> {
        val v = if (valueCol != null) "t.\"$valueCol\"" else "NULL"
        val sql = "SELECT t.fid, $v FROM \"$table\" t JOIN \"rtree_${table}_geom\" r ON t.fid = r.id " +
                "WHERE r.maxx >= CAST(? AS REAL) AND r.minx <= CAST(? AS REAL) AND r.maxy >= CAST(? AS REAL) AND r.miny <= CAST(? AS REAL)" +
                (if (orderBy != null) " ORDER BY t.\"$orderBy\"" else "")
        return runCatching {
            db.rawQuery(sql, arrayOf("$minLon", "$maxLon", "$minLat", "$maxLat")).use { c ->
                val out = mutableListOf<Pair<Long, Double?>>()
                while (c.moveToNext()) out += c.getLong(0) to (if (c.isNull(1)) null else c.getDouble(1))
                out
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Geometry blob of one feature. Read through a file descriptor first (no size limit); if that fails, in 1 MB
     * pieces through a cursor (a cursor cannot carry a single value over about 2 MB, and the approximate-depth
     * polygons are bigger than that).
     */
    fun geom(db: SQLiteDatabase, table: String, fid: Long): ByteArray? {
        runCatching {
            db.compileStatement("SELECT geom FROM \"$table\" WHERE fid = ?").use { st ->
                st.bindLong(1, fid)
                st.simpleQueryForBlobFileDescriptor()?.let { fd ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
                }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching {
            val len = db.rawQuery("SELECT length(geom) FROM \"$table\" WHERE fid = ?", arrayOf("$fid")).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0
            }
            if (len <= 0) return null
            val out = ByteArrayOutputStream(len)
            var pos = 1                                                  // SQLite substr is 1-based
            while (pos <= len) {
                db.rawQuery("SELECT substr(geom, ?, ?) FROM \"$table\" WHERE fid = ?", arrayOf("$pos", "$CHUNK", "$fid")).use { c ->
                    if (c.moveToFirst()) out.write(c.getBlob(0))
                }
                pos += CHUNK
            }
            out.toByteArray()
        }.getOrNull()
    }

    /** Polygons in a GeoPackage Polygon / MultiPolygon blob: each polygon is a list of rings of x,y (lon,lat) pairs. */
    fun polygons(blob: ByteArray): List<List<DoubleArray>> = runCatching {
        val flags = blob[3].toInt()
        val envBytes = when ((flags shr 1) and 7) { 1 -> 32; 2, 3 -> 48; 4 -> 64; else -> 0 }
        readPolygons(ByteBuffer.wrap(blob, 8 + envBytes, blob.size - 8 - envBytes))
    }.getOrDefault(emptyList())

    private fun readPolygons(b: ByteBuffer): List<List<DoubleArray>> {
        b.order(if (b.get() == 1.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        return when (b.int % 1000) {
            3 -> listOf((0 until b.int).map { val n = b.int; DoubleArray(n * 2) { b.double } })
            6 -> (0 until b.int).flatMap { readPolygons(b) }
            else -> emptyList()
        }
    }

    /** Even-odd point-in-polygon across all rings (outer ring and holes). */
    fun contains(rings: List<DoubleArray>, x: Double, y: Double): Boolean {
        var inside = false
        for (r in rings) {
            var j = r.size / 2 - 1
            for (i in 0 until r.size / 2) {
                val xi = r[2 * i]; val yi = r[2 * i + 1]; val xj = r[2 * j]; val yj = r[2 * j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }
}
