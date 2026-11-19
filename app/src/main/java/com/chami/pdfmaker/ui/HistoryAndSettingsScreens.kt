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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
// (Chami_ = Images to PDF, Split_ = Split, Merged_ = Merge,
// Compress_/Compressed_ = Compress, Resized_ = Resizer, protected_ = Protect).
// FIX 2026-11-19 (final audit): no Scan here — Scan was removed from the
// app, so a Scan_ prefix no longer maps to a tool (falls to "Other").
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

// FIX 2026-11-19 (final audit): tool / bucket / sort values are kept as
// canonical English ids internally (filter, sort and grouping logic
// compares them), and are only turned into display text here, at the
// point they are shown. That keeps the logic language-independent.
private fun historyToolLabel(ctx: Context, canonical: String): String {
  return when (canonical) {
    "All" -> LocaleHelper.t(ctx, "history_filter_all")
    "Images to PDF" -> LocaleHelper.t(ctx, "history_tool_images_to_pdf")
    "Merge" -> LocaleHelper.t(ctx, "history_tool_merge")
    "Split" -> LocaleHelper.t(ctx, "history_tool_split")
    "Protect" -> LocaleHelper.t(ctx, "history_tool_protect")
    "Compress" -> LocaleHelper.t(ctx, "history_tool_compress")
    "Resizer" -> LocaleHelper.t(ctx, "history_tool_resizer")
    "PDF to Images" -> LocaleHelper.t(ctx, "history_tool_pdf_to_images")
    "Format Convert" -> LocaleHelper.t(ctx, "history_tool_format_convert")
    "OCR" -> LocaleHelper.t(ctx, "history_tool_ocr")
    "Other" -> LocaleHelper.t(ctx, "history_tool_other")
    else -> canonical
  }
}

private fun historyBucketLabel(ctx: Context, bucket: String): String {
  return when (bucket) {
    "Today" -> LocaleHelper.t(ctx, "history_bucket_today")
    "Yesterday" -> LocaleHelper.t(ctx, "history_bucket_yesterday")
    "This week" -> LocaleHelper.t(ctx, "history_bucket_this_week")
    else -> LocaleHelper.t(ctx, "history_bucket_older")
  }
}

