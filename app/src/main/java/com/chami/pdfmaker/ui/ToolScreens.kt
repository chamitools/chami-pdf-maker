package com.chami.pdfmaker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chami.pdfmaker.LocaleHelper
import com.chami.pdfmaker.pdf.*
import com.chami.pdfmaker.theme.ChamiTheme
import com.chami.pdfmaker.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// PHASE 6 — NO ADS in this build (user decision 2026-11-13): no interstitial
// before a file is made, no banner, no AdMob anywhere in this file.
// ---------------------------------------------------------------------------
//
// FIX NOTES (2026-11-14 rewrite, audit chami-whole-app-audit-2026-11-14):
// - Images builds through PdfEngine.buildImagesPdf(ctx, List<Bitmap>,
//   ImagesPdfOptions, onProgress) — the shared engine API (merged from the
//   engine batch). The old buildImagesPdfWithOptions (duplicate engine,
//   wrong quality/watermark/Original-size behaviour) is deleted.
// - OcrHelper exposes ocrImage(ctx, uri, language) and
//   ocrImageAnyLanguage (Latin + Devanagari, no picking); the OCR
//   screen runs both recognisers and reads whatever is there.
// - ImagePageEditor.kt is unchanged in this copy, so its CropEditorDialog
//   still warps (cropToQuad). Crop therefore uses the new RectCropDialog
//   below (true rectangular CUT via ImagePageEditor.cropToRect); the warp
//   dialog is no longer reachable — the Straighten button that opened
//   it was removed at the user's request (2026-11-14).
// - PdfProtector exposes unlockPdf / setPermissions / verifyPassword /
//   patchPermissions; Protect and Unlock use them. The old local RC4/MD5
//   helpers were deleted in the Phase 2 final pass (L10) once the last
//   ToolScreens2.kt call site migrated to PdfProtector.patchPermissions.
// - ReaderTextTools already exposes saveBookmark / getBookmarks
//   (SharedPreferences "chami_bookmarks"); the Reader uses them, plus a
//   local removeBookmark with the same key scheme.

// --- Shared engine-facing helpers ----------------------------------------

/** One line per output file, for the multi-file Done screen. */
data class DoneFileInfo(val file: File, val mimeType: String, val detail: String = "")

fun formatSizeText(bytes: Long): String {
  if (bytes <= 0) return "0 KB"
  val kb = bytes / 1024.0
  return if (kb >= 1024) String.format("%.1f MB", kb / 1024.0) else String.format("%.0f KB", kb)
}

fun displayNameOf(ctx: Context, uri: Uri): String? {
  return try {
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
  } catch (_: Exception) { null }
}

fun baseNameOf(ctx: Context, uri: Uri, fallback: String): String {
  val name = displayNameOf(ctx, uri) ?: return fallback
  return name.substringBeforeLast(".", name).ifBlank { fallback }
}

/** Shown whenever a PDF cannot be opened at all (password-protected
 *  or damaged) — report #24. Used by the Reader and every tool that
 *  picks a PDF, so the message is identical everywhere. */
const val UNOPENABLE_PDF_MESSAGE = "This PDF can't be opened (password-protected or damaged)"

/** PHASE 2 (H10): most page thumbnails the PDF-to-Images screen keeps
 *  at once. Thumbnails render on demand for visible grid cells; when
 *  the cap is reached the farthest page's thumbnail is recycled. */
const val MAX_PAGE_THUMBS = 60

/** rememberSaveable for picked-file Uris (report #47): the saver
 *  stores the state's CURRENT Uri as a string (parsed back on
 *  restore), so picked work survives process death. The saver reads
 *  state.value at save time, so later picks are saved too. */
val UriStateSaver: Saver<MutableState<Uri?>, String> = Saver(
  save = { it.value?.toString() ?: "" },
  restore = { mutableStateOf(if (it.isEmpty()) null else Uri.parse(it)) }
)
val UriListStateSaver: Saver<MutableState<List<Uri>>, List<String>> = Saver(
  save = { state -> state.value.map { it.toString() } },
  restore = { list -> mutableStateOf(list.map(Uri::parse)) }
)

@Composable
fun rememberSaveableUri(initialValue: Uri? = null): MutableState<Uri?> =
  rememberSaveable(saver = UriStateSaver) { mutableStateOf(initialValue) }

@Composable
fun rememberSaveableUriList(initialValue: List<Uri> = emptyList()): MutableState<List<Uri>> =
  rememberSaveable(saver = UriListStateSaver) { mutableStateOf(initialValue) }


// PDF compress quality slider (10-100) with the exact quality, via the
// engine's compressPdfImages (scale 1.0, exact JPEG quality). NOTE: the
// engine re-renders every page as an image, so text in the output is
// rasterised, not kept as selectable text — the UI must not claim
// otherwise. Input is a Uri here, so it is copied to a temp file first.
fun compressPdfWithQuality(ctx: Context, uri: Uri, quality: Int, out: File): PdfCompressResult {
  // FIX 2026-11-18 (report #18): call the engine's
  // PdfCompress.compressPdfWithQuality directly and return its result
  // untouched — it already keeps the smaller file and reports
  // keptOriginal = true when the original was kept. The old code
  // rebuilt the result with keptOriginal = false, so the UI could
  // never tell the user the original was kept.
  return try {
    PdfCompress.compressPdfWithQuality(ctx, uri, quality.coerceIn(10, 100), out)
  } catch (_: Exception) {
    PdfCompressResult(false, 0, false, 0)
  }
}

// --- ViewModel: pages + per-page filter state survive rotation ---------
// Selection follows the page Uri, so reorder can never desync the
// preview from the strip. Filter mode is stored per page Uri, so
// switching pages never resets a filter.
class PagesViewModel : ViewModel() {
  var pages by mutableStateOf<List<PageItem>>(emptyList())
  var selectedUri by mutableStateOf<Uri?>(null)
  val filterModes = mutableStateMapOf<Uri, Int>()
  // PHASE 2 (L5): the edit guard lives here, in the ViewModel, as state —
  // not as a plain var on the remembered PageEditor object, so a second
  // edit cannot start mid-edit even if the composition is recreated
  // while an edit is running. PageEditor reads/writes this.
  val editBusy = mutableStateOf(false)

  val selectedIndex: Int
    get() = pages.indexOfFirst { it.uri == selectedUri }.takeIf { it >= 0 } ?: 0

  fun select(index: Int) {
    selectedUri = pages.getOrNull(index)?.uri
  }

  fun setPages(list: List<PageItem>, selectFirstNew: Int? = null) {
    pages = list
    if (selectFirstNew != null) selectedUri = list.getOrNull(selectFirstNew)?.uri
    else if (selectedUri != null && list.none { it.uri == selectedUri }) {
      selectedUri = list.getOrNull(selectedIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0)))?.uri
    }
  }

  fun movePage(from: Int, to: Int) {
    if (from !in pages.indices || to !in pages.indices) return
    pages = ImagePageEditor.move(pages, from, to)
    // selectedUri is unchanged, so selectedIndex follows the moved page.
  }

  /** Removes the page at [index]. The Uri is looked up BEFORE the list
   *  is filtered, so the filter maps lose the right entry. PHASE 2 (H2):
   *  the removed page's bitmap is recycled here — it is no longer shown
   *  anywhere (the thumbnail strip keeps its own downscaled copies). */
  fun deleteAt(index: Int) {
    val page = pages.getOrNull(index) ?: return
    val uri = page.uri
    filterModes.remove(uri)
    pages = pages.filterIndexed { i, _ -> i != index }
    if (selectedUri == uri) selectedUri = pages.getOrNull((index - 1).coerceAtLeast(0))?.uri
    if (!page.bitmap.isRecycled) page.bitmap.recycle()
  }
}

// --- Shared UI -----------------------------------------------------------

@Composable
fun ToolScreenContainer(
  title: String,
  theme: ChamiTheme,
  onBack: () -> Unit,
  content: @Composable ColumnScope.() -> Unit
) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  BackHandler {
    vibrate()
    onBack()
  }

  Column(
    Modifier
      .fillMaxSize()
      .background(theme.bg)
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(Ds.s16)
  ) {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      ChamiIconButton(
        icon = Icons.Filled.ArrowBack,
        description = "Back",
        onClick = { vibrate(); onBack() }
      )
      Spacer(Modifier.width(Ds.s8))
      // UI OVERHAUL 2026-11-18: the title takes the remaining width
      // and ellipsises at Large font instead of pushing the back
      // button or clipping.
      TitleText(title, color = theme.text, modifier = Modifier.weight(1f), maxLines = 1)
    }
    Spacer(Modifier.height(Ds.s16))
    content()
  }
}

@Composable
fun PrimaryButton(
  theme: ChamiTheme,
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  height: Dp = Ds.primaryButtonHeight
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.fillMaxWidth().height(height),
    colors = ButtonDefaults.buttonColors(
      containerColor = theme.primary,
      contentColor = theme.textOnPrimary,
      disabledContainerColor = theme.cardBorder,
      disabledContentColor = theme.textSecondary
    ),
    shape = RoundedCornerShape(Ds.buttonRadius)
  ) {
    // UI OVERHAUL 2026-11-18: the label used to stay white when
    // disabled — white on the light-grey disabled container is
    // unreadable. Disabled text is now the secondary colour.
    Text(text, color = if (enabled) theme.textOnPrimary else theme.textSecondary, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold)
  }
}

/** Text field whose value only propagates after the user stops typing
 *  for 300ms, so typing does not recompose the whole scrolling column
 *  and the 340dp preview on every keystroke. */
@Composable
fun DebouncedTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  singleLine: Boolean = true,
  keyboardType: KeyboardType = KeyboardType.Text,
  isError: Boolean = false,
  supportingText: String? = null
) {
  var draft by remember(value) { mutableStateOf(value) }
  LaunchedEffect(draft) {
    delay(300)
    if (draft != value) onValueChange(draft)
  }
  Column(modifier) {
    OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      label = { Text(label, fontSize = Ds.HINT_SP.sp) },
      singleLine = singleLine,
      isError = isError,
      keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(Ds.buttonRadius)
    )
    if (supportingText != null) {
      Spacer(Modifier.height(Ds.s4))
      Text(supportingText, fontSize = Ds.HINT_SP.sp, color = if (isError) ChamiColors.errorRed else ChamiColors.secondaryText)
    }
  }
}

/** Save-every-output flow: asks the folder ONCE (system folder picker),
 *  writes every file there, then reports the saved files.
 *
 *  PHASE 2 (M9): the pending save is persisted in SharedPreferences
 *  ("chami_pending_save") when the picker launches — file paths + MIME
 *  types — and cleared when the picker returns (saved or cancelled).
 *  Plain `remember` state does not survive process death, so if the
 *  system kills the app while the picker is open the in-memory pending
 *  list is gone; on the next composition the LaunchedEffect below
 *  finds the stale persisted entry and surfaces the Done state for
 *  those files (they already exist in the Chami folder) instead of
 *  silently dropping the save. Files that no longer exist are not
 *  surfaced. Note the entry is not scoped to one tool: whichever tool
 *  screen opens first after the death surfaces it, and the Done screen
 *  shows the real file names so the user can tell what they are.
 *
 *  FIX 2026-11-18 (Format Convert save): two bugs.
 *  (1) createDocument was passed the raw tree URI from OpenDocumentTree,
 *      but it needs a DOCUMENT URI — the tree URI is not a valid parent
 *      document URI and strict providers reject it, so every write
 *      failed and nothing was saved. The parent is now built with
 *      buildDocumentUriUsingTree (raw tree URI only as a fallback for
 *      lenient providers).
 *  (2) When every write failed, the old code called onCancelled, so a
 *      real failure showed "Save cancelled" even though the user picked
 *      a folder. "Cancelled" now fires ONLY when the picker itself is
 *      dismissed (treeUri == null); a write failure shows the real
 *      error, and a partial save reports how many files were saved. */
private const val PENDING_SAVE_PREFS = "chami_pending_save"
private const val PENDING_FOLDER_PATHS = "folder_paths"
private const val PENDING_FOLDER_MIMES = "folder_mimes"
private const val PENDING_SINGLE_PATH = "single_path"
private const val PENDING_SINGLE_MIME = "single_mime"

private fun pendingSavePrefs(ctx: Context) =
  ctx.getSharedPreferences(PENDING_SAVE_PREFS, Context.MODE_PRIVATE)

private fun readPendingFolderSave(ctx: Context): List<Pair<File, String>>? {
  val prefs = pendingSavePrefs(ctx)
  val paths = prefs.getString(PENDING_FOLDER_PATHS, null)?.split("\n")?.filter { it.isNotEmpty() } ?: return null
  val mimes = prefs.getString(PENDING_FOLDER_MIMES, null)?.split("\n") ?: emptyList()
  if (paths.isEmpty()) return null
  return paths.mapIndexed { i, path -> File(path) to (mimes.getOrNull(i) ?: "application/pdf") }
}

private fun writePendingFolderSave(ctx: Context, files: List<Pair<File, String>>) {
  pendingSavePrefs(ctx).edit()
    .putString(PENDING_FOLDER_PATHS, files.joinToString("\n") { it.first.absolutePath })
    .putString(PENDING_FOLDER_MIMES, files.joinToString("\n") { it.second })
    .apply()
}

private fun clearPendingFolderSave(ctx: Context) {
  pendingSavePrefs(ctx).edit()
    .remove(PENDING_FOLDER_PATHS).remove(PENDING_FOLDER_MIMES).apply()
}

private fun readPendingSingleSave(ctx: Context): Pair<File, String>? {
  val path = pendingSavePrefs(ctx).getString(PENDING_SINGLE_PATH, null) ?: return null
  if (path.isEmpty()) return null
  val mime = pendingSavePrefs(ctx).getString(PENDING_SINGLE_MIME, null) ?: "application/pdf"
  return File(path) to mime
}

