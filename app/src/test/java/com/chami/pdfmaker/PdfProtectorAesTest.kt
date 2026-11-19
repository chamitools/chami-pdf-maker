package com.chami.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.chami.pdfmaker.pdf.EncryptedPdfWriter
import com.chami.pdfmaker.pdf.PdfEngine
import com.chami.pdfmaker.pdf.PdfProtector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Phase 2 (H9) — AES-128 round-trip tests for PdfProtector.
 *
 * Robolectric has no PdfRenderer, so protectPdf / setPermissions
 * (which render source PDFs) cannot run here — those paths are covered
 * by the instrumented round-trip tests on a real device/emulator.
 * What runs here, on the JVM, is the whole crypto + writer + unlock
 * chain: the streaming EncryptedPdfWriter (the same writer protectPdf
 * and imagesToProtectedPdf use), verifyPassword, unlockPdf,
 * patchPermissions and isEncryptedFile/isEncrypted.
 *
 * The AES design itself was additionally verified outside Android
 * against pypdf 7.1.1 (see hidden_files/phase2-fix/verify_crypto_h9.py):
 * pypdf opens files in this writer's exact layout, password checks
 * agree byte-for-byte, and image streams decrypt identically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfProtectorAesTest {

  private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun testJpeg(w: Int, h: Int, color: Int): ByteArray {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawColor(color)
    val baos = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
    bmp.recycle()
    return baos.toByteArray()
  }

  private fun tmpFile(name: String): File = File(ctx.cacheDir, name)

  private fun testDocId(): ByteArray = ByteArray(16) { i -> (i * 7 + 3).toByte() }

  private fun writeProtected(
    outFile: File,
    enc: PdfProtector.EncryptionParams,
    docId: ByteArray,
    pages: List<Triple<ByteArray, Int, Int>> = listOf(
      Triple(testJpeg(400, 300, Color.RED), 400, 300),
      Triple(testJpeg(300, 400, Color.BLUE), 300, 400)
    )
  ): Boolean {
    val writer = EncryptedPdfWriter(outFile, enc, docId)
    for ((jpg, w, h) in pages) {
      writer.writePage(jpg, 595, 842, w, h)
    }
    return writer.finish()
  }

  private fun readText(file: File): String =
    String(file.readBytes(), Charsets.ISO_8859_1)

  private fun assertPlainPdf(file: File) {
    assertTrue(file.exists())
    assertTrue(file.length() > 0)
    assertTrue(file.readBytes().take(4).toByteArray().contentEquals("%PDF".toByteArray()))
    assertFalse(readText(file).contains("/Encrypt"))
  }

  @Test
  fun aes_roundTrip_verifyAndUnlock() {
    val docId = testDocId()
    val enc = PdfProtector.computeEncryptionParams("secret123", "", docId)
    assertEquals(16, enc.encryptionKey.size)
    assertEquals(4, enc.rVal)
    assertEquals(4, enc.vVal)

    val protected = tmpFile("aes-protected.pdf")
    assertTrue(writeProtected(protected, enc, docId))

    // The Encrypt dictionary really is AES-128.
    val text = readText(protected)
    assertTrue(text.contains("/V 4"))
    assertTrue(text.contains("/R 4"))
    assertTrue(text.contains("/AESV2"))
    assertTrue(text.contains("/Length 128"))

    assertTrue(PdfProtector.isEncryptedFile(protected))
    assertTrue(PdfProtector.verifyPassword(protected, "secret123"))
    assertFalse(PdfProtector.verifyPassword(protected, "wrong-password"))
    assertFalse(PdfProtector.verifyPassword(protected, ""))

    val unlocked = tmpFile("aes-unlocked.pdf")
    assertTrue(PdfProtector.unlockPdf(protected, unlocked, "secret123"))
    assertPlainPdf(unlocked)
    assertFalse(PdfProtector.isEncryptedFile(unlocked))
    assertFalse(PdfProtector.verifyPassword(unlocked, "secret123"))
  }

  @Test
  fun legacyRc4_roundTrip_verifyAndUnlock() {
    val docId = testDocId()
    val enc = PdfProtector.computeLegacyEncryptionParams("secret123", "", docId)
    assertEquals(5, enc.encryptionKey.size)
    assertEquals(2, enc.rVal)
    assertEquals(1, enc.vVal)

    val protected = tmpFile("rc4-protected.pdf")
    assertTrue(writeProtected(protected, enc, docId))

    val text = readText(protected)
    assertTrue(text.contains("/V 1"))
    assertTrue(text.contains("/R 2"))
    assertFalse(text.contains("AESV2"))

    assertTrue(PdfProtector.isEncryptedFile(protected))
    assertTrue(PdfProtector.verifyPassword(protected, "secret123"))
    assertFalse(PdfProtector.verifyPassword(protected, "wrong-password"))

    val unlocked = tmpFile("rc4-unlocked.pdf")
    assertTrue(PdfProtector.unlockPdf(protected, unlocked, "secret123"))
    assertPlainPdf(unlocked)
  }

  @Test
  fun unlock_wrongPassword_returnsFalse() {
    val docId = testDocId()
    val enc = PdfProtector.computeEncryptionParams("secret123", "", docId)
    val protected = tmpFile("aes-wrong-pw.pdf")
    assertTrue(writeProtected(protected, enc, docId))
    val out = tmpFile("aes-wrong-pw-out.pdf")
    assertFalse(PdfProtector.unlockPdf(protected, out, "not-the-password"))
  }

  @Test
  fun plainPdf_isNotEncrypted_andUnlockCopiesIt() {
    val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawColor(Color.GREEN)
    val plain = PdfEngine.buildImagesPdf(ctx, listOf(bmp), PdfEngine.ImagesPdfOptions.defaults()) { _, _ -> }
    bmp.recycle()

    assertFalse(PdfProtector.isEncryptedFile(plain))
    assertFalse(PdfProtector.verifyPassword(plain, "secret123"))

    val out = tmpFile("plain-copy.pdf")
    assertTrue(PdfProtector.unlockPdf(plain, out, "anything"))
    assertPlainPdf(out)
  }

  @Test
  fun patchPermissions_aes_stillVerifiesAndUnlocks() {
    val docId = testDocId()
    val enc = PdfProtector.computeEncryptionParams("secret123", "", docId)
    val protected = tmpFile("aes-patch.pdf")
    assertTrue(writeProtected(protected, enc, docId))

    // Restrict print + copy: pVal -4 -> -24, replacement "/P -24>>".
    assertTrue(PdfProtector.patchPermissions(protected, "secret123", allowPrint = false, allowCopy = false, allowEdit = true))
    val text = readText(protected)
    assertTrue(text.contains("/P -24>>"))
    assertFalse(text.contains("/P -4 >>"))

    // The patched file still verifies and unlocks — the streams were
    // re-encrypted under the new key, not left under the old one.
    assertTrue(PdfProtector.verifyPassword(protected, "secret123"))
    val unlocked = tmpFile("aes-patch-unlocked.pdf")
    assertTrue(PdfProtector.unlockPdf(protected, unlocked, "secret123"))
    assertPlainPdf(unlocked)
  }

  @Test
  fun patchPermissions_rc4_stillVerifiesAndUnlocks() {
    val docId = testDocId()
    val enc = PdfProtector.computeLegacyEncryptionParams("secret123", "", docId)
    val protected = tmpFile("rc4-patch.pdf")
    assertTrue(writeProtected(protected, enc, docId))

    // Restrict print only: pVal -4 -> -8, replacement "/P -8 >>".
    assertTrue(PdfProtector.patchPermissions(protected, "secret123", allowPrint = false, allowCopy = true, allowEdit = true))
    assertTrue(readText(protected).contains("/P -8 >>"))

    assertTrue(PdfProtector.verifyPassword(protected, "secret123"))
    val unlocked = tmpFile("rc4-patch-unlocked.pdf")
    assertTrue(PdfProtector.unlockPdf(protected, unlocked, "secret123"))
    assertPlainPdf(unlocked)
  }

  @Test
  fun patchPermissions_noRestriction_returnsTrueAndLeavesFileAlone() {
    val docId = testDocId()
    val enc = PdfProtector.computeEncryptionParams("secret123", "", docId)
    val protected = tmpFile("aes-patch-noop.pdf")
    assertTrue(writeProtected(protected, enc, docId))
    val before = protected.readBytes()
    assertTrue(PdfProtector.patchPermissions(protected, "secret123", allowPrint = true, allowCopy = true, allowEdit = true))
    assertTrue(protected.readBytes().contentEquals(before))
  }

  @Test
  fun patchPermissions_wrongPassword_returnsFalseAndLeavesFileAlone() {
    val docId = testDocId()
    val enc = PdfProtector.computeEncryptionParams("secret123", "", docId)
    val protected = tmpFile("aes-patch-badpw.pdf")
    assertTrue(writeProtected(protected, enc, docId))
    val before = protected.readBytes()
    assertFalse(PdfProtector.patchPermissions(protected, "not-the-password", allowPrint = false, allowCopy = true, allowEdit = true))
    assertTrue(protected.readBytes().contentEquals(before))
  }

  @Test
  fun isEncrypted_uriCheck_matchesFileCheck() {
    val docId = testDocId()
    val enc = PdfProtector.computeEncryptionParams("secret123", "", docId)
    val protected = tmpFile("aes-uri-check.pdf")
    assertTrue(writeProtected(protected, enc, docId))
    assertTrue(PdfProtector.isEncrypted(ctx, Uri.fromFile(protected)))

    val unlocked = tmpFile("plain-uri-check-out.pdf")
    assertTrue(PdfProtector.unlockPdf(protected, unlocked, "secret123"))
    assertFalse(PdfProtector.isEncrypted(ctx, Uri.fromFile(unlocked)))
  }

  @Test
  fun objectKey_lengthsAndAesSalt() {
    val aesKey = ByteArray(16) { it.toByte() }
    val rc4Key = ByteArray(5) { it.toByte() }
    assertEquals(16, PdfProtector.objectKey(aesKey, 7, 0, true).size)
    assertEquals(10, PdfProtector.objectKey(rc4Key, 7).size)
    // The AES "sAlT" step makes the AES object key differ from the
    // RC4 object key for the same file key and object number.
    assertFalse(
      PdfProtector.objectKey(aesKey, 7, 0, true)
        .contentEquals(PdfProtector.objectKey(aesKey, 7, 0, false))
    )
  }
}
