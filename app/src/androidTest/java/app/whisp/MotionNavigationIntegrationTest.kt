// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.whisp.data.WorkEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MotionNavigationIntegrationTest {
    @get:Rule val permission = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun libraryScrollSurvivesCoverNavigationAndReducedMotion() {
        val repo = (ui.activity.application as WhispApp).repository
        assertTrue(repo.context.packageName.endsWith(".smoketest"))
        fun foreground() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ui.activity.getSystemService(android.app.ActivityManager::class.java).appTasks
                    .first { it.taskInfo.taskId == ui.activity.taskId }.moveToFront()
            }
            assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .wait(Until.hasObject(By.pkg(repo.context.packageName)), 5000))
        }
        foreground()
        runBlocking {
            repo.dao.putWorks((1..24).map {
                WorkEntity("motion-$it", null, "Motion %02d".format(it), "Motion %02d".format(it),
                    addedAt = it.toLong(), offlineAttachments = true)
            })
        }
        ui.waitUntil(15000) { repo.library.value.size == 24 }
        ui.onNodeWithTag("library-grid").performScrollToNode(hasContentDescription("Motion 06"))
        val original = ui.onNodeWithContentDescription("Motion 06").fetchSemanticsNode().boundsInRoot
        val gridBefore = ui.onNodeWithTag("library-grid").fetchSemanticsNode()
        val gridBoundsBefore = gridBefore.boundsInRoot
        val scrollBefore = gridBefore.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        ui.onNodeWithContentDescription("Motion 06").performClick()
        ui.onNodeWithText("播放作品").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回作品库").performClick()
        assertEquals(original, ui.onNodeWithContentDescription("Motion 06").fetchSemanticsNode().boundsInRoot)

        // A back action during the transition must leave the original library in place.
        ui.onNodeWithContentDescription("Motion 06").performClick()
        foreground()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("Details must register a system back callback", ui.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        ui.onNodeWithTag("library-grid").assertIsDisplayed()
        assertEquals(original, ui.onNodeWithContentDescription("Motion 06").fetchSemanticsNode().boundsInRoot)

        runBlocking { repo.setSettings(reduceMotion = true) }
        ui.waitUntil(5000) { repo.settings.value.reduceMotion }
        ui.onNodeWithContentDescription("Motion 06").performClick()
        ui.onNodeWithContentDescription("返回作品库").performClick()
        assertEquals(original, ui.onNodeWithContentDescription("Motion 06").fetchSemanticsNode().boundsInRoot)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ui.activity.recreate() }
        foreground()
        ui.onNodeWithTag("library-grid").assertIsDisplayed()
        val gridAfter = ui.onNodeWithTag("library-grid").fetchSemanticsNode()
        assertEquals(scrollBefore,
            gridAfter.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value(), 0f)
        // System insets can change while recreating a window. Verify the saved
        // scroll offset and the card's position inside its grid, not screen chrome.
        val restored = ui.onNodeWithContentDescription("Motion 06").fetchSemanticsNode().boundsInRoot
        assertEquals(original.translate(-gridBoundsBefore.left, -gridBoundsBefore.top),
            restored.translate(-gridAfter.boundsInRoot.left, -gridAfter.boundsInRoot.top))
        assertTrue(repo.settings.value.reduceMotion)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(repo.context.getExternalFilesDir(null), "motion-library.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
