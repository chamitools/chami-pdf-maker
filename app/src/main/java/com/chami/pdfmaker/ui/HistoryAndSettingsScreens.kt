package com.chami.pdfmaker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.chami.pdfmaker.BuildConfig
import com.chami.pdfmaker.pdf.PdfEngine
import com.chami.pdfmaker.pdf.PdfReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.chami.pdfmaker.LocaleHelper
import com.chami.pdfmaker.pdf.ShareHelper
import com.chami.pdfmaker.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Phase 4, point 6: History tab — search, filter by tool, sort, first-page
// thumbnails, date groups, multi-select, rename, favourites, storage used.
// Tool is resolved by [historyToolName]: the per-file record in
// HistoryStore first (written at creation, survives a rename), falling
// back to the file-name prefix inference below
// (Chami_ = Images to PDF, Scan_ = Scan, Split_ = Split, Merged_ = Merge,
// Compress_/Compressed_ = Compress, Resized_ = Resizer, protected_ = Protect).
// Files with an unknown prefix and no record only appear under "All".
// Before→after KB is shown for compress results
// recorded via recordCompressResult (SharedPreferences, keyed by output
// file name); other files show only their current size.

private sealed class HistoryEntry {
  data class Header(val title: String) : HistoryEntry()
  data class FileRow(val file: File) : HistoryEntry()
}

private fun historyToolOf(file: File): String {
  val n = file.name
  return when {
    n.startsWith("Chami_") -> "Images to PDF"
    n.startsWith("Scan_") -> "Scan"
    n.startsWith("Split_") -> "Split"
    n.startsWith("Merged_") -> "Merge"
    n.startsWith("Compress_") || n.startsWith("Compressed_") -> "Compress"
    n.startsWith("Resized_") -> "Resizer"
    n.startsWith("protected_") -> "Protect"
    else -> "Other"
  }
}

// FIX 2026-11-18 (report #40): resolve a file's tool from the
// HistoryStore record written at creation first, so a renamed file
// stays filterable; fall back to the filename-prefix inference above
// when no record exists (old files, files from another app).
// FIX 2026-11-19 (M19): the tool map is loaded ONCE per History
// screen (see HistoryScreen's toolMap) and passed in — looking a row
// up here must do NO disk read or JSON parse, so item composition
// stays off the disk.
private fun historyToolName(toolMap: Map<String, String>, file: File): String {
  return when (toolMap[file.name]) {
    "img_pdf" -> "Images to PDF"
    "merge" -> "Merge"
    "split" -> "Split"
    "protect" -> "Protect"
    "compress" -> "Compress"
    "pdf_img" -> "PDF to Images"
    "resize" -> "Resizer"
    "convert" -> "Format Convert"
    "ocr" -> "OCR"
    else -> historyToolOf(file)
  }
}

/** FIX 2026-11-19 (L21): derive the open/share MIME from the file
 *  extension — a PNG/WebP in History was previously handed to viewers
 *  as "image/jpeg". Unknown extensions fall back to
 *  "application/octet-stream" so the viewer still gets a real file. */
private fun mimeForFile(file: File): String {
  return when (file.extension.lowercase(Locale.getDefault())) {
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
  }
}

private fun historyDateBucket(lastModified: Long): String {
  val cal = java.util.Calendar.getInstance()
  val today = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
  }.timeInMillis
  val day = 24L * 60 * 60 * 1000
  return when {
    lastModified >= today -> "Today"
    lastModified >= today - day -> "Yesterday"
    lastModified >= today - 7 * day -> "This week"
    else -> "Older"
  }
}

private fun formatKb(bytes: Long): String {
  return if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / (1024f * 1024f))
  else "${bytes / 1024} KB"
}

private fun formatKbFromKb(kb: Long): String {
  return if (kb >= 1024) "%.1f MB".format(kb / 1024f) else "$kb KB"
}

/** App Lock PIN hashing (salted SHA-256, never plain text).
 *  FIX 2026-11-19 (L23): the old unsalted SHA-256 of a 4-digit PIN was
 *  trivially brute-forceable offline (10,000 possibilities). New PINs
 *  are stored as "sha256:<saltHex>:<hex>" where the salt is 16 random
 *  bytes and the hash covers salt + PIN. Legacy "sha256:<hex>" hashes
 *  (and plain 4-digit PINs) are still accepted by [pinMatches] and are
 *  re-hashed with a salt on the next successful unlock (see
 *  [isLegacyPinHash] and the verify dialog / MainActivity migration). */
fun hashPin(pin: String): String {
  val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
  return "sha256:" + salt.toHex() + ":" + hashPinWithSalt(pin, salt)
}

private fun ByteArray.toHex(): String =
  joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun String.hexToBytes(): ByteArray? {
  if (length % 2 != 0) return null
  return try {
    ByteArray(length / 2) { i ->
      ((substring(i * 2, i * 2 + 2).toInt(16)) and 0xff).toByte()
    }
  } catch (_: Exception) {
    null
  }
}

private fun hashPinWithSalt(pin: String, salt: ByteArray): String {
  val digest = MessageDigest.getInstance("SHA-256")
  digest.update(salt)
  val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
  return hash.toHex()
}

/** True when [stored] is a pre-salt hash (or a plain PIN) that should
 *  be re-hashed with a salt after a successful unlock. */
fun isLegacyPinHash(stored: String?): Boolean {
  if (stored.isNullOrBlank()) return false
  if (!stored.startsWith("sha256:")) return true // legacy plain PIN
  return stored.split(":").size == 2 // unsalted "sha256:<hex>"
}

/** Accepts a salted "sha256:<salt>:<hex>" hash, a legacy unsalted
 *  "sha256:<hex>" hash, or a legacy plain 4-digit PIN (migrated on
 *  next unlock). */
fun pinMatches(stored: String?, input: String): Boolean {
  if (stored.isNullOrBlank() || input.length != 4) return false
  if (!stored.startsWith("sha256:")) return stored == input
  val parts = stored.split(":")
  return when (parts.size) {
    3 -> {
      val salt = parts[1].hexToBytes() ?: return false
      stored == "sha256:${parts[1]}:${hashPinWithSalt(input, salt)}"
    }
    2 -> stored == "sha256:" + MessageDigest.getInstance("SHA-256")
      .digest(input.toByteArray(Charsets.UTF_8)).toHex()
    else -> false
  }
}

private const val COMPRESS_RESULTS_PREFS = "chami_compress_results"

/**
 * Record a compress result so History can show before→after KB.
 * Call from the Compress screen when a compress finishes. Keyed by
 * output file name; values are KB (before|after).
 */
fun recordCompressResult(ctx: Context, outputName: String, beforeKb: Long, afterKb: Long) {
  ctx.getSharedPreferences(COMPRESS_RESULTS_PREFS, Context.MODE_PRIVATE)
    .edit().putString(outputName, "$beforeKb|$afterKb").apply()
}

