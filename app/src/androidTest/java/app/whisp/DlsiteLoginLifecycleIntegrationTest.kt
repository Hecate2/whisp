// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DlsiteLoginLifecycleIntegrationTest {
    @Test
    fun rotationPreservesOfficialBrowserSessionAndLeavingClearsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".smoketest"))
        val cookies = CookieManager.getInstance()
        val origin = "https://login.dlsite.com/"
        ActivityScenario.launch<DlsiteLoginActivity>(
                Intent(context, DlsiteLoginActivity::class.java),
            )
            .use { scenario ->
                waitUntil {
                    var ready = false
                    scenario.onActivity { ready = browser(it.window.decorView) != null }
                    ready
                }
                scenario.onActivity {
                    browser(it.window.decorView)!!.apply {
                        stopLoading()
                        loadUrl("about:blank")
                    }
                }
                waitUntil {
                    var blank = false
                    scenario.onActivity {
                        blank = browser(it.window.decorView)?.url == "about:blank"
                    }
                    blank
                }
                val installed = CountDownLatch(1)
                scenario.onActivity {
                    cookies.setCookie(origin, "whisp_rotation_probe=synthetic; Path=/; Secure") {
                        installed.countDown()
                    }
                }
                assertTrue(installed.await(5, TimeUnit.SECONDS))
                scenario.recreate()
                waitUntil {
                    var restored = false
                    scenario.onActivity {
                        restored = browser(it.window.decorView)?.url == "about:blank"
                    }
                    restored
                }
                scenario.onActivity {
                    assertTrue(cookies.getCookie(origin).orEmpty().contains("whisp_rotation_probe"))
                }
            }
        waitUntil { !cookies.getCookie(origin).orEmpty().contains("whisp_rotation_probe") }
    }

    private fun browser(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup)
            for (index in 0 until view.childCount) browser(view.getChildAt(index))?.let {
                return it
            }
        return null
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            assertTrue(
                "Browser lifecycle operation timed out",
                SystemClock.uptimeMillis() < deadline,
            )
            SystemClock.sleep(50)
        }
    }
}