private fun writePendingSingleSave(ctx: Context, file: File, mimeType: String) {
  pendingSavePrefs(ctx).edit()
    .putString(PENDING_SINGLE_PATH, file.absolutePath)
    .putString(PENDING_SINGLE_MIME, mimeType)
    .apply()
}

private fun clearPendingSingleSave(ctx: Context) {
  pendingSavePrefs(ctx).edit()
    .remove(PENDING_SINGLE_PATH).remove(PENDING_SINGLE_MIME).apply()
}

@Composable
fun rememberFolderSaver(
  onSaved: (List<File>) -> Unit,
  onCancelled: () -> Unit,
  onSavedToFolder: ((Uri) -> Unit)? = null
): (List<Pair<File, String>>) -> Unit {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var pending by remember { mutableStateOf<List<Pair<File, String>>?>(null) }
  // PHASE 2 (M9): recover a save whose picker was open when the process
  // died — the files already exist in the Chami folder, so show Done
  // for them instead of dropping the save silently.
  LaunchedEffect(Unit) {
    val stale = readPendingFolderSave(ctx)
    if (stale != null) {
      clearPendingFolderSave(ctx)
      val existing = stale.filter { it.first.exists() }.map { it.first }
      if (existing.isNotEmpty()) onSaved(existing)
    }
  }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
    val files = pending
    pending = null
    clearPendingFolderSave(ctx)
    if (treeUri == null || files == null) {
      if (files != null) onCancelled()
      return@rememberLauncherForActivityResult
    }
    scope.launch(Dispatchers.IO) {
      try {
        ctx.contentResolver.takePersistableUriPermission(
          treeUri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
      } catch (_: Exception) {}
      // The folder picker returns a TREE uri; createDocument needs the
      // parent DOCUMENT uri for that tree.
      val parentDocUri = try {
        DocumentsContract.buildDocumentUriUsingTree(
          treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
      } catch (_: Exception) { treeUri }
      val saved = mutableListOf<File>()
      var failed = 0
      for ((file, mime) in files) {
        try {
          val docUri = DocumentsContract.createDocument(ctx.contentResolver, parentDocUri, mime, file.name)
          if (docUri != null && SaveLocation.copyToUri(ctx, file, docUri)) saved.add(file)
          else failed++
        } catch (_: Exception) { failed++ }
      }
      withContext(Dispatchers.Main) {
        // FIX 2026-11-18 (report #3): report the folder the user
        // actually picked, so the Done screen can name it.
        if (saved.isNotEmpty()) onSavedToFolder?.invoke(treeUri)
        when {
          saved.isNotEmpty() && failed == 0 -> onSaved(saved)
          saved.isNotEmpty() -> {
            Toast.makeText(
              ctx,
              LocaleHelper.t(ctx, "toast_save_partial")
                .replace("{saved}", "${saved.size}")
                .replace("{total}", "${files.size}")
                .replace("{failed}", "$failed"),
              Toast.LENGTH_LONG
            ).show()
            onSaved(saved)
          }
          else -> Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_save_failed_folder"), Toast.LENGTH_LONG).show()
        }
      }
    }
  }
  return { files ->
    pending = files
    writePendingFolderSave(ctx, files)
    launcher.launch(null)
  }
}

/** Single-file save flow shared by the one-output tools: build -> save
 *  picker opens directly -> DoneScreen. Returns the launcher trigger.
 *  FIX 2026-11-18: the picker MIME must match the file being saved. It
 *  was hardcoded to "application/pdf", so Image Resizer offered a
 *  JPG/PNG/WEBP file as a PDF — the system picker then creates a
 *  PDF-typed document for image bytes. PDF tools keep the default;
 *  image tools pass their own MIME type.
 *  FIX 2026-11-18 (build break, runs #121-#143): `mimeType` was added
 *  AFTER `onSaved`, so every trailing-lambda call
 *  (`rememberSingleFileSaver { file -> ... }`) bound the lambda to the
 *  String parameter and left `onSaved` empty — 16 compile errors, and
 *  every CI run after that push failed. `onSaved` MUST stay last so
 *  the trailing lambda binds to it. */
@Composable
fun rememberSingleFileSaver(
  mimeType: String = "application/pdf",
  onSavedToUri: ((Uri) -> Unit)? = null,
  onSaved: (File) -> Unit
): (File) -> Unit {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var pending by remember { mutableStateOf<File?>(null) }
  // PHASE 2 (M9): same recovery as rememberFolderSaver — if the process
  // died while the picker was open, the file already exists in the
  // Chami folder, so show Done for it instead of dropping the save.
  // The persisted MIME is kept with the path (see writePendingSingleSave)
  // even though recovery only needs the file itself.
  LaunchedEffect(Unit) {
    val stale = readPendingSingleSave(ctx)
    if (stale != null) {
      clearPendingSingleSave(ctx)
      if (stale.first.exists()) onSaved(stale.first)
    }
  }
  // FIX 2026-11-18 (report #21): the launcher is keyed on the MIME
  // type, so changing format (JPG -> PNG) after picking an image
  // recreates the picker with the current format instead of offering
  // the stale one. onSaved MUST stay last so trailing-lambda calls
  // (`rememberSingleFileSaver { file -> ... }`) still bind to it.
  // FIX 2026-11-18 (report #3): onSavedToUri reports the Uri the
  // user picked, so the Done screen can name the chosen location.
  val launcher = key(mimeType) {
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
      val file = pending
      pending = null
      clearPendingSingleSave(ctx)
      if (uri != null && file != null) {
        scope.launch {
          val ok = withContext(Dispatchers.IO) { SaveLocation.copyToUri(ctx, file, uri) }
          if (ok) {
            onSavedToUri?.invoke(uri)
            onSaved(file)
          } else Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_save_failed_file"), Toast.LENGTH_SHORT).show()
        }
      } else if (uri == null && file != null) {
        Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_save_cancelled_file"), Toast.LENGTH_SHORT).show()
      }
    }
  }
  return { file ->
    pending = file
    writePendingSingleSave(ctx, file, mimeType)
    launcher.launch(file.name)
  }
}

/** Done screen for multi-file results: tick, count, per-file list. */
@Composable
fun MultiDoneScreen(
  files: List<DoneFileInfo>,
  title: String,
  summaryLines: List<String>,
  theme: ChamiTheme,
  onBackHome: () -> Unit,
  onOpen: (File) -> Unit,
  savedLocationText: String? = null,
  onOpenPdfInReader: ((File) -> Unit)? = null
) {
  val ctx = LocalContext.current
  // PHASE 2 (H1): system Back on a multi-file Done screen goes Home
  // instead of exiting the app (this screen early-returns before
  // ToolScreenContainer, so it never gets that container's BackHandler).
  BackHandler { onBackHome() }
  Column(
    Modifier
      .fillMaxSize()
      .background(theme.bg)
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(Ds.s16)
      .verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Spacer(Modifier.height(Ds.s24))
    Box(
      Modifier.size(72.dp).clip(CircleShape).background(ChamiColors.lightGreen),
      contentAlignment = Alignment.Center
    ) {
      Icon(Icons.Filled.Check, contentDescription = "Done", tint = ChamiColors.darkGreen, modifier = Modifier.size(36.dp))
    }
    Spacer(Modifier.height(Ds.s16))
    TitleText(title, color = theme.text)
    Spacer(Modifier.height(Ds.s8))
    summaryLines.forEach { line ->
      Text(line, fontSize = Ds.BODY_SP.sp, color = theme.text, lineHeight = (Ds.BODY_SP * 1.4).sp)
      Spacer(Modifier.height(Ds.s4))
    }
    // FIX 2026-11-18 (report #3): the location the user actually
    // chose in the save picker, instead of "Chami folder".
    if (savedLocationText != null) {
      Text(
        LocaleHelper.t(ctx, "label_saved_to").replace("{location}", savedLocationText),
        fontSize = Ds.BODY_SP.sp, color = theme.text, lineHeight = (Ds.BODY_SP * 1.4).sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis
      )
      Spacer(Modifier.height(Ds.s4))
    }
    Spacer(Modifier.height(Ds.s16))
    Column(verticalArrangement = Arrangement.spacedBy(Ds.s8), modifier = Modifier.fillMaxWidth()) {
      files.forEach { info ->
        Row(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.tileRadius))
            .background(theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
            .padding(Ds.s12),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            if (info.mimeType.startsWith("image/")) Icons.Filled.Image else Icons.Filled.PictureAsPdf,
            contentDescription = null, tint = ChamiColors.darkGreen, modifier = Modifier.size(24.dp)
          )
          Spacer(Modifier.width(Ds.s12))
          Column(Modifier.weight(1f)) {
            Text(info.file.name, fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (info.detail.isNotBlank()) {
              Text(info.detail, fontSize = Ds.HINT_SP.sp, color = theme.textSecondary)
            }
          }
        }
      }
    }
    Spacer(Modifier.height(Ds.s24))
    ChamiPrimaryButton(
      text = LocaleHelper.t(ctx, "btn_open_first_file"),
      onClick = {
        files.firstOrNull()?.let {
          // FIX 2026-11-18 (report #4): a PDF opens in the inbuilt
          // Reader when the caller supplies onOpenPdfInReader.
          if (it.file.extension.lowercase() == "pdf" && onOpenPdfInReader != null) onOpenPdfInReader(it.file)
          else onOpen(it.file)
        }
      },
      modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(Ds.s12))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
      ChamiSecondaryButton(
        text = LocaleHelper.t(ctx, "btn_share_first_file"),
        onClick = { files.firstOrNull()?.let { ShareHelper.shareFile(ctx, it.file, it.mimeType) } },
        modifier = Modifier.weight(1f)
      )
      ChamiSecondaryButton(text = LocaleHelper.t(ctx, "done"), onClick = onBackHome, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(Ds.s16))
  }
}

// Big main preview (340dp, full width) with the left-corner vertical icon
// stack: Crop on top, then Delete, Filter, Adjust. Each is a
// 48dp PreviewActionButton with a real vector icon. The second crop
// (Straighten / perspective warp) was removed at the user's request —
// Crop is the only crop entry point on Images to PDF.
@Composable
fun BigPagePreview(
  page: PageItem?,
  label: String,
  theme: ChamiTheme,
  onCrop: () -> Unit,
  onDelete: () -> Unit,
  onFilter: () -> Unit,
  onAdjust: () -> Unit,
  height: Dp = 340.dp
) {
  val ctx = LocalContext.current
  Box(
    Modifier
      .fillMaxWidth()
      .height(height)
      .clip(RoundedCornerShape(Ds.s8))
      .background(theme.card)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.s8))
  ) {
    val shown = page?.bitmap
    // PHASE 4: gentle crossfade when the page changes (tap a thumbnail,
    // crop/filter/rotate) — the preview used to swap instantly, which
    // read as a flicker. Crossfade keeps the old bitmap alive until
    // the fade finishes; the A4 page box is fixed-size, so nothing
    // jumps during the transition.
    Crossfade(targetState = shown, label = "pagePreview") { bmp ->
      if (bmp != null) {
        // PERFORMANCE: wrap once per bitmap — this preview recomposes
        // on every page / selection change, and re-wrapping the
        // full-res bitmap each time dropped frames on low-end phones.
        val shownImage = remember(bmp) { bmp.asImageBitmap() }
        // FIX 2026-11-18 (fixed A4 page): the preview shows the FIXED A4
        // portrait page (595x842pt, white) with the image fitted inside
        // it at uniform 15mm margins, centred, aspect preserved — never
        // stretched, never cropped to fill. This is exactly what the
        // generated PDF page looks like.
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          val pageW = minOf(maxWidth, maxHeight * (595f / 842f))
          val pageH = pageW * (842f / 595f)
          Box(
            Modifier.size(pageW, pageH)
              .background(Color.White)
              .border(1.dp, theme.cardBorder)
          ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
              val padX = maxWidth * (15f / 210f)
              val padY = maxHeight * (15f / 297f)
              Image(
                bitmap = shownImage,
                contentDescription = label,
                modifier = Modifier.fillMaxSize().padding(horizontal = padX, vertical = padY),
                contentScale = ContentScale.Fit
              )
            }
          }
        }
      } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(LocaleHelper.t(ctx, "label_no_page_yet"), fontSize = Ds.HINT_SP.sp, color = theme.textSecondary)
        }
      }
    }
    Column(
      Modifier.align(Alignment.TopStart).padding(Ds.s8),
      verticalArrangement = Arrangement.spacedBy(Ds.s8)
    ) {
      // NOTE (M13): these descriptions are also the VISIBLE short labels
      // under the icons — DesignSystem derives the label from the text
      // before " —" (or the first word). Translations must keep the short
      // label first (and keep " —" where present) or the label breaks.
      PreviewActionButton(Icons.Filled.Crop, LocaleHelper.t(ctx, "preview_action_crop"), onCrop)
      PreviewActionButton(Icons.Filled.Delete, LocaleHelper.t(ctx, "preview_action_delete"), onDelete)
      // FIX 2026-11-18 (Fix 11): FilterList, NOT Filter. Verified by
      // rendering the exact vectors from material-icons-extended 1.7.0:
      // Icons.Filled.Filter draws a picture frame with mountains (the
      // user rightly read it as an image icon); FilterList is the
      // proper funnel glyph. Crop = crop corners, Delete = trash,
      // Tune = sliders — all three already read correctly, unchanged.
      PreviewActionButton(Icons.Filled.FilterList, LocaleHelper.t(ctx, "preview_action_filter"), onFilter)
      PreviewActionButton(Icons.Filled.Tune, LocaleHelper.t(ctx, "preview_action_adjust"), onAdjust)
    }
  }
}

