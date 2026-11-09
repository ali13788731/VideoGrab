package com.videograb.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.videograb.app.data.DownloadItem
import com.videograb.app.data.Status
import com.videograb.app.engine.DownloadEngine
import com.videograb.app.engine.Live
import com.videograb.app.engine.Storage
import kotlinx.coroutines.launch
import java.io.File

fun fmtSize(b: Long): String = when {
    b < 0 -> "—"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format("%.0f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format("%.1f MB", b / 1048576.0)
    else -> String.format("%.2f GB", b / 1073741824.0)
}

private fun fmtEta(sec: Long): String = when {
    sec < 60 -> "${sec}ث"
    sec < 3600 -> "${sec / 60}د ${sec % 60}ث"
    else -> "${sec / 3600}س ${(sec % 3600) / 60}د"
}

private fun uriFor(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

private fun openFile(ctx: Context, f: File) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(ctx, f), "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}

private fun shareFiles(ctx: Context, files: List<File>) {
    val uris = ArrayList(files.filter { it.exists() }.map { uriFor(ctx, it) })
    if (uris.isEmpty()) return
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    i.type = "video/*"
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "اشتراک‌گذاری").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by DownloadEngine.observeAll().collectAsState(emptyList())
    val live by DownloadEngine.live.collectAsState()

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("ALL") }
    var sort by remember { mutableStateOf("DATE") }
    var sortMenu by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    var toDelete by remember { mutableStateOf<List<DownloadItem>?>(null) }
    var toRename by remember { mutableStateOf<DownloadItem?>(null) }

    val shown = items.filter {
        val st = it.status
        val okFilter = when (filter) {
            "ACTIVE" -> st in listOf(Status.QUEUED.name, Status.DOWNLOADING.name, Status.PAUSED.name)
            "DONE" -> st == Status.COMPLETED.name
            "FAILED" -> st == Status.FAILED.name
            else -> true
        }
        okFilter && (query.isBlank() || it.title.contains(query, true) || it.fileName.contains(query, true))
    }.let { l ->
        when (sort) {
            "NAME" -> l.sortedBy { it.title.lowercase() }
            "SIZE" -> l.sortedByDescending { it.downloadedBytes }
            else -> l
        }
    }
    val selectedItems = items.filter { it.id in selected }
    val inSelection = selected.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                when {
                    inSelection -> Text("${selected.size} انتخاب شده")
                    searching -> OutlinedTextField(
                        query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("جست‌وجو در دانلودها") },
                    )
                    else -> Column {
                        Text("فایل منیجر")
                        Text(
                            "${items.size} فایل • ${fmtSize(items.sumOf { it.downloadedBytes })}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            },
            actions = {
                if (inSelection) {
                    IconButton({ selected.clear(); selected.addAll(shown.map { it.id }) }) { Icon(Icons.Default.SelectAll, "همه") }
                    IconButton({ selectedItems.forEach { DownloadEngine.start(it.id) } }) { Icon(Icons.Default.PlayArrow, "ادامه") }
                    IconButton({ selectedItems.forEach { DownloadEngine.pause(it.id) } }) { Icon(Icons.Default.Pause, "توقف") }
                    IconButton({ shareFiles(ctx, selectedItems.map { Storage.file(ctx, it) }) }) { Icon(Icons.Default.Share, "اشتراک") }
                    IconButton({ toDelete = selectedItems }) { Icon(Icons.Default.Delete, "حذف") }
                    IconButton({ selected.clear() }) { Icon(Icons.Default.Close, "لغو") }
                } else {
                    IconButton({ searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "جست‌وجو")
                    }
                    Box {
                        IconButton({ sortMenu = true }) { Icon(Icons.Default.Sort, "مرتب‌سازی") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            DropdownMenuItem({ Text("جدیدترین") }, { sort = "DATE"; sortMenu = false })
                            DropdownMenuItem({ Text("نام") }, { sort = "NAME"; sortMenu = false })
                            DropdownMenuItem({ Text("حجم") }, { sort = "SIZE"; sortMenu = false })
                            DropdownMenuItem({ Text("ادامه همه") }, {
                                sortMenu = false
                                scope.launch { DownloadEngine.resumeAllPaused(items) }
                            })
                            DropdownMenuItem({ Text("توقف همه") }, { sortMenu = false; DownloadEngine.pauseAll() })
                        }
                    }
                }
            },
        )
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL" to "همه", "ACTIVE" to "فعال", "DONE" to "کامل", "FAILED" to "ناموفق").forEach { (k, t) ->
                FilterChip(filter == k, { filter = k }, label = { Text(t) })
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("موردی نیست") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
            items(shown, key = { it.id }) { item ->
                DownloadRow(
                    item = item,
                    live = live[item.id],
                    isSelected = item.id in selected,
                    onClick = {
                        if (inSelection) { if (item.id in selected) selected.remove(item.id) else selected.add(item.id) }
                        else if (item.status == Status.COMPLETED.name) openFile(ctx, Storage.file(ctx, item))
                    },
                    onLong = { if (item.id !in selected) selected.add(item.id) },
                    onPause = { DownloadEngine.pause(item.id) },
                    onResume = { DownloadEngine.start(item.id) },
                    onRename = { toRename = item },
                    onShare = { shareFiles(ctx, listOf(Storage.file(ctx, item))) },
                    onExport = {
                        scope.launch {
                            val ok = DownloadEngine.exportToGallery(item)
                            snackbar.showSnackbar(if (ok) "در گالری ذخیره شد (Movies/VideoGrab)" else "ذخیره ناموفق بود")
                        }
                    },
                    onDelete = { toDelete = listOf(item) },
                )
            }
        }
    }

    toDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("حذف ${list.size} مورد؟") },
            text = { Text("می‌خواهید فایل ویدیو هم از حافظه پاک شود؟") },
            confirmButton = {
                Row {
                    TextButton({ toDelete = null }) { Text("لغو") }
                    TextButton({
                        scope.launch { list.forEach { DownloadEngine.delete(it.id, false) } }
                        selected.clear(); toDelete = null
                    }) { Text("فقط از لیست") }
                    TextButton({
                        scope.launch { list.forEach { DownloadEngine.delete(it.id, true) } }
                        selected.clear(); toDelete = null
                    }) { Text("با فایل") }
                }
            },
        )
    }

    toRename?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.fileName.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { toRename = null },
            title = { Text("تغییر نام") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton({
                    scope.launch {
                        if (!DownloadEngine.rename(item, name)) snackbar.showSnackbar("تغییر نام ممکن نشد")
                    }
                    toRename = null
                }) { Text("تأیید") }
            },
            dismissButton = { TextButton({ toRename = null }) { Text("لغو") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    item: DownloadItem, live: Live?, isSelected: Boolean,
    onClick: () -> Unit, onLong: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onRename: () -> Unit,
    onShare: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val done = item.status == Status.COMPLETED.name
    val running = item.status == Status.DOWNLOADING.name || item.status == Status.QUEUED.name
    val dl = live?.downloaded ?: item.downloadedBytes
    val total = live?.total ?: item.totalBytes
    val frac = if (total > 0) (dl.toFloat() / total).coerceIn(0f, 1f) else null
    var menu by remember { mutableStateOf(false) }

    val status = when (item.status) {
        Status.QUEUED.name -> "در صف"
        Status.DOWNLOADING.name -> {
            val sp = live?.speed ?: 0L
            val eta = if (sp > 0 && total > dl) " • ${fmtEta((total - dl) / sp)}" else ""
            "${fmtSize(sp)}/s$eta"
        }
        Status.PAUSED.name -> "متوقف"
        Status.COMPLETED.name -> "کامل"
        else -> "ناموفق: ${item.error ?: ""}"
    }

    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLong),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.outlineVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (done) AsyncImage(
                    model = Storage.file(ctx, item), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                ) else Icon(Icons.Default.Movie, null)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(item.title, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(item.quality, if (total > 0) "${fmtSize(dl)} / ${fmtSize(total)}" else fmtSize(dl), status)
                        .joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall, maxLines = 2,
                )
                if (!done) {
                    if (frac != null) LinearProgressIndicator({ frac }, Modifier.fillMaxWidth().padding(top = 4.dp))
                    else if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            when {
                running -> IconButton(onPause) { Icon(Icons.Default.Pause, "توقف") }
                !done -> IconButton(onResume) { Icon(Icons.Default.PlayArrow, "ادامه") }
                else -> IconButton(onClick) { Icon(Icons.Default.PlayArrow, "پخش") }
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "بیشتر") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("تغییر نام") }, { menu = false; onRename() },
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) })
                    if (done) {
                        DropdownMenuItem({ Text("اشتراک‌گذاری") }, { menu = false; onShare() },
                            leadingIcon = { Icon(Icons.Default.Share, null) })
                        DropdownMenuItem({ Text("ذخیره در گالری") }, { menu = false; onExport() },
                            leadingIcon = { Icon(Icons.Default.SaveAlt, null) })
                    }
                    DropdownMenuItem({ Text("حذف") }, { menu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, null) })
                }
            }
        }
    }
}
