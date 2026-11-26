package com.chami.pdfmaker.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.IdentityHashMap

/**
 * PHASE 4 (user approved ONE PDF library, 26 Nov 2026): PdfBox-Android
 * (com.tom_roush:pdfbox-android, Apache-2.0, offline, no key, no account).
 *
 * Used ONLY by Merge, Split and Compress KB (PDF). It copies / edits REAL
 * PDF pages, so text, fonts, page size and order are kept and the text
 * stays selectable. Callers fall back to the old image method when this
 * returns [Status.FAILED]; [Status.LOCKED] means a password is needed
 * (the image method cannot open such a file either).
 *
 * Safety rules kept here:
 *  - Every document uses a temp-file scratch (MemoryUsageSetting) in the
 *    app cache folder, so a big PDF does not sit in RAM.
 *  - Output is written to "<name>.tmp", re-opened to check the page
 *    count, and only then renamed. A failed / cancelled run never leaves
 *    a partial file.
 *  - Nothing here throws to the caller.
 */
object PdfBoxTools {

  enum class Status { OK, LOCKED, FAILED, CANCELLED }

  data class Outcome(val status: Status, val pages: Int = 0)

  @Volatile private var inited = false

  private fun ensureInit(ctx: Context) {
    if (inited) return
    synchronized(this) {
      if (!inited) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        inited = true
      }
    }
  }

  private fun mem(ctx: Context): MemoryUsageSetting =
    MemoryUsageSetting.setupTempFileOnly().setTempDir(ctx.cacheDir)

  private fun openStream(ctx: Context, uri: Uri): InputStream? =
    if (uri.scheme == "file") java.io.FileInputStream(File(uri.path ?: return null))
    else ctx.contentResolver.openInputStream(uri)

  private fun load(ctx: Context, uri: Uri): PDDocument {
    val input = openStream(ctx, uri) ?: throw java.io.IOException("cannot open")
    return input.use { PDDocument.load(it, mem(ctx)) }
  }

  private fun tmpOf(out: File) = File(out.parentFile, out.name + ".tmp")

  /** Saves [doc] to a temp file, re-opens it to prove it is a valid PDF
   *  with [expectedPages] pages, then renames it to [out]. */
  private fun saveVerified(ctx: Context, doc: PDDocument, out: File, expectedPages: Int): Boolean {
    val tmp = tmpOf(out)
    try {
      doc.save(tmp)
      if (!tmp.exists() || tmp.length() == 0L) return false
      val check = PDDocument.load(tmp, mem(ctx))
      val n = try { check.numberOfPages } finally { try { check.close() } catch (_: Exception) {} }
      if (n != expectedPages) return false
      try { out.delete() } catch (_: Exception) {}
      return tmp.renameTo(out)
    } finally {
      if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
    }
  }

  /**
   * MERGE (keep == null for a source) and SPLIT (keep = 1-based page set).
   * Same shape as PdfEngine.copyPages so the call sites barely change.
   * [onPageDone] gets the number of pages written so far.
   */
  fun copyPages(
    ctx: Context,
    sources: List<Pair<Uri, Set<Int>?>>,
    outFile: File,
    shouldContinue: () -> Boolean,
    onPageDone: (Int) -> Unit
  ): Outcome {
    val opened = mutableListOf<PDDocument>()
    var dest: PDDocument? = null
    try {
      ensureInit(ctx)
      dest = PDDocument(mem(ctx))
      val merger = PDFMergerUtility()
      var written = 0
      for ((uri, keep) in sources) {
        if (!shouldContinue()) return Outcome(Status.CANCELLED)
        val src = load(ctx, uri)
        // Sources stay open until the destination is saved: copied pages
        // may still read streams from them.
        opened.add(src)
        if (keep == null) {
          val n = src.numberOfPages
          merger.appendDocument(dest, src)
          written += n
          onPageDone(written)
        } else {
          for (i in 0 until src.numberOfPages) {
            if (!keep.contains(i + 1)) continue
            if (!shouldContinue()) return Outcome(Status.CANCELLED)
            val imported = dest.importPage(src.getPage(i))
            cleanImportedAnnotations(imported)
            written++
            onPageDone(written)
          }
        }
      }
      if (written == 0) return Outcome(Status.FAILED) // never write an empty PDF
      if (!shouldContinue()) return Outcome(Status.CANCELLED)
      val ok = saveVerified(ctx, dest, outFile, written)
      return if (ok) Outcome(Status.OK, written) else Outcome(Status.FAILED)
    } catch (e: InvalidPasswordException) {
      return Outcome(Status.LOCKED)
    } catch (e: Exception) {
      return Outcome(Status.FAILED)
    } catch (e: OutOfMemoryError) {
      return Outcome(Status.FAILED)
    } finally {
      try { dest?.close() } catch (_: Exception) {}
      opened.forEach { try { it.close() } catch (_: Exception) {} }
      // Anything but a finished file leaves nothing behind.
      try { tmpOf(outFile).delete() } catch (_: Exception) {}
    }
  }

  /**
   * An imported page keeps its annotations, and an internal link or a
   * /P entry would point back into the SOURCE document and drag its
   * other pages into the output. Keep web links (URI); drop internal
   * links; remove the back-pointer on everything else.
   */
  private fun cleanImportedAnnotations(page: PDPage) {
    try {
      val annots = page.annotations ?: return
      val keep = ArrayList<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>()
      for (a in annots) {
        if (a is PDAnnotationLink) {
          if (a.action is PDActionURI) {
            a.cosObject.removeItem(COSName.P)
            keep.add(a)
          }
        } else {
          a.cosObject.removeItem(COSName.P)
          keep.add(a)
        }
      }
      page.annotations = keep
    } catch (_: Exception) {
      // Annotations are cosmetic here; the page itself is already copied.
    }
  }

  /**
   * COMPRESS: recompress the pictures inside the PDF as JPEG (scale and
   * quality from [preset]); text, fonts and layout are NOT touched.
   * A picture is replaced only when the new one is really smaller. The
   * caller still keeps the original if the whole file did not shrink.
   */
  fun compressImages(
    ctx: Context,
    pdfUri: Uri,
    preset: PdfCompressPreset,
    outFile: File
  ): Outcome {
    var doc: PDDocument? = null
    try {
      ensureInit(ctx)
      doc = load(ctx, pdfUri)
      val pages = doc.numberOfPages
      if (pages == 0) return Outcome(Status.FAILED)
      val doneResources = IdentityHashMap<Any, Boolean>()
      val replaced = IdentityHashMap<COSStream, PDImageXObject>()
      for (page in doc.pages) {
        val res = page.resources ?: continue
        recompressResources(doc, res, preset, doneResources, replaced)
      }
      val ok = saveVerified(ctx, doc, outFile, pages)
      return if (ok) Outcome(Status.OK, pages) else Outcome(Status.FAILED)
    } catch (e: InvalidPasswordException) {
      return Outcome(Status.LOCKED)
    } catch (e: Exception) {
      return Outcome(Status.FAILED)
    } catch (e: OutOfMemoryError) {
      return Outcome(Status.FAILED)
    } finally {
      try { doc?.close() } catch (_: Exception) {}
      try { tmpOf(outFile).delete() } catch (_: Exception) {}
    }
  }

  private fun recompressResources(
    doc: PDDocument,
    res: PDResources,
    preset: PdfCompressPreset,
    doneResources: IdentityHashMap<Any, Boolean>,
    replaced: IdentityHashMap<COSStream, PDImageXObject>
  ) {
    // Shared resource dictionaries (and nested forms) are visited once.
    if (doneResources.put(res.cosObject, true) != null) return
    for (name in res.xObjectNames.toList()) {
      try {
        val x = res.getXObject(name) ?: continue
        if (x is PDFormXObject) {
          x.resources?.let { recompressResources(doc, it, preset, doneResources, replaced) }
        } else if (x is PDImageXObject) {
          val key = x.cosObject
          val already = replaced[key]
          if (already != null) {
            res.put(name, already)
            continue
          }
          val smaller = recompressImage(doc, x, preset)
          if (smaller != null) {
            replaced[key] = smaller
            res.put(name, smaller)
          }
        }
      } catch (_: Exception) {
        // One picture that cannot be handled stays as it is.
      } catch (_: OutOfMemoryError) {
        // Same: leave this picture untouched.
      }
    }
  }

  /** Returns a smaller JPEG image object, or null to keep the original. */
  private fun recompressImage(
    doc: PDDocument,
    img: PDImageXObject,
    preset: PdfCompressPreset
  ): PDImageXObject? {
    if (img.isStencil) return null                 // 1-colour mask
    if (img.softMask != null || img.mask != null) return null // keep transparency exact
    if (img.bitsPerComponent == 1) return null     // black/white scans: JPEG is bigger
    val w = img.width
    val h = img.height
    if (w <= 0 || h <= 0 || w.toLong() * h > 16_000_000L) return null
    val originalBytes = rawSize(img.cosObject)
    var src: Bitmap? = null
    var scaled: Bitmap? = null
    try {
      src = img.image ?: return null
      // Shrink only big pictures; never below ~600px on the long side.
      var s = preset.scale.coerceIn(0.3f, 1f)
      if (maxOf(w, h) * s < 600f) s = minOf(1f, 600f / maxOf(w, h))
      scaled = if (s < 0.99f) {
        Bitmap.createScaledBitmap(src, (w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1), true)
      } else src
      val baos = ByteArrayOutputStream()
      scaled.compress(Bitmap.CompressFormat.JPEG, preset.jpegQuality.coerceIn(10, 95), baos)
      val bytes = baos.toByteArray()
      if (bytes.isEmpty()) return null
      if (originalBytes in 1..bytes.size.toLong()) return null // not smaller: keep
      return PDImageXObject(
        doc, ByteArrayInputStream(bytes), COSName.DCT_DECODE,
        scaled.width, scaled.height, 8, PDDeviceRGB.INSTANCE
      )
    } finally {
      if (scaled != null && scaled !== src) scaled.recycle()
      src?.recycle()
    }
  }

  private fun rawSize(stream: COSStream): Long {
    return try {
      var n = 0L
      val buf = ByteArray(16 * 1024)
      stream.createRawInputStream().use { input ->
        while (true) {
          val r = input.read(buf)
          if (r < 0) break
          n += r
        }
      }
      n
    } catch (_: Exception) {
      -1L
    }
  }
}
