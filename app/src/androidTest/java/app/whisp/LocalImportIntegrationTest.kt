// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.*
import app.whisp.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalImportIntegrationTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun importsThroughSystemDirectoryPickerAndPersistsReadGrant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("mkdir -p /sdcard/Download/WhispFixture")
        device.executeShellCommand(
            "cp -r /data/local/tmp/whisp-fixture/. /sdcard/Download/WhispFixture/",
        )
        ui.onNodeWithText("来源", useUnmergedTree = true).performClick()
        ui.onNodeWithContentDescription("添加来源").performClick()
        ui.onNodeWithText("本地目录").performClick()
        // Android's tree picker opens at the storage root; Download itself is
        // restricted, but a user-selected subdirectory remains grantable.
        device.wait(Until.findObject(By.text("Download")), 10000)?.click()
            ?: error("Download folder not shown in directory picker")
        device.wait(Until.findObject(By.text("WhispFixture")), 10000)?.click()
            ?: error("Fixture folder not shown")
        device.wait(Until.findObject(By.text("USE THIS FOLDER")), 10000)?.click()
            ?: error("Directory permission button not shown")
        device.wait(Until.findObject(By.text("ALLOW")), 10000)?.click()
            ?: error("Directory confirmation not shown")
        val repo = (ui.activity.application as WhispApp).repository
        ui.waitUntil(30000) {
            repo.sources.value.any { it.kind == "local" && it.lastScan > 0 } &&
                repo.busy.value.isEmpty()
        }
        val source = repo.sources.value.first { it.kind == "local" }
        assertNull(source.error)
        assertTrue(
            repo.context.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == source.root && it.isReadPermission
            },
        )
        runBlocking {
            val work = repo.library.value.first { it.work.rj == "RJ00000001" }
            val local =
                repo.dao.locations(work.tracks.single().id).first { it.sourceId == source.id }
            val bytes =
                repo.context.contentResolver
                    .openInputStream(android.net.Uri.parse(local.uri))!!
                    .use { stream ->
                        ByteArray(10).also { assertTrue(stream.read(it) > 0) }
                    }
            assertEquals(10, bytes.size)
            assertEquals(1, repo.library.value.count { it.work.rj == "RJ00000001" })
        }
    }
}