// Thumbnail strip: 72dp thumbnails, 8dp gaps, selected thumbnail has a
// 2dp dark-green outline. Tap a thumbnail to change the big preview,
// hold and drag to reorder (drop at half a card width), big "+" tile
// after the last thumbnail.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageThumbnailStrip(
  pages: List<PageItem>,
  selectedIndex: Int,
  theme: ChamiTheme,
  onSelect: (Int) -> Unit,
  onMove: (Int, Int) -> Unit,
  onAddPages: () -> Unit
) {
  val density = LocalDensity.current
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  val latestPages by rememberUpdatedState(pages)
  val latestOnMove by rememberUpdatedState(onMove)
  // PHASE 2 (M11): thumbnails are ~200px downscales, same pattern as
  // ImagePageEditor.thumbnailBitmap — but ALWAYS a copy, never the page
  // bitmap itself, so the cache owns its bitmaps and can recycle them.
  // Keyed by page Uri (the Uri changes on every crop/rotate, so a stale
  // thumbnail cannot survive an edit); entries for removed pages are
  // recycled below, and the whole cache is recycled when the strip
  // leaves composition.
  val thumbCache = remember { HashMap<Uri, Bitmap>() }
  val currentUris = pages.map { it.uri }.toSet()
  // PHASE 4: pruning is a side effect, not composition work — it used
  // to run inline here (recycling bitmaps mid-composition).
  SideEffect {
    thumbCache.keys.filter { it !in currentUris }.forEach { uri ->
      thumbCache.remove(uri)?.let { bmp -> if (!bmp.isRecycled) bmp.recycle() }
    }
  }
  DisposableEffect(Unit) {
    onDispose {
      thumbCache.values.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
      thumbCache.clear()
    }
  }
  var draggingUri by remember { mutableStateOf<Uri?>(null) }
  var dragOffsetX by remember { mutableStateOf(0f) }
  val cardPx = with(density) { Ds.thumbStripHeight.toPx() }
  val gapPx = with(density) { Ds.s8.toPx() }
  val cardFullPx = cardPx + gapPx
  LazyRow(
    state = listState,
    horizontalArrangement = Arrangement.spacedBy(Ds.s8),
    modifier = Modifier.fillMaxWidth()
  ) {
    itemsIndexed(pages, key = { _, page -> page.uri.toString() }) { index, page ->
      val isSel = index == selectedIndex
      val isDragging = draggingUri == page.uri
      // PHASE 4: the 200px downscale runs on Dispatchers.Default and
      // the cell shows a skeleton until it lands — the old code scaled
      // the full-res bitmap synchronously during composition, so the
      // first scroll through a strip of phone photos janked. The cache
      // is still the owner (see above); a Uri change (crop/rotate)
      // resets the state and reloads, so no stale thumbnail survives.
      var thumbImage by remember(page.uri) {
        mutableStateOf<ImageBitmap?>(thumbCache[page.uri]?.asImageBitmap())
      }
      LaunchedEffect(page.uri) {
        if (thumbImage != null) return@LaunchedEffect
        val scaled = withContext(Dispatchers.Default) {
          thumbCache[page.uri] ?: run {
            val bmp = page.bitmap
            val h = (bmp.height * (200f / bmp.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, 200, h, true).also { thumbCache[page.uri] = it }
          }
        }
        thumbImage = scaled.asImageBitmap()
      }
      // PHASE 4: neighbours slide aside instead of teleporting when the
      // list reorders mid-drag. animateItemPlacement is applied to
      // every item EXCEPT the dragged one — the dragged card is glued
      // to the finger (dragOffsetX) and animating its placement would
      // make it jump back a card width, then glide forward.
      Column(
        modifier = if (isDragging) Modifier else Modifier.animateItemPlacement(),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Box(
          Modifier
            .size(Ds.thumbStripHeight)
            .graphicsLayer {
              translationX = if (isDragging) dragOffsetX else 0f
              scaleX = if (isDragging) 1.08f else 1f
              scaleY = if (isDragging) 1.08f else 1f
              shadowElevation = if (isDragging) 12f else 0f
              shape = RoundedCornerShape(Ds.s8)
              clip = true
            }
            .pointerInput(page.uri) {
              detectDragGesturesAfterLongPress(
                onDragStart = {
                  draggingUri = page.uri
                  dragOffsetX = 0f
                  try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                },
                onDrag = { change, dragAmount ->
                  change.consume()
                  dragOffsetX += dragAmount.x
                  val cur = latestPages.indexOfFirst { it.uri == page.uri }
                  if (cur < 0) return@detectDragGesturesAfterLongPress
                  val halfPx = cardFullPx / 2f
                  if (dragOffsetX > halfPx && cur < latestPages.lastIndex) {
                    latestOnMove(cur, cur + 1)
                    dragOffsetX -= cardFullPx
                    // PHASE 4: a light tick on every swap, so the drag
                    // feels physical — pickup/drop already tick; the
                    // in-between swaps were silent.
                    try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                  } else if (dragOffsetX < -halfPx && cur > 0) {
                    latestOnMove(cur, cur - 1)
                    dragOffsetX += cardFullPx
                    try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                  }
                  if (dragOffsetX > cardFullPx * 0.7f) scope.launch { listState.scrollBy(cardFullPx * 0.3f) }
                  else if (dragOffsetX < -cardFullPx * 0.7f) scope.launch { listState.scrollBy(-cardFullPx * 0.3f) }
                },
                onDragEnd = {
                  draggingUri = null
                  dragOffsetX = 0f
                  try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                },
                onDragCancel = { draggingUri = null; dragOffsetX = 0f }
              )
            }
            .background(theme.card)
            .border(2.dp, if (isSel) ChamiColors.darkGreen else theme.cardBorder, RoundedCornerShape(Ds.s8))
            .clickable { onSelect(index) }
        ) {
          // PHASE 4: skeleton while the downscale is still loading
          // (see LaunchedEffect above) — the cell never shows a blank
          // hole or a half-decoded image while scrolling.
          val loadedThumb = thumbImage
          if (loadedThumb != null) {
            Image(
              bitmap = loadedThumb,
              contentDescription = "Page ${index + 1}",
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop
            )
          } else {
            Box(Modifier.fillMaxSize().background(ChamiColors.skeletonBg))
          }
          if (isSel) {
            Box(
              Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
                .clip(CircleShape)
                .background(ChamiColors.darkGreen),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(16.dp))
            }
          }
        }
        Spacer(Modifier.height(Ds.s4))
        Text(LocaleHelper.t(ctx, "label_page_number").replace("{n}", "${index + 1}"),
          fontSize = Ds.HINT_SP.sp,
          fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
          color = if (isSel) theme.primary else theme.text
        )
      }
    }
    item {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
          Modifier
            .size(Ds.thumbStripHeight)
            .clip(RoundedCornerShape(Ds.s8))
            .background(theme.card)
            .border(2.dp, theme.primary, RoundedCornerShape(Ds.s8))
            .clickable { onAddPages() },
          contentAlignment = Alignment.Center
        ) {
          Icon(Icons.Filled.Add, contentDescription = "Add pages", tint = theme.primary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(Ds.s4))
        Text(LocaleHelper.t(ctx, "btn_add"), fontSize = Ds.HINT_SP.sp, color = theme.text)
      }
    }
  }
}

@Composable
fun FilterPickerDialog(
  selected: Int,
  theme: ChamiTheme,
  onPick: (Int) -> Unit,
  onDismiss: () -> Unit,
  bitmap: Bitmap? = null
) {
  val ctx = LocalContext.current
  val names = listOf(
    LocaleHelper.t(ctx, "label_original"),
    LocaleHelper.t(ctx, "filter_name_bw"),
    LocaleHelper.t(ctx, "filter_name_grayscale"),
    LocaleHelper.t(ctx, "filter_name_enhance")
  )
  // PHASE 4: live previews — one small downscale, each filter applied
  // to it off the main thread, so the user sees the effect before
  // tapping instead of applying blind. The tap behaviour is unchanged
  // (apply on tap, dialog closes); the previews only show what the tap
  // will do. The same ImagePageEditor.applyFilter runs on the small
  // copy, so the preview matches the result.
  val previews = remember { mutableStateMapOf<Int, ImageBitmap?>() }
  LaunchedEffect(bitmap) {
    previews.clear()
    val src = bitmap ?: return@LaunchedEffect
    val small = withContext(Dispatchers.Default) {
      val w = 160
      val h = (src.height * (w.toFloat() / src.width)).toInt().coerceAtLeast(1)
      Bitmap.createScaledBitmap(src, w, h, true)
    }
    for (mode in 0..3) {
      val filtered = withContext(Dispatchers.Default) {
        if (mode == 0) small else ImagePageEditor.applyFilter(small, mode)
      }
      previews[mode] = filtered.asImageBitmap()
    }
  }
  ChamiAlertDialog(theme,
    onDismissRequest = onDismiss,
    title = { Text(LocaleHelper.t(ctx, "dialog_filter_title"), fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold, color = theme.text) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(Ds.s8)) {
        if (bitmap != null) {
          // 2x2 grid of preview cards: image on top, name below; the
          // selected card gets the green border + check badge, so the
          // current filter is visible at a glance.
          listOf(0 to 1, 2 to 3).forEach { (a, b) ->
            Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
              listOf(a, b).forEach { i ->
                Column(
                  Modifier.weight(1f)
                    .clip(RoundedCornerShape(Ds.s8))
                    .background(theme.card)
                    .border(2.dp, if (selected == i) ChamiColors.darkGreen else theme.cardBorder, RoundedCornerShape(Ds.s8))
                    .clickable { onPick(i) },
                  horizontalAlignment = Alignment.CenterHorizontally
                ) {
                  Box(
                    Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                      .clip(RoundedCornerShape(Ds.s8))
                      .background(ChamiColors.skeletonBg)
                  ) {
                    previews[i]?.let { preview ->
                      Image(
                        bitmap = preview,
                        contentDescription = names[i],
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                      )
                    }
                    if (selected == i) {
                      Box(
                        Modifier
                          .align(Alignment.TopEnd)
                          .padding(Ds.s4)
                          .size(24.dp)
                          .clip(CircleShape)
                          .background(ChamiColors.darkGreen),
                        contentAlignment = Alignment.Center
                      ) {
                        Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(16.dp))
                      }
                    }
                  }
                  Text(
                    names[i],
                    fontSize = Ds.BODY_SP.sp,
                    fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected == i) ChamiColors.deepGreen else theme.text,
                    modifier = Modifier.padding(vertical = Ds.s8)
                  )
                }
              }
            }
          }
        } else {
          Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
            names.take(2).forEachIndexed { i, n ->
              ChamiChip(n, selected == i, { onPick(i) }, Modifier.weight(1f))
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
            names.drop(2).forEachIndexed { k, n ->
              val i = k + 2
              ChamiChip(n, selected == i, { onPick(i) }, Modifier.weight(1f))
            }
          }
        }
        HintText(LocaleHelper.t(ctx, "hint_filter_page_only"))
      }
    },
    // FIX 2026-11-18 (report #9): filters apply on tap, so this
    // button only closes the dialog — label it "Close", not "Done".
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(LocaleHelper.t(ctx, "btn_close"), color = theme.primaryDark, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold)
      }
    }
  )
}

@Composable
fun BrightnessDialog(
  value: Float,
  theme: ChamiTheme,
  onValueChange: (Float) -> Unit,
  onApply: () -> Unit,
  onDismiss: () -> Unit,
  bitmap: Bitmap? = null
) {
  val ctx = LocalContext.current
  // PHASE 4: wrap once — the slider recomposes this dialog on every
  // move, and re-wrapping the full-res bitmap each frame is wasted
  // allocation on the drag path.
  val previewImage = remember(bitmap) { bitmap?.asImageBitmap() }
  ChamiAlertDialog(theme,
    onDismissRequest = onDismiss,
    title = { Text(LocaleHelper.t(ctx, "dialog_brightness_title"), fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold, color = theme.text) },
    text = {
      Column {
        // FIX 2026-11-18 (report #8): live preview — the image is
        // shown with the slider's brightness applied (same ColorMatrix
        // offset ImagePageEditor.adjustBrightness uses), so the effect
        // is visible while sliding, not only after Apply.
        if (previewImage != null) {
          Image(
            bitmap = previewImage,
            contentDescription = "Brightness preview",
            colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
              androidx.compose.ui.graphics.ColorMatrix(
                floatArrayOf(
                  1f, 0f, 0f, 0f, value,
                  0f, 1f, 0f, 0f, value,
                  0f, 0f, 1f, 0f, value,
                  0f, 0f, 0f, 1f, 0f
                )
              )
            ),
            modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(Ds.s8)),
            contentScale = ContentScale.Fit
          )
          Spacer(Modifier.height(Ds.s12))
        }
        Text(LocaleHelper.t(ctx, "label_brightness_value").replace("{n}", "${value.toInt()}"), fontSize = Ds.BODY_SP.sp, color = theme.textSecondary)
        Slider(
          value = value,
          onValueChange = onValueChange,
          valueRange = -50f..50f,
          colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary)
        )
      }
    },
    confirmButton = {
      Button(onClick = onApply, colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) {
        Text(LocaleHelper.t(ctx, "btn_apply"), color = theme.textOnPrimary, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(LocaleHelper.t(ctx, "btn_cancel"), color = theme.text) }
    }
  )
}


