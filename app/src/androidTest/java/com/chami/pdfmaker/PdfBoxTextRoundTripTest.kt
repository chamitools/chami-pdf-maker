package com.chami.pdfmaker

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chami.pdfmaker.pdf.PdfCompress
import com.chami.pdfmaker.pdf.PdfEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PHASE 4 (written, NOT run): Merge, Split and Compress must keep REAL
 * text. Each result is read back with PDFTextStripper — if the text were
 * a picture the word would not be found.
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxTextRoundTripTest {
  private lateinit var ctx: Context

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    PDFBoxResourceLoader.init(ctx)
  }

  private fun makePdf(name: String, vararg words: String): File {
    val f = File(ctx.cacheDir, name)
    PDDocument().use { doc ->
      for (w in words) {
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
          cs.beginText()
          cs.setFont(PDType1Font.HELVETICA, 18f)
          cs.newLineAtOffset(72f, 700f)
          cs.showText(w)
          cs.endText()
        }
      }
      doc.save(f)
    }
    return f
  }

  private fun textOf(f: File): String =
    PDDocument.load(f).use { PDFTextStripper().getText(it) }

  private fun pagesOf(f: File): Int = PDDocument.load(f).use { it.numberOfPages }

  @Test fun merge_keepsSelectableText() {
    val a = makePdf("a.pdf", "ALPHA-ONE", "ALPHA-TWO")
    val b = makePdf("b.pdf", "BRAVO-ONE")
    val out = File(ctx.cacheDir, "merged.pdf")
    val r = PdfEngine.copyPagesKeepText(ctx, listOf(Uri.fromFile(a) to null, Uri.fromFile(b) to null), out)
    assertEquals(PdfEngine.CopyOutcome.TEXT_KEPT, r)
    assertEquals(3, pagesOf(out))
    val t = textOf(out)
    assertTrue(t.contains("ALPHA-ONE") && t.contains("ALPHA-TWO") && t.contains("BRAVO-ONE"))
  }

  @Test fun split_keepsSelectableTextInThePart() {
    val a = makePdf("s.pdf", "PAGE-ONE", "PAGE-TWO", "PAGE-THREE")
    val out = File(ctx.cacheDir, "part.pdf")
    val r = PdfEngine.copyPagesKeepText(ctx, listOf(Uri.fromFile(a) to setOf(2, 3)), out)
    assertEquals(PdfEngine.CopyOutcome.TEXT_KEPT, r)
    assertEquals(2, pagesOf(out))
    val t = textOf(out)
    assertTrue(t.contains("PAGE-TWO") && t.contains("PAGE-THREE") && !t.contains("PAGE-ONE"))
  }

  @Test fun compress_keepsSelectableText() {
    val a = makePdf("c.pdf", "COMPRESS-ME")
    val out = File(ctx.cacheDir, "compressed.pdf")
    val res = PdfCompress.compressPdf(ctx, Uri.fromFile(a), PdfCompress.presetForQuality(50), out)
    assertTrue(res.ok)
    assertTrue(res.textPreserved)
    assertTrue(textOf(out).contains("COMPRESS-ME"))
  }
}
