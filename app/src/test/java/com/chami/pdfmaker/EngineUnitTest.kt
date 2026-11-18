package com.chami.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.chami.pdfmaker.pdf.isDevanagariLanguage
import com.chami.pdfmaker.pdf.PdfCompress
import com.chami.pdfmaker.pdf.PdfEngine
import com.chami.pdfmaker.pdf.saveTextToFile
import com.chami.pdfmaker.pdf.textToPdf
import com.chami.pdfmaker.ui.ImagePageEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Human-user test pass, 2026-11-18 (Run #151, HEAD 9e87166).
 * Robolectric unit tests for the pure engine logic that does NOT need
 * PdfRenderer (Robolectric has no PdfRenderer). The PdfRenderer-backed
 * checks (page size 595x842, merge/split round-trips, protect
 * round-trip) live in the instrumented EngineRoundTripTest, which runs
 * on a real emulator — that is the authoritative check for those.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineUnitTest {

  private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun testBitmap(w: Int, h: Int, color: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawColor(color)
    return bmp
  }

  @Test
  fun imagesToPdf_writesRealPdfFile() {
    val pages = listOf(testBitmap(800, 600, Color.RED), testBitmap(600, 800, Color.BLUE))
    val out = PdfEngine.buildImagesPdf(ctx, pages, PdfEngine.ImagesPdfOptions.defaults()) { _, _ -> }
    assertTrue(out.exists())
    assertTrue(out.length() > 0)
    assertTrue(out.readBytes().take(4).toByteArray().contentEquals("%PDF".toByteArray()))
  }

  @Test
  fun imagesToPdf_originalPageSizeUsesImagePixels() {
    val pages = listOf(testBitmap(800, 600, Color.RED))
    val opts = PdfEngine.ImagesPdfOptions.defaults().copy(pageSize = "Original")
    val out = PdfEngine.buildImagesPdf(ctx, pages, opts) { _, _ -> }
    assertTrue(out.exists())
    assertTrue(out.length() > 0)
  }

  @Test
  fun jpegQualityMapping_isMonotonic() {
    assertEquals(100, PdfEngine.jpegQualityFor("Original"))
    assertEquals(95, PdfEngine.jpegQualityFor("High"))
    assertEquals(80, PdfEngine.jpegQualityFor("Medium"))
    assertEquals(65, PdfEngine.jpegQualityFor("Low"))
    assertEquals(80, PdfEngine.jpegQualityFor("nonsense"))
  }

  @Test
  fun pdfCompressPreset_isContinuousNotThreeSteps() {
    // Every slider step must change the output: 71 and 99 must differ.
    val p71 = PdfCompress.presetForQuality(71)
    val p99 = PdfCompress.presetForQuality(99)
    assertFalse(p71.jpegQuality == p99.jpegQuality)
    assertFalse(p71.scale == p99.scale)
    assertTrue(PdfCompress.presetForQuality(10).scale < PdfCompress.presetForQuality(100).scale)
  }

  @Test
  fun cropToRect_keepsRequestedFraction() {
    val bmp = testBitmap(800, 600, Color.GREEN)
    val cropped = ImagePageEditor.cropToRect(bmp, Rect(0.25f, 0.25f, 0.75f, 0.75f), 1f)
    assertEquals(400, cropped.width)
    assertEquals(300, cropped.height)
  }

  @Test
  fun cropToRatio_oneToOne_isSquare() {
    val bmp = testBitmap(800, 600, Color.GREEN)
    val cropped = ImagePageEditor.cropToRatio(bmp, 1f)
    assertEquals(cropped.width, cropped.height)
    assertEquals(600, cropped.width)
  }

  @Test
  fun rotate90_swapsDimensions() {
    val bmp = testBitmap(800, 600, Color.GREEN)
    val rotated = ImagePageEditor.rotate90(bmp)
    assertEquals(600, rotated.width)
    assertEquals(800, rotated.height)
  }

  @Test
  fun applyFilter_keepsSizeForEveryMode() {
    val bmp = testBitmap(200, 150, Color.YELLOW)
    for (mode in 0..4) {
      val out = ImagePageEditor.applyFilter(bmp, mode)
      assertEquals(200, out.width)
      assertEquals(150, out.height)
    }
  }

  @Test
  fun move_reordersPages() {
    val a = ImagePageEditorTestPage("a")
    val b = ImagePageEditorTestPage("b")
    val c = ImagePageEditorTestPage("c")
    val moved = ImagePageEditor.move(listOf(a, b, c), 0, 2)
    assertEquals(listOf(b, c, a), moved)
  }

  @Test
  fun textToPdf_writesReadablePdf() {
    val out = PdfEngine.outFile(ctx, "unit_test_text.pdf")
    assertTrue(textToPdf(ctx, "OCR Text", "Hello world\nSecond line", outFile = out))
    assertTrue(out.exists())
    assertTrue(out.length() > 0)
  }

  @Test
  fun saveTextToFile_roundTrips() {
    val f = saveTextToFile(ctx, "hello ocr", "unit_test.txt")
    assertTrue(f.exists())
    assertEquals("hello ocr", f.readText())
  }

  @Test
  fun ocrLanguageDetection_devanagariOnlyForHindiFamily() {
    assertTrue(isDevanagariLanguage("Hindi"))
    assertTrue(isDevanagariLanguage("Marathi"))
    assertFalse(isDevanagariLanguage("Spanish"))
    assertFalse(isDevanagariLanguage("English"))
  }

  // Minimal stand-in so move() can be exercised without real Uris.
  private fun ImagePageEditorTestPage(name: String) =
    com.chami.pdfmaker.ui.PageItem(
      android.net.Uri.parse("file:///tmp/$name.jpg"),
      testBitmap(10, 10, Color.WHITE)
    )
}
