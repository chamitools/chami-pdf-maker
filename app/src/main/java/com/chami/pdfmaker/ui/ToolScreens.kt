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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chami.pdfmaker.pdf.*
import com.chami.pdfmaker.theme.ChamiTheme
import com.chami.pdfmaker.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

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
// - PdfProtector already exposes unlockPdf / setPermissions /
//   verifyPassword in this copy; Protect uses them instead of the old
//   local RC4 unlock (kept only where still needed: patchPdfPermissions).
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

// --- Local PDF helpers (Phase 6) ------------------------------------------
// PdfProtector.unlockPdf / setPermissions DO exist in this project, so
// Protect uses them. The RC4 helpers below stay only for
// patchPdfPermissions (permission-bit patching on the app's own V1/R2
// files) and the unlock fallback path.

val PDF_PADDING = byteArrayOf(
  0x28.toByte(), 0xBF.toByte(), 0x4E.toByte(), 0x5E.toByte(), 0x4E.toByte(), 0x75.toByte(), 0x8A.toByte(), 0x41.toByte(),
  0x64.toByte(), 0x00.toByte(), 0x4E.toByte(), 0x56.toByte(), 0xFF.toByte(), 0xFA.toByte(), 0x01.toByte(), 0x08.toByte(),
  0x2E.toByte(), 0x2E.toByte(), 0x00.toByte(), 0xB6.toByte(), 0xD0.toByte(), 0x68.toByte(), 0x3E.toByte(), 0x80.toByte(),
  0x2F.toByte(), 0x0C.toByte(), 0xA9.toByte(), 0xFE.toByte(), 0x64.toByte(), 0x53.toByte(), 0x69.toByte(), 0x7A.toByte()
)

fun rc4Crypt(key: ByteArray, data: ByteArray): ByteArray {
  val s = IntArray(256) { it }
  var j = 0
  for (i in 0 until 256) {
    j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
    val t = s[i]; s[i] = s[j]; s[j] = t
  }
  val out = ByteArray(data.size)
  var x = 0; var y = 0
  for (i in data.indices) {
    x = (x + 1) and 0xFF
    y = (y + s[x]) and 0xFF
    val t = s[x]; s[x] = s[y]; s[y] = t
    val k = s[(s[x] + s[y]) and 0xFF]
    out[i] = (data[i].toInt() xor k).toByte()
  }
  return out
}

fun pdfMd5(vararg parts: ByteArray): ByteArray {
  val d = MessageDigest.getInstance("MD5")
  parts.forEach { d.update(it) }
  return d.digest()
}

fun pdfPadPassword(pw: String): ByteArray {
  val raw = pw.toByteArray(Charsets.ISO_8859_1)
  val out = ByteArray(32)
  val n = minOf(raw.size, 32)
  System.arraycopy(raw, 0, out, 0, n)
  if (n < 32) System.arraycopy(PDF_PADDING, 0, out, n, 32 - n)
  return out
}

fun pdfHexToBytes(hex: String): ByteArray {
  val clean = hex.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
  val out = ByteArray(clean.length / 2)
  for (i in out.indices) {
    out[i] = ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
  }
  return out
}

fun pdfBytesToHex(b: ByteArray): String {
  val sb = StringBuilder()
  b.forEach { sb.append(String.format("%02x", it.toInt() and 0xFF)) }
  return sb.toString()
}

