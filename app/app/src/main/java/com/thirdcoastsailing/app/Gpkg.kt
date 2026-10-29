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

    /** Open a GeoPackage read-only, or null if the file is missing or not a valid database. */
    fun open(f: File): SQLiteDatabase? =
        if (!f.exists()) null
        else runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()

    /**
     * Feature ids (and an optional numeric column, null when empty) whose bounding box overlaps the given box.
     * Android's built-in SQLite has no R*Tree module, so the file's spatial index (rtree_* tables) can't be used:
     * instead each table's boxes are read once from the geometry headers (the GeoPackage envelope) and kept.
     */
    fun query(db: SQLiteDatabase, table: String, valueCol: String?,
              minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, orderBy: String? = null): List<Pair<Long, Double?>> {
        val ix = index(db, table, valueCol, orderBy) ?: return emptyList()
        val out = ArrayList<Pair<Long, Double?>>()
        for (k in ix.fid.indices) {
            if (ix.maxx[k] < minLon || ix.minx[k] > maxLon || ix.maxy[k] < minLat || ix.miny[k] > maxLat) continue
            out += ix.fid[k] to ix.value[k]
        }
        return out                                                       // already in orderBy order
    }

    /** Bounding boxes of one table's features, in orderBy order (if any). */
    private class BoxIndex(val fid: LongArray, val value: Array<Double?>,
                           val minx: DoubleArray, val maxx: DoubleArray, val miny: DoubleArray, val maxy: DoubleArray)

    private val indexes = HashMap<String, BoxIndex>()

    /** Bounding boxes of one table's features, read once and cached until the file changes (key = path, size and time). */
    @Synchronized
    private fun index(db: SQLiteDatabase, table: String, valueCol: String?, orderBy: String?): BoxIndex? {
        val f = File(db.path)
        val key = "${db.path}|${f.length()}|${f.lastModified()}|$table|$valueCol|$orderBy"
        indexes[key]?.let { return it }
        val v = if (valueCol != null) "\"$valueCol\"" else "NULL"
        // the first 40 bytes of a geometry: 8-byte header, then the envelope (min x, max x, min y, max y)
        val sql = "SELECT fid, $v, substr(geom, 1, 40) FROM \"$table\"" + (if (orderBy != null) " ORDER BY \"$orderBy\"" else "")
        val ix = runCatching {
            db.rawQuery(sql, null).use { c ->
                val n = c.count
                val fid = LongArray(n); val value = arrayOfNulls<Double>(n)
                val minx = DoubleArray(n); val maxx = DoubleArray(n); val miny = DoubleArray(n); val maxy = DoubleArray(n)
                var k = 0
                while (c.moveToNext()) {
                    fid[k] = c.getLong(0)
                    value[k] = if (c.isNull(1)) null else c.getDouble(1)
                    val h = if (c.isNull(2)) null else c.getBlob(2)
                    val env = h?.let { (it[3].toInt() shr 1) and 7 } ?: 0
                    if (h != null && env != 0 && h.size >= 40) {
                        val bb = ByteBuffer.wrap(h, 8, 32).order(if (h[3].toInt() and 1 == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                        minx[k] = bb.double; maxx[k] = bb.double; miny[k] = bb.double; maxy[k] = bb.double
                    } else {                                             // no envelope: always a candidate
                        minx[k] = -180.0; maxx[k] = 180.0; miny[k] = -90.0; maxy[k] = 90.0
                    }
                    k++
                }
                BoxIndex(fid, value, minx, maxx, miny, maxy)
            }
        }.getOrNull() ?: return null
        indexes[key] = ix
        return ix
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

    /** Read one WKB Polygon (type 3) or MultiPolygon (type 6) from the buffer; other types give nothing. */
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