private fun historySortLabel(ctx: Context, mode: String): String {
  return when (mode) {
    "Newest" -> LocaleHelper.t(ctx, "history_sort_newest")
    "Oldest" -> LocaleHelper.t(ctx, "history_sort_oldest")
    "Biggest" -> LocaleHelper.t(ctx, "history_sort_biggest")
    else -> LocaleHelper.t(ctx, "history_sort_name")
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
      // FIX 2026-11-19 (final audit): recycle the evicted bitmap so its
      // native pixels are freed instead of leaking until GC. Rows guard
      // against a recycled bitmap (see HistoryThumb) and re-render it.
      val evict = size > MAX_ENTRIES
      if (evict) {
        eldest?.value?.let { if (!it.isRecycled) it.recycle() }
      }
      return evict
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
  var bmp by remember(file.absolutePath) {
    mutableStateOf(HistoryThumbCache.get(file.absolutePath)?.takeUnless { it.isRecycled })
  }
  LaunchedEffect(file.absolutePath, bmp?.isRecycled) {
    if (bmp == null || bmp?.isRecycled == true) {
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
  // Phase 6: thumbnail drawn from the cache when ready; while it
  // loads the tile is a grey skeleton (not a blank tile), matching
  // the Phase 4/5 skeleton pattern. Radius is Ds.tileRadius (L22).
  val thumbImage = remember(bmp) { bmp?.takeUnless { it.isRecycled }?.asImageBitmap() }
  Box(
    Modifier
      .size(width = 48.dp, height = 64.dp)
      .clip(RoundedCornerShape(Ds.tileRadius))
      .background(if (thumbImage != null) theme.bg else ChamiColors.skeletonBg)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius)),
    contentAlignment = Alignment.Center
  ) {
    // PERFORMANCE (UI overhaul): wrap once per bitmap — rows recompose
    // on scroll/selection and re-wrapping every thumbnail each time
    // dropped frames.
    if (thumbImage != null) {
      Image(
        bitmap = thumbImage,
        contentDescription = LocaleHelper.t(ctx, "history_thumb_desc").replace("{name}", file.name),
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit
      )
    } else {
      // Real vector icon while the thumbnail loads — never an emoji.
      Icon(
        if (isPdf) Icons.Filled.Description else Icons.Filled.Image,
        contentDescription = null,
        tint = theme.textSecondary,
        modifier = Modifier.size(Ds.iconSize)
      )
    }
  }
}

/** Phase 6: pulsing skeleton row while History loads — same 900ms
 *  pulse as MergeRowSkeleton, 96dp matches the file row (16dp
 *  padding + 64dp thumbnail). GPU alpha only, no layout work. */
@Composable
private fun HistoryRowSkeleton(animOn: Boolean) {
  // FIX 2026-11-19 (final audit): gate the infinite pulse on
  // animationsEnabled() — with animations off the skeleton is a static
  // grey box, same shape, no motion (same animOn gate the caller
  // already computes for press-scale / toolEnterFade).
  if (animOn) {
    val pulse by rememberInfiniteTransition(label = "historySkeleton").animateFloat(
      initialValue = 0.35f, targetValue = 0.7f,
      animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
      label = "historySkeletonAlpha"
    )
    Box(
      Modifier
        .fillMaxWidth()
        .height(96.dp)
        .clip(RoundedCornerShape(Ds.tileRadius))
        .background(ChamiColors.skeletonBg)
        .graphicsLayer { alpha = pulse }
    )
  } else {
    Box(
      Modifier
        .fillMaxWidth()
        .height(96.dp)
        .clip(RoundedCornerShape(Ds.tileRadius))
        .background(ChamiColors.skeletonBg)
    )
  }
}

/** FIX 2026-11-19 (final audit): local section header for History /
 *  Settings — same green accent bar look as the shared
 *  ToolSectionHeader, but the text honours the Font Size setting
 *  (fontScale) and PixelFont like the rest of these screens. The shared
 *  header is fixed 16sp with no fontScale, which was a Font Size
 *  regression for date-group headers and Settings section headings. */
@Composable
private fun FontScaledSectionHeader(
  text: String,
  color: Color,
  fontScale: Float,
  topPadding: Dp = Ds.s16
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.padding(top = topPadding, bottom = Ds.s12)
  ) {
    Box(
      Modifier
        .width(Ds.s4)
        .height(Ds.s20)
        .clip(RoundedCornerShape(Ds.pillRadius))
        .background(ChamiColors.darkGreen)
    )
    Spacer(Modifier.width(Ds.s8))
    Text(
      text,
      fontFamily = PixelFont,
      fontWeight = FontWeight.Bold,
      fontSize = (Ds.SECTION_SP * fontScale).sp,
      lineHeight = (Ds.SECTION_SP * 1.4 * fontScale).sp,
      color = color
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
  theme: ChamiTheme,
  listState: LazyListState = rememberLazyListState(),
) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  // Phase 6: motion gate for the staggered row enter animation and
  // press-scale — off when the system "remove animations" setting is on.
  val animOn = animationsEnabled(ctx)
  // Phase 6 Font Size: "font_scale" in chami_prefs (0.85 / 1.0 / 1.15).
  // Main History text sizes are multiplied by it. Read once per visit
  // (remember) instead of on every composition — HistoryScreen leaves
  // the composition when the tab changes, so a change made in Settings
  // is still picked up on the next visit.
  val fontScale = remember {
    ctx.getSharedPreferences(LocaleHelper.PREFS, Context.MODE_PRIVATE)
      .getFloat("font_scale", 1.0f)
  }
  val favPrefs = remember { ctx.getSharedPreferences("chami_history_favs", Context.MODE_PRIVATE) }
  // FIX 2026-11-19 (final audit): the file list is loaded on an IO
  // thread, not synchronously in remember{} on first composition
  // (listFiles + sumOf length is disk IO). `loaded` is the no-flicker
  // guard: until the first load completes the screen shows skeleton
  // rows, never the "No Documents Found" empty state — the first frame
  // therefore still never flashes a wrong empty state when files exist.
  var fileList by remember { mutableStateOf<List<File>>(emptyList()) }
  var storageUsed by remember { mutableStateOf(0L) }
  var loaded by remember { mutableStateOf(false) }
  // FIX 2026-11-19 (final audit): rotation state — query, filter,
  // sort mode, selection and favourites survive rotation.
  var query by rememberSaveable { mutableStateOf("") }
  var filter by rememberSaveable { mutableStateOf("All") }
  var sortMode by rememberSaveable { mutableStateOf("Newest") }
  var sortMenuOpen by remember { mutableStateOf(false) }
  var selected by rememberSaveable(
    saver = listSaver<MutableState<Set<String>>, String>(
      save = { it.value.toList() },
      restore = { mutableStateOf(it.toSet()) }
    )
  ) { mutableStateOf<Set<String>>(emptySet()) }
  var favourites by rememberSaveable(
    saver = listSaver<MutableState<Set<String>>, String>(
      save = { it.value.toList() },
      restore = { mutableStateOf(it.toSet()) }
    )
  ) {
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

  // FIX 2026-11-19 (final audit): compress results are loaded ONCE per
  // screen (off the main thread) instead of readCompressResult() doing
  // a SharedPreferences read inside every Compress row on every
  // recomposition. Rows look the map up in memory (see FileRow below).
  val compressResults by produceState<Map<String, Pair<Long, Long>>>(emptyMap(), fileList, toolMap) {
    value = withContext(Dispatchers.IO) {
      fileList
        .filter { historyToolName(toolMap, it) == "Compress" }
        .mapNotNull { f -> readCompressResult(ctx, f.name)?.let { f.name to it } }
        .toMap()
    }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
      } catch (_: Exception) {}
    }
  }

  val historyScope = rememberCoroutineScope()
  fun refresh() {
    // Disk IO off the main thread (see the first-load note above);
    // state is only written once the IO result is in.
    historyScope.launch {
      val files = withContext(Dispatchers.IO) {
        val dir = File(ctx.getExternalFilesDir(null), "Chami")
        if (dir.exists()) dir.listFiles()?.filter { it.isFile } ?: emptyList() else emptyList()
      }
      fileList = files
      storageUsed = files.sumOf { it.length() }
      loaded = true
    }
  }

  LaunchedEffect(Unit) { refresh() }

  // Page counts are loaded off the main thread, in parallel, without
  // blocking the list — rows show their metadata as counts arrive.
  LaunchedEffect(fileList) {
    withContext(Dispatchers.IO) {
      // FIX 2026-11-19 (final audit): cap parallel page-count renders
      // at 4 in flight — the old unbounded map{async} rendered every
      // PDF at once on a long list.
      val pageCountSlots = Semaphore(4)
      val counts = fileList
        .filter { it.name.endsWith(".pdf", ignoreCase = true) }
        .map { f -> async { pageCountSlots.withPermit { f.absolutePath to PdfEngine.pageCount(ctx, Uri.fromFile(f)) } } }
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
    Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_file_deleted"), Toast.LENGTH_SHORT).show()
  }

  val sdf = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

  // Search + filter + favourite-first + sort. Tool filter uses the
  // HistoryStore record first, falling back to the file-name prefix
  // (see historyToolName); "PDF to Images" and "OCR"
  // write no files into the Chami folder, so those chips are
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
  // file (and has produced one here). PDF to Images / OCR
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
      text = if (!loaded) LocaleHelper.t(ctx, "calculating_sizes")
        else LocaleHelper.t(ctx, "history_documents_saved").replace("{n}", "${fileList.size}"),
      fontSize = (Ds.HINT_SP * fontScale).sp,
      lineHeight = (Ds.HINT_SP * 1.4 * fontScale).sp,
      color = theme.textSecondary
    )

    Spacer(Modifier.height(Ds.s12))

    // Storage used — real folder size, stated plainly, in the shared
    // Phase 5 card style (12dp radius, card background, 1dp border).
    // UI OVERHAUL 2026-11-18: the old progress bar is REMOVED — it
    // compared folder size against folder size + free space, so it
    // always read ~0% and looked broken to a reviewer. Honest text
    // only, no fake bar.
    ToolCard(theme = theme, padding = Ds.s12) {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          Icons.Filled.Folder,
          contentDescription = null,
          tint = theme.primary,
          modifier = Modifier.size(Ds.iconSize)
        )
        Spacer(Modifier.width(Ds.s12))
        Text(
          if (!loaded) LocaleHelper.t(ctx, "calculating_sizes")
          else LocaleHelper.t(ctx, "history_storage_used").replace("{size}", formatKb(storageUsed)),
          fontSize = (Ds.HINT_SP * fontScale).sp,
          color = theme.textSecondary
        )
      }
    }

    Spacer(Modifier.height(Ds.s12))

    // Search by file name.
    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      placeholder = {
        Text(LocaleHelper.t(ctx, "history_search_hint"), fontSize = (Ds.HINT_SP * fontScale).sp, color = theme.textSecondary)
      },
      leadingIcon = {
        Icon(Icons.Filled.Search, contentDescription = LocaleHelper.t(ctx, "history_search_label"), tint = theme.textSecondary)
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
          label = historyToolLabel(ctx, chip),
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
          Text(LocaleHelper.t(ctx, "history_sort_label").replace("{mode}", historySortLabel(ctx, sortMode)), fontFamily = PixelFont, fontSize = (Ds.HINT_SP * fontScale).sp, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(
          expanded = sortMenuOpen,
          onDismissRequest = { sortMenuOpen = false }
        ) {
          listOf("Newest", "Oldest", "Biggest", "Name").forEach { mode ->
            DropdownMenuItem(
              text = {
                Text(historySortLabel(ctx, mode), fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, color = theme.text)
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
        Text(LocaleHelper.t(ctx, "history_refresh"), fontFamily = PixelFont, fontSize = (Ds.HINT_SP * fontScale).sp, fontWeight = FontWeight.Bold)
      }
    }

    // Multi-select action bar.
    if (multiSelect) {
      Spacer(Modifier.height(Ds.s8))
      Row(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Ds.tileRadius))
          .background(theme.primary.copy(alpha = 0.12f))
          .padding(Ds.s8),
        horizontalArrangement = Arrangement.spacedBy(Ds.s8),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          LocaleHelper.t(ctx, "history_selected_count").replace("{n}", "${selected.size}"),
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
          shape = RoundedCornerShape(Ds.buttonRadius),
          contentPadding = PaddingValues(horizontal = Ds.s12, vertical = Ds.s4)
        ) {
          Text(LocaleHelper.t(ctx, "history_multi_share"), fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
        }
        Button(
          onClick = {
            vibrate()
            filesToDelete = fileList.filter { selected.contains(it.absolutePath) }
          },
          // UI OVERHAUL 2026-11-18: 48dp minimum touch target (see SHARE above).
          modifier = Modifier.defaultMinSize(minHeight = Ds.minTouch),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          shape = RoundedCornerShape(Ds.buttonRadius),
          contentPadding = PaddingValues(horizontal = Ds.s12, vertical = Ds.s4)
        ) {
          Text(LocaleHelper.t(ctx, "history_multi_delete"), fontFamily = PixelFont, color = Color.White, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { selected = emptySet() }) {
          Text(LocaleHelper.t(ctx, "btn_cancel"), fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, color = theme.text)
        }
      }
    }

    Spacer(Modifier.height(Ds.s12))

    if (!loaded) {
      // Loading: skeleton rows shaped like file rows, NOT the empty
      // state (flicker fix) and NOT a blank background.
      Column(verticalArrangement = Arrangement.spacedBy(Ds.s12)) {
        repeat(4) { HistoryRowSkeleton(animOn) }
      }
    } else if (visible.isEmpty()) {
      Box(
        Modifier
          .fillMaxSize()
          .toolEnterFade(0, animOn)
          .clip(RoundedCornerShape(Ds.dialogRadius))
          .background(theme.card)
          .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.dialogRadius))
          .padding(Ds.s24),
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
              modifier = Modifier.size(Ds.s32)
            )
          }
          Spacer(Modifier.height(Ds.s12))
          Text(
            LocaleHelper.t(ctx, "history_empty_title"),
            fontFamily = PixelFont,
            fontWeight = FontWeight.Bold,
            fontSize = (Ds.BODY_SP * fontScale).sp,
            color = theme.text
          )
          Spacer(Modifier.height(Ds.s4))
          Text(
            if (query.isNotBlank() || filter != "All") LocaleHelper.t(ctx, "history_empty_search")
            else LocaleHelper.t(ctx, "history_empty_hint"),
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
        itemsIndexed(entries, key = { _, entry ->
          when (entry) {
            is HistoryEntry.Header -> "header:${entry.title}"
            is HistoryEntry.FileRow -> entry.file.absolutePath
          }
        }) { index, entry ->
          when (entry) {
            is HistoryEntry.Header -> {
              // Phase 6: date-group header in the shared Phase 5 style
              // (green accent bar), with the staggered enter fade.
              Box(Modifier.toolEnterFade(minOf(index, 9), animOn)) {
                FontScaledSectionHeader(historyBucketLabel(ctx, entry.title), theme.primary, fontScale, topPadding = Ds.s4)
              }
            }
            is HistoryEntry.FileRow -> {
              val file = entry.file
              val isPdf = file.name.endsWith(".pdf", ignoreCase = true)
              val mime = mimeForFile(file)
              val isSel = selected.contains(file.absolutePath)
              val isFav = favourites.contains(file.absolutePath)
              val pages = pageCounts[file.absolutePath]
              val compressResult = compressResults[file.name]
              // Phase 6: press-scale on the row (shared interaction
              // source with combinedClickable) + staggered fade-up,
              // capped at 9 so long lists never delay late rows.
              val rowInteraction = remember { MutableInteractionSource() }
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
                  .toolEnterFade(minOf(index, 9), animOn)
                  .pressScale(animOn, rowInteraction)
                  .combinedClickable(
                    interactionSource = rowInteraction,
                    indication = LocalIndication.current,
                    onClick = {
                      vibrate()
                      if (multiSelect) {
                        selected = if (isSel) selected - file.absolutePath
                        else selected + file.absolutePath
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
                    Spacer(Modifier.height(Ds.s4))
                    Text(
                      buildString {
                        if (pages != null) append("$pages ${LocaleHelper.t(ctx, if (pages == 1) "done_page" else "done_pages")} • ")
                        append(formatKb(file.length()))
                        append(" • ${sdf.format(Date(file.lastModified()))}")
                      },
                      fontSize = (Ds.META_SP * fontScale).sp,
                      lineHeight = (Ds.META_SP * 1.4 * fontScale).sp,
                      color = theme.textSecondary
                    )
                    if (compressResult != null) {
                      Spacer(Modifier.height(Ds.s4))
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
                    description = if (isFav) LocaleHelper.t(ctx, "history_remove_favourite") else LocaleHelper.t(ctx, "history_favourite"),
                    onClick = { vibrate(); toggleFavourite(file) },
                    tint = if (isFav) theme.primary else theme.textSecondary
                  )
                  // Row menu: rename / share / delete.
                  Box {
                    ChamiIconButton(
                      icon = Icons.Filled.MoreVert,
                      description = LocaleHelper.t(ctx, "history_more_options"),
                      onClick = { menuFor = file.absolutePath }
                    )
                    DropdownMenu(
                      expanded = menuFor == file.absolutePath,
                      onDismissRequest = { menuFor = null }
                    ) {
                      DropdownMenuItem(
                        text = { Text(LocaleHelper.t(ctx, "history_rename"), fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, color = theme.text) },
                        onClick = {
                          menuFor = null
                          renameText = file.nameWithoutExtension
                          fileToRename = file
                        }
                      )
                      DropdownMenuItem(
                        text = { Text(LocaleHelper.t(ctx, "done_share"), fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, color = theme.text) },
                        onClick = {
                          menuFor = null
                          ShareHelper.shareFile(ctx, file, mime)
                        }
                      )
                      DropdownMenuItem(
                        text = { Text(LocaleHelper.t(ctx, "done_open"), fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, color = theme.text) },
                        onClick = {
                          menuFor = null
                          ShareHelper.openFile(ctx, file, mime)
                        }
                      )
                      DropdownMenuItem(
                        text = { Text(LocaleHelper.t(ctx, "history_delete"), fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, color = MaterialTheme.colorScheme.error) },
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
            Text(LocaleHelper.t(ctx, "history_delete_confirm"), fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
          }
        },
        dismissButton = {
          TextButton(onClick = { fileToDelete = null }) {
            Text(LocaleHelper.t(ctx, "btn_cancel"), fontFamily = PixelFont, color = theme.text)
          }
        },
        title = {
          Text(LocaleHelper.t(ctx, "history_delete_title"), fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = Ds.SECTION_SP.sp)
        },
        text = {
          Text(
            LocaleHelper.t(ctx, "history_delete_body").replace("{name}", fileToDelete?.name ?: ""),
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
            Text(LocaleHelper.t(ctx, "history_delete_multi_confirm").replace("{n}", "${filesToDelete.size}"), fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
          }
        },
        dismissButton = {
          TextButton(onClick = { filesToDelete = emptyList() }) {
            Text(LocaleHelper.t(ctx, "btn_cancel"), fontFamily = PixelFont, color = theme.text)
          }
        },
        title = {
          Text(LocaleHelper.t(ctx, "history_delete_multi_title").replace("{n}", "${filesToDelete.size}"), fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = Ds.SECTION_SP.sp)
        },
        text = {
          Text(
            LocaleHelper.t(ctx, "history_delete_multi_body"),
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
                  Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_enter_file_name"), Toast.LENGTH_SHORT).show()
                } else {
                  val newFile = File(f.parentFile, newName)
                  if (newFile.exists() && newFile.absolutePath != f.absolutePath) {
                    Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_rename_exists"), Toast.LENGTH_SHORT).show()
                  } else if (f.renameTo(newFile)) {
                    Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_renamed"), Toast.LENGTH_SHORT).show()
                    fileToRename = null
                    refresh()
                  } else {
                    Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_rename_failed"), Toast.LENGTH_SHORT).show()
                  }
                }
              }
            },
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
          ) {
            Text(LocaleHelper.t(ctx, "history_rename_confirm"), fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
          }
        },
        dismissButton = {
          TextButton(onClick = { fileToRename = null }) {
            Text(LocaleHelper.t(ctx, "btn_cancel"), fontFamily = PixelFont, color = theme.text)
          }
        },
        title = {
          Text(LocaleHelper.t(ctx, "history_rename_title"), fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = Ds.SECTION_SP.sp)
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
  // Phase 6: motion gate for press-scale and the staggered card enter
  // fade — off when the system "remove animations" setting is on.
  val animOn = animationsEnabled(ctx)
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
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_no_email_app"), Toast.LENGTH_SHORT).show()
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
      // FIX 2026-11-19: the app is not on Google Play, so the old
      // market:// link went nowhere. Updates now open the website's
      // download section, where the latest APK is.
      val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://chamitools.github.io/chami-pdf-maker-website/#download-app")
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
    LocaleHelper.t(ctx, "settings_font_small") to 0.85f,
    LocaleHelper.t(ctx, "settings_font_default") to 1.0f,
    LocaleHelper.t(ctx, "settings_font_large") to 1.15f
  )

  @Composable
  fun SectionHeading(text: String) {
    // FIX 2026-11-19 (final audit): local font-scaled header (see
    // FontScaledSectionHeader) — the shared ToolSectionHeader is fixed
    // 16sp with no fontScale, so Settings headings ignored Font Size.
    FontScaledSectionHeader(text, theme.primaryDark, fontScale, topPadding = Ds.s24)
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
    // Phase 6: press-scale on the row (shared interaction source with
    // clickable) — same 250ms shared easing as the tool screens.
    val interactionSource = remember { MutableInteractionSource() }
    Row(
      Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = Ds.listRowMinHeight)
        .clip(RoundedCornerShape(Ds.tileRadius))
        .pressScale(animOn, interactionSource)
        .clickable(
          interactionSource = interactionSource,
          indication = LocalIndication.current
        ) { vibrate(); onClick() }
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
          modifier = Modifier.size(Ds.iconSize)
        )
      }
    }
  }

  @Composable
  fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
  ) {
    Column(
      modifier
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
      LocaleHelper.t(ctx, "settings_storage_value")
        .replace("{used}", formatKb(storageUsedBytes!!))
        .replace("{cache}", formatKb(cacheBytes!!))
    }
    val cacheValue = cacheBytes?.let { formatKb(it) } ?: calculatingText

    // ---- General ----
    SectionHeading(LocaleHelper.t(ctx, "settings_section_general"))
    SettingsCard(modifier = Modifier.toolEnterFade(0, animOn)) {
      SettingsRow(
        title = LocaleHelper.t(ctx, "language"),
        value = LocaleHelper.LANGUAGES.firstOrNull { it.first == LocaleHelper.currentCode(ctx) }?.second,
        onClick = { showLanguageDialog = true }
      )
      // FIX 2026-11-18 (report #42): say plainly what the language
      // switch translates, so it is not sold as a full switch.
      Text(
        LocaleHelper.t(ctx, "settings_language_note"),
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
    SectionHeading(LocaleHelper.t(ctx, "settings_section_security"))
    SettingsCard(modifier = Modifier.toolEnterFade(1, animOn)) {
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
    SectionHeading(LocaleHelper.t(ctx, "settings_about"))
    SettingsCard(modifier = Modifier.toolEnterFade(2, animOn)) {
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
        title = LocaleHelper.t(ctx, "settings_about"),
        value = LocaleHelper.t(ctx, "settings_about_version").replace("{version}", BuildConfig.VERSION_NAME),
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
          Text(LocaleHelper.t(ctx, "btn_cancel"), fontFamily = PixelFont, color = theme.text)
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
                .clip(RoundedCornerShape(Ds.s8))
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
                Icon(Icons.Filled.Check, contentDescription = LocaleHelper.t(ctx, "settings_current_language"), tint = theme.primary)
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
          Text(LocaleHelper.t(ctx, "done"), fontFamily = PixelFont, color = theme.primaryDark)
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
              pinSetupError = LocaleHelper.t(ctx, "pin_enter_4_digits")
            } else if (pinStep == 0) {
              pinFirst = pin
              pinSetupText = ""
              pinStep = 1
            } else if (pin != pinFirst) {
              pinSetupError = LocaleHelper.t(ctx, "pin_mismatch")
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
              Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_app_lock_on"), Toast.LENGTH_SHORT).show()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
        ) {
          Text(
            if (pinStep == 0) LocaleHelper.t(ctx, "pin_next") else LocaleHelper.t(ctx, "pin_save"),
            fontFamily = PixelFont,
            color = theme.textOnPrimary,
            fontSize = (Ds.HINT_SP * fontScale).sp,
            fontWeight = FontWeight.Bold
          )
        }
      },
      dismissButton = {
        TextButton(onClick = { showPinSetupDialog = false }) {
          Text(LocaleHelper.t(ctx, "btn_cancel"), fontFamily = PixelFont, color = theme.text)
        }
      },
      title = {
        Text(
          if (pinStep == 0) LocaleHelper.t(ctx, "pin_set_title") else LocaleHelper.t(ctx, "pin_confirm_title"),
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
            LocaleHelper.t(ctx, "pin_forgot_note"),
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
              pinVerifyError = LocaleHelper.t(ctx, "pin_enter_4_digits")
            } else if (!pinMatches(prefs.getString("app_pin", null), pin)) {
              pinVerifyError = LocaleHelper.t(ctx, "pin_wrong_try_again")
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
              Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_app_lock_off"), Toast.LENGTH_SHORT).show()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
        ) {
          Text(
            LocaleHelper.t(ctx, "pin_turn_off"),
            fontFamily = PixelFont,
            color = theme.textOnPrimary,
            fontSize = (Ds.HINT_SP * fontScale).sp,
            fontWeight = FontWeight.Bold
          )
        }
      },
      dismissButton = {
        TextButton(onClick = { showPinVerifyDialog = false }) {
          Text(LocaleHelper.t(ctx, "btn_cancel"), fontFamily = PixelFont, color = theme.text)
        }
      },
      title = {
        Text(
          LocaleHelper.t(ctx, "pin_verify_title"),
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
              Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_cache_cleared"), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
              e.printStackTrace()
            }
            refreshSizes()
            showClearCacheDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
        ) {
          Text(LocaleHelper.t(ctx, "settings_clear_confirm"), fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = (Ds.HINT_SP * fontScale).sp, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearCacheDialog = false }) {
          Text(LocaleHelper.t(ctx, "btn_cancel"), fontFamily = PixelFont, color = theme.text)
        }
      },
      title = {
        Text(
          LocaleHelper.t(ctx, "clear_cache") + (cacheBytes?.let { " (${formatKb(it)})" } ?: "") + "?",
          fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = (Ds.BODY_SP * fontScale).sp
        )
      },
      text = {
        Text(
          LocaleHelper.t(ctx, "settings_clear_cache_body"),
          fontSize = (Ds.BODY_SP * fontScale).sp,
          lineHeight = (Ds.BODY_SP * 1.4 * fontScale).sp,
          color = theme.textSecondary
        )
      }
    )
  }

  // Dialog: Check for Updates — shown only when no browser can open
  // the website link, so the check cannot be faked as "latest version".
  if (showUpdateDialog) {
    ChamiAlertDialog(theme,
      onDismissRequest = { showUpdateDialog = false },
      confirmButton = {
        TextButton(onClick = { showUpdateDialog = false }) {
          Text(LocaleHelper.t(ctx, "close"), fontFamily = PixelFont, color = theme.primaryDark)
        }
      },
      title = {
        Text(LocaleHelper.t(ctx, "check_updates"), fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = Ds.SECTION_SP.sp)
      },
      text = {
        Text(
          LocaleHelper.t(ctx, "settings_update_unavailable_body")
            .replace("{version}", BuildConfig.VERSION_NAME),
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
          Text(LocaleHelper.t(ctx, "close"), fontFamily = PixelFont, color = theme.primaryDark)
        }
      },
      title = {
        Text(LocaleHelper.t(ctx, "settings_about"), fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = Ds.SECTION_SP.sp)
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
            LocaleHelper.t(ctx, "settings_about_version").replace("{version}", BuildConfig.VERSION_NAME),
            fontSize = (Ds.BODY_SP * fontScale).sp,
            color = theme.textSecondary
          )
          Text(
            LocaleHelper.t(ctx, "settings_about_package").replace("{package}", ctx.packageName),
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
          Text(LocaleHelper.t(ctx, "close"), fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = (Ds.HINT_SP * fontScale).sp, fontWeight = FontWeight.Bold)
        }
      },
      title = {
        Text(LocaleHelper.t(ctx, "privacy_policy"), fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = (Ds.BODY_SP * fontScale).sp)
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
