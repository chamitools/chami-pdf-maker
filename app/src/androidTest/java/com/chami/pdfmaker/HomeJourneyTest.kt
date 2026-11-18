package com.chami.pdfmaker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.fetchSemanticsNodes
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Human-user test pass, 2026-11-18 (Run #151, HEAD 9e87166).
 * Tap-path journey on a real emulator: Home -> every tool screen ->
 * back to Home, plus the micro-detail checks that are observable from
 * semantics (group order, no Scan tile, CHAMI PRO once, empty states,
 * disabled slide reason). Tiles are tapped with performTouchInput
 * because the tile Column (not its Text child) owns the click action.
 * Picker flows (gallery / file picker) cannot be driven from a
 * Compose test — the engine behind them is covered by
 * EngineRoundTripTest instead.
 */
@RunWith(AndroidJUnit4::class)
class HomeJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Before
  fun clearHistoryFolder() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    File(ctx.getExternalFilesDir(null), "Chami").listFiles()?.forEach { it.delete() }
  }

  private fun tapTile(text: String) {
    composeRule.onNodeWithText(text).performScrollTo()
    composeRule.onNodeWithText(text).performTouchInput { click() }
    composeRule.waitForIdle()
  }

  private fun goBack() {
    composeRule.onNodeWithContentDescription("Back").performClick()
    composeRule.waitForIdle()
  }

  @Test
  fun home_imagesToPdfIsFirstAndGroupsAreOrdered() {
    composeRule.onNodeWithText("Images to PDF").assertIsDisplayed()
    composeRule.onNodeWithText("Images & Reading").assertIsDisplayed()
    // Group order, top to bottom: Images & Reading, Fix a PDF, Convert.
    val readingTop = composeRule.onNodeWithText("Images & Reading")
      .fetchSemanticsNode().boundsInRoot.top
    val fixTop = composeRule.onNodeWithText("Fix a PDF")
      .fetchSemanticsNode().boundsInRoot.top
    val convertTop = composeRule.onNodeWithText("Convert")
      .fetchSemanticsNode().boundsInRoot.top
    assertTrue(readingTop < fixTop)
    assertTrue(fixTop < convertTop)
    // All 10 tools present, no Scan tile.
    listOf(
      "Images to PDF", "Image Resizer", "PDF Reader",
      "Merge PDF", "Split PDF", "Protect PDF", "Compress KB",
      "PDF to Images", "Format Convert", "OCR Text"
    ).forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
    composeRule.onNodeWithText("Scan to PDF").assertDoesNotExist()
  }

  @Test
  fun everyToolOpensAndBackReturnsHome() {
    // (tile text, text expected on the tool screen)
    val tools = listOf(
      "Images to PDF" to "Select photos from gallery",
      "Merge PDF" to "Choose PDFs (0 selected)",
      "Split PDF" to "Select PDF",
      "Protect PDF" to "Choose PDF file",
      "Compress KB" to "Compress image",
      "PDF to Images" to "Select PDF",
      "Image Resizer" to "Select image",
      "Format Convert" to "Select images",
      "OCR Text" to "Select photo",
      "PDF Reader" to "Open PDF file"
    )
    for ((tile, expected) in tools) {
      tapTile(tile)
      composeRule.onNodeWithText(expected).assertIsDisplayed()
      goBack()
      composeRule.onNodeWithText("Images to PDF").assertIsDisplayed()
    }
  }

  @Test
  fun imagesToPdf_emptyStateShowsDisabledSlideReason() {
    tapTile("Images to PDF")
    composeRule.onNodeWithText("Choose images").assertIsDisplayed()
    composeRule.onNodeWithText("Slide to Convert").assertIsDisplayed()
    composeRule.onNodeWithText("Not ready yet — finish the steps above first.").assertIsDisplayed()
    goBack()
  }

  @Test
  fun ocrScreen_showsNoLanguagePicker() {
    tapTile("OCR Text")
    composeRule.onNodeWithText("Select photo").assertIsDisplayed()
    composeRule.onNodeWithText("Slide to Extract Text").assertIsDisplayed()
    // The hint text names the supported scripts in prose; there must be
    // no selectable language option. A chip would be a clickable node
    // with the exact single-word text "English" / "Hindi" / "Spanish"
    // — the prose hint always contains them inside a longer sentence,
    // so exact-match nodes must not exist.
    composeRule.onNodeWithText("English", substring = false).assertDoesNotExist()
    composeRule.onNodeWithText("Hindi", substring = false).assertDoesNotExist()
    composeRule.onNodeWithText("Spanish", substring = false).assertDoesNotExist()
    goBack()
  }

  @Test
  fun compressScreen_showsTabsAndPresets() {
    tapTile("Compress KB")
    composeRule.onNodeWithText("Compress image").assertIsDisplayed()
    composeRule.onNodeWithText("Compress PDF").assertIsDisplayed()
    composeRule.onNodeWithText("Under 100 KB").assertIsDisplayed()
    composeRule.onNodeWithText("Under 200 KB").assertIsDisplayed()
    goBack()
  }

  @Test
  fun resizerScreen_showsPresetsIncluding1080x1350() {
    tapTile("Image Resizer")
    composeRule.onNodeWithText("Presets").assertIsDisplayed()
    composeRule.onNodeWithText("Instagram post").assertIsDisplayed()
    composeRule.onNodeWithText("Lock aspect ratio").assertIsDisplayed()
    goBack()
  }

  @Test
  fun settings_chamiProAppearsExactlyOnce() {
    composeRule.onNodeWithContentDescription("Settings").performClick()
    composeRule.waitForIdle()
    assertEquals(
      1,
      composeRule.onAllNodesWithText("CHAMI", substring = false).fetchSemanticsNodes().size
    )
    assertEquals(
      1,
      composeRule.onAllNodesWithText("PRO", substring = false).fetchSemanticsNodes().size
    )
    composeRule.onNodeWithText("Professional PDF Toolkit").assertIsDisplayed()
  }

  @Test
  fun history_emptyStateIsClear() {
    composeRule.onNodeWithContentDescription("History").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("No Documents Found").assertIsDisplayed()
    composeRule.onNodeWithText("0 documents saved").assertIsDisplayed()
  }

  @Test
  fun reader_emptyStateIsClear() {
    tapTile("PDF Reader")
    composeRule.onNodeWithText("Open PDF file").assertIsDisplayed()
    goBack()
  }
}
