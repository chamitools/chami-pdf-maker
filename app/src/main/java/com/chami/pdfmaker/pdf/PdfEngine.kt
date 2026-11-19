package com.chami.pdfmaker.pdf
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfEngine {
  // Load bitmap safely. maxDim is a LONG-SIDE cap only, to avoid OOM:
  // default 4096px. Callers that load a small preview pass their own
  // smaller cap explicitly (e.g. 1200 for a thumbnail); the PDF build
  // path (buildImagesPdf) always loads at full resolution up to 4096px.
  // There is NO 1600px cap on export any more (audit §1.1).
  fun loadBitmap(ctx:Context, uri:Uri, maxDim:Int=4096): Bitmap? {
    return try {
      val opts = BitmapFactory.Options().apply{ inJustDecodeBounds=true }
      ctx.contentResolver.openInputStream(uri)?.use{ BitmapFactory.decodeStream(it, null, opts) }
      var sample = 1
      val maxSide = maxOf(opts.outWidth, opts.outHeight)
      while(maxSide/sample > maxDim) sample *= 2
      val o2 = BitmapFactory.Options().apply{ inSampleSize=sample }
      ctx.contentResolver.openInputStream(uri)?.use{ BitmapFactory.decodeStream(it, null, o2) }
    } catch(e:Exception){ null }
  }

  // NOTE (L9, Phase 2): the legacy imagesToPdf / passportPhoto /
  // passportPhotoSheet8 functions were deleted — a grep confirms zero
  // callers in app/src/main, and the tests that covered imagesToPdf
  // now cover buildImagesPdf instead. buildImagesPdf below is the ONE
  // images-to-PDF implementation.


  // PDF TO IMAGES - renders each page to JPG, quality 85, max width 1500.
  // H7: renderer / fd / page / bitmap are closed/recycled in finally
  // blocks, so a mid-loop failure can never leak them.
  fun pdfToImages(ctx:Context, pdfUri:Uri, outDir:File, onPage:(Int,File)->Unit): Int {
    var count=0
    var pfd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    try {
      pfd = ctx.contentResolver.openFileDescriptor(pdfUri,"r") ?: return 0
      renderer = PdfRenderer(pfd)
      for(i in 0 until renderer.pageCount){
        var page: PdfRenderer.Page? = null
        var bmp: Bitmap? = null
        try {
          page = renderer.openPage(i)
          bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
          bmp.eraseColor(Color.WHITE); page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          val f = File(outDir,"page_${i+1}.jpg")
          FileOutputStream(f).use{ bmp.compress(Bitmap.CompressFormat.JPEG,85,it) }
          onPage(i+1,f); count++
        } finally {
          bmp?.recycle()
          try { page?.close() } catch (_: Exception) {}
        }
      }
    } catch(e:Exception){ e.printStackTrace() }
      catch(e:OutOfMemoryError){ e.printStackTrace() }
    finally {
      try { renderer?.close() } catch (_: Exception) {}
      try { pfd?.close() } catch (_: Exception) {}
    }
    return count
  }

  // NOTE (M7): an encrypted PDF cannot be opened by PdfRenderer, so
  // pageCount returns 0 for it — that means "unreadable here", not
  // "empty". Callers that need to tell the two apart must check
  // PdfProtector.isEncrypted(ctx, uri) first (e.g. Reader search).
  fun pageCount(ctx:Context, pdfUri:Uri): Int {
    var pfd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    return try {
      pfd = ctx.contentResolver.openFileDescriptor(pdfUri,"r") ?: return 0
      renderer = PdfRenderer(pfd)
      renderer.pageCount
    } catch(e:Exception){ 0 }
      catch(e:OutOfMemoryError){ 0 }
    finally {
      try { renderer?.close() } catch (_: Exception) {}
      try { pfd?.close() } catch (_: Exception) {}
    }
  }

  // SPLIT - keep only pages in keepSet (1-based). MERGE - pass multiple PDFs, all pages in order.
  // HONEST LIMIT — RASTERISED OUTPUT: every source page is rendered to
  // a bitmap and drawn into the new PDF, so selectable text, links and
  // vector content are LOST; a text PDF comes out as pictures of text.
  // android.graphics.pdf has no PDF object parser, so pages cannot be
  // copied as objects with this codebase's libraries. Do not describe
  // Merge / Split output as keeping text.
  fun copyPages(ctx:Context, sources:List<Pair<Uri,Set<Int>?>>, outFile:File): Boolean {
    val doc = PdfDocument(); var pageNo=1
    return try {
      sources.forEach{ (uri, keep) ->
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
          pfd = ctx.contentResolver.openFileDescriptor(uri,"r") ?: return@forEach
          renderer = PdfRenderer(pfd)
          for(i in 0 until renderer.pageCount){
            if(keep!=null && !keep.contains(i+1)) continue
            var page: PdfRenderer.Page? = null
            var bmp: Bitmap? = null
            try {
              page = renderer.openPage(i)
              // Render at 2x for sharp text, then draw into A4 page
              bmp = Bitmap.createBitmap(page.width*2, page.height*2, Bitmap.Config.ARGB_8888)
              bmp.eraseColor(Color.WHITE); page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              val info = PdfDocument.PageInfo.Builder(595,842,pageNo++).create()
              val np = doc.startPage(info); np.canvas.drawColor(Color.WHITE)
              np.canvas.drawBitmap(bmp,null,RectF(36f,36f,559f,806f),null); doc.finishPage(np)
            } finally {
              bmp?.recycle()
              try { page?.close() } catch (_: Exception) {}
            }
          }
        } finally {
          try { renderer?.close() } catch (_: Exception) {}
          try { pfd?.close() } catch (_: Exception) {}
        }
      }
      FileOutputStream(outFile).use{ doc.writeTo(it) }; true
    } catch(e:Exception){ e.printStackTrace(); false }
      catch(e:OutOfMemoryError){ false }
    finally {
      try { doc.close() } catch (_: Exception) {}
    }
  }

  // COMPRESS TO EXACT KB - binary search quality 95 -> 20, then loop
  // the 0.8x downscale (max 8 iterations, down to a 64px short side)
  // until the target is met; the smallest bytes seen are kept.
  // Keep-smaller (#15, report item 15): if the JPEG output is NOT
  // smaller than the original, the original is kept — but NEVER by
  // copying non-JPEG bytes into this .jpg output. A JPEG original is
  // copied over (formats agree); a PNG/WebP original is NOT copied
  // (that would put PNG bytes in a .jpg file with an image/jpeg MIME)
  // — the valid JPEG bytes are kept instead. Callers that can rename
  // the output should use PdfCompress.keepSmallerImage, which keeps
  // such an original in a correctly-named .png/.webp file.
  // M15: the result of compressImageToKbDetailed — `ok` is whether a
  // file was written at all; `targetMet` is whether that file is
  // actually at or under the requested KB target. The old Boolean-only
  // result let callers claim the target was met when the binary search
  // + 8 downscales still could not reach it.
  data class CompressImageResult(val ok: Boolean, val targetMet: Boolean)

  // COMPRESS TO EXACT KB — the byte-producing behaviour is unchanged
  // (binary search quality 95 -> 20, then up to 8 downscales); only
  // the result now reports whether the target was actually met.
  fun compressImageToKbDetailed(ctx:Context, uri:Uri, targetKb:Int, outFile:File): CompressImageResult {
    // `bmp` always points at the current working bitmap so the finally
    // below recycles exactly that one, once, on every exit path.
    var bmp: Bitmap? = null
    return try {
      bmp = loadBitmap(ctx, uri, 2000) ?: return CompressImageResult(ok = false, targetMet = false)
      var cur: Bitmap = bmp!!
      var quality=95; var bytes:ByteArray
      while(true){
        val baos=ByteArrayOutputStream(); cur.compress(Bitmap.CompressFormat.JPEG,quality,baos); bytes=baos.toByteArray()
        if(bytes.size <= targetKb*1024 || quality<=20) break
        quality-=10
      }
      var bestBytes = bytes
      // M15: loop the downscale instead of a single 0.8x retry, so a
      // very large image can actually reach its KB target.
      var downscales = 0
      while(bestBytes.size > targetKb*1024 && downscales < 8 &&
            minOf(cur.width, cur.height) > 64) {
        try {
          val w=(cur.width*0.8).toInt().coerceAtLeast(1)
          val h=(cur.height*0.8).toInt().coerceAtLeast(1)
          val small=Bitmap.createScaledBitmap(cur,w,h,true)
          cur.recycle(); cur=small; bmp=small
          val baos=ByteArrayOutputStream(); cur.compress(Bitmap.CompressFormat.JPEG,70,baos)
          val attempt=baos.toByteArray()
          if(attempt.size < bestBytes.size) bestBytes=attempt
          downscales++
        } catch(e:OutOfMemoryError){
          // Keep the best result so far instead of crashing.
          break
        }
      }
      bytes = bestBytes
      // Safety: if the JPEG is not smaller than the original, keep the
      // smaller one — only when the formats agree (#15). Copying a PNG
      // original over a .jpg output would corrupt the file's format.
      val origSize = PdfCompress.originalSizeBytes(ctx, uri)
      if (origSize > 0 && bytes.size >= origSize &&
          PdfCompress.imageMimeType(ctx, uri) == "image/jpeg") {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
          FileOutputStream(outFile).use { o -> input.copyTo(o) }
        }
        return CompressImageResult(ok = true, targetMet = outFile.length() <= targetKb * 1024L)
      }
      FileOutputStream(outFile).use{ it.write(bytes) }
      CompressImageResult(ok = true, targetMet = bytes.size <= targetKb * 1024L)
    } catch(e:Exception){ CompressImageResult(ok = false, targetMet = false) }
      catch(e:OutOfMemoryError){ CompressImageResult(ok = false, targetMet = false) }
    finally {
      bmp?.recycle()
    }
  }

  // Backwards-compatible wrapper: existing callers (and tests) keep
  // the Boolean result; callers that need the honest target verdict
  // use compressImageToKbDetailed instead.
  fun compressImageToKb(ctx:Context, uri:Uri, targetKb:Int, outFile:File): Boolean =
    compressImageToKbDetailed(ctx, uri, targetKb, outFile).ok

  // IMAGE RESIZE + CONVERT: width/height in px, format JPG/PNG/WEBP.
  // CROP-TO-FILL (#20): the source is centre-cropped to the target
  // aspect ratio FIRST, then scaled to the exact target size — never
  // stretched. A landscape photo resized to 1080x1350 loses its sides,
  // it does not come out tall and distorted.
  // H4: the target size is capped (1..4096 per side, max 16,000,000
  // pixels) and OutOfMemoryError is caught — a typo like 10000x10000
  // returns false instead of crashing the app. The screen enforces
  // the same caps in dimsValid.
  fun resizeConvert(ctx:Context, uri:Uri, newW:Int, newH:Int, format:Bitmap.CompressFormat, outFile:File): Boolean {
    if (newW !in 1..4096 || newH !in 1..4096) return false
    if (newW.toLong() * newH.toLong() > 16_000_000L) return false
    var bmp: Bitmap? = null
    var cropped: Bitmap? = null
    var resized: Bitmap? = null
    return try {
      bmp = loadBitmap(ctx,uri,3000) ?: return false
      val targetAspect = newW.toFloat() / newH.toFloat()
      val srcAspect = bmp.width.toFloat() / bmp.height.toFloat()
      if (srcAspect > targetAspect) {
        // Source is wider than the target: crop the sides.
        val cropW = (bmp.height * targetAspect).toInt().coerceIn(1, bmp.width)
        val x = ((bmp.width - cropW) / 2).coerceAtLeast(0)
        cropped = Bitmap.createBitmap(bmp, x, 0, cropW, bmp.height)
      } else {
        // Source is taller than (or equal to) the target: crop top/bottom.
        val cropH = (bmp.width / targetAspect).toInt().coerceIn(1, bmp.height)
        val y = ((bmp.height - cropH) / 2).coerceAtLeast(0)
        cropped = Bitmap.createBitmap(bmp, 0, y, bmp.width, cropH)
      }
      resized = Bitmap.createScaledBitmap(cropped!!,newW,newH,true)
      FileOutputStream(outFile).use{ resized.compress(format,90,it) }
      true
    } catch(e:Exception){ false }
      catch(e:OutOfMemoryError){ false }
    finally {
      resized?.recycle()
      if (cropped !== null && cropped !== bmp) cropped.recycle()
      bmp?.recycle()
    }
  }

  // ------------------------------------------------------------------
  // CANONICAL IMAGES-TO-PDF API (v1.2 engine fix, 2026-11-14).
  // This is the ONE images-to-PDF implementation the ToolScreens batch
  // must call. The old duplicate `imagesToPdfWithOptions` (PdfEngine)
  // and the private `buildImagesPdfWithOptions` (ToolScreens) are
  // deleted / must be deleted — they disagreed on quality, watermark
  // and "Original" page size. Do not change this signature.
  //
  // Quality mapping, in this ONE place:
  //   Original = 100 — source pixels are embedded unmodified: NO JPEG
  //     round-trip is done on the working bitmap at all.
  //   High = 95, Medium = 80, Low = 65 — one JPEG round-trip at that
  //     quality, then the result is drawn. Unknown values fall back
  //     to Medium (80).
  // Page size:
  //   "A4" = 595x842pt, "Letter" = 612x792pt, "Original" = the image's
  //     TRUE size in points (width x height px at 72dpi) — NOT capped
  //     to 595x842, but capped at 14400pt per side (M1: the
  //     PdfDocument limit), scaling down preserving aspect when the
  //     image is larger. Unknown values fall back to A4.
  // Margins (marginsMm, all sides) apply only to A4 / Letter; they are
  // ignored for "Original" (the page IS the image). 1mm = 72/25.4 pt.
  // Watermark: size scales with the page (not a fixed 36/48pt), opacity
  // comes from options (10-50%), position is centre-diagonal or
  // bottom, and it is drawn on EVERY page. Blank text = no watermark.
  // Bitmaps are loaded by the CALLER at full resolution (long side
  // capped at 4096px only, to avoid OOM — see loadBitmap); this
  // function never downsamples them. The caller keeps ownership of
  // `pages`: they are NOT recycled here (only JPEG round-trip copies
  // made inside this function are recycled).
  // onProgress(done, total) is called after each page is finished, on
  // the calling thread — call from a background thread and post to UI.
  // Returns the written File (Chami folder, IMG_PDF_yyyyMMdd_HHmmss.pdf).
  // Throws IllegalArgumentException if pages is empty.
  // ------------------------------------------------------------------
  data class ImagesPdfOptions(
    val pageSize: String, /* A4|Letter|Original */
    val quality: String, /* Original|High|Medium|Low */
    val watermarkText: String,
    val watermarkOpacity: Int, /* 10-50 */
    val watermarkPosition: String, /* centre|bottom */
    val pageNumbers: Boolean,
    val marginsMm: Int
  ) {
    companion object {
      /** Spec defaults: A4, Original quality, no watermark, no page numbers, 15mm margins. */
      fun defaults() = ImagesPdfOptions(
        pageSize = "A4", quality = "Original", watermarkText = "",
        watermarkOpacity = 25, watermarkPosition = "centre",
        pageNumbers = false, marginsMm = 15
      )
    }
  }

  /** The single quality mapping for the whole app (see ImagesPdfOptions). */
  fun jpegQualityFor(quality: String): Int = when (quality.trim().lowercase(Locale.US)) {
    "original" -> 100
    "high" -> 95
    "medium" -> 80
    "low" -> 65
    else -> 80 // unknown -> Medium
  }

  fun buildImagesPdf(
    ctx: Context,
    pages: List<Bitmap>,
    options: ImagesPdfOptions,
    onProgress: (Int, Int) -> Unit
  ): File {
    require(pages.isNotEmpty()) { "buildImagesPdf: pages must not be empty" }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val outFile = outFile(ctx, "IMG_PDF_$stamp.pdf")
    val doc = PdfDocument()
    try {
      val total = pages.size
      pages.forEachIndexed { index, source ->
        // "Original" quality: embed source pixels unmodified — no JPEG
        // round-trip. Any other quality: one JPEG round-trip at the
        // mapped quality, then draw the result.
        val quality = jpegQualityFor(options.quality)
        val bmp: Bitmap
        var roundTripped: Bitmap? = null
        if (quality >= 100) {
          bmp = source
        } else {
          roundTripped = recompressAtQuality(source, quality)
          bmp = roundTripped
        }
        try {
          val (pw, ph) = when (options.pageSize.trim()) {
            "Letter" -> Pair(612, 792)
            // "Original": TRUE image size in points (px at 72dpi),
            // capped at 14400pt per side (M1, the PdfDocument limit):
            // a larger image is scaled down preserving aspect, so the
            // Builder never throws and the whole conversion never fails.
            "Original" -> {
              val capScale = minOf(14400f / bmp.width, 14400f / bmp.height, 1f)
              Pair((bmp.width * capScale).toInt().coerceAtLeast(1), (bmp.height * capScale).toInt().coerceAtLeast(1))
            }
            else -> Pair(595, 842) // "A4" and any unknown value
          }
          val pageInfo = PdfDocument.PageInfo.Builder(pw, ph, index + 1).create()
          val page = doc.startPage(pageInfo)
          val c = page.canvas
          c.drawColor(Color.WHITE)
          if (options.pageSize.trim() == "Original") {
            c.drawBitmap(bmp, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), null)
          } else {
            val marginPt = (options.marginsMm.coerceAtLeast(0) * 72f / 25.4f)
            val availW = (pw - 2 * marginPt).coerceAtLeast(1f)
            val availH = (ph - 2 * marginPt).coerceAtLeast(1f)
            val scale = minOf(availW / bmp.width, availH / bmp.height)
            val w = bmp.width * scale; val h = bmp.height * scale
            val dst = RectF((pw - w) / 2, (ph - h) / 2, (pw + w) / 2, (ph + h) / 2)
            c.drawBitmap(bmp, null, dst, null)
          }
          drawWatermark(c, options, pw, ph)
          if (options.pageNumbers) {
            // 12pt page number, bottom centre — a canvas point size,
            // not a Compose sp value (documented one-off).
            val numPaint = Paint().apply {
              color = Color.rgb(0x14, 0x53, 0x2D) // deep green
              textSize = 12f
              textAlign = Paint.Align.CENTER
            }
            c.drawText("Page ${index + 1}", pw / 2f, ph - 18f, numPaint)
          }
          doc.finishPage(page)
        } finally {
          // Only recycle a real round-trip copy — never the caller's
          // bitmap (recompressAtQuality returns the source unchanged
          // when the round-trip fails, and the caller still owns it).
          if (roundTripped != null && roundTripped !== source) roundTripped.recycle()
        }
        onProgress(index + 1, total)
      }
      FileOutputStream(outFile).use { doc.writeTo(it) }
      return outFile
    } finally {
      doc.close()
    }
  }

  // Watermark drawn on one page. Size scales with the page (a fixed
  // 36pt is tiny on A4 and huge on a small Original page); opacity is
  // taken from options (10-50%); centre = diagonal across the middle,
  // bottom = a smaller line near the foot of the page. Text is shrunk
  // until it fits inside 85% of the page width.
  private fun drawWatermark(canvas: Canvas, options: ImagesPdfOptions, pw: Int, ph: Int) {
    val text = options.watermarkText.trim()
    if (text.isEmpty()) return
    val opacity = options.watermarkOpacity.coerceIn(10, 50)
    val alpha = (opacity * 255 / 100).coerceIn(0, 255)
    val base = minOf(pw, ph).toFloat()
    val paint = Paint().apply {
      color = Color.argb(alpha, 0x14, 0x53, 0x2D) // deep green, visible unlike faint grey
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
      isAntiAlias = true
    }
    if (options.watermarkPosition.trim().lowercase(Locale.US) == "bottom") {
      paint.textSize = (base * 0.05f).coerceIn(12f, 48f)
      while (paint.measureText(text) > pw * 0.85f && paint.textSize > 12f) {
        paint.textSize -= 1f
      }
      // Above the page-number line (drawn at ph - 18) so they never collide.
      canvas.drawText(text, pw / 2f, ph - 40f, paint)
    } else { // "centre" (default) — diagonal across the middle
      paint.textSize = (base * 0.09f).coerceIn(14f, 96f)
      while (paint.measureText(text) > pw * 0.85f && paint.textSize > 14f) {
        paint.textSize -= 1f
      }
      canvas.save()
      canvas.rotate(-30f, pw / 2f, ph / 2f)
      canvas.drawText(text, pw / 2f, ph / 2f, paint)
      canvas.restore()
    }
  }

  // JPEG round-trip at the given quality. Returns the original bitmap
  // unchanged if the round-trip fails.
  private fun recompressAtQuality(bmp: Bitmap, quality: Int): Bitmap {
    return try {
      val baos = ByteArrayOutputStream()
      bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), baos)
      val bytes = baos.toByteArray()
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: bmp
    } catch (e: Exception) { bmp }
  }

  // Phase 6 — COMPRESS IMAGES INSIDE A PDF without changing the text.
  // Honest limit: android.graphics.pdf has no PDF object parser, so
  // embedded image streams cannot be recompressed in place with the
  // libraries in this project. This is a best-effort version: it
  // re-renders each page at the requested JPEG quality (same engine as
  // PdfCompress, full scale, exact quality) which shrinks image-heavy
  // PDFs; text becomes rasterised — see PdfCompress.compressPdf, which
  // says so plainly. Returns false on failure.
  fun compressPdfImages(ctx: Context, input: File, out: File, quality: Int): Boolean {
    if (!input.exists()) return false
    val preset = PdfCompress.presetForQuality(quality.coerceIn(10, 100))
    val result = PdfCompress.compressPdf(ctx, Uri.fromFile(input), preset, out)
    return result.ok
  }

  fun outFile(ctx:Context, name:String): File {
    val dir=File(ctx.getExternalFilesDir(null),"Chami"); if(!dir.exists()) dir.mkdirs()
    return File(dir,name)
  }
}
