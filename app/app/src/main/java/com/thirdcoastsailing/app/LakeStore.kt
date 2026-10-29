package com.thirdcoastsailing.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File

/** One imported lake package stored inside the app. */
data class Lake(
    val id: String,            // superior, michigan, huron, erie, ontario
    val name: String,          // display name
    val chartsAsOf: String,    // newest NOAA chart issue date in the package (yyyy-mm-dd)
    val built: String,         // package build date (yyyy-mm-dd)
    val pmtiles: File,
)

/**
 * Lake packages live in the app's private storage: files/packages/<lake>/.
 * Import copies them from a folder the user picks in Android's system file picker,
 * so no storage permission is needed (works the same on stock, GrapheneOS and LineageOS).
 */
class LakeStore(private val context: Context) {

    private val root = File(context.filesDir, "packages").apply { mkdirs() }

    // Display order west to east
    private val order = listOf("superior", "michigan", "huron", "erie", "ontario")

    /** All lakes that have a chart file. */
    fun list(): List<Lake> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { read(it) }
            .sortedBy { order.indexOf(it.id).let { i -> if (i < 0) 99 else i } }

    /** One installed lake from its folder: needs a chart file; dates come from manifest.json when it is there. */
    private fun read(dir: File): Lake? {
        val pm = dir.listFiles()?.firstOrNull { it.name.endsWith("_chart.pmtiles") } ?: return null
        var built = ""
        var asOf = ""
        val man = File(dir, "manifest.json")
        if (man.exists()) {
            runCatching {
                val m = JSONObject(man.readText())
                built = m.optString("built_utc").take(10)
                val cells = m.optJSONObject("sources")?.optJSONObject("enc")?.optJSONArray("cells")
                if (cells != null) {
                    var newest = ""
                    for (i in 0 until cells.length()) {
                        val d = cells.getJSONObject(i).optString("issue_date")
                        if (d > newest) newest = d
                    }
                    if (newest.length == 8) asOf = "${newest.take(4)}-${newest.substring(4, 6)}-${newest.takeLast(2)}"
                }
            }
        }
        return Lake(dir.name, dir.name.replaceFirstChar { it.uppercase() }, asOf, built, pm)
    }

    /** Delete a lake package from the phone (frees its storage). */
    fun remove(id: String) {
        File(root, id).deleteRecursively()
    }

    /** A folder import copied into the app but not installed yet. unverified = lakes that don't match the signed chart list. */
    class StagedImport(val lakes: List<Pair<String, File>>, val unverified: List<String>)

    private val importStage = File(context.filesDir, "import_staging")

    /**
     * Import step 1: copy from a picked folder into the app's import area and check each file against the signed
     * chart list (catalog). The folder can be one lake (contains manifest.json) or the whole "packages" folder
     * (one sub-folder per lake). Lakes whose files all match the published charts are "verified"; anything else
     * (self-built or altered charts) is reported so the user can decide.
     * @param catalog the last signature-checked chart list (empty when none was ever fetched: nothing verifies)
     * @param progress called with a short status line while copying
     */
    fun stageImport(treeUri: Uri, catalog: List<CatalogLake>, progress: (String) -> Unit): StagedImport {
        importStage.deleteRecursively()
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return StagedImport(emptyList(), emptyList())
        val lakeDirs = if (tree.findFile("manifest.json") != null) listOf(tree)
        else tree.listFiles().filter { it.isDirectory && it.findFile("manifest.json") != null }
        val staged = mutableListOf<Pair<String, File>>()
        val unverified = mutableListOf<String>()
        for (d in lakeDirs) {
            val chart = d.listFiles().firstOrNull { it.name?.endsWith("_chart.pmtiles") == true } ?: continue
            val id = chart.name!!.removeSuffix("_chart.pmtiles")
            if (!CatalogTrust.validId(id)) continue                      // only plain lake names ("michigan")
            val dir = File(importStage, id).apply { mkdirs() }
            // Chart tiles, routing data and manifest; the .mbtiles copy is not needed on the phone
            val wanted = d.listFiles().filter { f ->
                val n = f.name ?: ""
                CatalogTrust.validName(n) && (n == "manifest.json" || n == "${id}_chart.pmtiles" || n == "${id}_hazards.gpkg")
            }
            for (f in wanted) {
                progress("Importing $id: ${f.name}")
                context.contentResolver.openInputStream(f.uri)?.use { input ->
                    File(dir, f.name!!).outputStream().use { out -> CatalogTrust.copyCapped(input, out, CatalogTrust.MAX_CHART_FILE_BYTES) }
                }
            }
            staged += id to dir
            if (!matchesCatalog(id, dir, catalog)) unverified += id
        }
        return StagedImport(staged, unverified)
    }

    /**
     * True when every copied file of a lake matches the signed chart list: the chart tiles and manifest by their
     * checksum, the routing file by its checksum before compression.
     */
    private fun matchesCatalog(id: String, dir: File, catalog: List<CatalogLake>): Boolean {
        val lake = catalog.firstOrNull { it.id == id } ?: return false
        val files = dir.listFiles()?.toList().orEmpty()
        if (files.none { it.name.endsWith("_chart.pmtiles") }) return false
        return files.all { f ->
            val sha = CatalogTrust.sha256(f)
            when {
                f.name.endsWith("_chart.pmtiles") -> lake.files.any { it.name.endsWith("_chart.pmtiles") && it.sha256 == sha }
                f.name == "manifest.json" -> lake.files.any { it.name == "manifest.json" && it.sha256 == sha }
                f.name.endsWith("_hazards.gpkg") -> lake.files.any { it.name.endsWith("_hazards.gpkg") && it.sha256Raw == sha }
                else -> false
            }
        }
    }

    /** Import step 2: move the staged lakes into place (replacing older copies of the same files). Returns their ids. */
    fun commitImport(s: StagedImport, progress: (String) -> Unit): List<String> {
        val done = mutableListOf<String>()
        for ((id, dir) in s.lakes) {
            val dest = File(root, id).apply { mkdirs() }
            for (f in dir.listFiles().orEmpty()) f.renameTo(File(dest, f.name))
            done += id
        }
        importStage.deleteRecursively()
        progress(if (done.isEmpty()) "No lake package found in that folder" else "Imported: ${done.joinToString()}")
        return done
    }

    /** Import cancelled: drop the copied files. */
    fun discardImport() { importStage.deleteRecursively() }
}
