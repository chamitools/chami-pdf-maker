package com.chami.pdfmaker.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.*
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ------------------------------------------------------------------
// Standard PDF encryption (PDF 32000-1:2008 §7.6), implemented with
// only the standard Android/JDK APIs (MD5, RC4 by hand, AES/CBC via
// javax.crypto). No libraries.
//
// NEW files use AES-128 (Standard handler V=4, R=4, /CFM /AESV2,
// 128-bit key). Files this app wrote before (V=1, R=2, 40-bit RC4)
// are still verified and unlocked through the legacy path below —
// the format carries both schemes, so old files keep working.
// R>=5 (AES-256) and non-Standard handlers are NOT supported and
// return false from verify/unlock/patch.
// ------------------------------------------------------------------

private val PDF_PADDING = byteArrayOf(
  0x28.toByte(), 0xBF.toByte(), 0x4E.toByte(), 0x5E.toByte(), 0x4E.toByte(), 0x75.toByte(), 0x8A.toByte(), 0x41.toByte(),
  0x64.toByte(), 0x00.toByte(), 0x4E.toByte(), 0x56.toByte(), 0xFF.toByte(), 0xFA.toByte(), 0x01.toByte(), 0x08.toByte(),
  0x2E.toByte(), 0x2E.toByte(), 0x00.toByte(), 0xB6.toByte(), 0xD0.toByte(), 0x68.toByte(), 0x3E.toByte(), 0x80.toByte(),
  0x2F.toByte(), 0x0C.toByte(), 0xA9.toByte(), 0xFE.toByte(), 0x64.toByte(), 0x53.toByte(), 0x69.toByte(), 0x7A.toByte()
)

/** AES/CBC/PKCS5Padding (= PKCS#7 here: AES has a 16-byte block). */
private fun aesEncrypt(key: ByteArray, data: ByteArray): ByteArray {
  val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
  val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
  cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
  return iv + cipher.doFinal(data)
}

/** Inverse of [aesEncrypt]: the first 16 bytes are the IV. */
private fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray {
  require(data.size >= 16) { "AES stream shorter than its 16-byte IV" }
  val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
  cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(data, 0, 16))
  return cipher.doFinal(data, 16, data.size - 16)
}

// Counts bytes written so the xref table can record exact offsets.
private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
  var count = 0L
    private set

  override fun write(b: Int) {
    super.write(b)
    count++
  }

  override fun write(b: ByteArray, off: Int, len: Int) {
    out.write(b, off, len)
    count += len
  }
}

/**
 * Streaming encrypted-PDF writer (Phase 2, H5/M3). One page at a time:
 * the caller renders page i to JPEG bytes, calls [writePage], and
 * discards the bytes — peak memory is O(1 page), never O(document).
 * The output stream is always closed, by [finish] or [abort].
 *
 * Object numbering (per page i: pageObj = 3+3i, contentObj = 4+3i,
 * imageObj = 5+3i, encryptObj = 3+3*numPages). The Catalog (1) is
 * written first; the Pages object (2) is written LAST, once the kid
 * list is known — this also lets callers that do not know the page
 * count up front (imagesToProtectedPdf skips unloadable images)
 * stream without a pre-pass. File order does not matter: readers use
 * the xref table, and every offset is recorded in [offsets].
 * Internal so unit tests can drive it directly (same module).
 */
