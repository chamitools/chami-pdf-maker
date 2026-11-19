package com.chami.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.chami.pdfmaker.pdf.PdfEngine
import com.chami.pdfmaker.theme.ThemeManager
import com.chami.pdfmaker.ui.DoneScreen
import com.chami.pdfmaker.ui.HistoryStore
import com.chami.pdfmaker.ui.ProtectPdfScreen
import com.chami.pdfmaker.ui.ResizeConvertScreen
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * Phase 2 regression tests for audit findings C1, H1, H4 and M25
 * (app-audit-phase1-2026-11-19.md). The follow-up pass already fixed
 * all four in the working tree, so these tests pin the fixes: each
 * one fails against the pre-fix code and passes against the fix.
 *
 * Layout: Robolectric unit tests (like EngineUnitTest /
 * PdfProtectorAesTest) — NOT androidTest. The Compose UI tests use
 * createComposeRule, which runs on the JVM under Robolectric; the
 * compose-ui-test-junit4 dependency is already in testImplementation.
 * No picker flows are driven (they cannot be, from a Compose test);
 * the screens are rendered directly with no file picked, which is
 * exactly the state the C1 bug broke.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase2RegressionUiTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val theme get() = ThemeManager(ctx).current()

  /** The slide's bounds must lie inside the root bounds — the C1 bug
   *  laid it out below the bottom edge of the screen, outside the
   *  scrollable area, where assertIsDisplayed alone would not always
   *  catch it on a tall test viewport. */
  private fun assertSlideWithinRoot(label: String) {
    val slide = composeRule.onNodeWithContentDescription(label).fetchSemanticsNode()
    val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    assertTrue(slide.boundsInRoot.top >= root.top - 1f)
    assertTrue(slide.boundsInRoot.bottom <= root.bottom + 1f)
  }

  // C1 — Protect PDF's slide was pushed off-screen (content column
  // used fillMaxSize() instead of weight(1f)), so the tool could not
  // be used at all. The slide is rendered with no file picked, so
  // this is purely a layout/reachability check, not an action check.
  @Test
  fun protect_slideToProtect_isDisplayedWithinScreen() {
    composeRule.setContent { ProtectPdfScreen(theme = theme, onBack = {}) }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Slide to Protect PDF").assertIsDisplayed()
    assertSlideWithinRoot("Slide to Protect PDF")
  }

  // C1 — same bug in the other mode: the Unlock slide is the sibling
  // after the same content column.
  @Test
  fun protect_unlockMode_slideToUnlock_isDisplayedWithinScreen() {
    composeRule.setContent { ProtectPdfScreen(theme = theme, onBack = {}) }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("UNLOCK", substring = false).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Slide to Unlock PDF").assertIsDisplayed()
    assertSlideWithinRoot("Slide to Unlock PDF")
  }

  // H1 — system Back on a single-file Done screen exited the app
  // instead of going Home (DoneScreen early-returns before
  // ToolScreenContainer, so it never got that container's
  // BackHandler). pageCount is passed explicitly so the screen never
  // touches PdfRenderer (absent under Robolectric).
  @Test
  fun doneScreen_systemBack_callsOnBackHome_andDoesNotFinishActivity() {
    val file = File(ctx.cacheDir, "done-test.pdf").apply { writeText("%PDF-1.4\n") }
    var backHomeCalls = 0
    composeRule.setContent {
      DoneScreen(
        file = file,
        theme = theme,
        onBackHome = { backHomeCalls++ },
        onOpen = {},
        pageCount = 1
      )
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("done-test.pdf").assertIsDisplayed()
    composeRule.activity.onBackPressedDispatcher.onBackPressed()
    composeRule.waitForIdle()
    assertEquals(1, backHomeCalls)
    assertFalse(composeRule.activity.isFinishing)
    // The screen is still showing — Back went Home (the test's
    // onBackHome is a no-op flag), it did not close the activity.
    composeRule.onNodeWithText("done-test.pdf").assertIsDisplayed()
  }

  // H4 (screen half) — typing 10000 into the width field must be
  // rejected by the dimsValid guard: the range error shows and the
  // slide is disabled, instead of the engine being handed a
  // 10000x10000 resize that OOM-crashes. The two dimension fields
  // both start at "800"; the width field is the first editable node
  // in the tree. DebouncedTextField only commits 300ms after the
  // last keystroke, so the test clock is advanced past the debounce.
  @Test
  fun resizer_typingWidth10000_showsRangeErrorAndDisablesSlide() {
    composeRule.setContent { ResizeConvertScreen(theme = theme, onBack = {}) }
    composeRule.waitForIdle()
    val widthField = composeRule.onAllNodes(hasSetTextAction())[0]
    widthField.performScrollTo()
    widthField.performTextClearance()
    widthField.performTextInput("10000")
    composeRule.mainClock.advanceTimeBy(500)
    composeRule.waitForIdle()
    composeRule.onNodeWithText(
      "Enter a width and height between 1 and 4096 px (max 16 million pixels total)"
    ).assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Slide to Resize").assertIsNotEnabled()
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase2RegressionEngineTest {

  private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun testJpegFile(name: String): File {
    val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawColor(Color.RED)
    val f = File(ctx.cacheDir, name)
    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    bmp.recycle()
    return f
  }

  private fun resize(src: File, w: Int, h: Int): Boolean {
    val out = File(ctx.cacheDir, "h4-out-$w-$h.jpg")
    return PdfEngine.resizeConvert(ctx, Uri.fromFile(src), w, h, Bitmap.CompressFormat.JPEG, out)
  }

  // H4 (engine half) — resizeConvert rejects dimensions outside
  // 1..4096 px per side. The guard returns before the source is
  // even loaded, so a typo can never reach createScaledBitmap.
  @Test
  fun resizeConvert_rejectsSideOver4096() {
    val src = testJpegFile("h4-src.jpg")
    assertFalse(resize(src, 10000, 10000))
    assertFalse(resize(src, 4097, 100))
    assertFalse(resize(src, 100, 4097))
  }

  // H4 (engine half) — the total-pixel cap: 4000x4001 is 16,004,000
  // px, just over the 16,000,000 limit; 4096x4096 is 16,777,216 px.
  @Test
  fun resizeConvert_rejectsTotalPixelsOver16M() {
    val src = testJpegFile("h4-src.jpg")
    assertFalse(resize(src, 4000, 4001))
    assertFalse(resize(src, 4096, 4096))
  }

  // H4 (engine half) — zero / negative dimensions are rejected, not
  // passed to Bitmap.createScaledBitmap.
  @Test
  fun resizeConvert_rejectsZeroOrNegativeDimensions() {
    val src = testJpegFile("h4-src.jpg")
    assertFalse(resize(src, 0, 100))
    assertFalse(resize(src, 100, 0))
    assertFalse(resize(src, -5, 100))
  }

  // H4 (engine half) — the guard is a cap, not a block: in-range
  // dimensions still resize, including exactly 16,000,000 px
  // (4000x4000 — the cap rejects only *over* 16M).
  @Test
  fun resizeConvert_acceptsValidDimensions() {
    val src = testJpegFile("h4-src.jpg")
    val out = File(ctx.cacheDir, "h4-out-valid.jpg")
    assertTrue(
      PdfEngine.resizeConvert(ctx, Uri.fromFile(src), 200, 150, Bitmap.CompressFormat.JPEG, out)
    )
    assertTrue(out.exists())
    assertTrue(out.length() > 0)
  }

  @Test
  fun resizeConvert_acceptsExactly16MillionPixels() {
    val src = testJpegFile("h4-src.jpg")
    assertTrue(resize(src, 4000, 4000))
  }

  // M25 — HistoryStore eviction is oldest-first. The store file is
  // seeded directly (history_tools.json in filesDir) because only
  // seeded values carry known, distinct insertion timestamps:
  // record() stamps System.currentTimeMillis(), and 500+ rapid
  // records can share one millisecond, so record()-only ordering
  // cannot be asserted deterministically. Seeding exercises the same
  // writeAll() eviction path record() goes through.
  private val storeFile get() = File(ctx.filesDir, "history_tools.json")

  @Before
  fun clearHistoryStore() {
    storeFile.delete()
  }

  @After
  fun clearHistoryStoreAfter() {
    storeFile.delete()
  }

  @Test
  fun historyStore_evictsOldestTimestampedEntryFirst() {
    val seed = JSONObject()
    for (i in 0 until 500) seed.put("hist_%04d.pdf".format(i), "merge|${1000 + i}")
    storeFile.writeText(seed.toString())

    HistoryStore.record(ctx, "hist_new.pdf", "protect")

    assertEquals(500, HistoryStore.toolMap(ctx).size)
    // Oldest seeded entry (ts 1000) is the one evicted.
    assertNull(HistoryStore.toolFor(ctx, "hist_0000.pdf"))
    // The next-oldest and the newest seeded entries survive, and
    // toolFor still resolves them.
    assertEquals("merge", HistoryStore.toolFor(ctx, "hist_0001.pdf"))
    assertEquals("merge", HistoryStore.toolFor(ctx, "hist_0499.pdf"))
    assertEquals("protect", HistoryStore.toolFor(ctx, "hist_new.pdf"))
  }

  @Test
  fun historyStore_legacyEntriesEvictedBeforeTimestampedOnes() {
    // Legacy values are plain "toolId" with no timestamp — they count
    // as oldest (timestamp 0) and must go before any timestamped
    // entry. Which individual legacy rows go is unspecified (they all
    // tie at 0), so the test asserts counts, not identities.
    val seed = JSONObject()
    for (i in 0 until 500) seed.put("legacy_%04d.pdf".format(i), "merge")
    storeFile.writeText(seed.toString())

    for (i in 0 until 5) HistoryStore.record(ctx, "new_%d.pdf".format(i), "split")

    val map = HistoryStore.toolMap(ctx)
    assertEquals(500, map.size)
    for (i in 0 until 5) assertEquals("split", HistoryStore.toolFor(ctx, "new_%d.pdf".format(i)))
    assertEquals(495, map.keys.count { it.startsWith("legacy_") })
    // A surviving legacy entry still resolves to its tool.
    val survivor = map.keys.first { it.startsWith("legacy_") }
    assertEquals("merge", HistoryStore.toolFor(ctx, survivor))
  }

  // M25 — the same cap through the public API: recording more than
  // 500 entries via record() keeps the store at exactly 500, and
  // every surviving entry still resolves through toolFor.
  @Test
  fun historyStore_recordBeyondCap_keepsExactly500Entries() {
    for (i in 0 until 505) HistoryStore.record(ctx, "rec_%04d.pdf".format(i), "merge")
    val map = HistoryStore.toolMap(ctx)
    assertEquals(500, map.size)
    assertTrue(map.values.all { it == "merge" })
    val sample = map.keys.first()
    assertEquals("merge", HistoryStore.toolFor(ctx, sample))
  }
}