// Unlock a Standard-security V1/R2 (RC4) PDF — the format this app's own
// PdfProtector writes. Returns false for files this cannot decrypt
// (e.g. AES / R5+ / other encrypt handlers).
fun unlockProtectedPdf(input: File, password: String, out: File): Boolean {
  return try {
    val bytes = input.readBytes()
    val text = String(bytes, Charsets.ISO_8859_1)
    val oHex = Regex("""/O\s*<([0-9a-fA-F]+)>""").find(text)?.groupValues?.get(1) ?: return false
    val idHex = Regex("""/ID\s*\[\s*<([0-9a-fA-F]+)>""").find(text)?.groupValues?.get(1) ?: return false
    val pVal = Regex("""/P\s*(-?[0-9]+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    val oBytes = pdfHexToBytes(oHex)
    val idBytes = pdfHexToBytes(idHex)
    val pBytes = byteArrayOf(
      (pVal and 0xFF).toByte(), ((pVal shr 8) and 0xFF).toByte(),
      ((pVal shr 16) and 0xFF).toByte(), ((pVal shr 24) and 0xFF).toByte()
    )
    val encKey = pdfMd5(pdfPadPassword(password), oBytes, pBytes, idBytes).copyOfRange(0, 5)
    val uHex = Regex("""/U\s*<([0-9a-fA-F]+)>""").find(text)?.groupValues?.get(1) ?: return false
    if (!pdfBytesToHex(rc4Crypt(encKey, PDF_PADDING)).equals(uHex, ignoreCase = true)) return false
    val result = bytes.copyOf()
    var searchFrom = 0
    while (true) {
      val objMatch = Regex("""(\d+)\s+0\s+obj\b""").find(text, searchFrom) ?: break
      val objNum = objMatch.groupValues[1].toIntOrNull() ?: break
      val endObjIdx = text.indexOf("endobj", objMatch.range.last)
      if (endObjIdx == -1) break
      val streamIdx = text.indexOf("stream", objMatch.range.last)
      if (streamIdx != -1 && streamIdx < endObjIdx) {
        var dataStart = streamIdx + "stream".length
        if (text.getOrNull(dataStart) == '\r') dataStart++
        if (text.getOrNull(dataStart) == '\n') dataStart++
        val dictText = text.substring(objMatch.range.last, streamIdx)
        val len = Regex("""/Length\s+(\d+)""").find(dictText)?.groupValues?.get(1)?.toIntOrNull()
        if (len != null && dataStart + len <= result.size) {
          val md = MessageDigest.getInstance("MD5")
          md.update(encKey)
          md.update(
            byteArrayOf(
              (objNum and 0xFF).toByte(), ((objNum shr 8) and 0xFF).toByte(),
              ((objNum shr 16) and 0xFF).toByte(), 0.toByte(), 0.toByte()
            )
          )
          val objKey = md.digest().copyOfRange(0, minOf(encKey.size + 5, 16))
          val plain = rc4Crypt(objKey, result.copyOfRange(dataStart, dataStart + len))
          plain.copyInto(result, dataStart)
        }
      }
      searchFrom = endObjIdx + 6
    }
    var outText = String(result, Charsets.ISO_8859_1)
    val encRef = Regex("""/Encrypt\s+\d+\s+0\s+R""").find(outText) ?: return false
    outText = outText.replace(encRef.value, " ".repeat(encRef.value.length))
    out.writeBytes(outText.toByteArray(Charsets.ISO_8859_1))
    true
  } catch (_: Exception) {
    false
  }
}

// Apply print/copy/edit permissions to a PDF this app just protected.
// PdfProtector hardcodes /P -4 (everything allowed), so the permission
// bits are patched in place: /P and /U are recomputed consistently, and
// the /P swap is length-preserving (all replacements are 8 chars, same
// as "/P -4 >>") so the xref offsets stay valid. P bits: print -4,
// edit -8, copy -16 (from the -4 base). Returns false if the patch
// could not be applied — the file is then left as the plain protected PDF.
fun patchPdfPermissions(
  file: File,
  password: String,
  allowPrint: Boolean,
  allowCopy: Boolean,
  allowEdit: Boolean = true
): Boolean {
  return try {
    var pVal = -4
    if (!allowPrint) pVal -= 4
    if (!allowEdit) pVal -= 8
    if (!allowCopy) pVal -= 16
    if (pVal == -4) return true
    val text = String(file.readBytes(), Charsets.ISO_8859_1)
    val oHex = Regex("""/O\s*<([0-9a-fA-F]+)>""").find(text)?.groupValues?.get(1) ?: return false
    val idHex = Regex("""/ID\s*\[\s*<([0-9a-fA-F]+)>""").find(text)?.groupValues?.get(1) ?: return false
    val oBytes = pdfHexToBytes(oHex)
    val idBytes = pdfHexToBytes(idHex)
    val pBytes = byteArrayOf(
      (pVal and 0xFF).toByte(), ((pVal shr 8) and 0xFF).toByte(),
      ((pVal shr 16) and 0xFF).toByte(), ((pVal shr 24) and 0xFF).toByte()
    )
    val encKey = pdfMd5(pdfPadPassword(password), oBytes, pBytes, idBytes).copyOfRange(0, 5)
    val newU = pdfBytesToHex(rc4Crypt(encKey, PDF_PADDING))
    val uMatch = Regex("""/U\s*<[0-9a-fA-F]{64}>""").find(text) ?: return false
    var outText = text.replace(uMatch.value, "/U <$newU>")
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
    if (!outText.contains("/P -4 >>")) return false
    outText = outText.replace("/P -4 >>", pReplacement)
    file.writeBytes(outText.toByteArray(Charsets.ISO_8859_1))
    true
  } catch (_: Exception) {
    false
  }
}

// PDF compress quality slider (10-100) with the exact quality, via the
// engine's compressPdfImages (scale 1.0, exact JPEG quality). NOTE: the
// engine re-renders every page as an image, so text in the output is
// rasterised, not kept as selectable text — the UI must not claim
// otherwise. Input is a Uri here, so it is copied to a temp file first.
fun compressPdfWithQuality(ctx: Context, uri: Uri, quality: Int, out: File): PdfCompressResult {
  return try {
    val tmp = File(ctx.cacheDir, "compress_in_${System.currentTimeMillis()}.pdf")
    ctx.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tmp).use { o -> input.copyTo(o) } }
    if (!tmp.exists()) return PdfCompressResult(false, 0, false, 0)
    val ok = PdfEngine.compressPdfImages(ctx, tmp, out, quality.coerceIn(10, 100))
    val outKb = if (ok && out.exists()) out.length() / 1024L else 0L
    PdfCompressResult(ok, outKb, false, PdfEngine.pageCount(ctx, uri))
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
  val filterOriginals = mutableStateMapOf<Uri, Bitmap>()

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
   *  is filtered, so the filter maps lose the right entry. */
  fun deleteAt(index: Int) {
    val uri = pages.getOrNull(index)?.uri ?: return
    filterModes.remove(uri)
    filterOriginals.remove(uri)
    pages = pages.filterIndexed { i, _ -> i != index }
    if (selectedUri == uri) selectedUri = pages.getOrNull((index - 1).coerceAtLeast(0))?.uri
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
      TitleText(title, color = theme.text)
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
    Text(text, color = theme.textOnPrimary, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold)
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
@Composable
fun rememberFolderSaver(
  onSaved: (List<File>) -> Unit,
  onCancelled: () -> Unit
): (List<Pair<File, String>>) -> Unit {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var pending by remember { mutableStateOf<List<Pair<File, String>>?>(null) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
    val files = pending
    pending = null
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
        when {
          saved.isNotEmpty() && failed == 0 -> onSaved(saved)
          saved.isNotEmpty() -> {
            Toast.makeText(ctx, "Saved ${saved.size} of ${files.size} files — $failed could not be saved to the chosen folder", Toast.LENGTH_LONG).show()
            onSaved(saved)
          }
          else -> Toast.makeText(ctx, "Could not save to the chosen folder — files kept in Chami folder", Toast.LENGTH_LONG).show()
        }
      }
    }
  }
  return { files ->
    pending = files
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
  onSaved: (File) -> Unit
): (File) -> Unit {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var pending by remember { mutableStateOf<File?>(null) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
    val file = pending
    pending = null
    if (uri != null && file != null) {
      scope.launch {
        val ok = withContext(Dispatchers.IO) { SaveLocation.copyToUri(ctx, file, uri) }
        if (ok) onSaved(file)
        else Toast.makeText(ctx, "Could not save to chosen folder", Toast.LENGTH_SHORT).show()
      }
    } else if (uri == null && file != null) {
      Toast.makeText(ctx, "Save cancelled — file kept in Chami folder", Toast.LENGTH_SHORT).show()
    }
  }
  return { file ->
    pending = file
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
  onOpen: (File) -> Unit
) {
  val ctx = LocalContext.current
  Column(
    Modifier
      .fillMaxSize()
      .background(theme.bg)
      .statusBarsPadding()
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
      text = "Open first file",
      onClick = { files.firstOrNull()?.let { onOpen(it.file) } },
      modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(Ds.s12))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
      ChamiSecondaryButton(
        text = "Share first file",
        onClick = { files.firstOrNull()?.let { ShareHelper.shareFile(ctx, it.file, it.mimeType) } },
        modifier = Modifier.weight(1f)
      )
      ChamiSecondaryButton(text = "Done", onClick = onBackHome, modifier = Modifier.weight(1f))
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
  Box(
    Modifier
      .fillMaxWidth()
      .height(height)
      .clip(RoundedCornerShape(Ds.s8))
      .background(theme.card)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.s8))
  ) {
    val shown = page?.bitmap
    if (shown != null) {
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
              bitmap = shown.asImageBitmap(),
              contentDescription = label,
              modifier = Modifier.fillMaxSize().padding(horizontal = padX, vertical = padY),
              contentScale = ContentScale.Fit
            )
          }
        }
      }
    } else {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No page yet", fontSize = Ds.HINT_SP.sp, color = theme.textSecondary)
      }
    }
    Column(
      Modifier.align(Alignment.TopStart).padding(Ds.s8),
      verticalArrangement = Arrangement.spacedBy(Ds.s8)
    ) {
      PreviewActionButton(Icons.Filled.Crop, "Crop — cut the image", onCrop)
      PreviewActionButton(Icons.Filled.Delete, "Delete this page", onDelete)
      // FIX 2026-11-18 (Fix 11): FilterList, NOT Filter. Verified by
      // rendering the exact vectors from material-icons-extended 1.7.0:
      // Icons.Filled.Filter draws a picture frame with mountains (the
      // user rightly read it as an image icon); FilterList is the
      // proper funnel glyph. Crop = crop corners, Delete = trash,
      // Tune = sliders — all three already read correctly, unchanged.
      PreviewActionButton(Icons.Filled.FilterList, "Filter", onFilter)
      PreviewActionButton(Icons.Filled.Tune, "Adjust brightness", onAdjust)
    }
  }
}

// Thumbnail strip: 72dp thumbnails, 8dp gaps, selected thumbnail has a
// 2dp dark-green outline. Tap a thumbnail to change the big preview,
// hold and drag to reorder (drop at half a card width), big "+" tile
// after the last thumbnail.
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
  val haptic = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  val latestPages by rememberUpdatedState(pages)
  val latestOnMove by rememberUpdatedState(onMove)
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
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                  } else if (dragOffsetX < -halfPx && cur > 0) {
                    latestOnMove(cur, cur - 1)
                    dragOffsetX += cardFullPx
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
          Image(
            bitmap = page.bitmap.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
          )
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
        Text(
          "Page ${index + 1}",
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
        Text("Add", fontSize = Ds.HINT_SP.sp, color = theme.text)
      }
    }
  }
}

@Composable
fun FilterPickerDialog(
  selected: Int,
  theme: ChamiTheme,
  onPick: (Int) -> Unit,
  onDismiss: () -> Unit
) {
  val names = listOf("Original", "B&W", "Grayscale", "Enhance")
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Filter", fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold, color = theme.text) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(Ds.s8)) {
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
        HintText("Filter applies to this page only.")
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Done", color = theme.primary, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold)
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
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Adjust brightness", fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold, color = theme.text) },
    text = {
      Column {
        Text("Brightness: ${value.toInt()}", fontSize = Ds.BODY_SP.sp, color = theme.textSecondary)
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
        Text("Apply", color = theme.textOnPrimary, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel", color = theme.text) }
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
          Text("Cancel", color = Color.White, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("Crop — cuts the image", color = Color.White, fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold)
        TextButton(
          onClick = {
            try {
              onApply(ImagePageEditor.cropToRect(bitmap, rect, 1f))
            } catch (_: Exception) { onDismiss() }
          },
          modifier = Modifier.height(Ds.minTouch)
        ) {
          Text("Apply", color = ChamiColors.brightGreen, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold)
        }
      }
      Spacer(Modifier.height(Ds.s8))
      Text(
        "Drag the box to move it, or a corner / edge to resize it. With a ratio selected the box keeps that ratio. Everything outside the frame is removed.",
        fontSize = Ds.HINT_SP.sp, color = Color(0xFFCBD5E1)
      )
      Spacer(Modifier.height(Ds.s8))
      Row(
        horizontalArrangement = Arrangement.spacedBy(Ds.s8),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
      ) {
        ratios.forEach { (label, ratio) ->
          ChamiChip(label, ratioLabel == label, {
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
          val anchorX = if (corner == "tl" || corner == "bl") cur.right else cur.left
          val anchorY = if (corner == "tl" || corner == "tr") cur.bottom else cur.top
          val movesLeft = corner == "tl" || corner == "bl"
          val movesTop = corner == "tl" || corner == "tr"
          val availW = if (movesLeft) anchorX else 1f - anchorX
          val availH = if (movesTop) anchorY else 1f - anchorY
          // Drive by whichever dimension the finger moved most (dy
          // converted to width units through the ratio, so the
          // comparison is fair).
          val widthDriven = kotlin.math.abs(dx) >= kotlin.math.abs(dy) * ratio
          val w: Float; val h: Float
          if (widthDriven) {
            val maxW = minOf(availW, availH * ratio)
            if (maxW < min) return cur
            val rawW = if (movesLeft) anchorX - (cur.left + dx).coerceIn(0f, anchorX - min)
              else (cur.right + dx).coerceIn(anchorX + min, 1f) - anchorX
            w = rawW.coerceIn(min, maxW)
            h = w / ratio
          } else {
            val maxH = minOf(availH, availW / ratio)
            if (maxH < min) return cur
            val rawH = if (movesTop) anchorY - (cur.top + dy).coerceIn(0f, anchorY - min)
              else (cur.bottom + dy).coerceIn(anchorY + min, 1f) - anchorY
            h = rawH.coerceIn(min, maxH)
            w = h * ratio
          }
          val l = if (movesLeft) anchorX - w else anchorX
          val t = if (movesTop) anchorY - h else anchorY
          return ComposeRect(l, t, l + w, t + h)
        }

        // Resize an edge while KEEPING a locked ratio: the dragged
        // dimension drives, the other follows, centred on the frame.
        fun resizeEdgeRatio(cur: ComposeRect, edge: String, dx: Float, dy: Float, ratio: Float): ComposeRect {
          val min = 0.05f
          val cx = (cur.left + cur.right) / 2f
          val cy = (cur.top + cur.bottom) / 2f
          return when (edge) {
            "t", "b" -> {
              var h = if (edge == "t") cur.bottom - (cur.top + dy).coerceIn(0f, cur.bottom - min)
                else (cur.bottom + dy).coerceIn(cur.top + min, 1f) - cur.top
              h = h.coerceIn(min, 1f)
              var w = h * ratio
              if (w > 1f) { w = 1f; h = w / ratio }
              val l = (cx - w / 2f).coerceIn(0f, 1f - w)
              val t = if (edge == "t") cur.bottom - h else cur.top
              ComposeRect(l, t, l + w, t + h)
            }
            else -> { // "l" / "r"
              var w = if (edge == "l") cur.right - (cur.left + dx).coerceIn(0f, cur.right - min)
                else (cur.right + dx).coerceIn(cur.left + min, 1f) - cur.left
              w = w.coerceIn(min, 1f)
              var h = w / ratio
              if (h > 1f) { h = 1f; w = h * ratio }
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
            bitmap = bitmap.asImageBitmap(), contentDescription = "Crop preview",
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
          Box(
            Modifier.fillMaxSize().pointerInput("move") {
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
                .pointerInput(id) {
                  detectDragGestures { change, drag ->
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
                },
              contentAlignment = Alignment.Center
            ) {
              Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White).border(2.dp, ChamiColors.darkGreen, CircleShape))
            }
          }
        }
      }
      Spacer(Modifier.height(Ds.s16))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
        ChamiSecondaryButton(text = "Reset", onClick = { rect = ratioRect(ratios.first { it.first == ratioLabel }.second) }, modifier = Modifier.weight(1f))
        ChamiSecondaryButton(text = "Rotate 90°", onClick = onRotate, modifier = Modifier.weight(1f))
      }
    }
  }
}

// --- Shared page-editing helper (Images to PDF) ---------------------------
class PageEditor(
  private val ctx: Context,
  private val scope: kotlinx.coroutines.CoroutineScope,
  private val vm: PagesViewModel
) {
  private var busy = false

  /** Replaces page [index] with [bmp], saving it to a temp file first so
   *  the PDF uses the edited image. [newOriginal] becomes the filter
   *  base stored under the new Uri (crop/rotate = new base). */
  fun replacePage(index: Int, bmp: Bitmap, newOriginal: Bitmap? = bmp, onDone: () -> Unit = {}) {
    val page = vm.pages.getOrNull(index) ?: return
    if (busy) return
    busy = true
    scope.launch {
      try {
        val newUri = ImagePageEditor.bitmapToUri(ctx, bmp, "page_${index}_${System.currentTimeMillis()}.jpg")
        val oldUri = page.uri
        val wasSelected = vm.selectedUri == oldUri
        val oldMode = vm.filterModes.remove(oldUri)
        val updated = vm.pages.toMutableList()
        if (index in updated.indices) updated[index] = PageItem(newUri, bmp, cropped = true)
        vm.setPages(updated)
        if (wasSelected) vm.selectedUri = newUri
        if (oldMode != null) vm.filterModes[newUri] = oldMode
        vm.filterOriginals.remove(oldUri)
        if (newOriginal != null) vm.filterOriginals[newUri] = newOriginal
        onDone()
      } catch (_: Exception) {
        Toast.makeText(ctx, "Could not apply that edit", Toast.LENGTH_SHORT).show()
      }
      busy = false
    }
  }

  /** Applies filter [mode] to page [index] (0 = Original). The mode is
   *  stored per page Uri, so switching pages never resets it, and
   *  filters never stack: each applies to the stored original. */
  fun applyFilter(index: Int, mode: Int) {
    val page = vm.pages.getOrNull(index) ?: return
    if (busy) {
      Toast.makeText(ctx, "Please wait — finishing the last edit", Toast.LENGTH_SHORT).show()
      return
    }
    val base = vm.filterOriginals.getOrPut(page.uri) { page.bitmap }
    vm.filterModes[page.uri] = mode
    if (mode == 0) replacePage(index, base, newOriginal = base)
    else replacePage(index, ImagePageEditor.applyFilter(base, mode), newOriginal = base)
  }

  fun filterModeFor(page: PageItem): Int = vm.filterModes[page.uri] ?: 0
}

// 1. IMAGES TO PDF
// - Builds through the shared engine: PdfEngine.buildImagesPdf with
//   FIXED defaults — A4 page (595x842pt, fixed whatever the crop),
//   full-resolution quality, no watermark, no page numbers, 15mm
//   default margins (no option controls).
// - Picker loads at full resolution (loadBitmap cap 8000).
// - Crop CUTS by default (RectCropDialog) — the only crop on this
//   screen; the second crop (Straighten / warp) was removed at the
//   user's request. Per-page filter state, correct delete, selection
//   follows the page Uri.
@Composable
fun ImagesToPdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
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
  val saveFile = rememberSingleFileSaver { file -> doneFile = file; showDone = true }

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
        // Full resolution: cap 8000 effectively keeps the source pixels.
        val added = selected.mapNotNull { uri ->
          PdfEngine.loadBitmap(ctx, uri, 8000)?.let { bmp -> PageItem(uri, bmp) }
        }
        withContext(Dispatchers.Main) {
          val firstNew = pages.size
          vm.setPages(pages + added, selectFirstNew = firstNew)
        }
      }
    }
  }

  fun buildPdf() {
    if (pages.isEmpty()) {
      Toast.makeText(ctx, "Please select at least 1 image", Toast.LENGTH_SHORT).show()
      return
    }
    isProcessing = true
    progressText = "Page 1 of ${pages.size}"
    scope.launch {
      val out = PdfEngine.outFile(ctx, "IMG_PDF_${System.currentTimeMillis()}.pdf")
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
      } catch (e: Exception) {
        e.printStackTrace()
        null
      }
      isProcessing = false
      progressText = ""
      if (file != null) saveFile(file)
      else Toast.makeText(ctx, "Failed to create PDF", Toast.LENGTH_SHORT).show()
    }
  }

  cropIndex?.let { index ->
    val page = pages.getOrNull(index)
    if (page != null) {
      RectCropDialog(
        bitmap = page.bitmap,
        theme = theme,
        onApply = { bmp ->
          vm.filterOriginals.remove(page.uri)
          editor.replacePage(index, bmp, newOriginal = bmp) { cropIndex = null }
        },
        onDismiss = { cropIndex = null },
        onRotate = {
          vm.filterOriginals.remove(page.uri)
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
      onDismiss = { showFilters = false }
    )
  }
  if (showAdjust) {
    BrightnessDialog(
      value = brightness,
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
      onOpen = { ShareHelper.openFile(ctx, it, "application/pdf") }
    )
    return
  }

  ToolScreenContainer("Images to PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Text("Select photos from gallery", fontSize = Ds.BODY_SP.sp, color = theme.text)
      Spacer(Modifier.height(Ds.s16))

      if (pages.isEmpty()) {
        ChamiPrimaryButton(
          text = "Choose images",
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
          "Page ${safeSelected + 1} of ${pages.size}",
          fontWeight = FontWeight.Bold, fontSize = Ds.BODY_SP.sp, color = theme.text
        )
        if (pages.size > 50) {
          Spacer(Modifier.height(Ds.s4))
          HintText("Over 50 pages — processing may take longer.")
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
      label = "Slide to Convert",
      theme = theme,
      enabled = !isProcessing && pages.isNotEmpty(),
      onSlideComplete = { vibrate(); buildPdf() }
    )
  }
}

// 3. PDF TO IMAGES — JPG (default) / PNG, resolution Screen 150 /
// High 300 (default) / Print 600 DPI, page range with live validation,
// per-page progress, images named NAME_p1, NAME_p2... The save folder
// is asked ONCE and every image is saved there, then a Done screen
// lists them all.
// FIX 2026-11-18 (Fix 6): thumbnail grid of EVERY page — tap to
// select/deselect, Select all / Clear. Extraction converts only the
// selected pages inside the range (all selected = the old behaviour).
@Composable
fun PdfToImagesScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pdfUri by remember { mutableStateOf<Uri?>(null) }
  var totalPages by remember { mutableStateOf(0) }
  var pageOnePreview by remember { mutableStateOf<Bitmap?>(null) }
  // FIX 2026-11-18 (Fix 6): page selection. All pages are selected by
  // default (same behaviour as before); tapping a thumbnail toggles
  // it. Thumbnails render in the background, one page at a time, at
  // small scale; picking another PDF cancels the old render job so
  // stale thumbnails never land on the new document.
  val selectedPages = remember { mutableStateListOf<Int>() }
  val pageThumbs = remember { mutableStateMapOf<Int, Bitmap>() }
  var thumbJob by remember { mutableStateOf<Job?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var progressText by remember { mutableStateOf("") }
  var format by rememberSaveable { mutableStateOf("JPG") }
  var resolution by rememberSaveable { mutableStateOf("High 300") }
  var rangeFrom by rememberSaveable { mutableStateOf("1") }
  var rangeTo by rememberSaveable { mutableStateOf("") }

  var doneFiles by remember { mutableStateOf<List<DoneFileInfo>?>(null) }
  var showDone by remember { mutableStateOf(false) }
  val folderSave = rememberFolderSaver(
    onSaved = { files ->
      doneFiles = files.map { DoneFileInfo(it, if (format == "PNG") "image/png" else "image/jpeg", formatSizeText(it.length())) }
      showDone = true
    },
    onCancelled = { Toast.makeText(ctx, "Save cancelled — images kept in Chami folder", Toast.LENGTH_SHORT).show() }
  )
  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    pageOnePreview = null
    showDone = false
    doneFiles = null
    selectedPages.clear()
    pageThumbs.clear()
    if (selected != null) {
      thumbJob?.cancel()
      thumbJob = scope.launch(Dispatchers.IO) {
        val n = PdfEngine.pageCount(ctx, selected)
        val bmp = PdfReader.renderPageBitmap(ctx, selected, 0, 2.0f)
        withContext(Dispatchers.Main) {
          totalPages = n
          for (i in 1..n) selectedPages.add(i)
          pageOnePreview = bmp
          rangeFrom = "1"
          rangeTo = "$n"
        }
        // Thumbnails for every page, small scale, one at a time.
        for (p in 1..n) {
          val thumb = PdfReader.renderPageBitmap(ctx, selected, p - 1, 0.4f) ?: continue
          withContext(Dispatchers.Main) { pageThumbs[p] = thumb }
        }
      }
    }
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
      Toast.makeText(ctx, "Check the page range first", Toast.LENGTH_SHORT).show()
      return
    }
    if (effectivePages.isEmpty()) {
      Toast.makeText(ctx, "Select at least 1 page to extract", Toast.LENGTH_SHORT).show()
      return
    }
    isProcessing = true
    scope.launch {
      val dir = File(ctx.getExternalFilesDir(null), "Chami/Images_${System.currentTimeMillis()}")
      if (!dir.exists()) dir.mkdirs()
      val dpi = when (resolution) { "Screen 150" -> 150; "Print 600" -> 600; else -> 300 }
      val scale = dpi / 72f
      val fmt = if (format == "PNG") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
      val ext = if (format == "PNG") "png" else "jpg"
      val base = baseNameOf(ctx, u, "PDF")
      val files = mutableListOf<File>()
      withContext(Dispatchers.IO) {
        for ((idx, p) in effectivePages.withIndex()) {
          withContext(Dispatchers.Main) { progressText = "Image ${idx + 1} of ${effectivePages.size}" }
          val bmp = PdfReader.renderPageBitmap(ctx, u, p - 1, scale) ?: continue
          val f = File(dir, "${base}_p$p.$ext")
          try {
            FileOutputStream(f).use { bmp.compress(fmt, 90, it) }
            files.add(f)
          } catch (_: Exception) { }
          bmp.recycle()
        }
      }
      isProcessing = false
      progressText = ""
      if (files.isNotEmpty()) {
        val mime = if (format == "PNG") "image/png" else "image/jpeg"
        folderSave(files.map { it to mime })
      } else {
        Toast.makeText(ctx, "Could not extract images", Toast.LENGTH_SHORT).show()
      }
    }
  }

  if (showDone && doneFiles != null) {
    MultiDoneScreen(
      files = doneFiles!!,
      title = "Images extracted",
      summaryLines = listOf(
        "${doneFiles!!.size} images created as $format at $resolution DPI.",
        "Saved to the folder you chose."
      ),
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, if (format == "PNG") "image/png" else "image/jpeg") }
    )
    return
  }

  ToolScreenContainer("PDF to Images", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      ChamiSecondaryButton(
        text = if (pdfUri != null) "PDF selected ($totalPages pages)" else "Select PDF",
        onClick = { vibrate(); picker.launch("application/pdf") },
        modifier = Modifier.fillMaxWidth()
      )

      if (pageOnePreview != null) {
        Spacer(Modifier.height(Ds.s16))
        SectionText("Preview (page 1 of $totalPages)", color = theme.text)
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
        SectionText("Format", color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
          listOf("JPG", "PNG").forEach { f ->
            ChamiChip(f, format == f, { vibrate(); format = f })
          }
        }
        if (format == "PNG") {
          Spacer(Modifier.height(Ds.s4))
          HintText("PNG files are larger than JPG.")
        }
        Spacer(Modifier.height(Ds.s16))
        SectionText("Resolution", color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
          listOf("Screen 150", "High 300", "Print 600").forEach { r ->
            ChamiChip(r, resolution == r, { vibrate(); resolution = r })
          }
        }
        Spacer(Modifier.height(Ds.s16))
        SectionText("Page range", color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
          DebouncedTextField(
            value = rangeFrom, onValueChange = { rangeFrom = it.filter { ch -> ch.isDigit() } },
            label = "From page", keyboardType = KeyboardType.Number,
            isError = !rangeValid,
            modifier = Modifier.weight(1f)
          )
          DebouncedTextField(
            value = rangeTo, onValueChange = { rangeTo = it.filter { ch -> ch.isDigit() } },
            label = "To page", keyboardType = KeyboardType.Number,
            isError = !rangeValid,
            modifier = Modifier.weight(1f)
          )
        }
        if (!rangeValid) {
          Spacer(Modifier.height(Ds.s4))
          Text(
            "Enter pages from 1 to $totalPages, From before To.",
            fontSize = Ds.HINT_SP.sp, color = ChamiColors.errorRed
          )
        } else if (imageCount > 0) {
          Spacer(Modifier.height(Ds.s8))
          Text(
            "$imageCount image${if (imageCount == 1) "" else "s"} will be created",
            fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold, color = theme.text
          )
        }
        // FIX 2026-11-18 (Fix 6): thumbnail grid of EVERY page — tap
        // to select/deselect. Same pattern as Split PDF's page grid
        // (Select all / Clear + tick overlay), but with real page
        // thumbnails, labelled Page N. Extraction converts only the
        // selected pages inside the range above.
        Spacer(Modifier.height(Ds.s16))
        SectionText("Pages — tap to select", color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
          Text(
            "${selectedPages.size}/$totalPages selected",
            fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold, color = theme.text
          )
          Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
            TextButton(onClick = { vibrate(); selectedPages.clear(); for (i in 1..totalPages) selectedPages.add(i) }, modifier = Modifier.height(Ds.minTouch)) {
              Text("Select all", color = theme.primary, fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = { vibrate(); selectedPages.clear() }, modifier = Modifier.height(Ds.minTouch)) {
              Text("Clear", color = theme.primary, fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold)
            }
          }
        }
        Spacer(Modifier.height(Ds.s8))
        Column(verticalArrangement = Arrangement.spacedBy(Ds.s8)) {
          (1..totalPages).chunked(3).forEach { rowPages ->
            Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
              rowPages.forEach { p ->
                val isSelected = selectedPages.contains(p)
                val thumb = pageThumbs[p]
                Column(
                  Modifier.weight(1f).clickable {
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
                    "Page $p",
                    fontSize = Ds.HINT_SP.sp,
                    color = if (isSelected) theme.text else theme.textSecondary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                  )
                }
              }
              // Pad a short last row so the cells keep their width.
              if (rowPages.size < 3) {
                repeat(3 - rowPages.size) { Spacer(Modifier.weight(1f)) }
              }
            }
          }
        }
        if (selectedPages.isEmpty()) {
          Spacer(Modifier.height(Ds.s8))
          Text(
            "No pages selected — tap thumbnails to choose which pages to extract.",
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
      label = "Slide to Extract",
      theme = theme,
      enabled = !isProcessing && pdfUri != null && rangeValid && effectivePages.isNotEmpty(),
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
fun SplitPdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pdfUri by remember { mutableStateOf<Uri?>(null) }
  var totalPages by remember { mutableStateOf(0) }
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
  val singleSave = rememberSingleFileSaver { file -> doneFile = file; showDone = true }
  val folderSave = rememberFolderSaver(
    onSaved = { files ->
      doneFiles = files.map { DoneFileInfo(it, "application/pdf", formatSizeText(it.length())) }
      showDone = true
    },
    onCancelled = { Toast.makeText(ctx, "Save cancelled — files kept in Chami folder", Toast.LENGTH_SHORT).show() }
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

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    selectedPages.clear()
    showDone = false
    doneFile = null
    doneFiles = null
    pagePreviewBitmap = null
    if (selected != null) {
      scope.launch(Dispatchers.IO) {
        val n = PdfEngine.pageCount(ctx, selected)
        val bmp = PdfReader.renderPageBitmap(ctx, selected, 0, 2.0f)
        withContext(Dispatchers.Main) {
          totalPages = n
          for (i in 1..n) selectedPages.add(i)
          previewPageIndex = 1
          pagePreviewBitmap = bmp
          rangeFrom = "1"
          rangeTo = "$n"
        }
      }
    }
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
      Toast.makeText(ctx, "Select at least 1 page to keep", Toast.LENGTH_SHORT).show()
      return
    }
    if (splitMode == "Range" && !rangeValid) {
      Toast.makeText(ctx, "Check the page range first", Toast.LENGTH_SHORT).show()
      return
    }
    if (splitMode == "Every N" && totalPages <= 1) {
      Toast.makeText(ctx, "This PDF has only $totalPages page — nothing to split into parts", Toast.LENGTH_LONG).show()
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
        else -> Toast.makeText(ctx, "Failed to split PDF", Toast.LENGTH_SHORT).show()
      }
    }
  }

  if (showDone && doneFiles != null) {
    MultiDoneScreen(
      files = doneFiles!!,
      title = "PDF split",
      summaryLines = listOf("${doneFiles!!.size} parts created.", "All parts saved to the folder you chose."),
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, "application/pdf") }
    )
    return
  }
  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, "application/pdf") }
    )
    return
  }

  ToolScreenContainer("Split PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      ChamiSecondaryButton(
        text = if (pdfUri != null) "PDF ($totalPages pages)" else "Select PDF",
        onClick = { vibrate(); picker.launch("application/pdf") },
        modifier = Modifier.fillMaxWidth()
      )

      if (totalPages > 0) {
        Spacer(Modifier.height(Ds.s16))
        SectionText("Split mode", color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
          listOf("Selected", "Range", "Every N").forEach { m ->
            ChamiChip(m, splitMode == m, { vibrate(); splitMode = m })
          }
        }
        if (splitMode == "Range") {
          Spacer(Modifier.height(Ds.s12))
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
            DebouncedTextField(
              value = rangeFrom, onValueChange = { rangeFrom = it.filter { ch -> ch.isDigit() } },
              label = "From page", keyboardType = KeyboardType.Number, isError = !rangeValid,
              modifier = Modifier.weight(1f)
            )
            DebouncedTextField(
              value = rangeTo, onValueChange = { rangeTo = it.filter { ch -> ch.isDigit() } },
              label = "To page", keyboardType = KeyboardType.Number, isError = !rangeValid,
              modifier = Modifier.weight(1f)
            )
          }
          if (!rangeValid) {
            Spacer(Modifier.height(Ds.s4))
            Text("Enter pages from 1 to $totalPages, From before To.", fontSize = Ds.HINT_SP.sp, color = ChamiColors.errorRed)
          }
        }
        if (splitMode == "Every N") {
          Spacer(Modifier.height(Ds.s12))
          DebouncedTextField(
            value = everyN, onValueChange = { everyN = it.filter { ch -> ch.isDigit() } },
            label = "Pages per part", keyboardType = KeyboardType.Number,
            isError = nVal <= 0,
            supportingText = if (nVal <= 0) "Enter at least 1 page per part." else null
          )
          if (parts.isNotEmpty()) {
            Spacer(Modifier.height(Ds.s12))
            Column(verticalArrangement = Arrangement.spacedBy(Ds.s4)) {
              parts.forEachIndexed { i, range ->
                Text(
                  "Part ${i + 1}: pages ${range.first}–${range.last}",
                  fontSize = Ds.BODY_SP.sp, color = theme.text
                )
              }
            }
          }
        }
        Spacer(Modifier.height(Ds.s16))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
          Text(
            "Pages (${selectedPages.size}/$totalPages)",
            fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold, color = theme.text
          )
          Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
            TextButton(onClick = { vibrate(); selectedPages.clear(); for (i in 1..totalPages) selectedPages.add(i) }, modifier = Modifier.height(Ds.minTouch)) {
              Text("Select all", color = theme.primary, fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = { vibrate(); selectedPages.clear() }, modifier = Modifier.height(Ds.minTouch)) {
              Text("Clear", color = theme.primary, fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold)
            }
          }
        }
        Spacer(Modifier.height(Ds.s8))
        // Page grid: tap a page to select it (tick overlay) and preview it.
        Column(verticalArrangement = Arrangement.spacedBy(Ds.s8)) {
          (1..totalPages).chunked(5).forEach { rowPages ->
            Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
              rowPages.forEach { p ->
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
                      if (isSelected) selectedPages.remove(p) else selectedPages.add(p)
                    },
                  contentAlignment = Alignment.Center
                ) {
                  Text(
                    "$p",
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) theme.textOnPrimary else theme.text,
                    fontSize = Ds.BODY_SP.sp
                  )
                  if (isSelected) {
                    Icon(
                      Icons.Filled.Check, contentDescription = "Selected",
                      tint = theme.textOnPrimary, modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
                    )
                  }
                }
              }
            }
          }
        }

        if (pagePreviewBitmap != null) {
          Spacer(Modifier.height(Ds.s16))
          SectionText("Previewing page $previewPageIndex", color = theme.text)
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
        HintText("Split pages are rendered as images — text in the new file is not selectable.")
      }

      Spacer(Modifier.height(Ds.s16))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(Ds.s8))
        Text(progressText, fontSize = Ds.HINT_SP.sp, color = theme.text)
      }
    }

    SlideToAction(
      label = "Slide to Split",
      theme = theme,
      enabled = !isProcessing && pdfUri != null && rangeValid &&
        (splitMode != "Selected" || selectedPages.isNotEmpty()),
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