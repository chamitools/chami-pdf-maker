package com.chami.pdfmaker.pdf

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.chami.pdfmaker.LocaleHelper
import java.io.File

// M14: every function returns Boolean success, and a failure shows a
// translated Toast instead of failing silently (the old code only
// printed a stack trace, so the user saw nothing happen). Callers
// that ignore the result keep working — a Boolean return is still
// usable where Unit is expected.
object ShareHelper {
  fun shareFile(ctx: Context, file: File, mimeType: String = "application/pdf"): Boolean {
    return try {
      val uri = FileProvider.getUriForFile(ctx, "com.chami.pdfmaker.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      ctx.startActivity(Intent.createChooser(intent, LocaleHelper.t(ctx, "share_via")))
      true
    } catch (e: Exception) {
      e.printStackTrace()
      showToast(ctx, LocaleHelper.t(ctx, "share_failed"))
      false
    }
  }

  // Share several files in ONE share sheet (#34, report item 34):
  // a single ACTION_SEND_MULTIPLE intent with all files, so History
  // multi-select share shows one chooser instead of one per file.
  fun shareFiles(ctx: Context, files: List<File>, mimeType: String): Boolean {
    if (files.isEmpty()) return false
    return try {
      val uris = ArrayList<android.net.Uri>()
      for (file in files) {
        uris.add(FileProvider.getUriForFile(ctx, "com.chami.pdfmaker.fileprovider", file))
      }
      val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = mimeType
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      ctx.startActivity(Intent.createChooser(intent, LocaleHelper.t(ctx, "share_via")))
      true
    } catch (e: Exception) {
      e.printStackTrace()
      showToast(ctx, LocaleHelper.t(ctx, "share_failed"))
      false
    }
  }

  fun openFile(ctx: Context, file: File, mimeType: String = "application/pdf"): Boolean {
    return try {
      val uri = FileProvider.getUriForFile(ctx, "com.chami.pdfmaker.fileprovider", file)
      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      // Show a chooser (#5), and tell the user instead of failing
      // silently when nothing on the device can open the file.
      if (intent.resolveActivity(ctx.packageManager) == null) {
        showToast(ctx, LocaleHelper.t(ctx, "open_failed"))
        return false
      }
      ctx.startActivity(Intent.createChooser(intent, LocaleHelper.t(ctx, "open_with")))
      true
    } catch (e: ActivityNotFoundException) {
      showToast(ctx, LocaleHelper.t(ctx, "open_failed"))
      false
    } catch (e: Exception) {
      e.printStackTrace()
      showToast(ctx, LocaleHelper.t(ctx, "open_failed"))
      false
    }
  }

  private fun showToast(ctx: Context, text: String) {
    try {
      Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
      // A toast must never turn a handled failure into a crash.
    }
  }
}

fun sharePdf(ctx: Context, file: File): Boolean {
  return ShareHelper.shareFile(ctx, file, "application/pdf")
}
