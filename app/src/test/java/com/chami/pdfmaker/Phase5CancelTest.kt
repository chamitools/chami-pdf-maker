package com.chami.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.chami.pdfmaker.pdf.EncryptedPdfWriter
import com.chami.pdfmaker.pdf.PdfEngine
import com.chami.pdfmaker.pdf.PdfProtector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * PHASE 5 — Cancel + real progress. Robolectric tests for the engine
 * hooks that need no PdfRenderer (Robolectric has none): Images to PDF
 * and Unlock. Protect and Compress-PDF cancel use PdfRenderer / PdfBox
 * and can only be proven on a phone or emulator (see REN-REVIEW-PHASE5.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase5CancelTest {

  private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun testBitmap(w: Int, h: Int, color: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawColor(color)
    return bmp
  }

  private fun chamiFiles(): Set<String> {
    val dir = File(ctx.getExternalFilesDir(null), "Chami")
    return dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
  }

  @Test
  fun imagesToPdf_progressReachesTotal_andFileIsKept() {
    val pages = listOf(testBitmap(400, 300, Color.RED), testBitmap(300, 400, Color.BLUE), testBitmap(300, 300, Color.GREEN))
    val seen = mutableListOf<Pair<Int, Int>>()
    val out = PdfEngine.buildImagesPdf(ctx, pages, PdfEngine.ImagesPdfOptions.defaults(), shouldContinue = { true }) { d, t -> seen.add(d to t) }
    assertTrue(out.exists() && out.length() > 0)
    assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
  }

  @Test
  fun imagesToPdf_cancelMidway_throws_andLeavesNoFile() {
    val before = chamiFiles()
    val pages = listOf(testBitmap(400, 300, Color.RED), testBitmap(300, 400, Color.BLUE), testBitmap(300, 300, Color.GREEN))
    var pagesDone = 0
    try {
      PdfEngine.buildImagesPdf(ctx, pages, PdfEngine.ImagesPdfOptions.defaults(), shouldContinue = { pagesDone < 1 }) { d, _ -> pagesDone = d }
      fail("expected CancellationException")
    } catch (_: CancellationException) {
      // expected
    }
    assertEquals(1, pagesDone)
    assertEquals(before, chamiFiles())
  }

  @Test
  fun imagesToPdf_cancelBeforeFirstPage_leavesNoFile() {
    val before = chamiFiles()
    try {
      PdfEngine.buildImagesPdf(ctx, listOf(testBitmap(100, 100, Color.RED)), PdfEngine.ImagesPdfOptions.defaults(), shouldContinue = { false }) { _, _ -> }
      fail("expected CancellationException")
    } catch (_: CancellationException) {
    }
    assertEquals(before, chamiFiles())
  }

  private fun jpeg(w: Int, h: Int, color: Int): ByteArray {
    val bmp = testBitmap(w, h, color)
    val baos = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
    bmp.recycle()
    return baos.toByteArray()
  }

  private fun protectedFile(name: String): File {
    val docId = ByteArray(16) { i -> (i * 7 + 3).toByte() }
    val enc = PdfProtector.computeEncryptionParams("secret123", "", docId)
    val f = File(ctx.cacheDir, name)
    val w = EncryptedPdfWriter(f, enc, docId)
    w.writePage(jpeg(400, 300, Color.RED), 595, 842, 400, 300)
    w.writePage(jpeg(300, 400, Color.BLUE), 595, 842, 300, 400)
    assertTrue(w.finish())
    return f
  }

  @Test
  fun unlock_progressReachesTotal() {
    val src = protectedFile("p5-unlock-src.pdf")
    val out = File(ctx.cacheDir, "p5-unlock-out.pdf")
    val seen = mutableListOf<Pair<Int, Int>>()
    assertTrue(PdfProtector.unlockPdf(src, out, "secret123", shouldContinue = { true }) { d, t -> seen.add(d to t) })
    assertTrue(out.exists())
    assertEquals(listOf(1 to 2, 2 to 2), seen)
  }

  @Test
  fun unlock_cancelMidway_returnsFalse_andLeavesNoOutput() {
    val src = protectedFile("p5-unlock-src2.pdf")
    val out = File(ctx.cacheDir, "p5-unlock-out2.pdf")
    var done = 0
    val ok = PdfProtector.unlockPdf(src, out, "secret123", shouldContinue = { done < 1 }) { d, _ -> done = d }
    assertFalse(ok)
    assertFalse(out.exists())
  }
}
