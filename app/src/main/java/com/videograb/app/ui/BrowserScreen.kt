package com.videograb.app.ui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.videograb.app.engine.DownloadEngine
import com.videograb.app.engine.Hls
import com.videograb.app.engine.Media
import com.videograb.app.engine.Sniffer
import com.videograb.app.engine.Variant
import kotlinx.coroutines.launch
import org.json.JSONArray

/** وضعیت مرورگر؛ بیرون از Composable نگه داشته می‌شود تا با عوض شدن تب از بین نرود. */
class BrowserState(ctx: Context) {
    val detected: SnapshotStateList<Media> = mutableStateListOf()
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    private val main = Handler(Looper.getMainLooper())
    val web = WebView(ctx)

    init {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        web.addJavascriptInterface(Bridge(), "VG")
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
        }
        web.webViewClient = object : WebViewClient() {
            // جلوگیری از پرش به اپ اینستاگرام (intent:// و instagram://)
            override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme
                return s != "http" && s != "https"
            }

            override fun shouldInterceptRequest(view: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = Sniffer.clean(r.url.toString())
                Sniffer.classify(u)?.let { k -> main.post { add(Media(u, Sniffer.labelOf(u), k, null)) } }
                return null
            }

            override fun onPageStarted(view: WebView, u: String, favicon: android.graphics.Bitmap?) {
                url = u
                detected.clear()
            }

            override fun onPageFinished(view: WebView, u: String) {
                url = u
                view.evaluateJavascript(Sniffer.JS, null)
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) { url = u }
        }
    }

    fun add(m: Media) {
        if (detected.none { it.url == m.url }) detected.add(m)
    }

    fun load(raw: String) {
        var u = raw.trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http")) {
            u = if (u.contains('.') && !u.contains(' ')) "https://$u"
            else "https://www.google.com/search?q=" + Uri.encode(u)
        }
        url = u
        web.loadUrl(u)
        Sniffer.classify(u)?.let { add(Media(u, Sniffer.labelOf(u), it, null)) }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onMedia(json: String) {
            try {
                val arr = JSONArray(json)
                val found = (0 until arr.length()).map { arr.getJSONObject(it) }
                main.post {
                    found.forEach { o ->
                        val u = Sniffer.clean(o.optString("u"))
                        val kind = Sniffer.classify(u) ?: if (u.startsWith("http")) "HTTP" else return@forEach
                        add(Media(u, Sniffer.labelOf(u), kind, o.optString("q").ifBlank { null }))
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(state: BrowserState, snackbar: SnackbarHostState, onToggleBubble: () -> Unit) {
    val scope = rememberCoroutineScope()
    var addr by remember(state.url) { mutableStateOf(state.url) }
    var showSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var variants by remember { mutableStateOf<List<Variant>?>(null) }
    var pending by remember { mutableStateOf<Media?>(null) }

    BackHandler(enabled = state.web.canGoBack()) { state.web.goBack() }

    fun enqueue(m: Media, url: String = m.url, quality: String? = m.quality) {
        val cm = CookieManager.getInstance()
        val ref = state.url
        val ua = state.web.settings.userAgentString
        val cookie = cm.getCookie(url) ?: cm.getCookie(ref)
        val title = state.title.ifBlank { m.label }
        scope.launch {
            DownloadEngine.enqueue(url, title, m.kind, quality, ref, ua, cookie)
            snackbar.showSnackbar("به صف دانلود اضافه شد")
        }
    }

    fun pick(m: Media) {
        if (m.kind != "HLS") return enqueue(m)
        scope.launch {
            val cm = CookieManager.getInstance()
            val text = runCatching {
                DownloadEngine.fetchText(m.url, state.url, state.web.settings.userAgentString, cm.getCookie(m.url))
            }.getOrNull()
            if (text != null && Hls.isMaster(text)) {
                pending = m
                variants = Hls.parseMaster(m.url, text)
            } else enqueue(m)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = addr, onValueChange = { addr = it },
                modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("آدرس سایت یا جست‌وجو") },
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { state.load(addr) }),
            )
            IconButton({ state.load(addr) }) { Icon(Icons.Default.Search, "برو") }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "منو") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("نمایش حباب شناور") }, onClick = { menu = false; onToggleBubble() })
                    DropdownMenuItem(text = { Text("اینستاگرام") }, onClick = { menu = false; state.load("https://www.instagram.com/") })
                    DropdownMenuItem(text = { Text("بارگذاری مجدد") }, onClick = { menu = false; state.web.reload() })
                }
            }
        }
        if (state.progress < 100) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = {
                    if (state.web.url == null) state.load("https://www.instagram.com/")
                    state.web
                },
                modifier = Modifier.fillMaxSize(),
            )
            FloatingActionButton(
                onClick = { showSheet = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                BadgedBox(badge = { if (state.detected.isNotEmpty()) Badge { Text("${state.detected.size}") } }) {
                    Icon(Icons.Default.Download, "دانلود")
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ویدیوهای پیدا‌شده (${state.detected.size})", style = LocalTextStyle.current)
                TextButton({ state.detected.clear() }) { Text("پاک‌سازی") }
            }
            if (state.detected.isEmpty()) {
                Text(
                    "هنوز ویدیویی تشخیص داده نشد. ویدیو را در صفحه پخش کنید یا کمی صبر کنید.",
                    Modifier.padding(16.dp),
                )
            }
            LazyColumn {
                items(state.detected.toList()) { m ->
                    ListItem(
                        headlineContent = { Text(m.label, maxLines = 1) },
                        supportingContent = {
                            Text(
                                (if (m.kind == "HLS") "استریم HLS" else "فایل مستقیم") +
                                    (m.quality?.let { " • $it" } ?: " • کیفیت نامشخص")
                            )
                        },
                        trailingContent = { Icon(Icons.Default.Download, null) },
                        modifier = Modifier.clickable { showSheet = false; pick(m) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    variants?.let { list ->
        AlertDialog(
            onDismissRequest = { variants = null },
            title = { Text("انتخاب کیفیت") },
            text = {
                Column {
                    list.forEach { v ->
                        TextButton(
                            onClick = { pending?.let { enqueue(it, v.url, v.label) }; variants = null },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(v.label) }
                    }
                }
            },
            confirmButton = { TextButton({ variants = null }) { Text("لغو") } },
        )
    }
}
