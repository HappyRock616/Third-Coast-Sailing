package com.thirdcoastsailing.app

import android.content.Context
import java.io.File

/**
 * The rough North America basemap shipped inside the app (assets/basemap/), built by 1_Chart_Build/make_basemap.py
 * from Natural Earth: land, country and state/province lines and names. MapLibre reads PMTiles from a file, so the
 * asset is copied to app storage once. A new build gets a new file name (bump ASSET), which triggers a fresh copy.
 */
object Basemap {
    private const val ASSET = "basemap_v5.pmtiles"     // v2: + inland layer; v3-v5: inland cut around lake charts water only (250 m margin)

    /** The basemap file on app storage, copied from the assets on first use; null if the copy failed. */
    fun file(context: Context): File? {
        val dir = File(context.filesDir, "basemap")
        val f = File(dir, ASSET)
        if (f.isFile && f.length() > 0) return f
        return try {
            dir.mkdirs()
            val tmp = File(dir, "$ASSET.tmp")
            context.assets.open("basemap/$ASSET").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(f)) return null
            // copies of earlier basemap versions are no longer needed
            dir.listFiles()?.filter { it != f && it.name.startsWith("basemap_v") }?.forEach { it.delete() }
            f
        } catch (e: Exception) {
            null
        }
    }
}
