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
 *
 * FIX 2026-11-19 (M25): values are "toolId|epochMillis" so the
 * 500-entry cap evicts OLDEST-first instead of an arbitrary record
 * (JSONObject key order is unspecified). Entries written by older
 * versions are plain "toolId" strings with no timestamp — they are
 * treated as oldest (timestamp 0) and still resolve.
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

  /** Split a stored value into (toolId, insertion timestamp). Legacy
   *  plain-"toolId" values have no timestamp and count as oldest (0). */
  private fun parseEntry(raw: String?): Pair<String, Long> {
    if (raw.isNullOrBlank()) return "" to 0L
    val sep = raw.lastIndexOf('|')
    if (sep < 0) return raw to 0L
    val ts = raw.substring(sep + 1).toLongOrNull() ?: 0L
    return raw.substring(0, sep) to ts
  }

  private fun writeAll(ctx: Context, json: JSONObject) {
    try {
      // Cap the size so a long history never grows this file unbounded.
      // Evict OLDEST-first by insertion timestamp (M25); legacy
      // timestamp-less entries count as oldest (0) and go first.
      while (json.length() > MAX_ENTRIES) {
        var oldestKey: String? = null
        var oldestTs = Long.MAX_VALUE
        val keys = json.keys()
        while (keys.hasNext()) {
          val key = keys.next()
          val (_, ts) = parseEntry(json.optString(key, null))
          if (ts < oldestTs) {
            oldestTs = ts
            oldestKey = key
          }
        }
        val victim = oldestKey ?: break
        json.remove(victim)
      }
      storeFile(ctx).writeText(json.toString())
    } catch (_: Exception) {
      // Best-effort: History falls back to filename inference.
    }
  }

  fun record(ctx: Context, fileName: String, toolId: String) {
    try {
      val json = readAll(ctx)
      json.put(fileName, "$toolId|${System.currentTimeMillis()}")
      writeAll(ctx, json)
    } catch (_: Exception) {
      // Best-effort: History falls back to filename inference.
    }
  }

  /** FIX 2026-11-24 (Phase 3): forget a deleted file's record so the
   *  map does not keep names of files that no longer exist. */
  fun remove(ctx: Context, fileName: String) {
    try {
      val json = readAll(ctx)
      if (json.has(fileName)) {
        json.remove(fileName)
        writeAll(ctx, json)
      }
    } catch (_: Exception) {
      // Best-effort.
    }
  }

  /** FIX 2026-11-24 (Phase 3): carry the tool record over when a file is
   *  renamed. The record is keyed by file name, so without this a renamed
   *  file lost its tool and dropped out of its History filter. The
   *  original timestamp is kept. */
  fun rename(ctx: Context, oldName: String, newName: String) {
    try {
      val json = readAll(ctx)
      val raw = json.optString(oldName, null) ?: return
      json.remove(oldName)
      json.put(newName, raw)
      writeAll(ctx, json)
    } catch (_: Exception) {
      // Best-effort.
    }
  }

  fun toolFor(ctx: Context, fileName: String): String? {
    return try {
      parseEntry(readAll(ctx).optString(fileName, null)).first.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
      null
    }
  }

  /** The whole tool map in one disk read — History loads this ONCE per
   *  screen (M19) and looks rows up in memory instead of re-reading
   *  and re-parsing the JSON file for every row on every recomposition. */
  fun toolMap(ctx: Context): Map<String, String> {
    return try {
      val json = readAll(ctx)
      val out = LinkedHashMap<String, String>(json.length())
      val keys = json.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        val (toolId, _) = parseEntry(json.optString(key, null))
        if (toolId.isNotBlank()) out[key] = toolId
      }
      out
    } catch (_: Exception) {
      emptyMap()
    }
  }
}
