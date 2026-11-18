package com.chami.pdfmaker.pdf

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object ShareHelper {
  fun shareFile(ctx: Context, file: File, mimeType: String = "application/pdf") {
    try {
      val uri = FileProvider.getUriForFile(ctx, "com.chami.pdfmaker.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      ctx.startActivity(Intent.createChooser(intent, "Share via"))
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  // Share several files in ONE share sheet (#34, report item 34):
  // a single ACTION_SEND_MULTIPLE intent with all files, so History
  // multi-select share shows one chooser instead of one per file.
  fun shareFiles(ctx: Context, files: List<File>, mimeType: String) {
    if (files.isEmpty()) return
    try {
      val uris = ArrayList<android.net.Uri>()
      for (file in files) {
        uris.add(FileProvider.getUriForFile(ctx, "com.chami.pdfmaker.fileprovider", file))
      }
      val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = mimeType
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      ctx.startActivity(Intent.createChooser(intent, "Share via"))
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  fun openFile(ctx: Context, file: File, mimeType: String = "application/pdf") {
    try {
      val uri = FileProvider.getUriForFile(ctx, "com.chami.pdfmaker.fileprovider", file)
      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      // Show a chooser (#5), and tell the user instead of failing
      // silently when nothing on the device can open the file.
      if (intent.resolveActivity(ctx.packageManager) == null) {
        Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
        return
      }
      ctx.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: ActivityNotFoundException) {
      Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }
}

fun sharePdf(ctx: Context, file: File) {
  ShareHelper.shareFile(ctx, file, "application/pdf")
}