// --- Rectangular CUT crop dialog (default crop) ---------------------------
// Crop CUTS: dragging a corner or edge removes everything outside the
// frame. It never bends, warps or changes perspective. Ratio presets:
// Free / Original / 1:1 / 3:4 / 4:3 / 16:9 / A4. Apply calls
// ImagePageEditor.cropToRect.
@Composable
fun RectCropDialog(
  bitmap: Bitmap,
  theme: ChamiTheme,
  onApply: (Bitmap) -> Unit,
  onDismiss: () -> Unit,
  onRotate: () -> Unit = {}
) {
  var rect by remember(bitmap) { mutableStateOf(ComposeRect(0f, 0f, 1f, 1f)) }
  var ratioLabel by rememberSaveable { mutableStateOf("Free") }
  // PHASE 4: which handle (if any) is being dragged right now — drives
  // the magnifier loupe below and the handle's pressed scale. The
  // geometry functions are untouched; this is feedback only.
  var activeHandle by remember { mutableStateOf<String?>(null) }
  val ctx = LocalContext.current
  // PERFORMANCE: wrap the bitmap once — the drag path recomposes on every
  // pointer move, and asImageBitmap() on every frame made the crop drag
  // stutter on low-end phones.
  val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
  val ratios = listOf(
    "Free" to null as Float?,
    "Original" to null,
    "1:1" to 1f,
    "3:4" to 0.75f,
    "4:3" to (4f / 3f),
    "16:9" to (16f / 9f),
    "A4" to (1f / 1.414f)
  )
  fun ratioRect(ratio: Float?): ComposeRect {
    if (ratio == null) return ComposeRect(0f, 0f, 1f, 1f)
    val imgAspect = bitmap.width.toFloat() / bitmap.height
    return if (ratio >= imgAspect) {
      val h = imgAspect / ratio
      ComposeRect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
    } else {
      val w = ratio / imgAspect
      ComposeRect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
    }
  }
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    // FIX 2026-11-18 (Rotate/Reset unreachable): this dialog window runs
    // edge-to-edge, and the Column had NO WindowInsets padding — unlike
    // every other full-screen surface in the app (ToolScreenContainer,
    // MainActivity and the other full-screen surfaces all add statusBarsPadding /
    // navigationBarsPadding). The bottom Reset / Rotate 90° row therefore
    // sat UNDER the system navigation bar and could not be tapped.
    // statusBarsPadding + navigationBarsPadding keep the fixed bottom
    // action bar above the nav bar on every phone.
    Column(
      Modifier.fillMaxSize().background(Color.Black)
        .statusBarsPadding().navigationBarsPadding().padding(Ds.s16)
    ) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onDismiss, modifier = Modifier.height(Ds.minTouch)) {
          Text(LocaleHelper.t(ctx, "btn_cancel"), color = Color.White, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(LocaleHelper.t(ctx, "dialog_crop_title"), color = Color.White, fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold)
        TextButton(
          onClick = {
            try {
              onApply(ImagePageEditor.cropToRect(bitmap, rect, 1f))
            } catch (_: Exception) { onDismiss() }
          },
          modifier = Modifier.height(Ds.minTouch)
        ) {
          // M30: theme.primaryDark (#14532D) on this black dialog was
          // ~2:1 — unreadable. theme.accent (#22C55E) is 9.2:1 on black,
          // passing WCAG AA, and stays distinct from the white Cancel.
          Text(LocaleHelper.t(ctx, "btn_apply"), color = theme.accent, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold)
        }
      }
      Spacer(Modifier.height(Ds.s8))
      Text(
        LocaleHelper.t(ctx, "hint_crop_drag"),
        fontSize = Ds.HINT_SP.sp, color = Color(0xFFCBD5E1)
      )
      Spacer(Modifier.height(Ds.s8))
      Row(
        horizontalArrangement = Arrangement.spacedBy(Ds.s8),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
      ) {
        ratios.forEach { (label, ratio) ->
          // The ratio label is the internal lock key ("Free"/"Original"
          // drive the ratio logic), so only the DISPLAY text is
          // translated — numeric ratios ("1:1", "A4") need no key.
          val displayLabel = when (label) {
            "Free" -> LocaleHelper.t(ctx, "label_free")
            "Original" -> LocaleHelper.t(ctx, "label_original")
            else -> label
          }
          ChamiChip(displayLabel, ratioLabel == label, {
            ratioLabel = label
            rect = ratioRect(ratio)
          })
        }
      }
      Spacer(Modifier.height(Ds.s16))
      BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val imgAspect = bitmap.width.toFloat() / bitmap.height
        // FIX 2026-11-18 (fixed A4 page): the background is a FIXED A4
        // portrait page (595x842pt), fitted into this box — it never
        // changes size with the image. The image is fitted INSIDE that
        // page with uniform 15mm margins, centred, aspect preserved
        // (never stretched, never cropped to fill) — exactly what the
        // PDF page will look like. Crop fractions still map to the
        // image itself (offX/offY/dispW/dispH below), so cropping is
        // unchanged; only the page behind it is now fixed A4.
        val pageAspect = 595f / 842f
        val pageW = if (boxW / boxH > pageAspect) boxH * pageAspect else boxW
        val pageH = pageW / pageAspect
        val pageOffX = (boxW - pageW) / 2f
        val pageOffY = (boxH - pageH) / 2f
        val availW = pageW * (1f - 2f * 15f / 210f)
        val availH = pageH * (1f - 2f * 15f / 297f)
        val dispW = if (availW / availH > imgAspect) availH * imgAspect else availW
        val dispH = dispW / imgAspect
        val offX = pageOffX + (pageW - dispW) / 2f
        val offY = pageOffY + (pageH - dispH) / 2f
        fun toPxX(f: Float) = offX + f * dispW
        fun toPxY(f: Float) = offY + f * dispH

        // FIX 2026-11-18 (Fix 3): the active ratio lock. "Free" = no
        // lock; "Original" = the image's own aspect; every other chip
        // is a fixed width/height ratio. The drag handlers below MUST
        // honour this. Before this fix they ignored it completely:
        // there was no way to MOVE a ratio-locked box at all (only its
        // handles moved), and the first corner/edge drag turned a 1:1
        // box free-form.
        val activeRatio: Float? = when (ratioLabel) {
          "Free" -> null
          "Original" -> imgAspect
          else -> ratios.firstOrNull { it.first == ratioLabel }?.second
        }

        // Move the whole frame without changing its size or shape.
        fun moveRect(cur: ComposeRect, dx: Float, dy: Float): ComposeRect {
          val w = cur.width; val h = cur.height
          val l = (cur.left + dx).coerceIn(0f, 1f - w)
          val t = (cur.top + dy).coerceIn(0f, 1f - h)
          return ComposeRect(l, t, l + w, t + h)
        }

        // Resize a corner while KEEPING a locked ratio: the drag
        // dimension that moved most drives the resize, anchored at the
        // opposite corner; the other dimension follows from the ratio,
        // so the box NEVER goes free-form mid-drag.
        fun resizeCornerRatio(cur: ComposeRect, corner: String, dx: Float, dy: Float, ratio: Float): ComposeRect {
          val min = 0.05f
          // FIX: a crop's real (pixel) ratio is (w*W)/(h*H) = (w/h)*imgAspect,
          // so in fraction space the frame must keep w/h = ratio/imgAspect.
          // The old code used ratio directly in fraction space, which locked
          // the PIXEL ratio to ratio*imgAspect — on any non-square photo the
          // first corner drag snapped a 1:1 frame to the photo's own shape,
          // which reads as "the ratio lock broke / it went free". Verified
          // numerically: 1500x2000 photo, 1:1, shrink BR corner — old code
          // gave pixel ratio 0.75, fixed code keeps 1.0 in every direction.
          val fracRatio = ratio / imgAspect
          val anchorX = if (corner == "tl" || corner == "bl") cur.right else cur.left
          val anchorY = if (corner == "tl" || corner == "tr") cur.bottom else cur.top
          val movesLeft = corner == "tl" || corner == "bl"
          val movesTop = corner == "tl" || corner == "tr"
          val availW = if (movesLeft) anchorX else 1f - anchorX
          val availH = if (movesTop) anchorY else 1f - anchorY
          // Drive by whichever dimension the finger moved most (dy
          // converted to width units through the fraction-space ratio, so
          // the comparison is fair).
          val widthDriven = kotlin.math.abs(dx) >= kotlin.math.abs(dy) * fracRatio
          val w: Float; val h: Float
          if (widthDriven) {
            val maxW = minOf(availW, availH * fracRatio)
            if (maxW < min) return cur
            val rawW = if (movesLeft) anchorX - (cur.left + dx).coerceIn(0f, anchorX - min)
              else (cur.right + dx).coerceIn(anchorX + min, 1f) - anchorX
            w = rawW.coerceIn(min, maxW)
            h = w / fracRatio
          } else {
            val maxH = minOf(availH, availW / fracRatio)
            if (maxH < min) return cur
            val rawH = if (movesTop) anchorY - (cur.top + dy).coerceIn(0f, anchorY - min)
              else (cur.bottom + dy).coerceIn(anchorY + min, 1f) - anchorY
            h = rawH.coerceIn(min, maxH)
            w = h * fracRatio
          }
          val l = if (movesLeft) anchorX - w else anchorX
          val t = if (movesTop) anchorY - h else anchorY
          return ComposeRect(l, t, l + w, t + h)
        }

        // Resize an edge while KEEPING a locked ratio: the dragged
        // dimension drives, the other follows, centred on the frame.
        fun resizeEdgeRatio(cur: ComposeRect, edge: String, dx: Float, dy: Float, ratio: Float): ComposeRect {
          val min = 0.05f
          // Same fraction-vs-pixel fix as resizeCornerRatio: the frame must
          // keep w/h = ratio/imgAspect in fraction space.
          val fracRatio = ratio / imgAspect
          val cx = (cur.left + cur.right) / 2f
          val cy = (cur.top + cur.bottom) / 2f
          return when (edge) {
            "t", "b" -> {
              var h = if (edge == "t") cur.bottom - (cur.top + dy).coerceIn(0f, cur.bottom - min)
                else (cur.bottom + dy).coerceIn(cur.top + min, 1f) - cur.top
              h = h.coerceIn(min, 1f)
              var w = h * fracRatio
              if (w > 1f) { w = 1f; h = w / fracRatio }
              val l = (cx - w / 2f).coerceIn(0f, 1f - w)
              val t = if (edge == "t") cur.bottom - h else cur.top
              ComposeRect(l, t, l + w, t + h)
            }
            else -> { // "l" / "r"
              var w = if (edge == "l") cur.right - (cur.left + dx).coerceIn(0f, cur.right - min)
                else (cur.right + dx).coerceIn(cur.left + min, 1f) - cur.left
              w = w.coerceIn(min, 1f)
              var h = w / fracRatio
              if (h > 1f) { h = 1f; w = h * fracRatio }
              val t = (cy - h / 2f).coerceIn(0f, 1f - h)
              val l = if (edge == "l") cur.right - w else cur.left
              ComposeRect(l, t, l + w, t + h)
            }
          }
        }

        Box(Modifier.fillMaxSize()) {
          // Fixed A4 page background (white) behind the fitted image.
          Box(
            Modifier
              .offset(x = with(density) { pageOffX.toDp() }, y = with(density) { pageOffY.toDp() })
              .size(width = with(density) { pageW.toDp() }, height = with(density) { pageH.toDp() })
              .background(Color.White)
              .border(1.dp, Color(0xFF94A3B8))
          )
          Image(
            bitmap = imageBitmap, contentDescription = "Crop preview",
            modifier = Modifier
              .offset(x = with(density) { offX.toDp() }, y = with(density) { offY.toDp() })
              .size(width = with(density) { dispW.toDp() }, height = with(density) { dispH.toDp() }),
            contentScale = ContentScale.FillBounds
          )
          androidx.compose.foundation.Canvas(
            Modifier.fillMaxSize()
          ) {
            val left = toPxX(rect.left); val top = toPxY(rect.top)
            val right = toPxX(rect.right); val bottom = toPxY(rect.bottom)
            drawRect(Color.Black.copy(alpha = 0.5f), topLeft = androidx.compose.ui.geometry.Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(size.width, top))
            drawRect(Color.Black.copy(alpha = 0.5f), topLeft = androidx.compose.ui.geometry.Offset(0f, bottom), size = androidx.compose.ui.geometry.Size(size.width, size.height - bottom))
            drawRect(Color.Black.copy(alpha = 0.5f), topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(left, bottom - top))
            drawRect(Color.Black.copy(alpha = 0.5f), topLeft = androidx.compose.ui.geometry.Offset(right, top), size = androidx.compose.ui.geometry.Size(size.width - right, bottom - top))
            drawRect(
              color = ChamiColors.brightGreen,
              topLeft = androidx.compose.ui.geometry.Offset(left, top),
              size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
              style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
            val gw = (right - left) / 3f; val gh = (bottom - top) / 3f
            for (i in 1..2) {
              drawLine(ChamiColors.brightGreen.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(left + gw * i, top), androidx.compose.ui.geometry.Offset(left + gw * i, bottom))
              drawLine(ChamiColors.brightGreen.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(left, top + gh * i), androidx.compose.ui.geometry.Offset(right, top + gh * i))
            }
          }
          // 8 handles, 48dp touch targets: 4 corners + 4 edge midpoints.
          val handles = listOf(
            "tl" to androidx.compose.ui.geometry.Offset(rect.left, rect.top),
            "tr" to androidx.compose.ui.geometry.Offset(rect.right, rect.top),
            "bl" to androidx.compose.ui.geometry.Offset(rect.left, rect.bottom),
            "br" to androidx.compose.ui.geometry.Offset(rect.right, rect.bottom),
            "t" to androidx.compose.ui.geometry.Offset((rect.left + rect.right) / 2f, rect.top),
            "b" to androidx.compose.ui.geometry.Offset((rect.left + rect.right) / 2f, rect.bottom),
            "l" to androidx.compose.ui.geometry.Offset(rect.left, (rect.top + rect.bottom) / 2f),
            "r" to androidx.compose.ui.geometry.Offset(rect.right, (rect.top + rect.bottom) / 2f)
          )
          // Keyed on the handle id ONLY: keying on rect would restart
          // (and cancel) the drag on its first pixel, because dragging
          // changes rect. The current rect is read via rememberUpdatedState.
          val latestRect by rememberUpdatedState(rect)
          val latestRatio by rememberUpdatedState(activeRatio)
          // Drag the BOX ITSELF to move it (Fix 3): a drag starting
          // inside the frame (and not on a handle) translates the whole
          // frame, clamped inside the image, without changing its size
          // or shape — for every ratio, Free included. Handles are
          // declared after this layer, so a drag starting on a handle
          // still goes to the handle; the 24dp exclusion below is the
          // belt-and-braces for the same rule.
          // PHASE 2 (M21): the move handler reads the display geometry
          // (toPxX/toPxY/dispW/dispH) captured at setup — keying the
          // pointerInput on the box size restarts it after a rotation,
          // so drags map to the new geometry instead of the stale one.
          // The current rect/ratio are still read via rememberUpdatedState.
          Box(
            Modifier.fillMaxSize().pointerInput("move", boxW, boxH) {
              var moveActive = false
              detectDragGestures(
                onDragStart = { start ->
                  val cur = latestRect
                  val inside = start.x in toPxX(cur.left)..toPxX(cur.right) &&
                    start.y in toPxY(cur.top)..toPxY(cur.bottom)
                  val excludePx = with(density) { 24.dp.toPx() }
                  val handleFracs = listOf(
                    Offset(cur.left, cur.top), Offset(cur.right, cur.top),
                    Offset(cur.left, cur.bottom), Offset(cur.right, cur.bottom),
                    Offset((cur.left + cur.right) / 2f, cur.top),
                    Offset((cur.left + cur.right) / 2f, cur.bottom),
                    Offset(cur.left, (cur.top + cur.bottom) / 2f),
                    Offset(cur.right, (cur.top + cur.bottom) / 2f)
                  )
                  val onHandle = handleFracs.any { frac ->
                    (Offset(toPxX(frac.x), toPxY(frac.y)) - start).getDistance() < excludePx
                  }
                  moveActive = inside && !onHandle
                },
                onDragEnd = { moveActive = false },
                onDragCancel = { moveActive = false },
                onDrag = { change, drag ->
                  if (!moveActive) return@detectDragGestures
                  change.consume()
                  rect = moveRect(latestRect, drag.x / dispW, drag.y / dispH)
                }
              )
            }
          )
          handles.forEach { (id, frac) ->
            Box(
              Modifier
                .offset(
                  x = with(density) { (toPxX(frac.x) - 24.dp.toPx()).toDp() },
                  y = with(density) { (toPxY(frac.y) - 24.dp.toPx()).toDp() }
                )
                .size(Ds.minTouch)
                // PHASE 2 (M21): keyed on the display size as well as the
                // handle id, so a rotation restarts the handler with fresh
                // geometry instead of dragging against stale coordinates.
                // (Still NOT keyed on rect — that would cancel the drag
                // on its first pixel.)
                .pointerInput(id, dispW, dispH) {
                  detectDragGestures(
                    onDragStart = { activeHandle = id },
                    onDragEnd = { activeHandle = null },
                    onDragCancel = { activeHandle = null },
                    onDrag = { change, drag ->
                    change.consume()
                    val cur = latestRect
                    val dx = drag.x / dispW
                    val dy = drag.y / dispH
                    val ratio = latestRatio
                    rect = if (ratio == null) {
                      // Free: the exact old behaviour, untouched.
                      val min = 0.05f
                      when (id) {
                        "tl" -> ComposeRect((cur.left + dx).coerceIn(0f, cur.right - min), (cur.top + dy).coerceIn(0f, cur.bottom - min), cur.right, cur.bottom)
                        "tr" -> ComposeRect(cur.left, (cur.top + dy).coerceIn(0f, cur.bottom - min), (cur.right + dx).coerceIn(cur.left + min, 1f), cur.bottom)
                        "bl" -> ComposeRect((cur.left + dx).coerceIn(0f, cur.right - min), cur.top, cur.right, (cur.bottom + dy).coerceIn(cur.top + min, 1f))
                        "br" -> ComposeRect(cur.left, cur.top, (cur.right + dx).coerceIn(cur.left + min, 1f), (cur.bottom + dy).coerceIn(cur.top + min, 1f))
                        "t" -> ComposeRect(cur.left, (cur.top + dy).coerceIn(0f, cur.bottom - min), cur.right, cur.bottom)
                        "b" -> ComposeRect(cur.left, cur.top, cur.right, (cur.bottom + dy).coerceIn(cur.top + min, 1f))
                        "l" -> ComposeRect((cur.left + dx).coerceIn(0f, cur.right - min), cur.top, cur.right, cur.bottom)
                        else -> ComposeRect(cur.left, cur.top, (cur.right + dx).coerceIn(cur.left + min, 1f), cur.bottom)
                      }
                    } else if (id == "tl" || id == "tr" || id == "bl" || id == "br") {
                      // Ratio locked: corner resize keeps the ratio.
                      resizeCornerRatio(cur, id, dx, dy, ratio)
                    } else {
                      // Ratio locked: edge resize keeps the ratio.
                      resizeEdgeRatio(cur, id, dx, dy, ratio)
                    }
                    }
                  )
                },
              contentAlignment = Alignment.Center
            ) {
              // PHASE 4: the handle being dragged grows slightly, so the
              // finger's grip point stays visible under the finger.
              Box(
                Modifier.size(20.dp)
                  .graphicsLayer {
                    val s = if (activeHandle == id) 1.3f else 1f
                    scaleX = s; scaleY = s
                  }
                  .clip(CircleShape).background(Color.White).border(2.dp, ChamiColors.darkGreen, CircleShape)
              )
            }
          }
          // PHASE 4: magnifier loupe while a handle is dragged — a 2x
          // circular view of the pixels under the finger, above the
          // handle, so a precise cut is possible on small screens.
          // Same pattern as the loupe in CropEditorDialog: the loupe
          // shows the image at 2x with the handle point centred; the
          // image is offset by (offX, offY) inside this box, so the
          // handle's box coordinates map to image coordinates first.
          activeHandle?.let { handleId ->
            val frac = handles.firstOrNull { it.first == handleId }?.second ?: return@let
            val hx = toPxX(frac.x)
            val hy = toPxY(frac.y)
            val loupePx = with(density) { 120.dp.toPx() }
            val half = loupePx / 2f
            val loupeX = (hx - half).coerceIn(0f, (boxW - loupePx).coerceAtLeast(0f))
            val loupeY = (hy - loupePx - with(density) { Ds.s24.toPx() }).coerceIn(0f, (boxH - loupePx).coerceAtLeast(0f))
            Box(
              Modifier
                .offset { IntOffset(loupeX.roundToInt(), loupeY.roundToInt()) }
                .size(120.dp)
                .clip(CircleShape)
                .background(Color.Black)
                .border(2.dp, Color.White, CircleShape)
            ) {
              Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier
                  .size(
                    width = with(density) { (dispW * 2f).toDp() },
                    height = with(density) { (dispH * 2f).toDp() }
                  )
                  .offset {
                    IntOffset(
                      (half - (hx - offX) * 2f).roundToInt(),
                      (half - (hy - offY) * 2f).roundToInt()
                    )
                  },
                contentScale = ContentScale.FillBounds
              )
            }
          }
        }
      }
      Spacer(Modifier.height(Ds.s16))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
        ChamiSecondaryButton(text = LocaleHelper.t(ctx, "btn_reset"), onClick = { rect = ratioRect(ratios.first { it.first == ratioLabel }.second) }, modifier = Modifier.weight(1f))
        ChamiSecondaryButton(text = LocaleHelper.t(ctx, "btn_rotate_90"), onClick = onRotate, modifier = Modifier.weight(1f))
      }
    }
  }
}

