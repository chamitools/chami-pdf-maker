package com.chami.pdfmaker.ui

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * FIX 2026-11-18 (report #40): per-file tool record for History.
 * Tool screens call [record] when they create a file, so History can
 * filter a file by the tool that made it even after the user renames
 * it (the old filename-prefix inference, [historyToolOf], cannot).
 * Stored as a small JSON object {fileName: toolId} in the app's files
 * dir ("history_tools.json"). Tool ids: "img_pdf", "merge", "split",
 * "protect", "compress", "pdf_img", "resize", "convert", "ocr".
 * Best-effort only: any I/O failure is swallowed, callers fall back
 * to the filename-prefix inference.
 */
object HistoryStore {
  private const val FILE_NAME = "history_tools.json"
  private const val MAX_ENTRIES = 500

  private fun storeFile(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

  private fun readAll(ctx: Context): JSONObject {
    return try {
      val f = storeFile(ctx)
      if (!f.exists()) JSONObject() else JSONObject(f.readText())
    } catch (_: Exception) {
      JSONObject()
    }
  }

  private fun writeAll(ctx: Context, json: JSONObject) {
    try {
      // Cap the size so a long history never grows this file unbounded.
      while (json.length() > MAX_ENTRIES) {
        val first = json.keys().next()
        json.remove(first)
      }
      storeFile(ctx).writeText(json.toString())
    } catch (_: Exception) {
      // Best-effort: History falls back to filename inference.
    }
  }

  fun record(ctx: Context, fileName: String, toolId: String) {
    try {
      val json = readAll(ctx)
      json.put(fileName, toolId)
      writeAll(ctx, json)
    } catch (_: Exception) {
      // Best-effort: History falls back to filename inference.
    }
  }

  fun toolFor(ctx: Context, fileName: String): String? {
    return try {
      readAll(ctx).optString(fileName, null)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
      null
    }
  }
}
