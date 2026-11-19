package com.thirdcoastsailing.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One region row in the Charts screen. progress = 0..1 while downloading, else null. */
data class LakeRow(val id: String, val name: String, val installed: Boolean, val chartsAsOf: String,
                   val sizeBytes: Long?, val update: Boolean, val progress: Float?)

/**
 * The installed charts, the signed chart list, downloads, imports and removal, kept for the whole app process so a
 * screen rebuild can't leave a download running with nobody watching it (or start a second copy of it). All file work
 * runs off the main thread; the screen reads the Compose state below.
 *
 * Also owns the [DepthLookup] on the installed routing files (shallow-water warning, depth on the gauge, routing),
 * which notices replaced files by itself.
 */
object ChartManager {
    /** Installed regions (Compose state). */
    var lakes by mutableStateOf<List<Lake>>(emptyList())
        private set
    /** The last signature-checked chart list (cached copy until "Check for updates"). */
    var catalog by mutableStateOf<List<CatalogLake>>(emptyList())
        private set
    /** Status line under the Charts list ("Checked 14:02 · no updates"). */
    var catalogMsg by mutableStateOf("")
        private set
    /** True while the chart list is being fetched. */
    var checking by mutableStateOf(false)
        private set
    /** Region ids with an update available (worked out off the main thread when the list or the lakes change). */
    var updates by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Region id -> 0..1 while queued or downloading. */
    val progress = mutableStateMapOf<String, Float>()
    /** A staged folder import that doesn't match the signed chart list, waiting for the user's yes or no. */
    var pendingImport by mutableStateOf<LakeStore.StagedImport?>(null)
        private set
    /** True until the first [init] has read the installed lakes (the screen waits before deciding on first-run dialogs). */
    var loading by mutableStateOf(true)
        private set

    /** Shallow end of the charted depth at a position, from the installed routing files. */
    val depth = DepthLookup { lakes }

    private lateinit var lakeStore: LakeStore
    private lateinit var repo: ChartRepo
    private val queue = Channel<CatalogLake>(Channel.UNLIMITED)
    private var started = false

    /** Read the installed lakes and the cached chart list (once per process), and start the download worker. */
    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        lakeStore = LakeStore(app)
        repo = ChartRepo(app)
        appScope.launch {
            val (l, c) = withContext(Dispatchers.IO) { lakeStore.list() to repo.cachedCatalog() }
            lakes = l; catalog = c
            refreshUpdates()
            loading = false
        }
        // one download at a time, in the order requested
        appScope.launch {
            for (lake in queue) {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        repo.install(lake,
                            progress = { done, total -> appScope.launch { progress[lake.id] = if (total > 0) done.toFloat() / total else 0f } },
                            beforeSwap = { depth.close() },           // release the old routing file before replacing it
                            cancel = { false })                       // runs to the end (the app process keeps it alive)
                    }
                }
                progress.remove(lake.id)
                reloadLakes()
                Status.show(r.fold({ "${lake.name} charts installed" }, { "${lake.name}: ${it.message}" }))
            }
        }
    }

    /** Read the installed lakes again (after an install, import or removal). */
    private suspend fun reloadLakes() {
        lakes = withContext(Dispatchers.IO) { lakeStore.list() }
        refreshUpdates()
    }

    /** Work out which installed regions have an update in the chart list (reads each package's manifest). */
    private suspend fun refreshUpdates() {
        val c = catalog
        updates = withContext(Dispatchers.IO) { c.filter { runCatching { repo.updateAvailable(it) }.getOrDefault(false) }.map { it.id }.toSet() }
    }

    /** The Charts list: catalog and installed regions together, lakes west to east, then the coastal zones. */
    fun rows(): List<LakeRow> {
        val l = lakes; val c = catalog
        val ids = (c.map { it.id } + l.map { it.id }).distinct()
            .sortedBy { REGION_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
        return ids.map { id ->
            val cat = c.firstOrNull { it.id == id }
            val lake = l.firstOrNull { it.id == id }
            LakeRow(id, cat?.name ?: lake?.name ?: id, lake != null, lake?.chartsAsOf?.ifEmpty { null } ?: cat?.chartsAsOf ?: "",
                cat?.size, cat != null && lake != null && id in updates, progress[id])
        }
    }

    /** Queue regions for download (each only once), one at a time in order. */
    fun enqueue(list: List<CatalogLake>) {
        for (l in list) if (l.id !in progress) { progress[l.id] = 0f; queue.trySend(l) }
    }

    /** Fetch the chart list: which regions exist, their sizes and whether updates are out. */
    fun checkCharts() {
        if (checking || !started) return
        checking = true
        catalogMsg = "Checking…"
        appScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { repo.fetchCatalog() } }
            r.onSuccess { c ->
                catalog = c
                refreshUpdates()
                val n = updates.size
                catalogMsg = if (n == 0) "Checked ${clockText(System.currentTimeMillis())} · no updates"
                else "$n update${if (n > 1) "s" else ""} available"
            }.onFailure {
                // a chart list that fails its signature or format checks is refused, not "no connection"
                catalogMsg = if (it is CatalogTrust.Refused) "The chart list failed its security check. Try again later."
                else "Couldn't reach the chart server. Check your connection."
            }
            checking = false
        }
    }

    /** Remove a region's charts (off the main thread: a package is tens to hundreds of MB). */
    fun remove(lake: Lake) {
        appScope.launch {
            withContext(Dispatchers.IO) { depth.close(); lakeStore.remove(lake.id) }
            reloadLakes()
        }
    }

    /**
     * Import from a folder picked in the system file picker: files are copied and checked against the signed chart
     * list. Matching ones are installed at once; otherwise the staged import waits in [pendingImport] for the user.
     */
    fun import(uri: Uri) {
        appScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { lakeStore.stageImport(uri, catalog) { msg -> appScope.launch { Status.show(msg) } } }
                    .onFailure { lakeStore.discardImport() }
            }
            val s = r.getOrNull()
            when {
                // the real reason (too big, storage full, folder unreadable), not "nothing found"
                s == null -> Status.show("Import failed: ${r.exceptionOrNull()?.message ?: "could not read the folder"}")
                s.lakes.isEmpty() -> Status.show("No chart package found in that folder")
                s.unverified.isEmpty() -> commitImport(s)
                else -> pendingImport = s
            }
        }
    }

    /** Install a staged import, then read the lakes again. */
    fun commitImport(s: LakeStore.StagedImport) {
        pendingImport = null
        // a region being downloaded is swapped in by the download worker: importing it now could collide
        val busy = s.lakes.map { it.first }.filter { it in progress }
        if (busy.isNotEmpty()) {
            Status.show("Wait for the ${busy.joinToString()} download to finish, then import again")
            discardImport(); return
        }
        appScope.launch {
            withContext(Dispatchers.IO) { depth.close(); lakeStore.commitImport(s) { msg -> appScope.launch { Status.show(msg) } } }
            reloadLakes()
        }
    }

    /** Import cancelled: drop the copied files. */
    fun discardImport() { pendingImport = null; appScope.launch(Dispatchers.IO) { lakeStore.discardImport() } }
}

/** The message line at the bottom of the chart (fades after 10 s); anything in the app can post to it. */
object Status {
    var text by mutableStateOf("")
        private set
    fun show(msg: String) { text = msg }
    fun clear() { text = "" }
}