internal class EncryptedPdfWriter(
  private val outFile: File,
  private val enc: PdfProtector.EncryptionParams,
  private val docId: ByteArray
) {
  private val useAes = enc.vVal >= 4
  private val offsets = mutableMapOf<Int, Long>()
  private var fos: FileOutputStream? = null
  private var out: CountingOutputStream? = null
  private var pageCount = 0
  private var started = false
  private var finished = false

  private fun writeString(s: String) {
    out!!.write(s.toByteArray(Charsets.ISO_8859_1))
  }

  private fun startObj(objNum: Int) {
    offsets[objNum] = out!!.count
    writeString("$objNum 0 obj\n")
  }

  private fun endObj() {
    writeString("endobj\n")
  }

  private fun ensureStarted() {
    if (started) return
    started = true
    fos = FileOutputStream(outFile)
    out = CountingOutputStream(fos!!)
    writeString("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
    startObj(1)
    writeString("<< /Type /Catalog /Pages 2 0 R >>\n")
    endObj()
  }

  /** Encrypts and writes one page (page + content stream + image XObject) immediately. */
  fun writePage(jpeg: ByteArray, pageWidth: Int, pageHeight: Int, imageWidth: Int, imageHeight: Int) {
    check(!finished) { "EncryptedPdfWriter: writePage after finish/abort" }
    ensureStarted()
    val i = pageCount++
    val pObj = 3 + 3 * i
    val cObj = 4 + 3 * i
    val imgObj = 5 + 3 * i

    startObj(pObj)
    writeString(
      "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageWidth $pageHeight] " +
        "/Contents $cObj 0 R /Resources << /ProcSet [/PDF /ImageC] /XObject << /Im1 $imgObj 0 R >> >> >>\n"
    )
    endObj()

    val rawContent = "q\n$pageWidth 0 0 $pageHeight 0 0 cm\n/Im1 Do\nQ\n".toByteArray(Charsets.ISO_8859_1)
    val encContent = PdfProtector.encryptStreamData(enc.encryptionKey, cObj, rawContent, useAes)
    startObj(cObj)
    // /Length includes the 16 IV bytes for AES (they are part of encContent).
    writeString("<< /Length ${encContent.size} >>\nstream\n")
    out!!.write(encContent)
    writeString("\nendstream\n")
    endObj()

    val encJpg = PdfProtector.encryptStreamData(enc.encryptionKey, imgObj, jpeg, useAes)
    startObj(imgObj)
    writeString(
      "<< /Type /XObject /Subtype /Image /Width $imageWidth /Height $imageHeight " +
        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${encJpg.size} >>\nstream\n"
    )
    out!!.write(encJpg)
    writeString("\nendstream\n")
    endObj()
  }

  /**
   * Writes the Pages object, the Encrypt dictionary, xref and trailer,
   * closes the stream and returns true. Returns false (and writes
   * nothing — no file is created) when no page was ever written.
   */
  fun finish(): Boolean {
    if (finished) return pageCount > 0
    finished = true
    try {
      if (pageCount == 0) return false
      ensureStarted()
      startObj(2)
      val kidsStr = (0 until pageCount).joinToString(" ") { "${3 + 3 * it} 0 R" }
      writeString("<< /Type /Pages /Kids [$kidsStr] /Count $pageCount >>\n")
      endObj()

      val encryptObj = 3 + 3 * pageCount
      startObj(encryptObj)
      writeString(PdfProtector.encryptDictText(enc))
      endObj()

      val startXref = out!!.count
      val totalObjs = encryptObj + 1
      writeString("xref\n0 $totalObjs\n")
      writeString("0000000000 65535 f \n")
      for (objNum in 1 until totalObjs) {
        writeString(String.format("%010d 00000 n \n", offsets[objNum] ?: 0L))
      }
      val idHex = PdfProtector.toHexPublic(docId)
      writeString("trailer\n<< /Size $totalObjs /Root 1 0 R /Encrypt $encryptObj 0 R /ID [<$idHex> <$idHex>] >>\n")
      writeString("startxref\n$startXref\n%%EOF\n")
      out!!.flush()
      return true
    } finally {
      closeQuietly()
    }
  }

  /** Closes the stream without finishing; a no-op after [finish]. Callers delete the partial file. */
  fun abort() {
    if (finished) return
    finished = true
    closeQuietly()
  }

  private fun closeQuietly() {
    try { out?.flush() } catch (_: Exception) {}
    try { fos?.close() } catch (_: Exception) {}
    out = null
    fos = null
  }
}

object PdfProtector {

  private val PADDING = PDF_PADDING

  class RC4(key: ByteArray) {
    private val s = IntArray(256) { it }
    init {
      var k = 0
      for (i in 0 until 256) {
        k = (k + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
        val t = s[i]; s[i] = s[k]; s[k] = t
      }
    }
    fun process(data: ByteArray): ByteArray {
      val out = ByteArray(data.size)
      var i = 0
      var j = 0
      val st = s.clone()
      for (idx in data.indices) {
        i = (i + 1) and 0xFF
        j = (j + st[i]) and 0xFF
        val t = st[i]; st[i] = st[j]; st[j] = t
        val k = (st[i] + st[j]) and 0xFF
        out[idx] = (data[idx].toInt() xor st[k]).toByte()
      }
      return out
    }
  }

  private fun md5(vararg chunks: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("MD5")
    for (chunk in chunks) {
      md.update(chunk)
    }
    return md.digest()
  }

  private fun padPassword(pw: String): ByteArray {
    val b = pw.toByteArray(Charsets.ISO_8859_1)
    val out = ByteArray(32)
    val copyLen = minOf(b.size, 32)
    System.arraycopy(b, 0, out, 0, copyLen)
    if (copyLen < 32) {
      System.arraycopy(PADDING, 0, out, copyLen, 32 - copyLen)
    }
    return out
  }

  private fun toHex(bytes: ByteArray): String {
    val sb = StringBuilder()
    for (b in bytes) {
      sb.append(String.format("%02x", b.toInt() and 0xFF))
    }
    return sb.toString()
  }

  /** Same as [toHex], exposed for [EncryptedPdfWriter] (a top-level class in this file). */
  internal fun toHexPublic(bytes: ByteArray): String = toHex(bytes)

  private fun pBytesOf(pVal: Int): ByteArray = byteArrayOf(
    (pVal and 0xFF).toByte(),
    ((pVal shr 8) and 0xFF).toByte(),
    ((pVal shr 16) and 0xFF).toByte(),
    ((pVal shr 24) and 0xFF).toByte()
  )

  /**
   * Algorithm 3.2 — the file encryption key from the FILE's /O entry
   * (not a recomputed one, so this also verifies foreign files whose
   * owner password differs from the user password). R>=3: MD5 over
   * (padded user password + O + P + document ID), then 50 iterations
   * of MD5 over the first 16 bytes; key = first 16 bytes. R=2: a
   * single MD5; key = first 5 bytes.
   */
  private fun encryptionKeyFromO(
    userPassword: String,
    oBytes: ByteArray,
    pVal: Int,
    documentId: ByteArray,
    rVal: Int
  ): ByteArray {
    val paddedUser = padPassword(userPassword)
    return if (rVal >= 3) {
      var hash = md5(paddedUser, oBytes, pBytesOf(pVal), documentId)
      repeat(50) { hash = md5(hash.copyOfRange(0, 16)) }
      hash.copyOfRange(0, 16)
    } else {
      md5(paddedUser, oBytes, pBytesOf(pVal), documentId).copyOfRange(0, 5)
    }
  }

  /**
   * Algorithm 3.5 — the /U entry for a file encryption key. R>=3:
   * RC4(key, MD5(PADDING + document ID)), then 19 further RC4
   * encryptions with the key XOR i (i=1..19) — the same 19-round
   * pattern as the O entry — followed by 16 zero bytes (32 bytes
   * total; only the first 16 are compared on verify). R=2:
   * RC4(key, PADDING) (32 bytes).
   */
  private fun uEntryForKey(encKey: ByteArray, documentId: ByteArray, rVal: Int): ByteArray {
    return if (rVal >= 3) {
      var u = RC4(encKey).process(md5(PADDING, documentId))
      for (i in 1..19) {
        val xorKey = ByteArray(encKey.size) { j -> (encKey[j].toInt() xor i).toByte() }
        u = RC4(xorKey).process(u)
      }
      u + ByteArray(16)
    } else {
      RC4(encKey).process(PADDING)
    }
  }

  /**
   * Computes Standard PDF encryption parameters for a NEW file:
   * AES-128 (V=4, R=4, 128-bit key) per PDF 32000-1:2008 §7.6.
   * O entry (Algorithm 3.3, R>=3): MD5(padded owner password) with
   * 50 MD5 iterations, RC4 of the padded user password, then 19
   * further RC4 encryptions with the key XOR i (i=1..19).
   * Key (Algorithm 3.2, R>=3) and U (Algorithm 3.5) as documented on
   * [encryptionKeyFromO] / [uEntryForKey].
   * pVal: PDF permission flags. Default -4 = no restrictions other
   * than the open password. Callers clear the print bit (4) / copy
   * bit (16) to restrict.
   */
  fun computeEncryptionParams(userPassword: String, ownerPassword: String = "", documentId: ByteArray, pVal: Int = -4): EncryptionParams {
    val paddedUser = padPassword(userPassword)
    val paddedOwner = padPassword(ownerPassword.ifEmpty { userPassword })

    // Algorithm 3.3 (R>=3): O entry.
    var ownerKey = md5(paddedOwner)
    repeat(50) { ownerKey = md5(ownerKey.copyOfRange(0, 16)) }
    var oEntry = RC4(ownerKey).process(paddedUser)
    for (i in 1..19) {
      val xorKey = ByteArray(ownerKey.size) { j -> (ownerKey[j].toInt() xor i).toByte() }
      oEntry = RC4(xorKey).process(oEntry)
    }

    val encryptionKey = encryptionKeyFromO(userPassword, oEntry, pVal, documentId, 4)
    val uEntry = uEntryForKey(encryptionKey, documentId, 4)

    return EncryptionParams(
      encryptionKey = encryptionKey,
      oHex = toHex(oEntry),
      uHex = toHex(uEntry),
      pVal = pVal,
      documentIdHex = toHex(documentId),
      rVal = 4,
      vVal = 4
    )
  }

  /**
   * Legacy parameters for files written before the AES upgrade
   * (Standard V=1, R=2, 40-bit RC4, 5-byte key): single MD5, U =
   * RC4(key, PADDING). Used only to verify/unlock old files and by
   * the legacy round-trip test — new files never use this.
   */
  fun computeLegacyEncryptionParams(userPassword: String, ownerPassword: String = "", documentId: ByteArray, pVal: Int = -4): EncryptionParams {
    val paddedUser = padPassword(userPassword)
    val paddedOwner = padPassword(ownerPassword.ifEmpty { userPassword })

    // Algorithm 3.3 (R=2): O entry.
    val ownerKey = md5(paddedOwner).copyOfRange(0, 5)
    val oEntry = RC4(ownerKey).process(paddedUser)

    val encryptionKey = encryptionKeyFromO(userPassword, oEntry, pVal, documentId, 2)
    val uEntry = uEntryForKey(encryptionKey, documentId, 2)

    return EncryptionParams(
      encryptionKey = encryptionKey,
      oHex = toHex(oEntry),
      uHex = toHex(uEntry),
      pVal = pVal,
      documentIdHex = toHex(documentId),
      rVal = 2,
      vVal = 1
    )
  }

  data class EncryptionParams(
    val encryptionKey: ByteArray,
    val oHex: String,
    val uHex: String,
    val pVal: Int,
    val documentIdHex: String,
    /** Standard security handler revision: 4 for new (AES) files, 2 for legacy (RC4) files. */
    val rVal: Int = 2,
    /** Encryption dictionary /V: 4 for new (AES) files, 1 for legacy (RC4) files. */
    val vVal: Int = 1
  )

  /**
   * Algorithm 3.1 — the per-object key. MD5(encKey + objNum (3 bytes
   * LE) + genNum (2 bytes LE)), truncated to min(keyLen+5, 16) — with
   * one AES-only step the brief's shorthand omitted: for AES the MD5
   * input is extended with the four bytes "sAlT" (0x73, 0x41, 0x6C,
   * 0x54) before hashing. A 16-byte AES key gives a 16-byte object
   * key; a 5-byte RC4 key gives 10 bytes. Verified against pypdf:
   * without "sAlT" a real reader decrypts AES streams to garbage.
   */
  fun objectKey(encKey: ByteArray, objNum: Int, genNum: Int = 0, useAes: Boolean = false): ByteArray {
    val objBytes = byteArrayOf(
      (objNum and 0xFF).toByte(),
      ((objNum shr 8) and 0xFF).toByte(),
      ((objNum shr 16) and 0xFF).toByte(),
      (genNum and 0xFF).toByte(),
      ((genNum shr 8) and 0xFF).toByte()
    )
    val hash = if (useAes) md5(encKey, objBytes, "sAlT".toByteArray(Charsets.ISO_8859_1))
    else md5(encKey, objBytes)
    return hash.copyOfRange(0, minOf(encKey.size + 5, 16))
  }

  /** Encrypts one stream (content stream or image XObject) with the per-object key. */
  internal fun encryptStreamData(encKey: ByteArray, objNum: Int, data: ByteArray, useAes: Boolean): ByteArray {
    val key = objectKey(encKey, objNum, 0, useAes)
    return if (useAes) aesEncrypt(key, data) else RC4(key).process(data)
  }

  /** The /Encrypt dictionary text for [enc]. AES ends with "/P <pVal> >>" so patchPermissions can find it. */
  internal fun encryptDictText(enc: EncryptionParams): String {
    return if (enc.vVal >= 4) {
      "<< /Filter /Standard /V 4 /R 4 /Length 128 /CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV2 /Length 16 >> >> /StmF /StdCF /StrF /StdCF /O <${enc.oHex}> /U <${enc.uHex}> /P ${enc.pVal} >>\n"
    } else {
      "<< /Filter /Standard /V 1 /R 2 /O <${enc.oHex}> /U <${enc.uHex}> /P ${enc.pVal} >>\n"
    }
  }

  // Renders one source page to JPEG bytes and writes it to [writer]
  // immediately; the bitmap and JPEG bytes are discarded on return.
  // Used by protectPdf and setPermissions (both stream, H5).
  private fun renderPageToWriter(renderer: PdfRenderer, pageIndex: Int, writer: EncryptedPdfWriter) {
    var page: PdfRenderer.Page? = null
    var bmp: Bitmap? = null
    try {
      page = renderer.openPage(pageIndex)
      val w = page.width
      val h = page.height
      // Scale appropriately for crisp resolution (max side 1600)
      val maxSide = maxOf(w, h)
      val scale = if (maxSide > 1600) 1600f / maxSide else if (maxSide < 800) 1.5f else 1.0f
      val bw = (w * scale).toInt().coerceAtLeast(1)
      val bh = (h * scale).toInt().coerceAtLeast(1)
      bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
      bmp.eraseColor(Color.WHITE)
      page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      val baos = ByteArrayOutputStream()
      bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
      writer.writePage(baos.toByteArray(), w, h, bw, bh)
    } finally {
      try { page?.close() } catch (_: Exception) {}
      bmp?.recycle()
    }
  }

  /**
   * Encrypts and protects any PDF file with the specified password
   * (AES-128 for new files). Compatible with all standard PDF viewers.
   * Streams one page at a time (H5): peak memory is O(1 page).
   */
  fun protectPdf(
    ctx: Context,
    inputUri: Uri,
    password: String,
    outFile: File,
    // PHASE 5: Cancel. Checked before every page; false = stop, delete the
    // partial file and return false. Default keeps old callers working.
    shouldContinue: () -> Boolean = { true },
    progressCallback: (Int, Int) -> Unit = { _, _ -> }
  ): Boolean {
    var pfd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    var writer: EncryptedPdfWriter? = null
    var ok = false
    var cancelled = false
    try {
      pfd = ctx.contentResolver.openFileDescriptor(inputUri, "r") ?: return false
      renderer = PdfRenderer(pfd)
      val pageCount = renderer.pageCount
      if (pageCount == 0) return false
      val docId = md5(UUID.randomUUID().toString().toByteArray())
      val enc = computeEncryptionParams(password, "", docId)
      writer = EncryptedPdfWriter(outFile, enc, docId)
      for (i in 0 until pageCount) {
        if (!shouldContinue()) { cancelled = true; break }
        renderPageToWriter(renderer, i, writer)
        progressCallback(i + 1, pageCount)
      }
      ok = if (cancelled) false else writer.finish()
    } catch (e: Exception) {
      e.printStackTrace()
      ok = false
    } catch (e: OutOfMemoryError) {
      ok = false
    } finally {
      try { renderer?.close() } catch (_: Exception) {}
      try { pfd?.close() } catch (_: Exception) {}
      writer?.abort()
    }
    if (!ok) {
      try { outFile.delete() } catch (_: Exception) {}
    }
    return ok
  }

  /**
   * Creates an encrypted PDF directly from image URIs (AES-128).
   * Streams one image at a time; unloadable images are skipped (the
   * writer learns the final page count as pages arrive). Returns
   * false — and leaves no file behind — when no image could be loaded.
   */
  fun imagesToProtectedPdf(ctx: Context, uris: List<Uri>, password: String, outFile: File): Boolean {
    if (uris.isEmpty()) return false
    var writer: EncryptedPdfWriter? = null
    var ok = false
    try {
      val docId = md5(UUID.randomUUID().toString().toByteArray())
      val enc = computeEncryptionParams(password, "", docId)
      writer = EncryptedPdfWriter(outFile, enc, docId)
      for (uri in uris) {
        val bmp = PdfEngine.loadBitmap(ctx, uri, 1800) ?: continue
        try {
          val baos = ByteArrayOutputStream()
          bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
          // Standard A4 dimensions 595x842 or proportional; the
          // XObject dict needs the JPEG's real pixel size, not the page size.
          writer.writePage(baos.toByteArray(), 595, 842, bmp.width, bmp.height)
        } finally {
          bmp.recycle()
        }
      }
      ok = writer.finish()
    } catch (e: Exception) {
      e.printStackTrace()
      ok = false
    } catch (e: OutOfMemoryError) {
      ok = false
    } finally {
      writer?.abort()
    }
    if (!ok) {
      try { outFile.delete() } catch (_: Exception) {}
    }
    return ok
  }

  // Last up-to-`maxBytes` of a file as ISO-8859-1 text (#31). PDF
  // trailer keys (/Encrypt, /ID) and this app's /Encrypt dictionary
  // object sit at the END of the file, so a tail read finds them
  // without loading the whole file into memory. Files smaller than
  // maxBytes are read in full (they are small by definition).
  private fun readPdfTail(input: File, maxBytes: Long = 64 * 1024): String {
    return try {
      val len = input.length()
      val start = maxOf(0L, len - maxBytes)
      val size = (len - start).toInt()
      val buf = ByteArray(size)
      RandomAccessFile(input, "r").use { raf ->
        raf.seek(start)
        raf.readFully(buf)
      }
      String(buf, Charsets.ISO_8859_1)
    } catch (_: Exception) { "" }
  }

  // Text holding the password-check fields (/O /U /P /ID) for
  // verifyPassword: the tail first (they sit at the end of files this
  // app writes), the whole file only if a field is missing — so
  // behaviour is identical for any PDF while the common case never
  // reads a whole file into memory.
  private fun passwordFieldsText(pdfFile: File): String {
    val tail = readPdfTail(pdfFile)
    val hasAll = listOf(
      Regex("""/O\s*<([0-9a-fA-F]{64})>"""),
      Regex("""/U\s*<([0-9a-fA-F]{64})>"""),
      Regex("""/P\s*(-?[0-9]+)"""),
      Regex("""/ID\s*\[\s*<([0-9a-fA-F]+)>""")
    ).all { it.containsMatchIn(tail) }
    return if (hasAll) tail
    else pdfFile.inputStream().bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
  }

  /**
   * True when the file is an encrypted PDF. Tail-based only (see
   * [readPdfTail]) — the whole file is never read for this check.
   */
  fun isEncryptedFile(pdfFile: File): Boolean {
    if (!pdfFile.exists()) return false
    return readPdfTail(pdfFile).contains("/Encrypt")
  }

  /**
   * True when the PDF behind a content Uri is encrypted. Reads only
   * the last 64 KB (via openFileDescriptor + FileChannel, mapped
   * read-only) — never the whole file. Returns false when the check
   * cannot be performed (unreadable Uri, non-file descriptor).
   */
  fun isEncrypted(ctx: Context, pdfUri: Uri): Boolean {
    return try {
      val pfd = ctx.contentResolver.openFileDescriptor(pdfUri, "r") ?: return false
      ParcelFileDescriptor.AutoCloseInputStream(pfd).channel.use { channel ->
        val size = channel.size()
        if (size <= 0) return@use false
        val tailLen = minOf(size, 64 * 1024)
        val buf = channel.map(FileChannel.MapMode.READ_ONLY, size - tailLen, tailLen)
        val bytes = ByteArray(tailLen.toInt())
        for (k in bytes.indices) bytes[k] = buf.get(k)
        String(bytes, Charsets.ISO_8859_1).contains("/Encrypt")
      }
    } catch (_: Exception) {
      false
    }
  }

  // Byte-level helpers over a read-only mapped buffer: the file is
  // never copied to the heap (H6). All reads use absolute get(index),
  // so the buffer's position is never moved.
  private fun indexOfBytes(buf: java.nio.MappedByteBuffer, pattern: ByteArray, from: Int): Int? {
    if (pattern.isEmpty() || from < 0) return null
    val limit = buf.capacity() - pattern.size
    var i = from
    while (i <= limit) {
      var j = 0
      while (j < pattern.size && buf.get(i + j) == pattern[j]) j++
      if (j == pattern.size) return i
      i++
    }
    return null
  }

  private fun asciiSlice(buf: java.nio.MappedByteBuffer, start: Int, end: Int): String {
    val size = (end - start).coerceIn(0, buf.capacity() - start)
    val bytes = ByteArray(size)
    for (k in 0 until size) bytes[k] = buf.get(start + k)
    return String(bytes, Charsets.ISO_8859_1)
  }

  private data class PageRef(val objNum: Int, val w: Int, val h: Int, val imgObj: Int)

  // Finds every Page object in the mapped file: its MediaBox size and
  // its /Im1 image ref. Only the small dict region of each object is
  // decoded to String; binary stream bytes are never touched.
  private fun findPageRefs(buf: java.nio.MappedByteBuffer): List<PageRef> {
    val refs = mutableListOf<PageRef>()
    val marker = " 0 obj".toByteArray(Charsets.ISO_8859_1)
    val endMarker = "endobj".toByteArray(Charsets.ISO_8859_1)
    var from = 0
    while (true) {
      val objIdx = indexOfBytes(buf, marker, from) ?: break
      var numStart = objIdx
      while (numStart > 0 && buf.get(numStart - 1) in '0'.code.toByte()..'9'.code.toByte()) numStart--
      val objNum = asciiSlice(buf, numStart, objIdx).toIntOrNull()
      val dictStart = objIdx + marker.size
      if (objNum == null) {
        from = dictStart
        continue
      }
      val endIdx = indexOfBytes(buf, endMarker, dictStart)
      if (endIdx == null || endIdx - dictStart > 8192) {
        from = dictStart
        continue
      }
      val body = asciiSlice(buf, dictStart, endIdx)
      if (!body.contains("/Type /Page ")) {
        from = endIdx + endMarker.size
        continue
      }
      val media = Regex("""/MediaBox\s*\[\s*0\s+0\s+([\d.]+)\s+([\d.]+)\s*\]""").find(body)
      val img = Regex("""/Im1\s+(\d+)\s+0\s*R""").find(body)
      if (media != null && img != null) {
        refs.add(
          PageRef(
            objNum,
            media.groupValues[1].toFloat().toInt(),
            media.groupValues[2].toFloat().toInt(),
            img.groupValues[1].toInt()
          )
        )
      }
      from = endIdx + endMarker.size
    }
    refs.sortBy { it.objNum }
    return refs
  }

  // Decrypts one image XObject stream from the mapped file into a
  // single transient byte array (no copy of the whole file). The
  // /Length is authoritative (it includes the 16 IV bytes for AES);
  // the "\nendstream" marker is only a fallback when /Length is
  // missing. Returns null when the stream cannot be found/decoded.
  private fun decryptImageStream(
    buf: java.nio.MappedByteBuffer,
    imgObj: Int,
    encKey: ByteArray,
    useAes: Boolean
  ): ByteArray? {
    val header = "$imgObj 0 obj".toByteArray(Charsets.ISO_8859_1)
    val objStart = indexOfBytes(buf, header, 0) ?: return null
    val streamMarker = "stream".toByteArray(Charsets.ISO_8859_1)
    val streamIdx = indexOfBytes(buf, streamMarker, objStart) ?: return null
    if (streamIdx - objStart > 8192) return null
    val dictText = asciiSlice(buf, objStart + header.size, streamIdx)
    val len = Regex("""/Length\s+(\d+)""").find(dictText)?.groupValues?.get(1)?.toIntOrNull()
    var dataStart = streamIdx + streamMarker.size
    if (dataStart < buf.capacity() && buf.get(dataStart) == '\r'.code.toByte()) dataStart++
    if (dataStart < buf.capacity() && buf.get(dataStart) == '\n'.code.toByte()) dataStart++
    val dataEnd = if (len != null) {
      if (len < 0 || dataStart + len > buf.capacity()) return null
      dataStart + len
    } else {
      indexOfBytes(buf, "\nendstream".toByteArray(Charsets.ISO_8859_1), dataStart) ?: return null
    }
    val size = dataEnd - dataStart
    if (size <= 0) return null
    val encBytes = ByteArray(size)
    for (k in 0 until size) encBytes[k] = buf.get(dataStart + k)
    val key = objectKey(encKey, imgObj, 0, useAes)
    return try {
      if (useAes) aesDecrypt(key, encBytes) else RC4(key).process(encBytes)
    } catch (_: Exception) {
      null
    }
  }

  // Phase 6 — UNLOCK: remove the password from a PDF protected by
  // this app (protectPdf / imagesToProtectedPdf / setPermissions).
  // Supports both AES-128 (V>=4, R>=3) and legacy RC4 (V=1, R=2)
  // Standard-security files. Wrong password -> false. A PDF that was
  // never encrypted is simply copied to out and returns true.
  // R>=5 / AES-256 and non-Standard handlers return false. The output
  // is rebuilt from the decrypted page images, so it is a plain,
  // unencrypted PDF with the same pages. The input file is mapped
  // read-only and never copied to the heap (H6); a failed unlock
  // deletes the partial output instead of leaving it behind.
  fun unlockPdf(
    input: File,
    out: File,
    password: String,
    // PHASE 5: Cancel + real progress (pages done / total). Cancel = stop,
    // delete the partial output, return false. Defaults keep old callers.
    shouldContinue: () -> Boolean = { true },
    onProgress: (Int, Int) -> Unit = { _, _ -> }
  ): Boolean {
    if (!input.exists()) return false
    if (!isEncryptedFile(input)) {
      return try {
        input.copyTo(out, overwrite = true)
        true
      } catch (_: Exception) {
        false
      }
    }
    if (!verifyPassword(input, password)) return false
    val fieldsText = passwordFieldsText(input)
    val vVal = Regex("""/V\s+(\d+)""").find(fieldsText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    val rVal = Regex("""/R\s+(\d+)""").find(fieldsText)?.groupValues?.get(1)?.toIntOrNull() ?: 2
    if (rVal >= 5 || vVal >= 5) return false
    val pVal = Regex("""/P\s*(-?[0-9]+)""").find(fieldsText)?.groupValues?.get(1)?.toIntOrNull() ?: -4
    val idHex = Regex("""/ID\s*\[\s*<([0-9a-fA-F]+)>""").find(fieldsText)?.groupValues?.get(1) ?: return false
    val oHex = Regex("""/O\s*<([0-9a-fA-F]{64})>""").find(fieldsText)?.groupValues?.get(1) ?: return false
    val docId = hexToBytes(idHex)
    val encKey = encryptionKeyFromO(password, hexToBytes(oHex), pVal, docId, rVal)
    val useAes = vVal >= 4

    var doc: PdfDocument? = null
    var ok = false
    try {
      FileInputStream(input).channel.use { channel ->
        val size = channel.size()
        if (size <= 0) return@use
        val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
        val pageRefs = findPageRefs(buf)
        if (pageRefs.isEmpty()) return@use
        doc = PdfDocument()
        var pageNo = 1
        for (ref in pageRefs) {
          if (!shouldContinue()) return@use
          val jpg = decryptImageStream(buf, ref.imgObj, encKey, useAes) ?: return@use
          val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size) ?: return@use
          try {
            val info = PdfDocument.PageInfo.Builder(ref.w, ref.h, pageNo++).create()
            val page = doc!!.startPage(info)
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, ref.w.toFloat(), ref.h.toFloat()), null)
            doc!!.finishPage(page)
          } finally {
            bmp.recycle()
          }
          onProgress(pageNo - 1, pageRefs.size)
        }
        if (!shouldContinue()) return@use
        FileOutputStream(out).use { doc!!.writeTo(it) }
        ok = shouldContinue()
      }
    } catch (e: Exception) {
      e.printStackTrace()
      ok = false
    } catch (e: OutOfMemoryError) {
      ok = false
    } finally {
      try { doc?.close() } catch (_: Exception) {}
    }
    if (!ok) {
      try { out.delete() } catch (_: Exception) {}
    }
    return ok
  }

  // Phase 6 — PERMISSIONS: (re-)protect a PLAIN pdf with a password
  // and set print / copy permissions. allowPrint=false clears the
  // print bit (4), allowCopy=false clears the copy/extract bit (16).
  // Input must be an unencrypted PDF (PdfRenderer cannot open an
  // encrypted one) — to change permissions on an already-protected
  // file, unlock it first with unlockPdf(), then call this.
  // Streams one page at a time (H5); the ParcelFileDescriptor is
  // opened inside the try so a throwing PdfRenderer constructor
  // cannot leak it (M2).
  fun setPermissions(input: File, out: File, password: String, allowPrint: Boolean, allowCopy: Boolean): Boolean {
    var pfd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    var writer: EncryptedPdfWriter? = null
    var ok = false
    try {
      if (!input.exists()) return false
      var pVal = -4
      if (!allowPrint) pVal = pVal and 4.inv()
      if (!allowCopy) pVal = pVal and 16.inv()
      pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
      renderer = PdfRenderer(pfd)
      val pageCount = renderer.pageCount
      if (pageCount == 0) return false
      val docId = md5(UUID.randomUUID().toString().toByteArray())
      val enc = computeEncryptionParams(password, "", docId, pVal)
      writer = EncryptedPdfWriter(out, enc, docId)
      for (i in 0 until pageCount) {
        renderPageToWriter(renderer, i, writer)
      }
      ok = writer.finish()
    } catch (e: Exception) {
      e.printStackTrace()
      ok = false
    } catch (e: OutOfMemoryError) {
      ok = false
    } finally {
      try { renderer?.close() } catch (_: Exception) {}
      try { pfd?.close() } catch (_: Exception) {}
      writer?.abort()
    }
    if (!ok) {
      try { out.delete() } catch (_: Exception) {}
    }
    return ok
  }

  /**
   * Verifies if a user password unlocks an encrypted PDF file.
   * Parses /R from the Encrypt dict (default 2). For R>=3 the key is
   * recomputed per Algorithm 3.2/3.5 and only the FIRST 16 bytes of
   * the /U entry are compared. For R=2 the existing full-U check is
   * kept. R>=5 files return false (unsupported handler revision).
   */
  fun verifyPassword(pdfFile: File, candidatePassword: String): Boolean {
    return try {
      // Tail first (see passwordFieldsText): the whole file is read
      // only if a password field is missing from the last 64 KB.
      val text = passwordFieldsText(pdfFile)
      val oMatch = Regex("""/O\s*<([0-9a-fA-F]{64})>""").find(text)
      val uMatch = Regex("""/U\s*<([0-9a-fA-F]{64})>""").find(text)
      val pMatch = Regex("""/P\s*(-?[0-9]+)""").find(text)
      val idMatch = Regex("""/ID\s*\[\s*<([0-9a-fA-F]+)>""").find(text)

      if (oMatch == null || uMatch == null || pMatch == null || idMatch == null) {
        return false
      }

      val oHex = oMatch.groupValues[1]
      val uHex = uMatch.groupValues[1]
      val pVal = pMatch.groupValues[1].toInt()
      val idHex = idMatch.groupValues[1]
      val rVal = Regex("""/R\s+(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 2
      if (rVal >= 5) return false

      val oBytes = hexToBytes(oHex)
      val idBytes = hexToBytes(idHex)
      val encKey = encryptionKeyFromO(candidatePassword, oBytes, pVal, idBytes, rVal)
      val computedU = uEntryForKey(encKey, idBytes, rVal)
      val fileU = hexToBytes(uHex)
      if (rVal >= 3) {
        // Algorithm 3.5: only the first 16 bytes of /U are significant.
        fileU.size >= 16 && computedU.size >= 16 &&
          fileU.copyOfRange(0, 16).contentEquals(computedU.copyOfRange(0, 16))
      } else {
        toHex(computedU).equals(uHex, ignoreCase = true)
      }
    } catch (e: Exception) {
      false
    }
  }

  // One in-place byte replacement (offset + replacement bytes) used by
  // patchPermissions. All edits are collected BEFORE anything is
  // written, so a failure leaves the file untouched.
  private data class ByteEdit(val offset: Int, val bytes: ByteArray)

  // Re-encrypts every stream object in [buf] from [oldKey] to [newKey]
  // and returns the in-place edits. Stream contents are skipped over
  // by /Length, so binary bytes are never mistaken for object headers.
  // Returns null when any stream cannot be re-encrypted at the same
  // length (lengths must stay identical so xref offsets stay valid).
  // RC4 ciphertext has the plaintext's length; AES ciphertext is
  // IV (16) + padded plaintext, so re-encrypting the same plaintext
  // always yields the same length for both schemes.
  private fun collectStreamReencryptionEdits(
    buf: java.nio.MappedByteBuffer,
    oldKey: ByteArray,
    newKey: ByteArray,
    useAes: Boolean
  ): List<ByteEdit>? {
    val edits = mutableListOf<ByteEdit>()
    val objMarker = " 0 obj".toByteArray(Charsets.ISO_8859_1)
    val streamMarker = "stream".toByteArray(Charsets.ISO_8859_1)
    val endObjMarker = "endobj".toByteArray(Charsets.ISO_8859_1)
    var from = 0
    while (true) {
      val objIdx = indexOfBytes(buf, objMarker, from) ?: break
      var numStart = objIdx
      while (numStart > 0 && buf.get(numStart - 1) in '0'.code.toByte()..'9'.code.toByte()) numStart--
      val objNum = asciiSlice(buf, numStart, objIdx).toIntOrNull()
      val afterHeader = objIdx + objMarker.size
      if (objNum == null) {
        from = afterHeader
        continue
      }
      val streamIdx = indexOfBytes(buf, streamMarker, afterHeader)
      val endObjIdx = indexOfBytes(buf, endObjMarker, afterHeader)
      val afterStream = if (streamIdx != null) streamIdx + streamMarker.size else -1
      val isStream = streamIdx != null && (endObjIdx == null || streamIdx < endObjIdx) &&
        afterStream < buf.capacity() &&
        (buf.get(afterStream) == '\n'.code.toByte() || buf.get(afterStream) == '\r'.code.toByte())
      if (!isStream) {
        from = if (endObjIdx != null) endObjIdx + endObjMarker.size else afterHeader
        continue
      }
      if (streamIdx - afterHeader > 8192) return null
      val dictText = asciiSlice(buf, afterHeader, streamIdx)
      val len = Regex("""/Length\s+(\d+)""").find(dictText)?.groupValues?.get(1)?.toIntOrNull() ?: return null
      var dataStart = streamIdx + streamMarker.size
      if (dataStart < buf.capacity() && buf.get(dataStart) == '\r'.code.toByte()) dataStart++
      if (dataStart < buf.capacity() && buf.get(dataStart) == '\n'.code.toByte()) dataStart++
      val dataEnd = dataStart + len
      if (len < 0 || dataEnd > buf.capacity()) return null
      val encBytes = ByteArray(len)
      for (k in 0 until len) encBytes[k] = buf.get(dataStart + k)
      val oldObjKey = objectKey(oldKey, objNum, 0, useAes)
      val newObjKey = objectKey(newKey, objNum, 0, useAes)
      val plain = try {
        if (useAes) aesDecrypt(oldObjKey, encBytes) else RC4(oldObjKey).process(encBytes)
      } catch (_: Exception) {
        return null
      }
      val reEnc = try {
        if (useAes) aesEncrypt(newObjKey, plain) else RC4(newObjKey).process(plain)
      } catch (_: Exception) {
        return null
      }
      if (reEnc.size != len) return null
      edits.add(ByteEdit(dataStart, reEnc))
      from = dataEnd
    }
    return edits
  }

  /**
   * Applies print/copy/edit permissions to a PDF this app protected,
   * IN PLACE (same file, same xref offsets). Ported from
   * ToolScreens.patchPdfPermissions, with one correction that the
   * original got wrong: changing /P changes the derived file
   * encryption key (Algorithm 3.2 takes /P as an input), so merely
   * recomputing /U leaves every stream encrypted under the OLD key
   * and readers derive the NEW key and get garbage. This version
   * therefore also re-encrypts every stream in place (same byte
   * lengths, so offsets stay valid) and then patches /U (Algorithm
   * 3.5 recomputation for AES files, RC4 for legacy files) and /P
   * with the same length-preserving replacements the original used
   * (all 8 chars, same as "/P -4 >>"). P bits from the -4 base:
   * print -4, edit -8, copy -16. Only files written with /P -4 can
   * be patched; anything else returns false and the file is left
   * untouched. Callers already handle patched=false with a toast.
   * Supports both AES (V>=4/R>=3) and legacy RC4 (V=1/R=2) files.
   */
  fun patchPermissions(input: File, password: String, allowPrint: Boolean, allowCopy: Boolean, allowEdit: Boolean): Boolean {
    return try {
      var pVal = -4
      if (!allowPrint) pVal -= 4
      if (!allowEdit) pVal -= 8
      if (!allowCopy) pVal -= 16
      if (pVal == -4) return true
      if (!input.exists()) return false
      // The password must verify against the CURRENT /P first.
      if (!verifyPassword(input, password)) return false
      val fieldsText = passwordFieldsText(input)
      val oHex = Regex("""/O\s*<([0-9a-fA-F]{64})>""").find(fieldsText)?.groupValues?.get(1) ?: return false
      val idHex = Regex("""/ID\s*\[\s*<([0-9a-fA-F]+)>""").find(fieldsText)?.groupValues?.get(1) ?: return false
      val curP = Regex("""/P\s*(-?[0-9]+)""").find(fieldsText)?.groupValues?.get(1)?.toIntOrNull() ?: return false
      if (curP != -4) return false
      val rVal = Regex("""/R\s+(\d+)""").find(fieldsText)?.groupValues?.get(1)?.toIntOrNull() ?: 2
      val vVal = Regex("""/V\s+(\d+)""").find(fieldsText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
      if (rVal >= 5 || vVal >= 5) return false
      val oBytes = hexToBytes(oHex)
      val idBytes = hexToBytes(idHex)
      val oldKey = encryptionKeyFromO(password, oBytes, curP, idBytes, rVal)
      val newKey = encryptionKeyFromO(password, oBytes, pVal, idBytes, rVal)
      val newUHex = toHex(uEntryForKey(newKey, idBytes, rVal))
      val pReplacement = when (pVal) {
        -8 -> "/P -8 >>"
        -12 -> "/P -12>>"
        -16 -> "/P -16>>"
        -20 -> "/P -20>>"
        -24 -> "/P -24>>"
        -28 -> "/P -28>>"
        -32 -> "/P -32>>"
        else -> return false
      }

      // Collect EVERY edit before writing anything, so a failure
      // leaves the file untouched.
      FileInputStream(input).channel.use { channel ->
        val size = channel.size()
        if (size <= 0 || size > Int.MAX_VALUE) return false
        val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
        val useAes = vVal >= 4
        val edits = collectStreamReencryptionEdits(buf, oldKey, newKey, useAes)?.toMutableList() ?: return false
        // /U replacement: "/U <" + 64 hex + ">" in the tail region
        // (the Encrypt dict is the last object in files this app writes).
        val tailStart = maxOf(0, size.toInt() - 64 * 1024)
        val uMarker = "/U <".toByteArray(Charsets.ISO_8859_1)
        val uIdx = indexOfBytes(buf, uMarker, tailStart) ?: return false
        val uHexStart = uIdx + uMarker.size
        if (uHexStart + 65 > buf.capacity()) return false
        if (!asciiSlice(buf, uHexStart, uHexStart + 65).matches(Regex("[0-9a-fA-F]{64}>"))) return false
        edits.add(ByteEdit(uHexStart, newUHex.toByteArray(Charsets.ISO_8859_1)))
        // /P replacement: the exact 8 chars "/P -4 >>" in the tail.
        val pMarker = "/P -4 >>".toByteArray(Charsets.ISO_8859_1)
        val pIdx = indexOfBytes(buf, pMarker, tailStart) ?: return false
        edits.add(ByteEdit(pIdx, pReplacement.toByteArray(Charsets.ISO_8859_1)))
        RandomAccessFile(input, "rw").use { raf ->
          for (edit in edits) {
            raf.seek(edit.offset.toLong())
            raf.write(edit.bytes)
          }
        }
      }
      // The patched file must still verify with the same password.
      verifyPassword(input, password)
    } catch (_: Exception) {
      false
    }
  }

  private fun hexToBytes(hex: String): ByteArray {
    val len = hex.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
      data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
      i += 2
    }
    return data
  }
}
