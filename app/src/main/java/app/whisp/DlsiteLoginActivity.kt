// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.whisp.data.DlsiteLoginRequired
import app.whisp.data.DlsiteSession
import app.whisp.ui.WhispTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Official pages own the login form; the app receives only the completed session. */
class DlsiteLoginActivity : ComponentActivity() {
    companion object {
        const val SOURCE = "dlsite-source"
    }

    private var browser: WebView? = null
    private var checking by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var socialLogin by mutableStateOf(false)
    private var closing = false
    private var ready by mutableStateOf(false)

    private fun verify(showFailure: Boolean = false) {
        if (checking || closing) return
        val manager = CookieManager.getInstance()
        val snapshot =
            DlsiteSession.fromBrowser(
                DlsiteSession.origins.associateWith { manager.getCookie(it).orEmpty() },
            )
        checking = true
        lifecycleScope.launch {
            try {
                val id =
                    (application as WhispApp)
                        .repository
                        .acceptDlsiteLogin(snapshot, intent.getStringExtra(SOURCE))
                closing = true
                setResult(RESULT_OK, Intent().putExtra(SOURCE, id))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: DlsiteLoginRequired) {
                if (showFailure) error = "请先完成官方账号登录"
            } catch (_: Exception) {
                error = "登录验证失败，请重试"
            } finally {
                checking = false
            }
        }
    }

    // Official account pages require JavaScript; file access and app bridges are disabled.
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        CookieManager.getInstance().removeAllCookies {
            if (!isFinishing) ready = true
        }
        setContent {
            WhispTheme {
                Scaffold(
                    topBar = {
                        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp)) {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                            }
                            Column(Modifier.weight(1f).padding(8.dp)) {
                                Text("DLsite 登录", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "login.dlsite.com",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            TextButton(enabled = !checking, onClick = { verify(true) }) {
                                Text("完成")
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error?.let {
                            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(it, Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        error = null
                                        verify(true)
                                    },
                                ) {
                                    Text("重试")
                                }
                            }
                        }
                        if (ready)
                            AndroidView(
                                modifier = Modifier.weight(1f),
                                factory = { context ->
                                    WebView(context).apply {
                                        browser = this
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        settings.mixedContentMode =
                                            WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                        CookieManager.getInstance()
                                            .setAcceptThirdPartyCookies(this, false)
                                        webViewClient =
                                            object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(
                                                    view: WebView,
                                                    request: WebResourceRequest,
                                                ): Boolean {
                                                    val uri = request.url
                                                    if (
                                                        uri.scheme == "https" &&
                                                            (uri.host == "dlsite.com" ||
                                                                uri.host
                                                                    .orEmpty()
                                                                    .endsWith(".dlsite.com"))
                                                    )
                                                        return false
                                                    if (request.isForMainFrame) socialLogin = true
                                                    return true
                                                }

                                                override fun onPageFinished(
                                                    view: WebView,
                                                    url: String,
                                                ) {
                                                    if (url.toUri().host == "play.dlsite.com")
                                                        verify()
                                                }
                                            }
                                        loadUrl("https://play.dlsite.com/login/?to=%2Flibrary")
                                    }
                                },
                            )
                    }
                }
                BackHandler { if (browser?.canGoBack() == true) browser?.goBack() else finish() }
                if (socialLogin)
                    AlertDialog(
                        onDismissRequest = { socialLogin = false },
                        title = { Text("使用 viviON ID 登录") },
                        text = { Text("在 DLsite 账号管理中设置登录 ID 和密码，然后返回登录。") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    socialLogin = false
                                    runCatching {
                                        startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                "https://login.dlsite.com/".toUri(),
                                            ),
                                        )
                                    }
                                },
                            ) {
                                Text("账号管理")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { socialLogin = false }) { Text("返回") }
                        },
                    )
            }
        }
    }

    override fun onDestroy() {
        closing = true
        browser?.apply {
            stopLoading()
            clearHistory()
            clearCache(true)
            destroy()
        }
        browser = null
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        WebStorage.getInstance().deleteAllData()
        super.onDestroy()
    }
}
