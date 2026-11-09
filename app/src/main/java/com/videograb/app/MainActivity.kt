package com.videograb.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.videograb.app.engine.DownloadEngine
import com.videograb.app.overlay.OverlayService
import com.videograb.app.ui.BrowserScreen
import com.videograb.app.ui.BrowserState
import com.videograb.app.ui.DownloadsScreen

class MainActivity : ComponentActivity() {
    private val pendingUrl = mutableStateOf<String?>(null)
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        DownloadEngine.resumeInterrupted()
        if (savedInstanceState == null) handle(intent)
        setContent { MaterialTheme { AppUi(pendingUrl) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** لینک از Share اینستاگرام یا از حباب شناور. */
    private fun handle(i: Intent?) {
        val text = i?.getStringExtra("url")
            ?: if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT) else null
        Regex("https?://\\S+").find(text.orEmpty())?.value?.let { pendingUrl.value = it }
    }
}

@Composable
fun AppUi(pendingUrl: MutableState<String?>) {
    val ctx = LocalContext.current
    val browser = remember { BrowserState(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(pendingUrl.value) {
        pendingUrl.value?.let { tab = 0; browser.load(it); pendingUrl.value = null }
    }

    val toggleBubble = {
        if (!Settings.canDrawOverlays(ctx)) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            )
        } else {
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Language, null) }, label = { Text("مرورگر") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Folder, null) }, label = { Text("دانلودها") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            if (tab == 0) BrowserScreen(browser, snack, toggleBubble) else DownloadsScreen(snack)
        }
    }
}
