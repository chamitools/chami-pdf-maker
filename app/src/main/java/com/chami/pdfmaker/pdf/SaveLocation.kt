package com.chami.pdfmaker.pdf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

object SaveLocation {
  // FIX 2026-11-26 (Phase 1): the copy is now verified and cleaned up.
  //  * opened with "wt" (truncate) so a reused document can never keep
  //    stale trailing bytes; falls back to "w" for providers that do not
  //    accept "wt".
  //  * the number of bytes written must equal the file's size — a short
  //    write is a failure, not a success.
  //  * on failure the half-written document is deleted, so the user's
  //    chosen folder is not left with an empty / broken file.
  fun copyToUri(ctx: Context, file: File, uri: Uri): Boolean {
    var written = -1L
    try {
      val out: OutputStream? = try {
        ctx.contentResolver.openOutputStream(uri, "wt")
      } catch (_: Exception) {
        ctx.contentResolver.openOutputStream(uri, "w")
      }
      out?.use { o ->
        FileInputStream(file).use { written = it.copyTo(o) }
        o.flush()
      }
    } catch (_: Exception) {
      written = -1L
    }
    val ok = written >= 0L && written == file.length()
    if (!ok) {
      try { DocumentsContract.deleteDocument(ctx.contentResolver, uri) } catch (_: Exception) {}
    }
    return ok
  }
}
