package com.thirdcoastsailing.app

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Charted depth at a position, read from the lake's routing file (<lake>_hazards.gpkg, a GeoPackage/SQLite file).
 * Returns the SHALLOW end of the depth range the point falls in (conservative), in feet.
 * Charted depth areas are used first; the approximate NCEI depth bands only where no chart covers.
 */
class DepthLookup(private val lakes: () -> List<Lake>) {

    private val open = mutableMapOf<String, SQLiteDatabase>()
    // parsed polygons, kept because the approximate-depth bands are large and the boat sits in the same one for hours
    private val polys = object : LinkedHashMap<String, List<List<DoubleArray>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<List<DoubleArray>>>?) = size > 64
    }

    /** The open routing file of a lake (opened on first use and kept open), or null if it is missing. */
    private fun db(lake: Lake): SQLiteDatabase? {
        open[lake.id]?.let { return it }
        return Gpkg.open(File(lake.pmtiles.parentFile, "${lake.id}_hazards.gpkg"))?.also { open[lake.id] = it }
    }

    /** Close every open routing file and forget the cached polygons (before chart files are replaced). */
    @Synchronized
    fun close() { open.values.forEach { it.close() }; open.clear(); polys.clear() }

    /** Shallow end of the depth range at (lat, lon) in feet, or null if not on charted water. */
    @Synchronized
    fun depthFt(lat: Double, lon: Double): Double? {
        for (lake in lakes()) {
            val db = db(lake) ?: continue
            minDepth(lake.id, db, "depth_area", "drval1", lat, lon)?.let { return it * 3.28084 }
            minDepth(lake.id, db, "bathy_band", "dmin", lat, lon)?.let { return it * 3.28084 }
        }
        return null
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