// --- Shared page-editing helper (Images to PDF) ---------------------------
// PHASE 2 (H2): there is exactly ONE bitmap per page in memory. The old
// filterOriginals map held a second full-res copy of every filtered page.
// The filter base is now re-derived from the page's source Uri whenever
// a filter is (re-)applied: replacePage persists the unfiltered base
// bitmap to the page's temp file, so loading that Uri gives the base
// back — no second copy is ever held. (Crop/rotate/brightness persist
// the displayed bitmap itself, which is the base for later filters.)
// PHASE 2 (L5): the busy guard is vm.editBusy (ViewModel state), not a
// plain var on this remembered object, so it survives composition
// recreation and a second edit cannot start mid-edit.
class PageEditor(
  private val ctx: Context,
  private val scope: kotlinx.coroutines.CoroutineScope,
  private val vm: PagesViewModel
) {
  /** Replaces page [index] with [bmp], saving it to a temp file first so
   *  the PDF uses the edited image. [newOriginal] is the bitmap written
   *  to that temp file — the filter base. It defaults to [bmp] (crop /
   *  rotate / brightness: the displayed image IS the base); a filter
   *  apply passes the unfiltered base here so "reset filter" can reload
   *  it from the Uri later. Pass null to persist [bmp] itself. */
  fun replacePage(index: Int, bmp: Bitmap, newOriginal: Bitmap? = bmp, onDone: () -> Unit = {}) {
    val page = vm.pages.getOrNull(index) ?: return
    if (vm.editBusy.value) {
      // FIX 2026-11-18 (report #49): a tap dropped while an edit is
      // running says so, instead of silently doing nothing (same
      // wording as applyFilter's busy toast below).
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_edit_busy"), Toast.LENGTH_SHORT).show()
      return
    }
    vm.editBusy.value = true
    scope.launch {
      try {
        replacePageInternal(index, bmp, newOriginal)
        onDone()
      } catch (_: Exception) {
        Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_edit_failed"), Toast.LENGTH_SHORT).show()
      } finally {
        vm.editBusy.value = false
      }
    }
  }

  /** The shared write + list update. Runs inside the caller's coroutine
   *  (which already holds the busy guard). The superseded page bitmap
   *  is recycled when nothing else needs it. */
  private suspend fun replacePageInternal(index: Int, bmp: Bitmap, newOriginal: Bitmap?) {
    val page = vm.pages.getOrNull(index) ?: return
    val fileBmp = newOriginal ?: bmp
    val newUri = ImagePageEditor.bitmapToUri(ctx, fileBmp, "page_${index}_${System.currentTimeMillis()}.jpg")
    val oldUri = page.uri
    val oldBitmap = page.bitmap
    val wasSelected = vm.selectedUri == oldUri
    val oldMode = vm.filterModes.remove(oldUri)
    val updated = vm.pages.toMutableList()
    if (index in updated.indices) updated[index] = PageItem(newUri, bmp, cropped = true)
    vm.setPages(updated)
    if (wasSelected) vm.selectedUri = newUri
    if (oldMode != null) vm.filterModes[newUri] = oldMode
    if (oldBitmap !== bmp && oldBitmap !== fileBmp && !oldBitmap.isRecycled) oldBitmap.recycle()
  }

  /** Loads the unfiltered base for [page] from its source Uri (2048px
   *  working cap, same as the picker). Falls back to the displayed
   *  bitmap if the Uri cannot be reloaded, so a filter tap never
   *  fails outright — it just filters the current image. */
  private suspend fun loadFilterBase(page: PageItem): Bitmap {
    val loaded = try {
      withContext(Dispatchers.IO) { PdfEngine.loadBitmap(ctx, page.uri, 2048) }
    } catch (_: OutOfMemoryError) {
      null
    } catch (_: Exception) {
      null
    }
    return loaded ?: page.bitmap
  }

  /** Applies filter [mode] to page [index] (0 = Original). The mode is
   *  stored per page Uri, so switching pages never resets it, and
   *  filters never stack: each applies to the base reloaded from the
   *  page's Uri (see loadFilterBase). */
  fun applyFilter(index: Int, mode: Int) {
    val page = vm.pages.getOrNull(index) ?: return
    if (vm.editBusy.value) {
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_edit_busy"), Toast.LENGTH_SHORT).show()
      return
    }
    vm.editBusy.value = true
    vm.filterModes[page.uri] = mode
    scope.launch {
      try {
        val base = loadFilterBase(page)
        val result = if (mode == 0) base else ImagePageEditor.applyFilter(base, mode)
        replacePageInternal(index, result, base)
      } catch (_: OutOfMemoryError) {
        Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_edit_failed"), Toast.LENGTH_SHORT).show()
      } catch (_: Exception) {
        Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_edit_failed"), Toast.LENGTH_SHORT).show()
      } finally {
        vm.editBusy.value = false
      }
    }
  }

  fun filterModeFor(page: PageItem): Int = vm.filterModes[page.uri] ?: 0
}

