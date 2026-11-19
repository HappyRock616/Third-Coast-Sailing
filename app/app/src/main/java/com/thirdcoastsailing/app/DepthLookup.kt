package com.thirdcoastsailing.app

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Charted depth at a position, read from the lake's routing file (<lake>_hazards.gpkg, a GeoPackage/SQLite file).
 * Returns the SHALLOW end of the depth range the point falls in (conservative), in feet.
 * Charted depth areas are used first; the approximate NCEI depth bands only where no chart covers.
 */
class DepthLookup(private val lakes: () -> List<Lake>) {

    /** An open routing file and the file's size and time when it was opened (to notice a replaced file). */
    private class Handle(val db: SQLiteDatabase, val stamp: String)

    private val open = mutableMapOf<String, Handle>()
    // parsed polygons, kept because the approximate-depth bands are large and the boat sits in the same one for hours
    private val polys = object : LinkedHashMap<String, List<List<DoubleArray>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<List<DoubleArray>>>?) = size > 64
    }

    /**
     * The open routing file of a lake (opened on first use and kept open), or null if it is missing. A file replaced
     * since it was opened (chart update or import) is closed and the new one opened, so depths never come from the
     * old file while the feature ids come from the new one.
     */
    private fun db(lake: Lake): SQLiteDatabase? {
        val f = File(lake.pmtiles.parentFile, "${lake.id}_hazards.gpkg")
        if (!f.exists()) { forget(lake.id); return null }
        val stamp = "${f.length()}|${f.lastModified()}"
        open[lake.id]?.let { if (it.stamp == stamp) return it.db; forget(lake.id) }
        return Gpkg.open(f)?.also { open[lake.id] = Handle(it, stamp) }
    }

    /** Close one lake's routing file and drop its cached polygons. */
    private fun forget(id: String) {
        open.remove(id)?.db?.close()
        polys.keys.removeAll { it.startsWith("$id/") }
    }

    private companion object {
        /** Tables of the routing file that are never navigable (as in NavMask.build). */
        val BLOCKED_TABLES = listOf("land", "shoreline_construction_area", "hazard_area")
    }

    /** Close every open routing file and forget the cached polygons (before chart files are replaced). */
    @Synchronized
    fun close() { open.values.forEach { it.db.close() }; open.clear(); polys.clear() }

    /** Shallow end of the depth range at (lat, lon) in feet, or null if not on charted water. */
    @Synchronized
    fun depthFt(lat: Double, lon: Double): Double? {
        for (lake in lakes()) {
            val db = db(lake) ?: continue
            minDepth(lake.id, db, "depth_area", "drval1", lat, lon)?.let { return it * FT_PER_M }
            minDepth(lake.id, db, "bathy_band", "dmin", lat, lon)?.let { return it * FT_PER_M }
        }
        return null
    }

    /**
     * True when the point is on charted land, a pier or breakwater (shoreline construction) or a charted hazard area:
     * the same areas the weather routing treats as blocked. A point off every chart is not blocked.
     */
    @Synchronized
    fun blocked(lat: Double, lon: Double): Boolean {
        for (lake in lakes()) {
            val db = db(lake) ?: continue
            for (table in BLOCKED_TABLES) if (inside(lake.id, db, table, lat, lon)) return true
        }
        return false
    }

    /** True when any polygon of `table` contains the point. */
    private fun inside(lake: String, db: SQLiteDatabase, table: String, lat: Double, lon: Double): Boolean {
        for ((fid, _) in Gpkg.query(db, table, null, lon, lat, lon, lat)) {
            val p = polys.getOrPut("$lake/$table/$fid") { Gpkg.geom(db, table, fid)?.let { Gpkg.polygons(it) } ?: emptyList() }
            if (p.any { Gpkg.contains(it, lon, lat) }) return true
        }
        return false
    }

    /** Smallest depth value among polygons of `table` that contain the point (uses the spatial index). */
    private fun minDepth(lake: String, db: SQLiteDatabase, table: String, field: String, lat: Double, lon: Double): Double? {
        var best: Double? = null
        for ((fid, value) in Gpkg.query(db, table, field, lon, lat, lon, lat)) {
            val v = value ?: 0.0
            val b = best
            if (b != null && v >= b) continue
            val key = "$lake/$table/$fid"
            val p = polys.getOrPut(key) { Gpkg.geom(db, table, fid)?.let { Gpkg.polygons(it) } ?: emptyList() }
            if (p.any { Gpkg.contains(it, lon, lat) }) best = v
        }
        return best
    }
}