private fun readCompressResult(ctx: Context, outputName: String): Pair<Long, Long>? {
  val raw = ctx.getSharedPreferences(COMPRESS_RESULTS_PREFS, Context.MODE_PRIVATE)
    .getString(outputName, null) ?: return null
  val parts = raw.split("|")
  if (parts.size != 2) return null
  val before = parts[0].toLongOrNull() ?: return null
  val after = parts[1].toLongOrNull() ?: return null
  return before to after
}

private fun dirSizeBytes(dir: File): Long {
  if (!dir.exists()) return 0L
  return try { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Exception) { 0L }
}

/** In-memory first-page thumbnail cache, keyed by file path (max ~40
 *  entries, LRU). Rows render from the cache instantly instead of
 *  popping in one by one with a placeholder. */
private object HistoryThumbCache {
  private const val MAX_ENTRIES = 40
  private val map = object : LinkedHashMap<String, Bitmap>(MAX_ENTRIES, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
      return size > MAX_ENTRIES
    }
  }

  @Synchronized fun get(path: String): Bitmap? = map[path]
  @Synchronized fun put(path: String, bmp: Bitmap) { map[path] = bmp }
}

@Composable
private fun HistoryThumb(file: File, theme: ChamiTheme) {
  val ctx = LocalContext.current
  val isPdf = file.name.endsWith(".pdf", ignoreCase = true)
  // Render from the in-memory cache first — no pop-in on revisit.
  var bmp by remember(file.absolutePath) { mutableStateOf(HistoryThumbCache.get(file.absolutePath)) }
  LaunchedEffect(file.absolutePath) {
    if (bmp == null) {
      withContext(Dispatchers.IO) {
        val uri = Uri.fromFile(file)
        // Whole first page, fitted (never a cropped square): render at
        // a modest scale and letterbox with ContentScale.Fit.
        val b = if (isPdf) {
          PdfReader.renderPageBitmap(ctx, uri, 0, 0.5f)
        } else {
          PdfEngine.loadBitmap(ctx, uri, 200)
        }
        if (b != null) {
          HistoryThumbCache.put(file.absolutePath, b)
          withContext(Dispatchers.Main) { bmp = b }
        }
      }
    }
  }
  Box(
    Modifier
      .size(width = 48.dp, height = 64.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(theme.bg)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(8.dp)),
    contentAlignment = Alignment.Center
  ) {
    val b = bmp
    // PERFORMANCE (UI overhaul): wrap once per bitmap — rows recompose
    // on scroll/selection and re-wrapping every thumbnail each time
    // dropped frames.
    val thumbImage = remember(b) { b?.asImageBitmap() }
    if (thumbImage != null) {
      Image(
        bitmap = thumbImage,
        contentDescription = "First page of ${file.name}",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit
      )
    } else {
      // Real vector icon while the thumbnail loads — never an emoji.
      Icon(
        if (isPdf) Icons.Filled.Description else Icons.Filled.Image,
        contentDescription = null,
        tint = theme.textSecondary,
        modifier = Modifier.size(24.dp)
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
  theme: ChamiTheme,
  // FIX 2026-11-18 (Fix 9): required, NOT nullable — a PDF tap in
  // History must ALWAYS open the inbuilt PDF Reader. The old nullable
  // default silently fell back to an external viewer whenever a caller
  // forgot the callback; that fallback is gone for PDFs (images still
  // open externally, the Reader is PDF-only).
  onOpenInReader: (File) -> Unit,
  listState: LazyListState = rememberLazyListState(),
) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  // Phase 6 Font Size: "font_scale" in chami_prefs (0.85 / 1.0 / 1.15).
  // Main History text sizes are multiplied by it (read fresh each
  // composition so a change in Settings applies on the next visit).
  val fontScale = ctx.getSharedPreferences(LocaleHelper.PREFS, Context.MODE_PRIVATE)
    .getFloat("font_scale", 1.0f)
  val favPrefs = remember { ctx.getSharedPreferences("chami_history_favs", Context.MODE_PRIVATE) }
  // Phase 5 FLICKER FIX: the file list is loaded SYNCHRONOUSLY here,
  // before the first composition, instead of starting empty and filling
  // in from LaunchedEffect. The screen therefore never shows the
  // "No Documents Found" empty state for a millisecond when files exist
  // — the first frame already has the real list (or the true empty state).
  // `loaded` stays as a guard: until the first refresh completes, show
  // a blank background, never the empty state.
  fun loadFilesNow(): List<File> {
    val dir = File(ctx.getExternalFilesDir(null), "Chami")
    return if (dir.exists()) dir.listFiles()?.filter { it.isFile } ?: emptyList() else emptyList()
  }
  var fileList by remember { mutableStateOf(loadFilesNow()) }
  var storageUsed by remember { mutableStateOf(fileList.sumOf { it.length() }) }
  var loaded by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  var filter by remember { mutableStateOf("All") }
  var sortMode by remember { mutableStateOf("Newest") }
  var sortMenuOpen by remember { mutableStateOf(false) }
  var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
  var favourites by remember {
    mutableStateOf(favPrefs.getStringSet("favs", emptySet())?.toSet() ?: emptySet())
  }
  var fileToDelete by remember { mutableStateOf<File?>(null) }
  var filesToDelete by remember { mutableStateOf<List<File>>(emptyList()) }
  var fileToRename by remember { mutableStateOf<File?>(null) }
  var renameText by remember { mutableStateOf("") }
  var menuFor by remember { mutableStateOf<String?>(null) }
  var pageCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

  // FIX 2026-11-19 (M19): the HistoryStore tool map is loaded ONCE per
  // screen, off the main thread, and rows look it up in memory — no
  // disk read or JSON parse inside item composition. Reloads when the
  // file list changes (refresh / delete / rename).
  val toolMap by produceState<Map<String, String>>(emptyMap(), fileList) {
    value = withContext(Dispatchers.IO) { HistoryStore.toolMap(ctx) }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
      } catch (_: Exception) {}
    }
  }

  fun refresh() {
    val dir = File(ctx.getExternalFilesDir(null), "Chami")
    if (dir.exists()) {
      val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
      fileList = files
      storageUsed = files.sumOf { it.length() }
    } else {
      fileList = emptyList()
      storageUsed = 0L
    }
    loaded = true
  }

  LaunchedEffect(Unit) { refresh() }

  // Page counts are loaded off the main thread, in parallel, without
  // blocking the list — rows show their metadata as counts arrive.
  LaunchedEffect(fileList) {
    withContext(Dispatchers.IO) {
      val counts = fileList
        .filter { it.name.endsWith(".pdf", ignoreCase = true) }
        .map { f -> async { f.absolutePath to PdfEngine.pageCount(ctx, Uri.fromFile(f)) } }
        .awaitAll()
        .filter { it.second > 0 }
        .toMap()
      withContext(Dispatchers.Main) { pageCounts = counts }
    }
  }

  fun toggleFavourite(file: File) {
    val next = if (favourites.contains(file.absolutePath)) favourites - file.absolutePath
    else favourites + file.absolutePath
    favourites = next
    favPrefs.edit().putStringSet("favs", next).apply()
  }

  fun deleteFiles(files: List<File>) {
    files.forEach { it.delete() }
    selected = emptySet()
    fileToDelete = null
    filesToDelete = emptyList()
    refresh()
    Toast.makeText(ctx, "File deleted", Toast.LENGTH_SHORT).show()
  }

  val sdf = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

  // Search + filter + favourite-first + sort. Tool filter uses the
  // HistoryStore record first, falling back to the file-name prefix
  // (see historyToolName); "PDF to Images", "Reader" and
  // "OCR" write no files into the Chami folder, so those chips are
  // honestly empty unless such a file exists.
  val visible = remember(fileList, query, filter, sortMode, favourites, toolMap) {
    var list = fileList.filter { f ->
      (query.isBlank() || f.name.contains(query, ignoreCase = true)) &&
        (filter == "All" || historyToolName(toolMap, f) == filter)
    }
    list = when (sortMode) {
      "Oldest" -> list.sortedBy { it.lastModified() }
      "Biggest" -> list.sortedByDescending { it.length() }
      "Name" -> list.sortedBy { it.name.lowercase(Locale.getDefault()) }
      else -> list.sortedByDescending { it.lastModified() }
    }
    // Favourites / pinned files always come first.
    list.sortedByDescending { favourites.contains(it.absolutePath) }
  }

  // Group headers only for date sorts; Name/Biggest stay a flat list.
  val entries: List<HistoryEntry> = remember(visible, sortMode) {
    if (sortMode == "Name" || sortMode == "Biggest") {
      visible.map { HistoryEntry.FileRow(it) }
    } else {
      val out = mutableListOf<HistoryEntry>()
      var lastBucket: String? = null
      visible.forEach { f ->
        val bucket = historyDateBucket(f.lastModified())
        if (bucket != lastBucket) {
          out.add(HistoryEntry.Header(bucket))
          lastBucket = bucket
        }
        out.add(HistoryEntry.FileRow(f))
      }
      out
    }
  }

  // Filter chips: All + one chip per tool that can actually produce a
  // file (and has produced one here). PDF to Images / Reader / OCR
  // write no files into the Chami folder, so those dead chips are gone.
  // FIX 2026-11-18 (report #37): "Scan" removed — Scan was removed
  // in Run #150, so there is no Scan chip any more.
  val toolOrder = listOf(
    "Images to PDF", "Merge", "Split", "Protect", "Compress", "Resizer"
  )
  val filterChips = remember(fileList, toolMap) {
    val present = fileList.map { historyToolName(toolMap, it) }.toSet()
    listOf("All") + toolOrder.filter { it in present }
  }
  val multiSelect = selected.isNotEmpty()

  Column(
    Modifier
      .fillMaxSize()
      .padding(Ds.s16)
  ) {
    // Title — Ds typography scale, multiplied by the Font Size setting.
    Text(
      text = LocaleHelper.t(ctx, "history"),
      fontFamily = PixelFont,
      fontWeight = FontWeight.ExtraBold,
      fontSize = (Ds.TITLE_SP * fontScale).sp,
      lineHeight = (Ds.TITLE_SP * 1.4 * fontScale).sp,
      color = theme.text
    )
    Text(
      text = "${fileList.size} documents saved",
      fontSize = (Ds.HINT_SP * fontScale).sp,
      lineHeight = (Ds.HINT_SP * 1.4 * fontScale).sp,
      color = theme.textSecondary
    )

    Spacer(Modifier.height(Ds.s12))

    // Storage used — real folder size, stated plainly.
    // UI OVERHAUL 2026-11-18: the old progress bar is REMOVED — it
    // compared folder size against folder size + free space, so it
    // always read ~0% and looked broken to a reviewer. Honest text
    // only, no fake bar.
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        "${formatKb(storageUsed)} used in Chami folder",
        fontSize = (Ds.HINT_SP * fontScale).sp,
        color = theme.textSecondary
      )
    }

    Spacer(Modifier.height(Ds.s12))

    // Search by file name.
    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      placeholder = {
        Text("Search files…", fontSize = (Ds.HINT_SP * fontScale).sp, color = theme.textSecondary)
      },
      leadingIcon = {
        Icon(Icons.Filled.Search, contentDescription = "Search", tint = theme.textSecondary)
      },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().height(Ds.minTouch),
      shape = RoundedCornerShape(Ds.buttonRadius),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = theme.primary,
        unfocusedBorderColor = theme.cardBorder,
        focusedContainerColor = theme.card,
        unfocusedContainerColor = theme.card
      )
    )

    Spacer(Modifier.height(Ds.s8))

    // Filter by tool (horizontal chip row, 48dp targets).
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
      items(filterChips) { chip ->
        ChamiChip(
          label = chip,
          selected = filter == chip,
          onClick = { vibrate(); filter = chip }
        )
      }
    }

    Spacer(Modifier.height(Ds.s8))

    // Sort + refresh row.
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box {
        OutlinedButton(
          onClick = { vibrate(); sortMenuOpen = true },
          shape = RoundedCornerShape(Ds.buttonRadius),
          border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.primary),
          modifier = Modifier.height(Ds.minTouch),
          contentPadding = PaddingValues(horizontal = Ds.s12, vertical = Ds.s8)
        ) {
          Text("SORT: $sortMode", fontFamily = PixelFont, fontSize = (Ds.HINT_SP * fontScale).sp, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(
          expanded = sortMenuOpen,
          onDismissRequest = { sortMenuOpen = false }
        ) {
          listOf("Newest", "Oldest", "Biggest", "Name").forEach { mode ->
            DropdownMenuItem(
              text = {
                Text(mode, fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, color = theme.text)
              },
              onClick = { sortMode = mode; sortMenuOpen = false }
            )
          }
        }
      }
      OutlinedButton(
        onClick = { vibrate(); refresh() },
        shape = RoundedCornerShape(Ds.buttonRadius),
        border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.primary),
        modifier = Modifier.height(Ds.minTouch),
        contentPadding = PaddingValues(horizontal = Ds.s12, vertical = Ds.s8)
      ) {
        Text("REFRESH", fontFamily = PixelFont, fontSize = (Ds.HINT_SP * fontScale).sp, fontWeight = FontWeight.Bold)
      }
    }

    // Multi-select action bar.
    if (multiSelect) {
      Spacer(Modifier.height(8.dp))
      Row(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(theme.primary.copy(alpha = 0.12f))
          .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          "${selected.size} selected",
          fontFamily = PixelFont,
          fontSize = Ds.HINT_SP.sp,
          fontWeight = FontWeight.Bold,
          color = theme.text,
          modifier = Modifier.weight(1f)
        )
        Button(
          onClick = {
            vibrate()
            // FIX 2026-11-18 (report #34): ONE share sheet for all
            // selected files (ACTION_SEND_MULTIPLE via
            // ShareHelper.shareFiles), not one shareFile call per file.
            val files = fileList.filter { selected.contains(it.absolutePath) }
            val mime = if (files.all { it.name.endsWith(".pdf", ignoreCase = true) }) "application/pdf"
              else if (files.all { it.name.endsWith(".jpg", ignoreCase = true) || it.name.endsWith(".jpeg", ignoreCase = true) || it.name.endsWith(".png", ignoreCase = true) }) "image/*"
              else "*/*"
            ShareHelper.shareFiles(ctx, files, mime)
          },
          // UI OVERHAUL 2026-11-18: 48dp minimum touch target — the
          // old small-padding buttons were ~36dp tall.
          modifier = Modifier.defaultMinSize(minHeight = Ds.minTouch),
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
          Text("SHARE", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
        }
        Button(
          onClick = {
            vibrate()
            filesToDelete = fileList.filter { selected.contains(it.absolutePath) }
          },
          // UI OVERHAUL 2026-11-18: 48dp minimum touch target (see SHARE above).
          modifier = Modifier.defaultMinSize(minHeight = Ds.minTouch),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
          Text("DELETE", fontFamily = PixelFont, color = Color.White, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { selected = emptySet() }) {
          Text("CANCEL", fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, color = theme.text)
        }
      }
    }

    Spacer(Modifier.height(10.dp))

    if (!loaded) {
      // Loading: blank background, NOT the empty state (flicker fix).
      Box(Modifier.fillMaxSize().background(theme.bg))
    } else if (visible.isEmpty()) {
      Box(
        Modifier
          .fillMaxSize()
          .clip(RoundedCornerShape(16.dp))
          .background(theme.card)
          .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
          .padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Box(
            Modifier
              .size(64.dp)
              .clip(CircleShape)
              .background(theme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              Icons.Filled.Folder,
              contentDescription = null,
              tint = theme.primary,
              modifier = Modifier.size(32.dp)
            )
          }
          Spacer(Modifier.height(14.dp))
          Text(
            "No Documents Found",
            fontFamily = PixelFont,
            fontWeight = FontWeight.Bold,
            fontSize = (Ds.BODY_SP * fontScale).sp,
            color = theme.text
          )
          Spacer(Modifier.height(6.dp))
          Text(
            if (query.isNotBlank() || filter != "All") "No files match this search or filter."
            else "Created PDFs and converted files will appear here.",
            fontFamily = PixelFont,
            fontSize = Ds.HINT_SP.sp,
            color = theme.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
        }
      }
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Ds.s12)
      ) {
        items(entries, key = { entry ->
          when (entry) {
            is HistoryEntry.Header -> "header:${entry.title}"
            is HistoryEntry.FileRow -> entry.file.absolutePath
          }
        }) { entry ->
          when (entry) {
            is HistoryEntry.Header -> {
              Text(
                entry.title,
                fontFamily = PixelFont,
                fontWeight = FontWeight.Bold,
                fontSize = (Ds.BODY_SP * fontScale).sp,
                lineHeight = (Ds.BODY_SP * 1.4 * fontScale).sp,
                color = theme.primary,
                modifier = Modifier.padding(top = Ds.s4)
              )
            }
            is HistoryEntry.FileRow -> {
              val file = entry.file
              val isPdf = file.name.endsWith(".pdf", ignoreCase = true)
              val mime = mimeForFile(file)
              val isSel = selected.contains(file.absolutePath)
              val isFav = favourites.contains(file.absolutePath)
              val pages = pageCounts[file.absolutePath]
              val compressResult = if (historyToolName(toolMap, file) == "Compress") {
                readCompressResult(ctx, file.name)
              } else null
              Column(
                Modifier
                  .fillMaxWidth()
                  .defaultMinSize(minHeight = Ds.listRowMinHeight)
                  .clip(RoundedCornerShape(Ds.tileRadius))
                  .background(if (isSel) theme.primary.copy(alpha = 0.12f) else theme.card)
                  .border(
                    1.dp,
                    if (isSel) theme.primary else theme.cardBorder,
                    RoundedCornerShape(Ds.tileRadius)
                  )
                  .combinedClickable(
                    onClick = {
                      vibrate()
                      if (multiSelect) {
                        selected = if (isSel) selected - file.absolutePath
                        else selected + file.absolutePath
                      } else if (isPdf) {
                        // Tap opens the file in the inbuilt PDF Reader.
                        onOpenInReader(file)
                      } else {
                        ShareHelper.openFile(ctx, file, mime)
                      }
                    },
                    onLongClick = {
                      vibrate()
                      selected = if (isSel) selected - file.absolutePath
                      else selected + file.absolutePath
                    }
                  )
                  .padding(Ds.s16)
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  if (multiSelect) {
                    Checkbox(
                      checked = isSel,
                      onCheckedChange = {
                        selected = if (isSel) selected - file.absolutePath
                        else selected + file.absolutePath
                      },
                      colors = CheckboxDefaults.colors(checkedColor = theme.primary)
                    )
                    Spacer(Modifier.width(Ds.s8))
                  }
                  HistoryThumb(file, theme)
                  Spacer(Modifier.width(Ds.s12))
                  Column(Modifier.weight(1f)) {
                    Text(
                      file.name,
                      fontFamily = PixelFont,
                      fontWeight = FontWeight.Bold,
                      fontSize = (Ds.BODY_SP * fontScale).sp,
                      lineHeight = (Ds.BODY_SP * 1.4 * fontScale).sp,
                      color = theme.text,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                      buildString {
                        if (pages != null) append("$pages pages • ")
                        append(formatKb(file.length()))
                        append(" • ${sdf.format(Date(file.lastModified()))}")
                      },
                      fontSize = (Ds.META_SP * fontScale).sp,
                      lineHeight = (Ds.META_SP * 1.4 * fontScale).sp,
                      color = theme.textSecondary
                    )
                    if (compressResult != null) {
                      Spacer(Modifier.height(3.dp))
                      Text(
                        "${formatKbFromKb(compressResult.first)} → ${formatKbFromKb(compressResult.second)}",
                        fontSize = (Ds.META_SP * fontScale).sp,
                        lineHeight = (Ds.META_SP * 1.4 * fontScale).sp,
                        // UI OVERHAUL 2026-11-18: primaryDark, not
                        // primary — 12sp #16A34A on white is ~3:1 and
                        // fails WCAG AA (4.5:1); #14532D passes.
                        color = theme.primaryDark
                      )
                    }
                  }
                  // Favourite / pin (star, persisted in SharedPreferences).
                  ChamiIconButton(
                    icon = if (isFav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    description = if (isFav) "Remove favourite" else "Favourite",
                    onClick = { vibrate(); toggleFavourite(file) },
                    tint = if (isFav) theme.primary else theme.textSecondary
                  )
                  // Row menu: rename / share / delete.
                  Box {
                    ChamiIconButton(
                      icon = Icons.Filled.MoreVert,
                      description = "More options",
                      onClick = { menuFor = file.absolutePath }
                    )
                    DropdownMenu(
                      expanded = menuFor == file.absolutePath,
                      onDismissRequest = { menuFor = null }
                    ) {
                      DropdownMenuItem(
                        text = { Text("Rename", fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, color = theme.text) },
                        onClick = {
                          menuFor = null
                          renameText = file.nameWithoutExtension
                          fileToRename = file
                        }
                      )
                      DropdownMenuItem(
                        text = { Text("Share", fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, color = theme.text) },
                        onClick = {
                          menuFor = null
                          ShareHelper.shareFile(ctx, file, mime)
                        }
                      )
                      DropdownMenuItem(
                        text = { Text("Open", fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, color = theme.text) },
                        onClick = {
                          menuFor = null
                          if (isPdf) onOpenInReader(file)
                          else ShareHelper.openFile(ctx, file, mime)
                        }
                      )
                      DropdownMenuItem(
                        text = { Text("Delete", fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, color = MaterialTheme.colorScheme.error) },
                        onClick = { menuFor = null; fileToDelete = file }
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    // Delete one file (from the row menu).
    if (fileToDelete != null) {
      ChamiAlertDialog(theme,
        onDismissRequest = { fileToDelete = null },
        confirmButton = {
          Button(
            onClick = {
              vibrate()
              fileToDelete?.let { deleteFiles(listOf(it)) }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
          ) {
            Text("DELETE", fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
          }
        },
        dismissButton = {
          TextButton(onClick = { fileToDelete = null }) {
            Text("CANCEL", fontFamily = PixelFont, color = theme.text)
          }
        },
        title = {
          Text("Delete Document?", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = Ds.SECTION_SP.sp)
        },
        text = {
          Text(
            "Permanently delete \"${fileToDelete?.name}\"? This action cannot be undone.",
            fontFamily = PixelFont,
            fontSize = Ds.BODY_SP.sp,
            color = theme.textSecondary
          )
        }
      )
    }

    // Delete many files (multi-select bar).
    if (filesToDelete.isNotEmpty()) {
      ChamiAlertDialog(theme,
        onDismissRequest = { filesToDelete = emptyList() },
        confirmButton = {
          Button(
            onClick = { vibrate(); deleteFiles(filesToDelete) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
          ) {
            Text("DELETE ${filesToDelete.size}", fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
          }
        },
        dismissButton = {
          TextButton(onClick = { filesToDelete = emptyList() }) {
            Text("CANCEL", fontFamily = PixelFont, color = theme.text)
          }
        },
        title = {
          Text("Delete ${filesToDelete.size} files?", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = Ds.SECTION_SP.sp)
        },
        text = {
          Text(
            "These files will be permanently deleted. This action cannot be undone.",
            fontFamily = PixelFont,
            fontSize = Ds.BODY_SP.sp,
            color = theme.textSecondary
          )
        }
      )
    }

    // Rename a file (same folder, same extension).
    if (fileToRename != null) {
      ChamiAlertDialog(theme,
        onDismissRequest = { fileToRename = null },
        confirmButton = {
          Button(
            onClick = {
              vibrate()
              val f = fileToRename
              if (f != null && renameText.isNotBlank()) {
                // FIX 2026-11-18 (report #39): strip a trailing
                // extension the user typed ("report.pdf" -> "report")
                // before appending "." + extension, so the result is
                // never "report.pdf.pdf".
                val typed = renameText.trim()
                val base = if (typed.endsWith("." + f.extension, ignoreCase = true)) {
                  typed.dropLast(f.extension.length + 1)
                } else typed
                val newName = base + "." + f.extension
                if (base.isBlank()) {
                  Toast.makeText(ctx, "Enter a file name", Toast.LENGTH_SHORT).show()
                } else {
                  val newFile = File(f.parentFile, newName)
                  if (newFile.exists() && newFile.absolutePath != f.absolutePath) {
                    Toast.makeText(ctx, "A file with that name already exists", Toast.LENGTH_SHORT).show()
                  } else if (f.renameTo(newFile)) {
                    Toast.makeText(ctx, "Renamed", Toast.LENGTH_SHORT).show()
                    fileToRename = null
                    refresh()
                  } else {
                    Toast.makeText(ctx, "Could not rename", Toast.LENGTH_SHORT).show()
                  }
                }
              }
            },
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
          ) {
            Text("RENAME", fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
          }
        },
        dismissButton = {
          TextButton(onClick = { fileToRename = null }) {
            Text("CANCEL", fontFamily = PixelFont, color = theme.text)
          }
        },
        title = {
          Text("Rename file", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = Ds.SECTION_SP.sp)
        },
        text = {
          OutlinedTextField(
            value = renameText,
            onValueChange = { renameText = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
        }
      )
    }

  }
}

@Composable
fun SettingsScreen(
  theme: ChamiTheme,
  tm: ThemeManager,
  onThemeChanged: (ChamiTheme) -> Unit
) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  // THEME picker removed entirely (Phase 6 lock): the theme is forced to
  // Executive Emerald in MainActivity. onThemeChanged is kept only so the
  // existing call site still compiles; it is never used.
  val prefs = remember { ctx.getSharedPreferences(LocaleHelper.PREFS, Context.MODE_PRIVATE) }
  var fontScale by remember { mutableStateOf(prefs.getFloat("font_scale", 1.0f)) }

  // Migrate a legacy plain-text PIN to its SHA-256 hash, once.
  LaunchedEffect(Unit) {
    val stored = prefs.getString("app_pin", null)
    if (stored != null && !stored.startsWith("sha256:") && stored.length == 4 &&
      stored.all { it.isDigit() }
    ) {
      prefs.edit().putString("app_pin", hashPin(stored)).apply()
    }
  }

  var showClearCacheDialog by remember { mutableStateOf(false) }
  var showPrivacyPolicyFallback by remember { mutableStateOf(false) }
  var showLanguageDialog by remember { mutableStateOf(false) }
  var showFontSizeDialog by remember { mutableStateOf(false) }
  var showPinSetupDialog by remember { mutableStateOf(false) }
  // FIX 2026-11-18 (report #41): turning App Lock OFF requires the
  // current PIN first — these drive the verify dialog below, separate
  // from the set-a-new-PIN setup dialog.
  var showPinVerifyDialog by remember { mutableStateOf(false) }
  var pinVerifyText by remember { mutableStateOf("") }
  var pinVerifyError by remember { mutableStateOf("") }
  var showUpdateDialog by remember { mutableStateOf(false) }
  var showAboutDialog by remember { mutableStateOf(false) }
  var pinStep by remember { mutableStateOf(0) } // 0 = enter PIN, 1 = confirm it
  var pinFirst by remember { mutableStateOf("") }
  var pinSetupText by remember { mutableStateOf("") }
  var pinSetupError by remember { mutableStateOf("") }
  var appLockOn by remember { mutableStateOf(prefs.getBoolean("app_lock", false)) }
  var previewFontScale by remember { mutableStateOf(fontScale) }

  val chamiDir = remember { File(ctx.getExternalFilesDir(null), "Chami") }
  // FIX 2026-11-19 (M20): folder sizes are computed off the main
  // thread — walking the whole file tree at first composition blocked
  // the first frame. null = still calculating; the rows show a
  // "Calculating…" placeholder until the values arrive.
  val settingsScope = rememberCoroutineScope()
  var storageUsedBytes by remember { mutableStateOf<Long?>(null) }
  var cacheBytes by remember { mutableStateOf<Long?>(null) }
  fun refreshSizes() {
    storageUsedBytes = null
    cacheBytes = null
    settingsScope.launch {
      val sizes = withContext(Dispatchers.IO) {
        dirSizeBytes(chamiDir) to dirSizeBytes(ctx.cacheDir)
      }
      storageUsedBytes = sizes.first
      cacheBytes = sizes.second
    }
  }
  LaunchedEffect(Unit) { refreshSizes() }

  fun vibrate() {
    try {
      haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    } catch (_: Exception) {}
  }

  fun openSupportEmail() {
    try {
      val subject = Uri.encode("Chami PDF Maker ${BuildConfig.VERSION_NAME} — Support")
      val intent = Intent(
        Intent.ACTION_SENDTO,
        Uri.parse("mailto:chami.support@gmail.com?subject=$subject")
      )
      ctx.startActivity(intent)
    } catch (_: Exception) {
      Toast.makeText(ctx, "No email app found", Toast.LENGTH_SHORT).show()
    }
  }

  fun openPrivacyPolicy() {
    try {
      val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://flemax369-sketch.github.io/chami-privacy/")
      )
      ctx.startActivity(intent)
    } catch (_: Exception) {
      // No browser available — fall back to the bundled copy.
      showPrivacyPolicyFallback = true
    }
  }

  fun checkForUpdates() {
    try {
      val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=${ctx.packageName}")
      )
      if (intent.resolveActivity(ctx.packageManager) != null) {
        ctx.startActivity(intent)
      } else {
        showUpdateDialog = true
      }
    } catch (_: Exception) {
      showUpdateDialog = true
    }
  }

  val fontOptions = listOf(
    "Small" to 0.85f,
    "Default" to 1.0f,
    "Large" to 1.15f
  )

  @Composable
  fun SectionHeading(text: String) {
    Text(
      text,
      fontFamily = PixelFont,
      fontWeight = FontWeight.Bold,
      fontSize = (Ds.SECTION_SP * fontScale).sp,
      lineHeight = (Ds.SECTION_SP * 1.4 * fontScale).sp,
      color = theme.primaryDark,
      modifier = Modifier.padding(top = Ds.s24, bottom = Ds.s12)
    )
  }

  @Composable
  fun SettingsRow(
    title: String,
    value: String? = null,
    showSwitch: Boolean = false,
    switchOn: Boolean = false,
    onSwitchChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = Ds.listRowMinHeight)
        .clip(RoundedCornerShape(Ds.tileRadius))
        .clickable { vibrate(); onClick() }
        .padding(horizontal = Ds.s16, vertical = Ds.s12),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        title,
        fontSize = (Ds.BODY_SP * fontScale).sp,
        lineHeight = (Ds.BODY_SP * 1.4 * fontScale).sp,
        fontWeight = FontWeight.SemiBold,
        color = theme.text,
        // UI OVERHAUL 2026-11-18: long titles wrap to 2 lines max at
        // Large font instead of pushing the chevron/value off-screen.
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
      )
      if (showSwitch) {
        Switch(
          checked = switchOn,
          onCheckedChange = { onSwitchChange?.invoke(it) },
          colors = SwitchDefaults.colors(
            checkedThumbColor = theme.textOnPrimary,
            checkedTrackColor = theme.primary
          )
        )
      } else {
        if (value != null) {
          Text(
            value,
            fontSize = (Ds.HINT_SP * fontScale).sp,
            lineHeight = (Ds.HINT_SP * 1.4 * fontScale).sp,
            color = theme.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(Modifier.width(Ds.s8))
        }
        Icon(
          Icons.Filled.ChevronRight,
          contentDescription = null,
          tint = theme.textSecondary,
          modifier = Modifier.size(24.dp)
        )
      }
    }
  }

  @Composable
  fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(Ds.tileRadius))
        .background(theme.card)
        .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
        .padding(Ds.s4),
      verticalArrangement = Arrangement.spacedBy(Ds.s4),
      content = content
    )
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(Ds.s16)
      .verticalScroll(rememberScrollState())
  ) {
    Text(
      text = LocaleHelper.t(ctx, "settings"),
      fontFamily = PixelFont,
      fontWeight = FontWeight.ExtraBold,
      fontSize = (Ds.TITLE_SP * fontScale).sp,
      lineHeight = (Ds.TITLE_SP * 1.4 * fontScale).sp,
      color = theme.text
    )

    // FIX 2026-11-18 (Fix 8): the duplicate CHAMI PRO header card is
    // REMOVED. CHAMI PRO now appears exactly once in Settings: the top
    // header above the tabs (MainActivity — panda logo + CHAMI + green
    // PRO pill + "Professional PDF Toolkit"). The version shown here
    // also lives in the About card below, so nothing is lost.

    // M20: while the folder sizes are still being computed off the
    // main thread, show a placeholder instead of a stale "0 KB".
    val calculatingText = LocaleHelper.t(ctx, "calculating_sizes")
    val storageValue = if (storageUsedBytes == null || cacheBytes == null) {
      calculatingText
    } else {
      "${formatKb(storageUsedBytes!!)} used • ${formatKb(cacheBytes!!)} cache"
    }
    val cacheValue = cacheBytes?.let { formatKb(it) } ?: calculatingText

    // ---- General ----
    SectionHeading("General")
    SettingsCard {
      SettingsRow(
        title = LocaleHelper.t(ctx, "language"),
        value = LocaleHelper.LANGUAGES.firstOrNull { it.first == LocaleHelper.currentCode(ctx) }?.second,
        onClick = { showLanguageDialog = true }
      )
      // FIX 2026-11-18 (report #42): say plainly what the language
      // switch translates, so it is not sold as a full switch.
      Text(
        "Language changes Home, tool names and slide buttons. Tool screens stay in English.",
        fontSize = (Ds.HINT_SP * fontScale).sp,
        lineHeight = (Ds.HINT_SP * 1.4 * fontScale).sp,
        color = theme.textSecondary,
        modifier = Modifier.padding(horizontal = Ds.s16, vertical = Ds.s8)
      )
      SettingsRow(
        title = LocaleHelper.t(ctx, "font_size"),
        value = fontOptions.firstOrNull { it.second == fontScale }?.first,
        onClick = { previewFontScale = fontScale; showFontSizeDialog = true }
      )
      SettingsRow(
        title = LocaleHelper.t(ctx, "storage"),
        value = storageValue,
        onClick = { refreshSizes() }
      )
      SettingsRow(
        title = LocaleHelper.t(ctx, "clear_cache"),
        value = cacheValue,
        onClick = { refreshSizes(); showClearCacheDialog = true }
      )
      SettingsRow(
        title = LocaleHelper.t(ctx, "check_updates"),
        value = BuildConfig.VERSION_NAME,
        onClick = { checkForUpdates() }
      )
    }

    // ---- Security ----
    SectionHeading("Security")
    SettingsCard {
      SettingsRow(
        title = LocaleHelper.t(ctx, "app_lock"),
        showSwitch = true,
        switchOn = appLockOn,
        onSwitchChange = { wantOn ->
          if (wantOn) {
            pinStep = 0
            pinFirst = ""
            pinSetupText = ""
            pinSetupError = ""
            showPinSetupDialog = true
          } else {
            // FIX 2026-11-18 (report #41): turning App Lock OFF
            // requires the current PIN first — open the verify dialog
            // instead of writing app_lock=false here. The flag is only
            // written after the PIN matches (see the verify dialog).
            pinVerifyText = ""
            pinVerifyError = ""
            showPinVerifyDialog = true
          }
        },
        onClick = {
          if (!appLockOn) {
            pinStep = 0
            pinFirst = ""
            pinSetupText = ""
            pinSetupError = ""
            showPinSetupDialog = true
          }
        }
      )
    }

    // ---- About ----
    SectionHeading("About")
    SettingsCard {
      SettingsRow(
        title = LocaleHelper.t(ctx, "contact_support"),
        value = "chami.support@gmail.com",
        onClick = { openSupportEmail() }
      )
      SettingsRow(
        title = LocaleHelper.t(ctx, "privacy_policy"),
        onClick = { openPrivacyPolicy() }
      )
      SettingsRow(
        title = "About",
        value = "Version ${BuildConfig.VERSION_NAME}",
        onClick = { showAboutDialog = true }
      )
    }

    Spacer(Modifier.height(Ds.s24))
  }

  // Dialog: Language — tapping a language switches immediately.
  if (showLanguageDialog) {
    ChamiAlertDialog(theme,
      onDismissRequest = { showLanguageDialog = false },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = { showLanguageDialog = false }) {
          Text("CANCEL", fontFamily = PixelFont, color = theme.text)
        }
      },
      title = {
        Text(LocaleHelper.t(ctx, "language"), fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = Ds.SECTION_SP.sp)
      },
      text = {
        Column {
          LocaleHelper.LANGUAGES.forEach { (code, name) ->
            val isCurrent = code == LocaleHelper.currentCode(ctx)
            Row(
              Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Ds.minTouch)
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                  vibrate()
                  showLanguageDialog = false
                  LocaleHelper.setLocale(ctx, code)
                }
                .padding(vertical = Ds.s8, horizontal = Ds.s4),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                name,
                fontSize = (Ds.BODY_SP * fontScale).sp,
                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = if (isCurrent) theme.primary else theme.text,
                modifier = Modifier.weight(1f)
              )
              if (isCurrent) {
                Icon(Icons.Filled.Check, contentDescription = "Current language", tint = theme.primary)
              }
            }
          }
        }
      }
    )
  }

  // Dialog: Font Size — segmented Small / Default / Large, with a
  // preview sentence rendered at the chosen size before leaving.
  if (showFontSizeDialog) {
    ChamiAlertDialog(theme,
      onDismissRequest = { showFontSizeDialog = false },
      confirmButton = {
        TextButton(onClick = { showFontSizeDialog = false }) {
          // UI OVERHAUL 2026-11-18: primaryDark — 12sp #16A34A on white
          // is ~3:1 and fails WCAG AA (4.5:1); #14532D passes.
          Text("DONE", fontFamily = PixelFont, color = theme.primaryDark)
        }
      },
      title = {
        Text(LocaleHelper.t(ctx, "font_size"), fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = Ds.SECTION_SP.sp)
      },
      text = {
        Column {
          Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Ds.s8)
          ) {
            fontOptions.forEach { (name, scale) ->
              ChamiChip(
                label = name,
                selected = previewFontScale == scale,
                onClick = {
                  vibrate()
                  previewFontScale = scale
                  fontScale = scale
                  prefs.edit().putFloat("font_scale", scale).apply()
                },
                modifier = Modifier.weight(1f)
              )
            }
          }
          Spacer(Modifier.height(Ds.s16))
          Text(
            "The quick brown fox jumps over the lazy dog.",
            fontSize = (Ds.BODY_SP * previewFontScale).sp,
            lineHeight = (Ds.BODY_SP * 1.4 * previewFontScale).sp,
            color = theme.text
          )
        }
      }
    )
  }

  // Dialog: App Lock — set a 4-digit PIN, entered twice. The PIN is
  // stored SHA-256 hashed, never plain text. "app_lock" turns on only
  // once a PIN is saved. Forgot PIN requires reinstall — stated here.
  if (showPinSetupDialog) {
    ChamiAlertDialog(theme,
      onDismissRequest = { showPinSetupDialog = false },
      confirmButton = {
        Button(
          onClick = {
            val pin = pinSetupText.filter { it.isDigit() }
            if (pin.length != 4) {
              pinSetupError = "Enter exactly 4 digits"
            } else if (pinStep == 0) {
              pinFirst = pin
              pinSetupText = ""
              pinStep = 1
            } else if (pin != pinFirst) {
              pinSetupError = "PINs do not match — enter the PIN again"
              pinStep = 0
              pinFirst = ""
              pinSetupText = ""
            } else {
              prefs.edit()
                .putString("app_pin", hashPin(pin))
                .putBoolean("app_lock", true)
                .apply()
              appLockOn = true
              showPinSetupDialog = false
              Toast.makeText(ctx, "App Lock ON", Toast.LENGTH_SHORT).show()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
        ) {
          Text(
            if (pinStep == 0) "NEXT" else "SAVE PIN",
            fontFamily = PixelFont,
            color = theme.textOnPrimary,
            fontSize = (Ds.HINT_SP * fontScale).sp,
            fontWeight = FontWeight.Bold
          )
        }
      },
      dismissButton = {
        TextButton(onClick = { showPinSetupDialog = false }) {
          Text("CANCEL", fontFamily = PixelFont, color = theme.text)
        }
      },
      title = {
        Text(
          if (pinStep == 0) "Set a 4-digit PIN" else "Enter the PIN again",
          fontFamily = PixelFont,
          fontWeight = FontWeight.Bold,
          fontSize = Ds.SECTION_SP.sp
        )
      },
      text = {
        Column {
          OutlinedTextField(
            value = pinSetupText,
            onValueChange = {
              pinSetupText = it.filter { c -> c.isDigit() }.take(4)
              pinSetupError = ""
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = pinSetupError.isNotEmpty(),
            placeholder = { Text("••••", fontSize = (Ds.BODY_SP * fontScale).sp) },
            modifier = Modifier.fillMaxWidth()
          )
          if (pinSetupError.isNotEmpty()) {
            Spacer(Modifier.height(Ds.s8))
            Text(
              pinSetupError,
              fontSize = (Ds.HINT_SP * fontScale).sp,
              color = MaterialTheme.colorScheme.error
            )
          }
          Spacer(Modifier.height(Ds.s8))
          Text(
            "If you forget this PIN, resetting it requires reinstalling the app.",
            fontSize = (Ds.HINT_SP * fontScale).sp,
            lineHeight = (Ds.HINT_SP * 1.4 * fontScale).sp,
            color = theme.textSecondary
          )
        }
      }
    )
  }

  // Dialog: App Lock — turn OFF only after the current PIN matches.
  // FIX 2026-11-18 (report #41): previously the switch wrote
  // app_lock=false with no PIN prompt, so anyone holding the phone
  // could disable the lock. "app_lock" is written false only here,
  // after pinMatches() succeeds.
  if (showPinVerifyDialog) {
    ChamiAlertDialog(theme,
      onDismissRequest = { showPinVerifyDialog = false },
      confirmButton = {
        Button(
          onClick = {
            val pin = pinVerifyText.filter { it.isDigit() }
            if (pin.length != 4) {
              pinVerifyError = "Enter exactly 4 digits"
            } else if (!pinMatches(prefs.getString("app_pin", null), pin)) {
              pinVerifyError = "Wrong PIN — try again"
              pinVerifyText = ""
            } else {
              // L23 migration: a legacy unsalted/plain PIN that just
              // unlocked is re-hashed with a salt.
              if (isLegacyPinHash(prefs.getString("app_pin", null))) {
                prefs.edit().putString("app_pin", hashPin(pin)).apply()
              }
              prefs.edit().putBoolean("app_lock", false).apply()
              appLockOn = false
              showPinVerifyDialog = false
              Toast.makeText(ctx, "App Lock OFF", Toast.LENGTH_SHORT).show()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
        ) {
          Text(
            "TURN OFF",
            fontFamily = PixelFont,
            color = theme.textOnPrimary,
            fontSize = (Ds.HINT_SP * fontScale).sp,
            fontWeight = FontWeight.Bold
          )
        }
      },
      dismissButton = {
        TextButton(onClick = { showPinVerifyDialog = false }) {
          Text("CANCEL", fontFamily = PixelFont, color = theme.text)
        }
      },
      title = {
        Text(
          "Enter your PIN to turn App Lock off",
          fontFamily = PixelFont,
          fontWeight = FontWeight.Bold,
          fontSize = Ds.SECTION_SP.sp
        )
      },
      text = {
        Column {
          OutlinedTextField(
            value = pinVerifyText,
            onValueChange = {
              pinVerifyText = it.filter { c -> c.isDigit() }.take(4)
              pinVerifyError = ""
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = pinVerifyError.isNotEmpty(),
            placeholder = { Text("••••", fontSize = (Ds.BODY_SP * fontScale).sp) },
            modifier = Modifier.fillMaxWidth()
          )
          if (pinVerifyError.isNotEmpty()) {
            Spacer(Modifier.height(Ds.s8))
            Text(
              pinVerifyError,
              fontSize = (Ds.HINT_SP * fontScale).sp,
              color = MaterialTheme.colorScheme.error
            )
          }
        }
      }
    )
  }

  // Dialog: Clear Cache — shows the size, confirms, never deletes
  // user files or History entries (only cache + temp working files).
  if (showClearCacheDialog) {
    ChamiAlertDialog(theme,
      onDismissRequest = { showClearCacheDialog = false },
      confirmButton = {
        Button(
          onClick = {
            vibrate()
            try {
              ctx.cacheDir.deleteRecursively()
              val chamiTmp = File(ctx.getExternalFilesDir(null), "Chami/tmppdf")
              if (chamiTmp.exists()) chamiTmp.deleteRecursively()
              Toast.makeText(ctx, "Cache cleared", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
              e.printStackTrace()
            }
            refreshSizes()
            showClearCacheDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
        ) {
          Text("CLEAR", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = (Ds.HINT_SP * fontScale).sp, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearCacheDialog = false }) {
          Text("CANCEL", fontFamily = PixelFont, color = theme.text)
        }
      },
      title = {
        Text(
          "Clear Cache" + (cacheBytes?.let { " (${formatKb(it)})" } ?: "") + "?",
          fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = (Ds.BODY_SP * fontScale).sp
        )
      },
      text = {
        Text(
          "This clears temporary files only. Your saved documents and History entries will not be deleted.",
          fontSize = (Ds.BODY_SP * fontScale).sp,
          lineHeight = (Ds.BODY_SP * 1.4 * fontScale).sp,
          color = theme.textSecondary
        )
      }
    )
  }

  // Dialog: Check for Updates — shown only when the store app is not
  // available, so the check cannot be faked as "latest version".
  if (showUpdateDialog) {
    ChamiAlertDialog(theme,
      onDismissRequest = { showUpdateDialog = false },
      confirmButton = {
        TextButton(onClick = { showUpdateDialog = false }) {
          Text("CLOSE", fontFamily = PixelFont, color = theme.primaryDark)
        }
      },
      title = {
        Text("Check for Updates", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = Ds.SECTION_SP.sp)
      },
      text = {
        Text(
          "This check needs the store app, which is not available on this device. " +
            "You are on version ${BuildConfig.VERSION_NAME}. " +
            "Open the store listing on your phone to check for a newer version.",
          fontSize = (Ds.BODY_SP * fontScale).sp,
          lineHeight = (Ds.BODY_SP * 1.4 * fontScale).sp,
          color = theme.textSecondary
        )
      }
    )
  }

  // Dialog: About — app name, versionName, package. Nothing else.
  if (showAboutDialog) {
    ChamiAlertDialog(theme,
      onDismissRequest = { showAboutDialog = false },
      confirmButton = {
        TextButton(onClick = { showAboutDialog = false }) {
          Text("CLOSE", fontFamily = PixelFont, color = theme.primaryDark)
        }
      },
      title = {
        Text("About", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = Ds.SECTION_SP.sp)
      },
      text = {
        Column {
          Text(
            "Chami PDF Maker",
            fontSize = (Ds.BODY_SP * fontScale).sp,
            fontWeight = FontWeight.Bold,
            color = theme.text
          )
          Spacer(Modifier.height(Ds.s8))
          Text(
            "Version ${BuildConfig.VERSION_NAME}",
            fontSize = (Ds.BODY_SP * fontScale).sp,
            color = theme.textSecondary
          )
          Text(
            "Package ${ctx.packageName}",
            fontSize = (Ds.BODY_SP * fontScale).sp,
            color = theme.textSecondary
          )
        }
      }
    )
  }

  // Fallback: bundled privacy policy (used only if no browser can open
  // the live policy page).
  if (showPrivacyPolicyFallback) {
    ChamiAlertDialog(theme,
      onDismissRequest = { showPrivacyPolicyFallback = false },
      confirmButton = {
        Button(
          onClick = { showPrivacyPolicyFallback = false },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
        ) {
          Text("CLOSE", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = (Ds.HINT_SP * fontScale).sp, fontWeight = FontWeight.Bold)
        }
      },
      title = {
        Text("Privacy Policy", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = (Ds.BODY_SP * fontScale).sp)
      },
      text = {
        Box(Modifier.height(300.dp)) {
          AndroidView(
            factory = { context ->
              WebView(context).apply {
                loadUrl("file:///android_asset/privacy_policy.html")
              }
            },
            modifier = Modifier.fillMaxSize()
          )
        }
      }
    )
  }
}
