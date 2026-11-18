package com.chami.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chami.pdfmaker.pdf.PdfCompress
import com.chami.pdfmaker.pdf.PdfEngine
import com.chami.pdfmaker.pdf.PdfProtector
import com.chami.pdfmaker.pdf.textToPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Human-user test pass, 2026-11-18 (Run #151, HEAD 9e87166).
 * Runs on a real emulator (API 34) — PdfRenderer is real here, so
 * these are the authoritative checks for page size, merge/split and
 * protect. OCR model behaviour is NOT asserted here: ML Kit needs a
 * model download on the emulator and a blank result would be a
 * network/model artefact, not an app bug.
 */
@RunWith(AndroidJUnit4::class)
class EngineRoundTripTest {

  private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun cleanChamiFolder() {
    // Engine tests write into the Chami folder, which is also what
    // History shows. Clear it so the UI journey tests start empty.
    File(ctx.getExternalFilesDir(null), "Chami").listFiles()?.forEach { it.delete() }
  }

  private fun labelledBitmap(w: Int, h: Int, color: Int, label: String): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(color)
    val paint = Paint().apply { this.color = Color.WHITE; textSize = 64f; isAntiAlias = true }
    c.drawText(label, 40f, h / 2f, paint)
    // Add detail so JPEG compression has something to squeeze.
    val p2 = Paint().apply { this.color = Color.BLACK; strokeWidth = 6f }
    for (i in 0 until 20) c.drawLine(0f, (i * h / 20).toFloat(), w.toFloat(), (i * h / 20).toFloat(), p2)
    return bmp
  }

  private fun writeJpeg(bmp: Bitmap, name: String): File {
    val f = File(ctx.cacheDir, name)
    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    return f
  }

  private fun twoPagePdf(name: String): File {
    val pages = listOf(
      labelledBitmap(800, 600, Color.RED, "PAGE ONE"),
      labelledBitmap(600, 800, Color.BLUE, "PAGE TWO")
    )
    // buildImagesPdf names the file itself (IMG_PDF_<stamp>.pdf in the
    // Chami folder); the `name` arg is only a label for the test log.
    return PdfEngine.buildImagesPdf(ctx, pages, PdfEngine.ImagesPdfOptions.defaults()) { _, _ -> }
  }

  private fun pageCount(file: File): Int {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val r = PdfRenderer(pfd)
    val n = r.pageCount
    r.close(); pfd.close()
    return n
  }

  private fun pageSize(file: File, index: Int): Pair<Int, Int> {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val r = PdfRenderer(pfd)
    val page = r.openPage(index)
    val size = page.width to page.height
    page.close(); r.close(); pfd.close()
    return size
  }

  @Test
  fun imagesToPdf_pagesAreFixedA4Portrait() {
    val pdf = twoPagePdf("a4_test.pdf")
    assertTrue(pdf.exists())
    assertEquals(2, pageCount(pdf))
    // Fixed A4 portrait, 595x842pt — NOT the image's own pixel size.
    assertEquals(595 to 842, pageSize(pdf, 0))
    assertEquals(595 to 842, pageSize(pdf, 1))
  }

  @Test
  fun imagesToPdf_fittedImageIsNotBlank() {
    val pdf = twoPagePdf("a4_fit_test.pdf")
    val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
    val r = PdfRenderer(pfd)
    val page = r.openPage(0)
    val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(Color.WHITE)
    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    page.close(); r.close(); pfd.close()
    // The rendered page must contain non-white pixels (the fitted image).
    var nonWhite = 0
    for (x in 0 until bmp.width step 10) {
      for (y in 0 until bmp.height step 10) {
        if (bmp.getPixel(x, y) != Color.WHITE) nonWhite++
      }
    }
    assertTrue("rendered A4 page is blank", nonWhite > 0)
  }

  @Test
  fun mergeAndSplit_roundTrip() {
    val a = twoPagePdf("merge_a.pdf")
    val b = twoPagePdf("merge_b.pdf")
    val merged = PdfEngine.outFile(ctx, "merged_test.pdf")
    assertTrue(PdfEngine.copyPages(ctx, listOf(Uri.fromFile(a) to null, Uri.fromFile(b) to null), merged))
    assertEquals(4, pageCount(merged))
    // Split: keep only page 2 of the merged file.
    val split = PdfEngine.outFile(ctx, "split_test.pdf")
    assertTrue(PdfEngine.copyPages(ctx, listOf(Uri.fromFile(merged) to setOf(2)), split))
    assertEquals(1, pageCount(split))
    assertEquals(595 to 842, pageSize(split, 0))
  }

  @Test
  fun resizeTo1080x1350_producesExactDimensions() {
    // NOTE: this documents current behaviour — resizeConvert stretches
    // to the exact width/height (aspect is NOT preserved). A landscape
    // photo resized to the 1080x1350 portrait preset comes out
    // stretched tall. See fix list item on the Image Resizer preset.
    val src = writeJpeg(labelledBitmap(800, 600, Color.GREEN, "SRC"), "resize_src.jpg")
    val out = PdfEngine.outFile(ctx, "resized_test.jpg")
    assertTrue(PdfEngine.resizeConvert(ctx, Uri.fromFile(src), 1080, 1350, Bitmap.CompressFormat.JPEG, out))
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(out.absolutePath, opts)
    assertEquals(1080, opts.outWidth)
    assertEquals(1350, opts.outHeight)
  }

  @Test
  fun compressImageToKb_shrinksBelowTarget() {
    val bmp = labelledBitmap(1600, 1200, Color.MAGENTA, "PHOTO")
    val src = writeJpeg(bmp, "compress_src.jpg")
    val origLen = src.length()
    val out = PdfEngine.outFile(ctx, "compressed_test.jpg")
    assertTrue(PdfEngine.compressImageToKb(ctx, Uri.fromFile(src), 100, out))
    assertTrue(out.exists())
    assertTrue("compressed file is not smaller than the original", out.length() < origLen)
  }

  @Test
  fun compressPdf_keepsAllPages() {
    val src = twoPagePdf("compress_src.pdf")
    val out = PdfEngine.outFile(ctx, "compressed_pdf_test.pdf")
    val res = PdfCompress.compressPdf(ctx, Uri.fromFile(src), PdfCompress.presetForQuality(70), out)
    assertTrue(res.ok)
    assertEquals(2, res.pageCount)
    assertEquals(2, pageCount(out))
    assertTrue(out.length() > 0)
  }

  @Test
  fun protectAndUnlock_roundTrip() {
    val src = twoPagePdf("protect_src.pdf")
    val protected = PdfEngine.outFile(ctx, "protected_test.pdf")
    assertTrue(PdfProtector.protectPdf(ctx, Uri.fromFile(src), "test1234", protected) { _, _ -> })
    assertTrue(PdfProtector.verifyPassword(protected, "test1234"))
    assertTrue(!PdfProtector.verifyPassword(protected, "wrong-password"))
    val unlocked = PdfEngine.outFile(ctx, "unlocked_test.pdf")
    assertTrue(PdfProtector.unlockPdf(protected, unlocked, "test1234"))
    assertEquals(2, pageCount(unlocked))
  }

  @Test
  fun textToPdf_isReadable() {
    val out = PdfEngine.outFile(ctx, "text_test.pdf")
    assertTrue(textToPdf(ctx, "OCR Text", "Hello world\nSecond line", outFile = out))
    assertEquals(1, pageCount(out))
  }
}