// 1. IMAGES TO PDF
// - Builds through the shared engine: PdfEngine.buildImagesPdf with
//   FIXED defaults — A4 page (595x842pt, fixed whatever the crop),
//   full-resolution quality, no watermark, no page numbers, 15mm
//   default margins (no option controls).
// - Picker loads at a 2048px working cap (PHASE 2, H2 — the old 8000px
//   cap kept every phone photo at full resolution in memory).
// - Crop CUTS by default (RectCropDialog) — the only crop on this
//   screen; the second crop (Straighten / warp) was removed at the
//   user's request. Per-page filter state, correct delete, selection
//   follows the page Uri.
@Composable
fun ImagesToPdfScreen(theme: ChamiTheme, onOpenPdfInReader: ((File) -> Unit)? = null, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  val vm: PagesViewModel = viewModel(key = "images")
  val editor = remember(ctx, scope, vm) { PageEditor(ctx, scope, vm) }
  var isProcessing by remember { mutableStateOf(false) }
  var progressText by remember { mutableStateOf("") }
  var cropIndex by remember { mutableStateOf<Int?>(null) }
  var showFilters by remember { mutableStateOf(false) }
  var showAdjust by remember { mutableStateOf(false) }
  var brightness by remember { mutableStateOf(0f) }

  var doneFile by remember { mutableStateOf<File?>(null) }
  var showDone by remember { mutableStateOf(false) }
  // FIX 2026-11-18 (report #3): the picked save location's display
  // name is shown on the Done screen instead of "Chami folder".
  var savedLocationText by remember { mutableStateOf<String?>(null) }
  val saveFile = rememberSingleFileSaver(
    onSavedToUri = { uri -> savedLocationText = displayNameOf(ctx, uri) }
  ) { file ->
    HistoryStore.record(ctx, file.name, "img_pdf")
    doneFile = file
    showDone = true
  }

  val pages = vm.pages
  val safeSelected = vm.selectedIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
  val selectedPage = pages.getOrNull(safeSelected)
  val filterMode = selectedPage?.let { editor.filterModeFor(it) } ?: 0

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
    if (selected.isNotEmpty()) {
      showDone = false
      doneFile = null
      scope.launch(Dispatchers.IO) {
        // PHASE 2 (H2): 2048px long-side working cap — a 4000x3000 phone
        // photo stays ~12 MB instead of ~48 MB, and every page holds
        // exactly one bitmap (no second full-res copy anywhere).
        // PHASE 2 (M5): unloadable photos are counted, not silently
        // dropped — the user is told how many were skipped.
        var skipped = 0
        val added = mutableListOf<PageItem>()
        for (uri in selected) {
          val bmp = try {
            PdfEngine.loadBitmap(ctx, uri, 2048)
          } catch (_: OutOfMemoryError) {
            null
          } catch (_: Exception) {
            null
          }
          if (bmp == null) skipped++ else added.add(PageItem(uri, bmp))
        }
        withContext(Dispatchers.Main) {
          val firstNew = pages.size
          vm.setPages(pages + added, selectFirstNew = firstNew)
          if (skipped > 0) {
            Toast.makeText(
              ctx,
              if (skipped == 1) LocaleHelper.t(ctx, "toast_photo_skipped_one")
              else LocaleHelper.t(ctx, "toast_photos_skipped").replace("{n}", "$skipped"),
              Toast.LENGTH_LONG
            ).show()
          }
        }
      }
    }
  }

  fun buildPdf() {
    if (pages.isEmpty()) {
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_select_image"), Toast.LENGTH_SHORT).show()
      return
    }
    isProcessing = true
    progressText = "Page 1 of ${pages.size}"
    scope.launch {
      // FIX 2026-11-18 (fixed A4 page): pageSize was "Original", so the
      // PDF page became the cropped image's own pixel size — the page
      // changed with every crop. Now FIXED A4 (595x842pt) with the
      // 15mm default margins: buildImagesPdf fits each image inside,
      // centred, aspect preserved. Quality stays full-resolution
      // ("Original"), no watermark, no page numbers.
      val options = PdfEngine.ImagesPdfOptions(
        pageSize = "A4",
        quality = "Original",
        watermarkText = "",
        watermarkOpacity = 25,
        watermarkPosition = "centre",
        pageNumbers = false,
        marginsMm = 15
      )
      val file: File? = try {
        withContext(Dispatchers.IO) {
          PdfEngine.buildImagesPdf(ctx, pages.map { it.bitmap }, options) { done, total ->
            scope.launch { progressText = "Page $done of $total" }
          }
        }
      } catch (_: OutOfMemoryError) {
        // PHASE 2 (H2): out of memory mid-build — the page set is kept
        // and the user can retry (fewer/smaller photos). Never crash,
        // never silently drop the pages.
        Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_images_pdf_oom"), Toast.LENGTH_LONG).show()
        null
      } catch (e: Exception) {
        e.printStackTrace()
        null
      }
      isProcessing = false
      progressText = ""
      if (file != null) saveFile(file)
      else Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_create_pdf_failed"), Toast.LENGTH_SHORT).show()
    }
  }

  cropIndex?.let { index ->
    val page = pages.getOrNull(index)
    if (page != null) {
      RectCropDialog(
        bitmap = page.bitmap,
        theme = theme,
        onApply = { bmp ->
          editor.replacePage(index, bmp, newOriginal = bmp) { cropIndex = null }
        },
        onDismiss = { cropIndex = null },
        onRotate = {
          editor.replacePage(index, ImagePageEditor.rotate90(page.bitmap), newOriginal = null)
          cropIndex = null
        }
      )
    }
  }
  if (showFilters) {
    FilterPickerDialog(
      selected = filterMode,
      theme = theme,
      onPick = { mode -> vibrate(); editor.applyFilter(safeSelected, mode); showFilters = false },
      onDismiss = { showFilters = false },
      // PHASE 4: live filter previews need the current page bitmap.
      bitmap = selectedPage?.bitmap
    )
  }
  if (showAdjust) {
    BrightnessDialog(
      value = brightness,
      bitmap = selectedPage?.bitmap,
      theme = theme,
      onValueChange = { brightness = it },
      onApply = {
        showAdjust = false
        if (selectedPage != null) {
          editor.replacePage(safeSelected, ImagePageEditor.adjustBrightness(selectedPage.bitmap, brightness))
          brightness = 0f
        }
      },
      onDismiss = { showAdjust = false; brightness = 0f }
    )
  }

  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, "application/pdf") },
      savedLocationText = savedLocationText,
      onOpenPdfInReader = onOpenPdfInReader
    )
    return
  }

  ToolScreenContainer("Images to PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Text(LocaleHelper.t(ctx, "label_select_photos"), fontSize = Ds.BODY_SP.sp, color = theme.text)
      Spacer(Modifier.height(Ds.s16))

      if (pages.isEmpty()) {
        ChamiPrimaryButton(
          text = LocaleHelper.t(ctx, "btn_choose_images"),
          onClick = { vibrate(); picker.launch("image/*") },
          modifier = Modifier.fillMaxWidth()
        )
      } else {
        BigPagePreview(
          page = selectedPage,
          label = "Page ${safeSelected + 1}",
          theme = theme,
          onCrop = { cropIndex = safeSelected },
          onDelete = { vibrate(); vm.deleteAt(safeSelected) },
          onFilter = { showFilters = true },
          onAdjust = { showAdjust = true },
        )
        Spacer(Modifier.height(Ds.s8))
        Text(
          LocaleHelper.t(ctx, "label_page_of")
            .replace("{n}", "${safeSelected + 1}")
            .replace("{total}", "${pages.size}"),
          fontWeight = FontWeight.Bold, fontSize = Ds.BODY_SP.sp, color = theme.text
        )
        if (pages.size > 50) {
          Spacer(Modifier.height(Ds.s4))
          HintText(LocaleHelper.t(ctx, "hint_over_50_pages"))
        }
        Spacer(Modifier.height(Ds.s12))
        PageThumbnailStrip(
          pages = pages,
          selectedIndex = safeSelected,
          theme = theme,
          onSelect = { vm.select(it) },
          onMove = { from, to -> vm.movePage(from, to) },
          onAddPages = { vibrate(); picker.launch("image/*") }
        )
      }

      Spacer(Modifier.height(Ds.s16))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(Ds.s8))
        Text(progressText, fontSize = Ds.HINT_SP.sp, color = theme.text)
      }
    }

    SlideToAction(
      label = LocaleHelper.t(ctx, "slide_convert"),
      theme = theme,
      enabled = !isProcessing && pages.isNotEmpty(),
      disabledReason = if (pages.isEmpty()) LocaleHelper.t(ctx, "reason_select_image_first") else null,
      onSlideComplete = { vibrate(); buildPdf() }
    )
  }
}

