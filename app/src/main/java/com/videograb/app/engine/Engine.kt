package com.videograb.app.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.videograb.app.data.AppDb
import com.videograb.app.data.DownloadDao
import com.videograb.app.data.DownloadItem
import com.videograb.app.data.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class Live(val downloaded: Long, val total: Long, val speed: Long)

object Storage {
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir, "VideoGrab").apply { mkdirs() }

    fun file(ctx: Context, item: DownloadItem) = File(dir(ctx), item.fileName)
}

object DownloadEngine {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private lateinit var app: Context
    private lateinit var dao: DownloadDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val gate = Semaphore(3) // حداکثر ۳ دانلود هم‌زمان

    private val _live = MutableStateFlow<Map<Long, Live>>(emptyMap())
    val live: StateFlow<Map<Long, Live>> = _live.asStateFlow()

    private val _active = MutableStateFlow(0)
    val active: StateFlow<Int> = _active.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        dao = AppDb.get(app).dao()
    }

    fun observeAll() = dao.observeAll()

    // ───────────────────────── عملیات عمومی ─────────────────────────

    suspend fun enqueue(
        url: String, title: String, kind: String, quality: String?,
        referer: String?, ua: String?, cookie: String?,
    ): Long {
        val ext = if (kind == "HLS") "ts" else Sniffer.extOf(url)
        val name = uniqueName(title, ext)
        val id = dao.insert(
            DownloadItem(
                url = url, title = title, fileName = name, kind = kind, quality = quality,
                referer = referer, userAgent = ua, cookie = cookie,
            )
        )
        start(id)
        return id
    }

    fun start(id: Long) {
        if (jobs[id]?.isActive == true) return
        val job = scope.launch {
            dao.setStatus(id, Status.QUEUED.name, null)
            gate.withPermit {
                val item = dao.get(id) ?: return@withPermit
                dao.setStatus(id, Status.DOWNLOADING.name, null)
                try {
                    if (item.kind == "HLS") downloadHls(item) else downloadHttp(item)
                    dao.setStatus(id, Status.COMPLETED.name, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isActive) throw CancellationException("paused")
                    dao.setStatus(id, Status.FAILED.name, e.message ?: "خطای ناشناخته")
                }
            }
        }
        jobs[id] = job
        _active.value = jobs.size
        job.invokeOnCompletion {
            jobs.remove(id, job)
            _live.update { it - id }
            _active.value = jobs.size
        }
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
    }

    fun pause(id: Long) {
        jobs[id]?.cancel()
        scope.launch { dao.setStatus(id, Status.PAUSED.name, null) }
    }

    fun pauseAll() = jobs.keys.toList().forEach { pause(it) }

    suspend fun resumeAllPaused(items: List<DownloadItem>) {
        items.filter { it.status == Status.PAUSED.name || it.status == Status.FAILED.name }
            .forEach { start(it.id) }
    }

    /** بعد از کشته شدن برنامه، دانلودهای نیمه‌کاره را ادامه می‌دهد. */
    fun resumeInterrupted() {
        scope.launch { dao.interrupted().forEach { start(it.id) } }
    }

    suspend fun delete(id: Long, withFile: Boolean) = withContext(Dispatchers.IO) {
        jobs[id]?.cancel()
        jobs[id]?.join()
        val item = dao.get(id)
        if (withFile && item != null) Storage.file(app, item).delete()
        dao.delete(id)
    }

    suspend fun rename(item: DownloadItem, newBase: String): Boolean = withContext(Dispatchers.IO) {
        val ext = item.fileName.substringAfterLast('.', "")
        val safe = sanitize(newBase)
        val newName = if (ext.isEmpty()) safe else "$safe.$ext"
        if (newName == item.fileName || dao.nameCount(newName) > 0) return@withContext false
        val src = Storage.file(app, item)
        if (src.exists() && !src.renameTo(File(src.parentFile, newName))) return@withContext false
        dao.setFileName(item.id, newName)
        dao.update(dao.get(item.id)!!.copy(title = safe))
        true
    }

    suspend fun exportToGallery(item: DownloadItem): Boolean = withContext(Dispatchers.IO) {
        val f = Storage.file(app, item)
        if (!f.exists()) return@withContext false
        val mime = when (f.extension.lowercase()) {
            "webm" -> "video/webm"; "ts" -> "video/mp2t"; "mkv" -> "video/x-matroska"; else -> "video/mp4"
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, item.fileName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoGrab")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext false
        resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }

    // ───────────────────────── شبکه ─────────────────────────

    private fun Request.Builder.withHeaders(url: String, ref: String?, ua: String?, cookie: String?) = apply {
        url(url)
        header("User-Agent", ua ?: DEFAULT_UA)
        ref?.let { header("Referer", it) }
        cookie?.let { header("Cookie", it) }
    }

    private suspend fun <T> withResponse(req: Request, block: suspend (Response) -> T): T {
        val call = client.newCall(req)
        val reg = currentCoroutineContext()[Job]!!.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { return block(it) }
        } finally {
            reg.dispose()
        }
    }

    suspend fun fetchText(url: String, ref: String?, ua: String?, cookie: String?): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().withHeaders(url, ref, ua, cookie).build()
            withResponse(req) {
                if (!it.isSuccessful) error("HTTP ${it.code}")
                it.body!!.string()
            }
        }

    private suspend fun fetchBytes(url: String, item: DownloadItem): ByteArray {
        val req = Request.Builder().withHeaders(url, item.referer, item.userAgent, item.cookie).build()
        return withResponse(req) {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            it.body!!.bytes()
        }
    }

    // ───────────────────────── دانلود HTTP با ادامه (Range) ─────────────────────────

    private class Meter(val id: Long, start: Long, val dao: DownloadDao, val live: MutableStateFlow<Map<Long, Live>>) {
        var t = SystemClock.elapsedRealtime()
        var b = start
        var speed = 0L
        suspend fun tick(done: Long, total: Long, seg: Int = 0, segTotal: Int = 0, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            val dt = now - t
            if (!force && dt < 500) return
            val inst = if (dt > 0) (done - b) * 1000 / dt else 0
            speed = if (speed == 0L) inst else (speed * 0.6 + inst * 0.4).toLong()
            t = now; b = done
            live.update { it + (id to Live(done, total, speed)) }
            dao.progress(id, done, total, seg, segTotal)
        }
    }

    private suspend fun downloadHttp(item: DownloadItem) {
        val file = Storage.file(app, item)
        var existing = if (file.exists()) file.length() else 0L
        val b = Request.Builder().withHeaders(item.url, item.referer, item.userAgent, item.cookie)
        if (existing > 0) b.header("Range", "bytes=$existing-")
        withResponse(b.build()) { resp ->
            if (resp.code == 416 && existing > 0) return@withResponse // قبلاً کامل شده
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val ct = resp.header("Content-Type").orEmpty()
            if (ct.startsWith("text/")) error("این لینک فایل ویدیویی نیست")
            val append = resp.code == 206
            if (!append) existing = 0
            val body = resp.body ?: error("پاسخ خالی")
            val total = if (body.contentLength() >= 0) body.contentLength() + existing else -1L
            val meter = Meter(item.id, existing, dao, _live)
            var done = existing
            FileOutputStream(file, append).use { out ->
                val buf = ByteArray(64 * 1024)
                val src = body.byteStream()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    meter.tick(done, total)
                }
            }
            meter.tick(done, if (total > 0) total else done, force = true)
        }
    }

    // ───────────────────────── دانلود HLS (m3u8) با ادامه ─────────────────────────

    private suspend fun downloadHls(item0: DownloadItem) {
        var item = item0
        var text = fetchText(item.url, item.referer, item.userAgent, item.cookie)
        var playlistUrl = item.url
        if (Hls.isMaster(text)) { // اگر هنوز master بود، بهترین کیفیت
            val best = Hls.parseMaster(playlistUrl, text).firstOrNull() ?: error("پلی‌لیست خالی است")
            playlistUrl = best.url
            text = fetchText(playlistUrl, item.referer, item.userAgent, item.cookie)
        }
        val pl = Hls.parseMedia(playlistUrl, text)
        if (pl.encrypted) error("این استریم رمزگذاری‌شده است و پشتیبانی نمی‌شود")
        if (pl.segments.isEmpty()) error("هیچ قطعه‌ای پیدا نشد")

        // fMP4 → پسوند mp4
        if (pl.hasInitMap && item.segDone == 0 && item.fileName.endsWith(".ts")) {
            val newName = item.fileName.removeSuffix(".ts") + ".mp4"
            dao.setFileName(item.id, newName)
            item = item.copy(fileName = newName)
        }
        val file = Storage.file(app, item)
        var segDone = item.segDone
        if (segDone == 0 || !file.exists()) { segDone = 0; file.delete() }
        var bytes = file.length()
        val total = pl.segments.size
        val meter = Meter(item.id, bytes, dao, _live)

        FileOutputStream(file, segDone > 0).use { out ->
            for (i in segDone until total) {
                currentCoroutineContext().ensureActive()
                val data = fetchBytes(pl.segments[i], item)
                // نوشتن قطعه و ثبت پیشرفت به‌صورت اتمیک نسبت به لغو (pause)
                withContext(NonCancellable) {
                    out.write(data); out.flush()
                    bytes += data.size
                    segDone = i + 1
                    dao.progress(item.id, bytes, bytes * total / segDone, segDone, total)
                }
                meter.tick(bytes, bytes * total / segDone, segDone, total)
            }
        }
        meter.tick(bytes, bytes, total, total, force = true)
    }

    // ───────────────────────── کمکی‌ها ─────────────────────────

    private fun sanitize(s: String) =
        s.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(60).ifBlank { "video" }

    private suspend fun uniqueName(title: String, ext: String): String {
        val base = sanitize(title)
        var name = "$base.$ext"
        var i = 1
        while (dao.nameCount(name) > 0 || File(Storage.dir(app), name).exists()) {
            name = "$base (${i++}).$ext"
        }
        return name
    }
}