// 3. PDF TO IMAGES — JPG (default) / PNG, resolution Screen 150 /
// High 300 (default) DPI, page range with live validation,
// per-page progress, images named NAME_p1, NAME_p2... The save folder
// is asked ONCE and every image is saved there, then a Done screen
// lists them all.
// FIX 2026-11-18 (Fix 6): thumbnail grid of EVERY page — tap to
// select/deselect, Select all / Clear. Extraction converts only the
// selected pages inside the range (all selected = the old behaviour).
@Composable
fun PdfToImagesScreen(theme: ChamiTheme, onOpenPdfInReader: ((File) -> Unit)? = null, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pdfUri by rememberSaveableUri()
  var totalPages by remember { mutableStateOf(0) }
  var pageOnePreview by remember { mutableStateOf<Bitmap?>(null) }
  // FIX 2026-11-18 (Fix 6): page selection. All pages are selected by
  // default (same behaviour as before); tapping a thumbnail toggles
  // it. PHASE 2 (H10): thumbnails render ON DEMAND per visible grid
  // cell (see the LazyVerticalGrid below), capped at MAX_PAGE_THUMBS —
  // the old version rendered every page up front in a background job.
  val selectedPages = remember { mutableStateListOf<Int>() }
  // PHASE 2 (H10): page thumbnails are capped and owned by this screen.
  // They render ON DEMAND per visible grid cell (see the LazyVerticalGrid
  // below) instead of all up front, the map holds at most MAX_PAGE_THUMBS
  // entries (farthest pages evicted first), every bitmap is recycled when
  // its entry is replaced/evicted/cleared, and the whole map plus the
  // page-1 preview are recycled when the screen leaves composition.
  // A 200-page PDF no longer holds 200 thumbnails in memory.
  val pageThumbs = remember { mutableStateMapOf<Int, Bitmap>() }
  fun recyclePageThumb(page: Int) {
    pageThumbs.remove(page)?.let { bmp -> if (!bmp.isRecycled) bmp.recycle() }
  }
  fun recycleAllPageThumbs() {
    pageThumbs.values.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
    pageThumbs.clear()
  }
  fun putPageThumb(page: Int, bmp: Bitmap) {
    recyclePageThumb(page)
    while (pageThumbs.size >= MAX_PAGE_THUMBS) {
      val farthest = pageThumbs.keys.maxByOrNull { kotlin.math.abs(it - page) } ?: break
      recyclePageThumb(farthest)
    }
    pageThumbs[page] = bmp
  }
  DisposableEffect(Unit) {
    onDispose {
      recycleAllPageThumbs()
      pageOnePreview?.let { bmp -> if (!bmp.isRecycled) bmp.recycle() }
    }
  }
  // FIX 2026-11-18 (report #24): a picked PDF that cannot be opened.
  var pdfLoadFailed by remember { mutableStateOf(false) }
  var loadedPdfUri by remember { mutableStateOf<Uri?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var progressText by remember { mutableStateOf("") }
  var format by rememberSaveable { mutableStateOf("JPG") }
  var resolution by rememberSaveable { mutableStateOf("High 300") }
  var rangeFrom by rememberSaveable { mutableStateOf("1") }
  var rangeTo by rememberSaveable { mutableStateOf("") }

  var doneFiles by remember { mutableStateOf<List<DoneFileInfo>?>(null) }
  var showDone by remember { mutableStateOf(false) }
  var savedLocationText by remember { mutableStateOf<String?>(null) }
  val folderSave = rememberFolderSaver(
    onSaved = { files ->
      files.forEach { HistoryStore.record(ctx, it.name, "pdf_img") }
      doneFiles = files.map { DoneFileInfo(it, if (format == "PNG") "image/png" else "image/jpeg", formatSizeText(it.length())) }
      showDone = true
    },
    onCancelled = { Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_save_cancelled_images"), Toast.LENGTH_SHORT).show() },
    onSavedToFolder = { uri -> savedLocationText = displayNameOf(ctx, uri) }
  )
  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  // FIX 2026-11-18 (report #47): the picked Uri is restored by
  // rememberSaveable after process death, so loading runs from this
  // function — called by the picker and by the LaunchedEffect below
  // when a restored Uri has no page count yet.
  fun loadPdf(uri: Uri) {
    loadedPdfUri = uri
    scope.launch(Dispatchers.IO) {
      val n = PdfEngine.pageCount(ctx, uri)
      val bmp = PdfReader.renderPageBitmap(ctx, uri, 0, 2.0f)
      withContext(Dispatchers.Main) {
        totalPages = n
        pdfLoadFailed = n == 0
        for (i in 1..n) selectedPages.add(i)
        // PHASE 2 (H10): the old preview is recycled before the new one
        // lands, so re-picking never leaks a full-size page bitmap.
        val oldPreview = pageOnePreview
        pageOnePreview = bmp
        if (oldPreview != null && oldPreview !== bmp && !oldPreview.isRecycled) oldPreview.recycle()
        rangeFrom = "1"
        rangeTo = "$n"
      }
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    // PHASE 2 (H10): re-picking recycles every held thumbnail and the
    // old preview instead of dropping the Bitmaps for the GC to find.
    recycleAllPageThumbs()
    pageOnePreview?.let { bmp -> if (!bmp.isRecycled) bmp.recycle() }
    pageOnePreview = null
    showDone = false
    doneFiles = null
    pdfLoadFailed = false
    selectedPages.clear()
    if (selected != null) loadPdf(selected)
  }

  // Restored Uri (process death) or a Uri set before this composition.
  LaunchedEffect(pdfUri) {
    val u = pdfUri
    if (u != null && loadedPdfUri != u) loadPdf(u)
  }

  val fromInt = rangeFrom.toIntOrNull()
  val toInt = rangeTo.toIntOrNull()
  val rangeValid = pdfUri == null || (fromInt != null && toInt != null && fromInt in 1..totalPages && toInt in fromInt..totalPages)
  // Pages actually extracted: the selected pages inside the range.
  // All selected + full range = the old behaviour (every page).
  val effectivePages: List<Int> = if (rangeValid && fromInt != null && toInt != null) {
    (fromInt..toInt).filter { it in selectedPages }
  } else selectedPages.toList()
  val imageCount = effectivePages.size

  fun extract() {
    val u = pdfUri ?: return
    if (!rangeValid) {
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_range_check"), Toast.LENGTH_SHORT).show()
      return
    }
    if (effectivePages.isEmpty()) {
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_select_page_extract"), Toast.LENGTH_SHORT).show()
      return
    }
    isProcessing = true
    scope.launch {
      val dir = File(ctx.getExternalFilesDir(null), "Chami/Images_${System.currentTimeMillis()}")
      if (!dir.exists()) dir.mkdirs()
      // PHASE 2 (H3): "Print 600" is gone — one A4 page at 600 DPI is a
      // ~139 MB bitmap. Screen 150 / High 300 only. A saved "Print 600"
      // value from an older run falls through to High 300 here.
      val dpi = if (resolution == "Screen 150") 150 else 300
      val scale = dpi / 72f
      val fmt = if (format == "PNG") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
      val ext = if (format == "PNG") "png" else "jpg"
      val base = baseNameOf(ctx, u, "PDF")
      val files = mutableListOf<File>()
      // PHASE 2 (M5): a page whose bitmap cannot be rendered (the engine
      // returns null, e.g. on out-of-memory) is a FAILED page, counted
      // and reported — never silently skipped.
      var failedPages = 0
      withContext(Dispatchers.IO) {
        for ((idx, p) in effectivePages.withIndex()) {
          withContext(Dispatchers.Main) { progressText = "Image ${idx + 1} of ${effectivePages.size}" }
          val bmp = try {
            PdfReader.renderPageBitmap(ctx, u, p - 1, scale)
          } catch (_: OutOfMemoryError) {
            null
          } catch (_: Exception) {
            null
          }
          if (bmp == null) {
            failedPages++
            continue
          }
          val f = File(dir, "${base}_p$p.$ext")
          try {
            FileOutputStream(f).use { bmp.compress(fmt, 90, it) }
            files.add(f)
          } catch (_: Exception) {
            // PHASE 2 (L18): a failed write leaves no orphan partial
            // file behind in the Chami folder.
            f.delete()
            failedPages++
          }
          bmp.recycle()
        }
      }
      isProcessing = false
      progressText = ""
      if (files.isNotEmpty()) {
        if (failedPages > 0) {
          Toast.makeText(
            ctx,
            LocaleHelper.t(ctx, "toast_extract_partial")
              .replace("{saved}", "${files.size}")
              .replace("{total}", "${effectivePages.size}")
              .replace("{failed}", "$failedPages"),
            Toast.LENGTH_LONG
          ).show()
        }
        val mime = if (format == "PNG") "image/png" else "image/jpeg"
        folderSave(files.map { it to mime })
      } else {
        // Whole extract failed — the page set and selection are kept,
        // so the user can retry (e.g. at a lower resolution).
        Toast.makeText(
          ctx,
          LocaleHelper.t(ctx, "toast_extract_failed").replace("{failed}", "$failedPages"),
          Toast.LENGTH_LONG
        ).show()
      }
    }
  }

  // PHASE 2 (H3): a "Print 600" resolution saved by an older run is
  // shown as High 300 — the 600 DPI choice was removed (one A4 page
  // at 600 DPI allocates ~139 MB and crashes most phones).
  val resolutionLabel = if (resolution == "Screen 150") "Screen 150" else "High 300"
  if (showDone && doneFiles != null) {
    MultiDoneScreen(
      files = doneFiles!!,
      title = LocaleHelper.t(ctx, "done_images_title"),
      summaryLines = listOf(
        LocaleHelper.t(ctx, "done_images_summary")
          .replace("{n}", "${doneFiles!!.size}")
          .replace("{format}", format)
          .replace("{resolution}", resolutionLabel),
        LocaleHelper.t(ctx, "done_images_saved")
      ),
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, if (format == "PNG") "image/png" else "image/jpeg") },
      savedLocationText = savedLocationText,
      onOpenPdfInReader = onOpenPdfInReader
    )
    return
  }

  ToolScreenContainer("PDF to Images", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      ChamiSecondaryButton(
        text = if (pdfLoadFailed) LocaleHelper.t(ctx, "btn_pdf_unopenable")
          else if (pdfUri != null) LocaleHelper.t(ctx, "btn_pdf_selected").replace("{n}", "$totalPages")
          else LocaleHelper.t(ctx, "btn_select_pdf"),
        onClick = { vibrate(); picker.launch("application/pdf") },
        modifier = Modifier.fillMaxWidth()
      )
      // FIX 2026-11-18 (report #24): a protected/corrupt PDF says so
      // instead of showing "0 pages" with a disabled slide.
      if (pdfLoadFailed) {
        Spacer(Modifier.height(Ds.s8))
        Text(LocaleHelper.t(ctx, "label_pdf_unopenable"), fontSize = Ds.BODY_SP.sp, color = ChamiColors.errorRed)
      }

      if (pageOnePreview != null) {
        Spacer(Modifier.height(Ds.s16))
        SectionText(LocaleHelper.t(ctx, "label_preview_page_1").replace("{n}", "$totalPages"), color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.s8))
            .background(theme.card)
            .padding(Ds.s12),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Image(
            bitmap = pageOnePreview!!.asImageBitmap(),
            contentDescription = "Page 1 Preview",
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(Ds.s8)),
            contentScale = ContentScale.Fit
          )
        }
      }

      if (pdfUri != null) {
        Spacer(Modifier.height(Ds.s16))
        SectionText(LocaleHelper.t(ctx, "label_format"), color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
          listOf("JPG", "PNG").forEach { f ->
            ChamiChip(f, format == f, { vibrate(); format = f })
          }
        }
        if (format == "PNG") {
          Spacer(Modifier.height(Ds.s4))
          HintText(LocaleHelper.t(ctx, "hint_png_larger"))
        }
        Spacer(Modifier.height(Ds.s16))
        SectionText(LocaleHelper.t(ctx, "label_resolution"), color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
          // PHASE 2 (H3): "Print 600" removed — see extract().
          listOf("Screen 150", "High 300").forEach { r ->
            ChamiChip(r, resolutionLabel == r, { vibrate(); resolution = r })
          }
        }
        Spacer(Modifier.height(Ds.s16))
        SectionText(LocaleHelper.t(ctx, "label_page_range"), color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
          DebouncedTextField(
            value = rangeFrom, onValueChange = { rangeFrom = it.filter { ch -> ch.isDigit() } },
            label = LocaleHelper.t(ctx, "label_from_page"), keyboardType = KeyboardType.Number,
            isError = !rangeValid,
            modifier = Modifier.weight(1f)
          )
          DebouncedTextField(
            value = rangeTo, onValueChange = { rangeTo = it.filter { ch -> ch.isDigit() } },
            label = LocaleHelper.t(ctx, "label_to_page"), keyboardType = KeyboardType.Number,
            isError = !rangeValid,
            modifier = Modifier.weight(1f)
          )
        }
        if (!rangeValid) {
          Spacer(Modifier.height(Ds.s4))
          Text(
            LocaleHelper.t(ctx, "label_range_error").replace("{n}", "$totalPages"),
            fontSize = Ds.HINT_SP.sp, color = ChamiColors.errorRed
          )
        } else if (imageCount > 0) {
          Spacer(Modifier.height(Ds.s8))
          Text(
            if (imageCount == 1) LocaleHelper.t(ctx, "label_image_will_create_one")
            else LocaleHelper.t(ctx, "label_images_will_create").replace("{n}", "$imageCount"),
            fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold, color = theme.text
          )
        }
        // FIX 2026-11-18 (Fix 6): thumbnail grid of EVERY page — tap
        // to select/deselect. Same pattern as Split PDF's page grid
        // (Select all / Clear + tick overlay), but with real page
        // thumbnails, labelled Page N. Extraction converts only the
        // selected pages inside the range above.
        Spacer(Modifier.height(Ds.s16))
        SectionText(LocaleHelper.t(ctx, "label_pages_tap_select"), color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
          Text(
            LocaleHelper.t(ctx, "label_selected_pages_count")
              .replace("{selected}", "${selectedPages.size}")
              .replace("{total}", "$totalPages"),
            fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold, color = theme.text
          )
          Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
            TextButton(onClick = { vibrate(); selectedPages.clear(); for (i in 1..totalPages) selectedPages.add(i) }, modifier = Modifier.height(Ds.minTouch)) {
              Text(LocaleHelper.t(ctx, "btn_select_all"), color = theme.primaryDark, fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = { vibrate(); selectedPages.clear() }, modifier = Modifier.height(Ds.minTouch)) {
              Text(LocaleHelper.t(ctx, "btn_clear"), color = theme.primaryDark, fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold)
            }
          }
        }
        Spacer(Modifier.height(Ds.s8))
        // PHASE 2 (L6 + H10): lazy grid, not a chunked Column — a
        // 500-page PDF composes only the visible cells. Each cell
        // renders its own thumbnail on demand (small scale, one at a
        // time); the cell's LaunchedEffect is cancelled when the cell
        // leaves composition, and the map is capped (see putPageThumb),
        // so a long PDF never holds every thumbnail at once.
        val pdfU = pdfUri
        LazyVerticalGrid(
          columns = GridCells.Fixed(3),
          modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
          horizontalArrangement = Arrangement.spacedBy(Ds.s8),
          verticalArrangement = Arrangement.spacedBy(Ds.s8)
        ) {
          gridItems((1..totalPages).toList(), key = { it }) { p ->
            val isSelected = selectedPages.contains(p)
            val thumb = pageThumbs[p]
            if (pdfU != null) {
              LaunchedEffect(p, pdfU) {
                if (pageThumbs.containsKey(p)) return@LaunchedEffect
                val rendered = try {
                  withContext(Dispatchers.IO) { PdfReader.renderPageBitmap(ctx, pdfU, p - 1, 0.4f) }
                } catch (_: OutOfMemoryError) {
                  null
                } catch (_: Exception) {
                  null
                }
                if (rendered != null) putPageThumb(p, rendered)
              }
            }
            Column(
              Modifier.clickable {
                vibrate()
                if (isSelected) selectedPages.remove(p) else selectedPages.add(p)
              },
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Box(
                Modifier
                  .fillMaxWidth()
                  .aspectRatio(595f / 842f)
                  .clip(RoundedCornerShape(Ds.s8))
                  .background(theme.card)
                  .border(
                    2.dp,
                    if (isSelected) theme.primary else theme.cardBorder,
                    RoundedCornerShape(Ds.s8)
                  ),
                contentAlignment = Alignment.Center
              ) {
                if (thumb != null) {
                  Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = "Page $p",
                    modifier = Modifier.fillMaxSize().padding(Ds.s4),
                    contentScale = ContentScale.Fit
                  )
                } else {
                  Text("…", fontSize = Ds.BODY_SP.sp, color = theme.textSecondary)
                }
                if (isSelected) {
                  Box(
                    Modifier
                      .align(Alignment.TopEnd)
                      .padding(Ds.s4)
                      .size(22.dp)
                      .clip(CircleShape)
                      .background(theme.primary)
                      .border(1.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(14.dp))
                  }
                }
              }
              Spacer(Modifier.height(Ds.s4))
              Text(
                LocaleHelper.t(ctx, "label_page_number").replace("{n}", "$p"),
                fontSize = Ds.HINT_SP.sp,
                color = if (isSelected) theme.text else theme.textSecondary,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
              )
            }
          }
        }
        if (selectedPages.isEmpty()) {
          Spacer(Modifier.height(Ds.s8))
          Text(
            LocaleHelper.t(ctx, "label_no_pages_selected"),
            fontSize = Ds.HINT_SP.sp, color = ChamiColors.errorRed
          )
        }
      }

      Spacer(Modifier.height(Ds.s16))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(Ds.s8))
        Text(progressText, fontSize = Ds.HINT_SP.sp, color = theme.text)
      }
    }

    SlideToAction(
      label = LocaleHelper.t(ctx, "slide_extract"),
      theme = theme,
      enabled = !isProcessing && pdfUri != null && !pdfLoadFailed && rangeValid && effectivePages.isNotEmpty(),
      disabledReason = when {
        pdfUri == null -> LocaleHelper.t(ctx, "reason_pick_pdf_first")
        pdfLoadFailed -> LocaleHelper.t(ctx, "label_pdf_unopenable")
        !rangeValid -> LocaleHelper.t(ctx, "toast_range_check")
        effectivePages.isEmpty() -> LocaleHelper.t(ctx, "reason_select_pages_first")
        else -> null
      },
      onSlideComplete = { vibrate(); extract() }
    )
  }
}

// 4. SPLIT PDF — Range (live validation, thumbnails in range highlight),
// Every N (parts previewed before splitting), Extract selected (grid
// with tick overlay + Select all / Clear). Multi-part output asks the
// save folder ONCE and saves every part there. NOTE: copyPages renders
// pages as images, so text in split output is not selectable.
@Composable
fun SplitPdfScreen(theme: ChamiTheme, initialPdfUri: Uri? = null, onInitialPdfConsumed: () -> Unit = {}, onOpenPdfInReader: ((File) -> Unit)? = null, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pdfUri by rememberSaveableUri()
  var totalPages by remember { mutableStateOf(0) }
  var pdfLoadFailed by remember { mutableStateOf(false) }
  var loadedPdfUri by remember { mutableStateOf<Uri?>(null) }
  var initialConsumed by rememberSaveable { mutableStateOf(false) }
  val selectedPages = remember { mutableStateListOf<Int>() }
  var previewPageIndex by remember { mutableStateOf(1) }
  var pagePreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var progressText by remember { mutableStateOf("") }
  var splitMode by rememberSaveable { mutableStateOf("Selected") }
  var rangeFrom by rememberSaveable { mutableStateOf("1") }
  var rangeTo by rememberSaveable { mutableStateOf("") }
  var everyN by rememberSaveable { mutableStateOf("5") }

  var doneFile by remember { mutableStateOf<File?>(null) }
  var doneFiles by remember { mutableStateOf<List<DoneFileInfo>?>(null) }
  var showDone by remember { mutableStateOf(false) }
  var savedLocationText by remember { mutableStateOf<String?>(null) }
  val singleSave = rememberSingleFileSaver(
    onSavedToUri = { uri -> savedLocationText = displayNameOf(ctx, uri) }
  ) { file ->
    HistoryStore.record(ctx, file.name, "split")
    doneFile = file
    showDone = true
  }
  val folderSave = rememberFolderSaver(
    onSaved = { files ->
      files.forEach { HistoryStore.record(ctx, it.name, "split") }
      doneFiles = files.map { DoneFileInfo(it, "application/pdf", formatSizeText(it.length())) }
      showDone = true
    },
    onCancelled = { Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_save_cancelled_images"), Toast.LENGTH_SHORT).show() },
    onSavedToFolder = { uri -> savedLocationText = displayNameOf(ctx, uri) }
  )

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  fun loadPreviewForPage(page: Int) {
    val u = pdfUri ?: return
    scope.launch(Dispatchers.IO) {
      val bmp = PdfReader.renderPageBitmap(ctx, u, page - 1, 2.0f)
      withContext(Dispatchers.Main) { pagePreviewBitmap = bmp }
    }
  }

  // FIX 2026-11-18 (report #27/#47): the picked Uri is restored by
  // rememberSaveable after process death, and the Reader hands a Uri
  // in through initialPdfUri — both go through this loader.
  fun loadPdf(uri: Uri) {
    loadedPdfUri = uri
    scope.launch(Dispatchers.IO) {
      val n = PdfEngine.pageCount(ctx, uri)
      val bmp = PdfReader.renderPageBitmap(ctx, uri, 0, 2.0f)
      withContext(Dispatchers.Main) {
        totalPages = n
        pdfLoadFailed = n == 0
        for (i in 1..n) selectedPages.add(i)
        previewPageIndex = 1
        pagePreviewBitmap = bmp
        rangeFrom = "1"
        rangeTo = "$n"
      }
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    selectedPages.clear()
    showDone = false
    doneFile = null
    doneFiles = null
    pagePreviewBitmap = null
    pdfLoadFailed = false
    if (selected != null) loadPdf(selected)
  }

  // Reader hand-off (report #27): consume the incoming Uri once.
  LaunchedEffect(initialPdfUri) {
    if (initialPdfUri != null && !initialConsumed) {
      initialConsumed = true
      pdfUri = initialPdfUri
      onInitialPdfConsumed()
    }
  }

  // Restored Uri (process death) or the Reader's handed-over Uri.
  LaunchedEffect(pdfUri) {
    val u = pdfUri
    if (u != null && loadedPdfUri != u) loadPdf(u)
  }

  val fromInt = rangeFrom.toIntOrNull()
  val toInt = rangeTo.toIntOrNull()
  val rangeValid = splitMode != "Range" || (fromInt != null && toInt != null && fromInt in 1..totalPages && toInt in fromInt..totalPages)
  val nVal = everyN.toIntOrNull() ?: 5
  val parts: List<IntRange> = if (splitMode == "Every N" && totalPages > 0 && nVal > 0) {
    val list = mutableListOf<IntRange>()
    var start = 1
    while (start <= totalPages) {
      val end = minOf(start + nVal - 1, totalPages)
      list.add(start..end)
      start = end + 1
    }
    list
  } else emptyList()
  val inRange: (Int) -> Boolean = { p ->
    when (splitMode) {
      "Range" -> rangeValid && fromInt != null && toInt != null && p in fromInt!!..toInt!!
      "Every N" -> parts.any { p in it }
      else -> selectedPages.contains(p)
    }
  }

  fun split() {
    val u = pdfUri ?: return
    if (splitMode == "Selected" && selectedPages.isEmpty()) {
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_select_page_split"), Toast.LENGTH_SHORT).show()
      return
    }
    if (splitMode == "Range" && !rangeValid) {
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_range_check"), Toast.LENGTH_SHORT).show()
      return
    }
    if (splitMode == "Every N" && totalPages <= 1) {
      Toast.makeText(
        ctx,
        LocaleHelper.t(ctx, "toast_split_one_page").replace("{n}", "$totalPages"),
        Toast.LENGTH_LONG
      ).show()
      return
    }
    isProcessing = true
    scope.launch {
      val files = mutableListOf<File>()
      val base = baseNameOf(ctx, u, "Split")
      withContext(Dispatchers.IO) {
        when (splitMode) {
          "Every N" -> {
            parts.forEachIndexed { i, range ->
              withContext(Dispatchers.Main) { progressText = "Part ${i + 1} of ${parts.size}" }
              val out = PdfEngine.outFile(ctx, "${base}_part${i + 1}_${System.currentTimeMillis()}.pdf")
              if (PdfEngine.copyPages(ctx, listOf(u to range.toSet()), out)) files.add(out)
            }
          }
          "Range" -> {
            val f = fromInt ?: 1
            val t = toInt ?: totalPages
            val out = PdfEngine.outFile(ctx, "${base}_${f}-${t}_${System.currentTimeMillis()}.pdf")
            if (PdfEngine.copyPages(ctx, listOf(u to (f..t).toSet()), out)) files.add(out)
          }
          else -> {
            val out = PdfEngine.outFile(ctx, "${base}_split_${System.currentTimeMillis()}.pdf")
            if (PdfEngine.copyPages(ctx, listOf(u to selectedPages.toSet()), out)) files.add(out)
          }
        }
      }
      isProcessing = false
      progressText = ""
      when {
        files.size > 1 -> folderSave(files.map { it to "application/pdf" })
        files.size == 1 -> singleSave(files[0])
        else -> Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_split_failed"), Toast.LENGTH_SHORT).show()
      }
    }
  }

  if (showDone && doneFiles != null) {
    MultiDoneScreen(
      files = doneFiles!!,
      title = LocaleHelper.t(ctx, "done_split_title"),
      summaryLines = listOf(
        LocaleHelper.t(ctx, "done_split_summary").replace("{n}", "${doneFiles!!.size}"),
        LocaleHelper.t(ctx, "done_split_saved")
      ),
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, "application/pdf") },
      savedLocationText = savedLocationText,
      onOpenPdfInReader = onOpenPdfInReader
    )
    return
  }
  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, "application/pdf") },
      savedLocationText = savedLocationText,
      onOpenPdfInReader = onOpenPdfInReader
    )
    return
  }

  ToolScreenContainer("Split PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      ChamiSecondaryButton(
        text = if (pdfLoadFailed) LocaleHelper.t(ctx, "btn_pdf_unopenable")
          else if (pdfUri != null) LocaleHelper.t(ctx, "btn_pdf_selected_split").replace("{n}", "$totalPages")
          else LocaleHelper.t(ctx, "btn_select_pdf"),
        onClick = { vibrate(); picker.launch("application/pdf") },
        modifier = Modifier.fillMaxWidth()
      )
      // FIX 2026-11-18 (report #24): a protected/corrupt PDF says so
      // instead of showing "0 pages" with a disabled slide.
      if (pdfLoadFailed) {
        Spacer(Modifier.height(Ds.s8))
        Text(LocaleHelper.t(ctx, "label_pdf_unopenable"), fontSize = Ds.BODY_SP.sp, color = ChamiColors.errorRed)
      }

      if (totalPages > 0) {
        Spacer(Modifier.height(Ds.s16))
        SectionText(LocaleHelper.t(ctx, "label_split_mode"), color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
          // The mode string is the internal key (splitMode drives the
          // split logic), so only the DISPLAY text is translated.
          listOf("Selected", "Range", "Every N").forEach { m ->
            val modeLabel = when (m) {
              "Selected" -> LocaleHelper.t(ctx, "split_mode_selected")
              "Range" -> LocaleHelper.t(ctx, "split_mode_range")
              else -> LocaleHelper.t(ctx, "split_mode_every_n")
            }
            ChamiChip(modeLabel, splitMode == m, { vibrate(); splitMode = m })
          }
        }
        if (splitMode == "Range") {
          Spacer(Modifier.height(Ds.s12))
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
            DebouncedTextField(
              value = rangeFrom, onValueChange = { rangeFrom = it.filter { ch -> ch.isDigit() } },
              label = LocaleHelper.t(ctx, "label_from_page"), keyboardType = KeyboardType.Number, isError = !rangeValid,
              modifier = Modifier.weight(1f)
            )
            DebouncedTextField(
              value = rangeTo, onValueChange = { rangeTo = it.filter { ch -> ch.isDigit() } },
              label = LocaleHelper.t(ctx, "label_to_page"), keyboardType = KeyboardType.Number, isError = !rangeValid,
              modifier = Modifier.weight(1f)
            )
          }
          if (!rangeValid) {
            Spacer(Modifier.height(Ds.s4))
            Text(LocaleHelper.t(ctx, "label_range_error").replace("{n}", "$totalPages"), fontSize = Ds.HINT_SP.sp, color = ChamiColors.errorRed)
          }
        }
        if (splitMode == "Every N") {
          Spacer(Modifier.height(Ds.s12))
          DebouncedTextField(
            value = everyN, onValueChange = { everyN = it.filter { ch -> ch.isDigit() } },
            label = LocaleHelper.t(ctx, "label_pages_per_part"), keyboardType = KeyboardType.Number,
            isError = nVal <= 0,
            supportingText = if (nVal <= 0) LocaleHelper.t(ctx, "label_pages_per_part_error") else null
          )
          if (parts.isNotEmpty()) {
            Spacer(Modifier.height(Ds.s12))
            Column(verticalArrangement = Arrangement.spacedBy(Ds.s4)) {
              parts.forEachIndexed { i, range ->
                Text(
                  LocaleHelper.t(ctx, "label_part_pages")
                    .replace("{n}", "${i + 1}")
                    .replace("{from}", "${range.first}")
                    .replace("{to}", "${range.last}"),
                  fontSize = Ds.BODY_SP.sp, color = theme.text
                )
              }
            }
          }
        }
        Spacer(Modifier.height(Ds.s16))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
          Text(
            LocaleHelper.t(ctx, "label_pages_count")
              .replace("{selected}", "${selectedPages.size}")
              .replace("{total}", "$totalPages"),
            fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold, color = theme.text
          )
          Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
            TextButton(onClick = { vibrate(); selectedPages.clear(); for (i in 1..totalPages) selectedPages.add(i) }, modifier = Modifier.height(Ds.minTouch)) {
              Text(LocaleHelper.t(ctx, "btn_select_all"), color = theme.primaryDark, fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = { vibrate(); selectedPages.clear() }, modifier = Modifier.height(Ds.minTouch)) {
              Text(LocaleHelper.t(ctx, "btn_clear"), color = theme.primaryDark, fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold)
            }
          }
        }
        Spacer(Modifier.height(Ds.s8))
        // Page grid (FIX 2026-11-18, report #11): tap a page previews
        // it ONLY — selection is the corner tick, so browsing pages
        // never silently changes what gets split.
        HintText(LocaleHelper.t(ctx, "hint_split_tap_preview"))
        Spacer(Modifier.height(Ds.s8))
        // PHASE 2 (L6): lazy grid, not a chunked Column — a 500-page PDF
        // composes only the visible cells. Stable keys are the page numbers.
        LazyVerticalGrid(
          columns = GridCells.Fixed(5),
          modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
          horizontalArrangement = Arrangement.spacedBy(Ds.s8),
          verticalArrangement = Arrangement.spacedBy(Ds.s8)
        ) {
          gridItems((1..totalPages).toList(), key = { it }) { p ->
            val isSelected = selectedPages.contains(p)
            val highlighted = inRange(p)
            Box(
              Modifier
                .size(Ds.minTouch)
                .clip(RoundedCornerShape(Ds.s8))
                .background(if (isSelected) theme.primary else theme.card)
                .border(
                  2.dp,
                  when {
                    highlighted && splitMode != "Selected" -> ChamiColors.darkGreen
                    previewPageIndex == p -> theme.primaryDark
                    else -> theme.cardBorder
                  },
                  RoundedCornerShape(Ds.s8)
                )
                .clickable {
                  vibrate()
                  previewPageIndex = p
                  loadPreviewForPage(p)
                },
              contentAlignment = Alignment.Center
            ) {
              Text(
                "$p",
                fontWeight = FontWeight.Bold,
                color = if (isSelected) theme.textOnPrimary else theme.text,
                fontSize = Ds.BODY_SP.sp
              )
              // Corner tick: the ONLY way to select/deselect.
              Box(
                Modifier
                  .align(Alignment.TopEnd)
                  .size(24.dp)
                  .clip(CircleShape)
                  .background(if (isSelected) theme.textOnPrimary else theme.cardBorder)
                  .clickable {
                    vibrate()
                    if (isSelected) selectedPages.remove(p) else selectedPages.add(p)
                  },
                contentAlignment = Alignment.Center
              ) {
                if (isSelected) {
                  Icon(
                    Icons.Filled.Check, contentDescription = "Select page $p",
                    tint = theme.primary, modifier = Modifier.size(16.dp)
                  )
                }
              }
            }
          }
        }

        if (pagePreviewBitmap != null) {
          Spacer(Modifier.height(Ds.s16))
          SectionText(LocaleHelper.t(ctx, "label_previewing_page").replace("{n}", "$previewPageIndex"), color = theme.text)
          Spacer(Modifier.height(Ds.s8))
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(Ds.s8))
              .background(theme.card)
              .padding(Ds.s12),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Image(
              bitmap = pagePreviewBitmap!!.asImageBitmap(),
              contentDescription = "Page $previewPageIndex",
              modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(Ds.s8)),
              contentScale = ContentScale.Fit
            )
          }
        }
        Spacer(Modifier.height(Ds.s12))
        HintText(LocaleHelper.t(ctx, "hint_split_rasterised"))
      }

      Spacer(Modifier.height(Ds.s16))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(Ds.s8))
        Text(progressText, fontSize = Ds.HINT_SP.sp, color = theme.text)
      }
    }

    SlideToAction(
      label = LocaleHelper.t(ctx, "slide_split"),
      theme = theme,
      enabled = !isProcessing && pdfUri != null && !pdfLoadFailed && totalPages > 0 && rangeValid &&
        (splitMode != "Selected" || selectedPages.isNotEmpty()),
      disabledReason = when {
        pdfUri == null -> LocaleHelper.t(ctx, "reason_pick_pdf_first")
        pdfLoadFailed -> LocaleHelper.t(ctx, "label_pdf_unopenable")
        totalPages == 0 -> LocaleHelper.t(ctx, "reason_loading_pdf")
        !rangeValid -> LocaleHelper.t(ctx, "toast_range_check")
        splitMode == "Selected" && selectedPages.isEmpty() -> LocaleHelper.t(ctx, "reason_select_pages_first")
        splitMode == "Every N" && totalPages <= 1 -> LocaleHelper.t(ctx, "reason_split_one_page")
        else -> null
      },
      onSlideComplete = { vibrate(); split() }
    )
  }
}

// 5. MERGE PDF — drag reorder of file rows (long-press lifts, others
// slide aside, drop at half-row height, haptic on pickup; arrow buttons
// stay as the accessibility alternative) + per-file preview, page count
// and size. NOTE: PdfEngine.copyPages renders every source page to a
// bitmap, so selectable text, links and vector quality are lost in the
// merged file — the output is pictures of the pages.