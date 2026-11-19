package com.chami.pdfmaker.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chami.pdfmaker.LocaleHelper
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

// ---------------------------------------------------------------------------
// ToolScreens (part 2): Merge, Compress, Resize/Convert, OCR, Reader, Protect.
// Shares the helpers in ToolScreens.kt (same package).
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Phase 5 — shared polish for the Merge / Compress / Protect screens in
// this file: one section-header style (green accent bar, as on Home),
// one card style, one segmented tab row, one empty state, and the
// staggered enter fade for list rows. No behaviour change — these only
// restyle what the three screens already render.
// Phase 5 follow-up: the helpers are internal (not private) so Split /
// PDF to Images in ToolScreens.kt reuse them instead of duplicating them.
// ---------------------------------------------------------------------------

/** Phase 5: section header matching Home — 4dp green accent bar + bold
 *  section text. `topPadding` is 0 inside cards, Ds.s16 between blocks. */
@Composable
internal fun ToolSectionHeader(text: String, color: Color, topPadding: Dp = Ds.s16) {
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
    SectionText(text, color = color)
  }
}

/** Phase 5: the one card style for these screens — 12dp radius, card
 *  background, 1dp border, 16dp padding by default. */
@Composable
internal fun ToolCard(
  theme: ChamiTheme,
  modifier: Modifier = Modifier,
  padding: Dp = Ds.s16,
  horizontalAlignment: Alignment.Horizontal = Alignment.Start,
  content: @Composable ColumnScope.() -> Unit
) {
  Column(
    modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(Ds.tileRadius))
      .background(theme.card)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
      .padding(padding),
    horizontalAlignment = horizontalAlignment
  ) {
    content()
  }
}

/** Phase 5: segmented tab row shared by Compress (Image/PDF) and
 *  Protect (Protect/Unlock) — same card, same 48dp pills, same
 *  selected green. Pills shrink slightly while pressed (Phase 3
 *  pressScale); the labels are passed through unchanged. */
@Composable
internal fun ToolTabRow(
  tabs: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  theme: ChamiTheme,
  animOn: Boolean,
  onTap: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(Ds.tileRadius))
      .background(theme.card)
      .padding(Ds.s4),
    horizontalArrangement = Arrangement.spacedBy(Ds.s4)
  ) {
    tabs.forEachIndexed { index, label ->
      val interactionSource = remember { MutableInteractionSource() }
      Box(
        modifier = Modifier
          .weight(1f)
          .height(Ds.minTouch)
          .clip(RoundedCornerShape(Ds.s8))
          .background(if (selectedIndex == index) theme.primary else Color.Transparent)
          .pressScale(animOn, interactionSource)
          .clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current
          ) { onTap(); onSelect(index) },
        contentAlignment = Alignment.Center
      ) {
        Text(
          label,
          fontWeight = FontWeight.Bold,
          fontSize = Ds.HINT_SP.sp,
          color = if (selectedIndex == index) Color.White else theme.text
        )
      }
    }
  }
}

/** Phase 5: empty state for "no file yet" — icon + one line of
 *  guidance, in the same card style. The text reuses an existing
 *  LocaleHelper string (no new keys). */
@Composable
internal fun ToolEmptyState(icon: ImageVector, text: String, theme: ChamiTheme) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(Ds.tileRadius))
      .background(theme.card)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
      .padding(Ds.s16),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Icon(icon, contentDescription = null, tint = theme.textSecondary, modifier = Modifier.size(Ds.tileIconSize))
    Spacer(Modifier.height(Ds.s8))
    Text(text, fontSize = Ds.BODY_SP.sp, color = theme.textSecondary, textAlign = TextAlign.Center)
  }
}

/** Phase 5: pulsing skeleton row while Merge previews render — same
 *  900ms pulse as PageSkeletonLoader, GPU alpha only. 80dp matches
 *  the file row (12dp padding + 56dp thumbnail). */
@Composable
private fun MergeRowSkeleton() {
  val pulse by rememberInfiniteTransition(label = "mergeSkeleton").animateFloat(
    initialValue = 0.35f, targetValue = 0.7f,
    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    label = "mergeSkeletonAlpha"
  )
  Box(
    Modifier
      .fillMaxWidth()
      .height(80.dp)
      .clip(RoundedCornerShape(Ds.tileRadius))
      .background(ChamiColors.skeletonBg)
      .graphicsLayer { alpha = pulse }
  )
}

/** Phase 6: pulsing skeleton box while a preview / result loads
 *  (Resize/Convert preview, OCR result) — same 900ms pulse as
 *  MergeRowSkeleton / PageSkeletonLoader, GPU alpha only. The
 *  height matches the content that will replace it, so the layout
 *  does not jump when the load finishes. */
@Composable
private fun Phase6SkeletonBox(height: Dp) {
  val pulse by rememberInfiniteTransition(label = "phase6Skeleton").animateFloat(
    initialValue = 0.35f, targetValue = 0.7f,
    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    label = "phase6SkeletonAlpha"
  )
  Box(
    Modifier
      .fillMaxWidth()
      .height(height)
      .clip(RoundedCornerShape(Ds.tileRadius))
      .background(ChamiColors.skeletonBg)
      .graphicsLayer { alpha = pulse }
  )
}

/** Phase 5: staggered fade-up for list rows, mirroring the Home
 *  tiles — fade + 16dp rise, Ds.STAGGER_MS apart, shared easing.
 *  Instant (no motion) when animations are off. */
@Composable
internal fun Modifier.toolEnterFade(index: Int, enabled: Boolean): Modifier {
  var entered by remember { mutableStateOf(!enabled) }
  LaunchedEffect(Unit) {
    if (enabled) {
      delay((index * Ds.STAGGER_MS).toLong())
      entered = true
    }
  }
  val density = LocalDensity.current
  val alpha by animateFloatAsState(
    targetValue = if (entered) 1f else 0f,
    animationSpec = tween(Ds.ANIM_MS, easing = Ds.sharedEasing), label = "toolEnterAlpha"
  )
  val offsetY by animateFloatAsState(
    targetValue = if (entered) 0f else with(density) { Ds.enterOffsetY.toPx() },
    animationSpec = tween(Ds.ANIM_MS, easing = Ds.sharedEasing), label = "toolEnterOffset"
  )
  return this.graphicsLayer { this.alpha = alpha; translationY = offsetY }
}

@Composable
fun MergePdfScreen(theme: ChamiTheme, initialPdfUri: Uri? = null, onInitialPdfConsumed: () -> Unit = {}, onOpenPdfInReader: ((File) -> Unit)? = null, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  // Phase 5: motion gate for the staggered row enter animation.
  val animOn = animationsEnabled(ctx)
  var pdfUris by rememberSaveableUriList()
  var pdfPreviews by remember { mutableStateOf<List<Pair<Int, Bitmap?>>>(emptyList()) }
  var initialConsumed by rememberSaveable { mutableStateOf(false) }
  // FIX 2026-11-18 (report #13): progress text shown while merging.
  var mergeProgressText by remember { mutableStateOf("") }
  var pdfSizes by remember { mutableStateOf<List<Long>>(emptyList()) }
  var previewJob by remember { mutableStateOf<Job?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var draggingUri by remember { mutableStateOf<Uri?>(null) }
  var dragOffsetY by remember { mutableStateOf(0f) }

  var doneFile by remember { mutableStateOf<File?>(null) }
  var showDone by remember { mutableStateOf(false) }
  var savedLocationText by remember { mutableStateOf<String?>(null) }
  val singleSave = rememberSingleFileSaver(
    onSavedToUri = { uri -> savedLocationText = displayNameOf(ctx, uri) }
  ) { file ->
    HistoryStore.record(ctx, file.name, "merge")
    doneFile = file
    showDone = true
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  // L15: file size without streaming the whole file — the resolver's
  // OpenableColumns.SIZE for content Uris, File.length() for file Uris.
  // Returns -1 when the size cannot be determined.
  fun uriSizeBytes(ctx: Context, uri: Uri): Long {
    return try {
      when (uri.scheme) {
        "content" -> {
          var size = -1L
          ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
              val idx = c.getColumnIndex(OpenableColumns.SIZE)
              if (idx >= 0) {
                val s = c.getLong(idx)
                if (s >= 0) size = s
              }
            }
          }
          size
        }
        "file" -> uri.path?.let { File(it).length() } ?: -1L
        else -> -1L
      }
    } catch (_: Exception) { -1L }
  }

  fun refreshPreviews(uris: List<Uri>) {
    previewJob?.cancel()
    previewJob = scope.launch(Dispatchers.IO) {
      val previews = uris.map { u ->
        val count = PdfEngine.pageCount(ctx, u)
        val bmp = PdfReader.renderPageBitmap(ctx, u, 0, 1.5f)
        Pair(count, bmp)
      }
      val sizes = uris.map { uriSizeBytes(ctx, it).coerceAtLeast(0) }
      withContext(Dispatchers.Main) {
        if (pdfUris == uris) {
          // M22: recycle the bitmaps being replaced, never just drop them.
          val old = pdfPreviews
          pdfPreviews = previews
          pdfSizes = sizes
          old.forEach { (_, b) -> try { b?.recycle() } catch (_: Exception) {} }
        } else {
          // Stale render (list changed while rendering): nobody will
          // ever display these — recycle them instead of leaking them.
          previews.forEach { (_, b) -> try { b?.recycle() } catch (_: Exception) {} }
        }
      }
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
    if (selected.isNotEmpty()) {
      showDone = false
      doneFile = null
      val combined = pdfUris + selected
      pdfUris = combined
      refreshPreviews(combined)
    }
  }

  // Reader hand-off (report #27): consume the incoming Uri once.
  LaunchedEffect(initialPdfUri) {
    if (initialPdfUri != null && !initialConsumed) {
      initialConsumed = true
      pdfUris = listOf(initialPdfUri)
      onInitialPdfConsumed()
    }
  }

  // Restored Uris (process death, report #47): reload previews when
  // the restored list has none yet.
  LaunchedEffect(pdfUris) {
    if (pdfUris.isNotEmpty() && pdfPreviews.size != pdfUris.size) refreshPreviews(pdfUris)
  }

  fun moveFile(from: Int, to: Int) {
    if (from !in pdfUris.indices || to !in pdfUris.indices) return
    val m = pdfUris.toMutableList()
    val item = m.removeAt(from)
    m.add(to, item)
    pdfUris = m
    val p = pdfPreviews.toMutableList()
    if (from in p.indices && to in p.indices) {
      val pi = p.removeAt(from)
      p.add(to, pi)
      pdfPreviews = p
    }
    val s = pdfSizes.toMutableList()
    if (from in s.indices && to in s.indices) {
      val si = s.removeAt(from)
      s.add(to, si)
      pdfSizes = s
    }
  }

  fun merge() {
    if (pdfUris.size < 2) {
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_merge_need_two"), Toast.LENGTH_SHORT).show()
      return
    }
    isProcessing = true
    // FIX 2026-11-18 (report #13): show the real total while merging.
    // PdfEngine.copyPages exposes no per-page progress callback (the
    // callback lives on PdfProtector.protectPdf only), so a live
    // "Page X of N" is not possible from this screen without an engine
    // change — the total page count is shown instead of a bare spinner.
    val totalPages = pdfPreviews.sumOf { it.first }
    mergeProgressText = if (totalPages > 0) "Merging $totalPages pages…" else "Merging PDF pages..."
    scope.launch {
      val out = PdfEngine.outFile(ctx, "MERGED_${System.currentTimeMillis()}.pdf")
      val sources = pdfUris.map { it to null }
      val ok = withContext(Dispatchers.IO) { PdfEngine.copyPages(ctx, sources, out) }
      isProcessing = false
      mergeProgressText = ""
      if (ok) singleSave(out)
      else Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_merge_failed"), Toast.LENGTH_SHORT).show()
    }
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

  ToolScreenContainer("Merge PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      ChamiSecondaryButton(
        text = "Choose PDFs (${pdfUris.size} selected)",
        onClick = { vibrate(); picker.launch("application/pdf") },
        modifier = Modifier.fillMaxWidth()
      )
      HintText("Merged pages are rendered as images — text in the merged file is not selectable.")

      // Phase 5: empty state while no file is chosen yet.
      if (pdfUris.isEmpty()) {
        Spacer(Modifier.height(Ds.s16))
        ToolEmptyState(
          icon = Icons.Filled.PictureAsPdf,
          text = LocaleHelper.t(ctx, "toast_merge_need_two"),
          theme = theme
        )
      }

      if (pdfPreviews.isNotEmpty()) {
        Spacer(Modifier.height(Ds.s16))
        val totalCombinedPages = pdfPreviews.sumOf { it.first }
        Text(
          "$totalCombinedPages total pages combined",
          fontWeight = FontWeight.Bold, fontSize = Ds.BODY_SP.sp, color = theme.text
        )
        Spacer(Modifier.height(Ds.s8))
        // FIX 2026-11-18 (report #24): a file that cannot be opened
        // (0 pages) says so instead of silently listing "0 pages".
        if (pdfPreviews.any { it.first == 0 }) {
          Text(UNOPENABLE_PDF_MESSAGE, fontSize = Ds.BODY_SP.sp, color = ChamiColors.errorRed)
          Spacer(Modifier.height(Ds.s8))
        }
        val rowPx = with(density) { 112.dp.toPx() }
        Column(verticalArrangement = Arrangement.spacedBy(Ds.s12)) {
          pdfPreviews.forEachIndexed { index, pair ->
            val rowUri = pdfUris.getOrNull(index)
            val isDragging = draggingUri != null && draggingUri == rowUri
            Row(
              Modifier
                .fillMaxWidth()
                // Phase 5: staggered fade-up on first appearance.
                .toolEnterFade(index, animOn)
                .graphicsLayer {
                  translationY = if (isDragging) dragOffsetY else 0f
                  scaleX = if (isDragging) 1.02f else 1f
                  scaleY = if (isDragging) 1.02f else 1f
                  shadowElevation = if (isDragging) 12f else 0f
                }
                .clip(RoundedCornerShape(Ds.tileRadius))
                .background(theme.card)
                .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
                .pointerInput(rowUri) {
                  detectDragGesturesAfterLongPress(
                    onDragStart = {
                      draggingUri = rowUri
                      dragOffsetY = 0f
                      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                    },
                    onDrag = { change, dragAmount ->
                      change.consume()
                      dragOffsetY += dragAmount.y
                      val cur = pdfUris.indexOf(rowUri)
                      if (cur < 0) return@detectDragGesturesAfterLongPress
                      val halfPx = rowPx / 2f
                      if (dragOffsetY > halfPx && cur < pdfUris.lastIndex) {
                        moveFile(cur, cur + 1)
                        dragOffsetY -= rowPx
                      } else if (dragOffsetY < -halfPx && cur > 0) {
                        moveFile(cur, cur - 1)
                        dragOffsetY += rowPx
                      }
                    },
                    onDragEnd = { draggingUri = null; dragOffsetY = 0f },
                    onDragCancel = { draggingUri = null; dragOffsetY = 0f }
                  )
                }
                .padding(Ds.s12),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Filled.DragHandle, contentDescription = "Drag to reorder", tint = theme.textSecondary, modifier = Modifier.size(Ds.iconSize))
              Spacer(Modifier.width(Ds.s8))
              if (pair.second != null) {
                Image(
                  bitmap = pair.second!!.asImageBitmap(),
                  contentDescription = "PDF ${index + 1}",
                  modifier = Modifier.size(56.dp).clip(RoundedCornerShape(Ds.s4)),
                  contentScale = ContentScale.Fit
                )
              } else {
                // Phase 5: grey placeholder until the page render lands.
                Box(
                  Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(Ds.s4))
                    .background(ChamiColors.skeletonBg),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = theme.textSecondary, modifier = Modifier.size(Ds.iconSize))
                }
              }
              Spacer(Modifier.width(Ds.s12))
              Column(Modifier.weight(1f)) {
                Text(
                  displayNameOf(ctx, pdfUris[index]) ?: "Doc ${index + 1}",
                  fontSize = Ds.BODY_SP.sp, color = theme.text, fontWeight = FontWeight.Bold,
                  maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                  "${pair.first} pages • ${formatSizeText(pdfSizes.getOrNull(index) ?: 0)}",
                  fontSize = Ds.HINT_SP.sp, color = theme.textSecondary
                )
              }
              Column(verticalArrangement = Arrangement.spacedBy(Ds.s4)) {
                ChamiIconButton(
                  icon = Icons.Filled.KeyboardArrowUp, description = "Move up",
                  onClick = { vibrate(); moveFile(index, index - 1) }
                )
                ChamiIconButton(
                  icon = Icons.Filled.KeyboardArrowDown, description = "Move down",
                  onClick = { vibrate(); moveFile(index, index + 1) }
                )
              }
              ChamiIconButton(
                icon = Icons.Filled.Close, description = "Remove file",
                onClick = {
                  vibrate()
                  val m = pdfUris.toMutableList()
                  m.removeAt(index)
                  pdfUris = m
                  refreshPreviews(m)
                }
              )
            }
          }
          // Phase 5: skeleton rows while the picked files still render.
          if (pdfUris.isNotEmpty() && pdfPreviews.size < pdfUris.size) {
            repeat(pdfUris.size - pdfPreviews.size) { MergeRowSkeleton() }
          }
        }
        // FIX 2026-11-18 (report #14): the long-press drag hint sits
        // under the file list, where the files it describes are.
        Spacer(Modifier.height(Ds.s8))
        HintText("Long-press a row and drag to reorder. Arrow buttons move it one step.")
      }

      Spacer(Modifier.height(Ds.s16))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(Ds.s8))
        Text(if (mergeProgressText.isNotEmpty()) mergeProgressText else "Merging PDF pages...", fontSize = Ds.HINT_SP.sp, color = theme.text)
      }
    }

    SlideToAction(
      label = LocaleHelper.t(ctx, "slide_merge"),
      theme = theme,
      enabled = !isProcessing && pdfUris.size >= 2 && pdfPreviews.size == pdfUris.size && pdfPreviews.all { it.first > 0 },
      disabledReason = when {
        pdfUris.size < 2 -> LocaleHelper.t(ctx, "toast_merge_need_two")
        pdfPreviews.any { it.first == 0 } -> UNOPENABLE_PDF_MESSAGE
        else -> null
      },
      onSlideComplete = { vibrate(); merge() }
    )
  }
}

// 6. COMPRESS KB — Images / PDF tabs (48dp). Images: multi-select,
// quality slider 10-100% (default 70%) with live estimated size,
// presets Under 100 / 200 / 500 KB / 1 MB + custom KB field. If the
// target cannot be reached, the user is told BEFORE sliding
// ("Smallest possible is about X KB"). PDF: the quality slider uses
// the exact quality (no hidden 3-preset mapping); pages are
// re-encoded as images, so text becomes an image — the UI says so
// plainly. Done shows actual before/after + % saved, and says plainly
// if the output is not smaller (the smaller file is kept).
@Composable
fun CompressKbScreen(theme: ChamiTheme, initialPdfUri: Uri? = null, onInitialPdfConsumed: () -> Unit = {}, onOpenPdfInReader: ((File) -> Unit)? = null, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  // Phase 5: motion gate for the tab-row press-scale.
  val animOn = animationsEnabled(ctx)

  var selectedTab by rememberSaveable { mutableStateOf(0) } // 0: IMAGE, 1: PDF
  var quality by rememberSaveable { mutableStateOf(70) }

  // --- IMAGE TAB STATE ---
  var imageUris by rememberSaveableUriList()
  var imageOrigKb by remember { mutableStateOf(0L) }
  var imagePreview by remember { mutableStateOf<Bitmap?>(null) }
  var targetKb by rememberSaveable { mutableStateOf<Int?>(null) }
  var customInput by rememberSaveable { mutableStateOf("") }
  var minPossibleKb by remember { mutableStateOf<Long?>(null) }
  var isImageProcessing by remember { mutableStateOf(false) }
  var imageProgressText by remember { mutableStateOf("") }
  var imageResults by remember { mutableStateOf<List<String>>(emptyList()) }

  // --- PDF TAB STATE ---
  var pdfUri by rememberSaveableUri()
  var pdfLoadFailed by remember { mutableStateOf(false) }
  var loadedPdfUri by remember { mutableStateOf<Uri?>(null) }
  var initialConsumed by rememberSaveable { mutableStateOf(false) }
  var pdfOrigBytes by remember { mutableStateOf(0L) }
  var pdfTotalPages by remember { mutableStateOf(0) }
  var pdfPreviewBmp by remember { mutableStateOf<Bitmap?>(null) }
  var isPdfProcessing by remember { mutableStateOf(false) }
  var pdfProgressText by remember { mutableStateOf("") }

  // --- DONE STATE ---
  var doneFiles by remember { mutableStateOf<List<DoneFileInfo>?>(null) }
  var doneSummary by remember { mutableStateOf<List<String>>(emptyList()) }
  var showDone by remember { mutableStateOf(false) }
  // FIX 2026-11-18 (report #15): per-file MIME types for compressed
  // images — a kept PNG original is a .png file, not a .jpg.
  var lastImageMimes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
  var savedLocationText by remember { mutableStateOf<String?>(null) }
  val folderSave = rememberFolderSaver(
    onSaved = { files ->
      files.forEach { HistoryStore.record(ctx, it.name, "compress") }
      doneFiles = files.map { DoneFileInfo(it, if (selectedTab == 0) (lastImageMimes[it.name] ?: "image/jpeg") else "application/pdf", formatSizeText(it.length())) }
      showDone = true
    },
    onCancelled = { Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_save_cancelled_files"), Toast.LENGTH_SHORT).show() },
    onSavedToFolder = { uri -> savedLocationText = displayNameOf(ctx, uri) }
  )
  val singleSave = rememberSingleFileSaver(
    onSavedToUri = { uri -> savedLocationText = displayNameOf(ctx, uri) }
  ) { file ->
    HistoryStore.record(ctx, file.name, "compress")
    doneFiles = listOf(DoneFileInfo(file, "application/pdf", formatSizeText(file.length())))
    showDone = true
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
    if (selected.isNotEmpty()) {
      imageUris = imageUris + selected
      showDone = false
      doneFiles = null
      imageResults = emptyList()
      scope.launch(Dispatchers.IO) {
        val first = selected.firstOrNull() ?: return@launch
        val bmp = PdfEngine.loadBitmap(ctx, first, 1200)
        var size = 0L
        try {
          ctx.contentResolver.openInputStream(first)?.use { input ->
            var total = 0L
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) total += n
            size = total / 1024L
          }
        } catch (_: Exception) {}
        withContext(Dispatchers.Main) {
          imagePreview = bmp
          imageOrigKb = size
        }
      }
    }
  }

  // M24: smallest-possible estimate, debounced. The custom-KB field
  // already debounces its own value (DebouncedTextField, 300ms); this
  // effect waits a further 400ms after the last targetKb change, so
  // typing never starts a decode/compress. Restarting the effect
  // cancels the previous wait AND the previous in-flight estimate, so
  // no heavy work runs per keystroke.
  LaunchedEffect(imageUris, targetKb) {
    val first = imageUris.firstOrNull()
    if (first == null) { minPossibleKb = null; return@LaunchedEffect }
    delay(400)
    minPossibleKb = withContext(Dispatchers.IO) {
      try {
        val bmp = PdfEngine.loadBitmap(ctx, first, 1200) ?: return@withContext null
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 20, baos)
        val previewBytes = baos.size().toLong()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(first)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val fullPixels = (opts.outWidth * opts.outHeight).toLong().coerceAtLeast(1)
        val prevPixels = (bmp.width * bmp.height).toLong().coerceAtLeast(1)
        bmp.recycle()
        (previewBytes * fullPixels / prevPixels / 1024L).coerceAtLeast(1)
      } catch (_: Exception) { null }
    }
  }

  // FIX 2026-11-18 (report #27/#47): the picked Uri is restored by
  // rememberSaveable after process death, and the Reader hands a Uri
  // in through initialPdfUri — both go through this loader.
  fun loadPdf(uri: Uri) {
    loadedPdfUri = uri
    scope.launch(Dispatchers.IO) {
      val bytes = PdfCompress.originalSizeBytes(ctx, uri)
      val pages = PdfEngine.pageCount(ctx, uri)
      val bmp = PdfReader.renderPageBitmap(ctx, uri, 0, 1.5f)
      withContext(Dispatchers.Main) {
        pdfOrigBytes = bytes
        pdfTotalPages = pages
        pdfLoadFailed = pages == 0
        pdfPreviewBmp = bmp
      }
    }
  }

  val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    showDone = false
    doneFiles = null
    pdfPreviewBmp = null
    pdfLoadFailed = false
    if (selected != null) loadPdf(selected)
  }

  // Reader hand-off (report #27): consume the incoming Uri once, on
  // the PDF tab.
  LaunchedEffect(initialPdfUri) {
    if (initialPdfUri != null && !initialConsumed) {
      initialConsumed = true
      selectedTab = 1
      pdfUri = initialPdfUri
      onInitialPdfConsumed()
    }
  }

  // Restored Uri (process death) or the Reader's handed-over Uri.
  LaunchedEffect(pdfUri) {
    val u = pdfUri
    if (u != null && loadedPdfUri != u) loadPdf(u)
  }

  // If the compressed file is not smaller, keep the smaller one: copy
  // the original over the output so the saved file is never bigger.
  // M4/L16: if the original cannot be opened at all (null stream),
  // FAIL — never report success for a missing file, and never leave
  // the larger output standing as the result.
  fun keepSmaller(originalUri: Uri, out: File): Boolean {
    return try {
      val origBytes = PdfCompress.originalSizeBytes(ctx, originalUri)
      if (origBytes > 0 && out.exists() && out.length() > origBytes) {
        val stream = ctx.contentResolver.openInputStream(originalUri) ?: return false
        try {
          stream.use { input ->
            FileOutputStream(out).use { o -> input.copyTo(o) }
          }
        } catch (_: Exception) {
          try { out.delete() } catch (_: Exception) {}
          return false
        }
        true // output was not smaller; original kept
      } else false
    } catch (_: Exception) { false }
  }

  val estimatedImageKb: Long? = if (imageUris.isEmpty()) null
    else targetKb?.toLong() ?: (imageOrigKb * quality / 100L).coerceAtLeast(1)
  val targetUnreachable = targetKb != null && minPossibleKb != null && targetKb!! < minPossibleKb!!

  fun compressImages() {
    if (imageUris.isEmpty()) return
    isImageProcessing = true
    imageResults = emptyList()
    scope.launch {
      val outs = mutableListOf<Pair<File, String>>()
      val lines = mutableListOf<String>()
      imageUris.forEachIndexed { i, u ->
        withContext(Dispatchers.Main) { imageProgressText = "File ${i + 1} of ${imageUris.size}" }
        val origKb = (PdfCompress.originalSizeBytes(ctx, u) / 1024L).coerceAtLeast(0)
        val tgt = targetKb ?: (origKb * quality / 100L).toInt().coerceAtLeast(10)
        val out = PdfEngine.outFile(ctx, "Compressed_${i + 1}_${System.currentTimeMillis()}.jpg")
        val ok = withContext(Dispatchers.IO) { PdfEngine.compressImageToKb(ctx, u, tgt, out) }
        if (ok && out.exists()) {
          // FIX 2026-11-18 (report #15): format-aware keep-smaller —
          // a PNG original that does not shrink is kept in a sibling
          // .png file with MIME image/png, never as PNG bytes in a
          // .jpg. Use the returned file/MIME, never assume .jpg.
          val kept = withContext(Dispatchers.IO) { PdfCompress.keepSmallerImage(ctx, u, out) }
          if (!kept.ok) {
            lines.add("${displayNameOf(ctx, u) ?: "Image ${i + 1}"}: could not compress")
          } else {
            val finalOut = kept.outFile
            val afterKb = finalOut.length() / 1024L
            val savedPct = if (origKb > 0) ((origKb - afterKb) * 100 / origKb) else 0
            lines.add(
              if (kept.keptOriginal || afterKb >= origKb) "${displayNameOf(ctx, u) ?: "Image ${i + 1}"}: ${origKb} KB to ${afterKb} KB — not smaller, original kept"
              else "${displayNameOf(ctx, u) ?: "Image ${i + 1}"}: ${origKb} KB to ${afterKb} KB (${savedPct}% saved)"
            )
            // FIX 2026-11-18 (report #16): record the result so
            // History can show before→after KB.
            recordCompressResult(ctx, finalOut.name, origKb, afterKb)
            outs.add(finalOut to kept.mimeType)
          }
        } else {
          lines.add("${displayNameOf(ctx, u) ?: "Image ${i + 1}"}: could not compress")
        }
      }
      isImageProcessing = false
      imageProgressText = ""
      imageResults = lines
      if (outs.isNotEmpty()) {
        doneSummary = lines
        lastImageMimes = outs.associate { it.first.name to it.second }
        folderSave(outs)
      } else {
        Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_compress_failed"), Toast.LENGTH_SHORT).show()
      }
    }
  }

  fun compressPdf() {
    val u = pdfUri ?: return
    isPdfProcessing = true
    pdfProgressText = "Compressing PDF pages..."
    scope.launch {
      val out = PdfEngine.outFile(ctx, "Compressed_${System.currentTimeMillis()}.pdf")
      val res = withContext(Dispatchers.IO) { compressPdfWithQuality(ctx, u, quality, out) }
      isPdfProcessing = false
      pdfProgressText = ""
      if (res.ok && out.exists()) {
        // FIX 2026-11-18 (report #18): the engine already keeps the
        // smaller file and reports it via res.keptOriginal — no
        // second keep-smaller pass here. res.outKb is the measured
        // result, not an estimate.
        val origKb = pdfOrigBytes / 1024L
        val finalKb = out.length() / 1024L
        val savedPct = if (origKb > 0) ((origKb - finalKb) * 100 / origKb).coerceAtLeast(0) else 0
        // FIX 2026-11-18 (report #16): record the result so History
        // can show before→after KB.
        recordCompressResult(ctx, out.name, origKb, finalKb)
        doneSummary = if (res.keptOriginal) {
          listOf("Before: ${origKb} KB — after: ${finalKb} KB.", "The compressed file was larger, so the original file was kept.")
        } else {
          listOf("Before: ${origKb} KB — after: ${finalKb} KB.", "Saved $savedPct%.")
        }
        singleSave(out)
      } else {
        Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_compress_pdf_failed"), Toast.LENGTH_SHORT).show()
      }
    }
  }

  if (showDone && doneFiles != null) {
    MultiDoneScreen(
      files = doneFiles!!,
      title = "Compressed",
      summaryLines = doneSummary,
      theme = theme,
      onBackHome = onBack,
      onOpen = { f ->
        val mime = doneFiles?.firstOrNull { it.file == f }?.mimeType
          ?: if (selectedTab == 0) "image/jpeg" else "application/pdf"
        ShareHelper.openFile(ctx, f, mime)
      },
      savedLocationText = savedLocationText,
      onOpenPdfInReader = onOpenPdfInReader
    )
    return
  }

  ToolScreenContainer("Compress KB", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      // Phase 5: shared segmented tab row (same as Protect).
      ToolTabRow(
        tabs = listOf("Compress image", "Compress PDF"),
        selectedIndex = selectedTab,
        onSelect = { selectedTab = it },
        theme = theme,
        animOn = animOn,
        onTap = { vibrate() }
      )

      Spacer(Modifier.height(Ds.s16))

      if (selectedTab == 0) {
        ChamiSecondaryButton(
          text = if (imageUris.isNotEmpty()) "${imageUris.size} image${if (imageUris.size == 1) "" else "s"} chosen" else "Select images",
          onClick = { vibrate(); imagePicker.launch("image/*") },
          modifier = Modifier.fillMaxWidth()
        )
        // Phase 5: empty state while no image is chosen yet.
        if (imageUris.isEmpty()) {
          Spacer(Modifier.height(Ds.s16))
          ToolEmptyState(
            icon = Icons.Filled.Image,
            text = LocaleHelper.t(ctx, "reason_select_images_first"),
            theme = theme
          )
        }
        if (imagePreview != null) {
          Spacer(Modifier.height(Ds.s16))
          ToolSectionHeader("Original preview", theme.text)
          ToolCard(
            theme = theme,
            padding = Ds.s12,
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Image(
              bitmap = imagePreview!!.asImageBitmap(),
              contentDescription = "Original Preview",
              modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(Ds.s8)),
              contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(Ds.s8))
            Text("Original size: ${if (imageOrigKb > 0) "$imageOrigKb KB" else "—"}", fontSize = Ds.BODY_SP.sp, color = theme.text, fontWeight = FontWeight.Bold)
            estimatedImageKb?.let {
              Text("Rough estimate: about $it KB", fontSize = Ds.HINT_SP.sp, color = theme.primaryDark)
            }
          }
        }
        Spacer(Modifier.height(Ds.s16))
        ToolSectionHeader("Target size", theme.text)
        // Compact 2x2 grid (8dp gaps) — the old single Row was ~545dp
        // wide on a ~360dp screen, so chips ran off-screen and the
        // block looked spaced out. Same options, same behaviour.
        Column(verticalArrangement = Arrangement.spacedBy(Ds.s8)) {
          listOf(
            listOf(100 to "Under 100 KB", 200 to "Under 200 KB"),
            listOf(500 to "Under 500 KB", 1024 to "Under 1 MB")
          ).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
              row.forEach { (kb, label) ->
                ChamiChip(label, targetKb == kb, { vibrate(); targetKb = kb; customInput = "" }, modifier = Modifier.weight(1f))
              }
            }
          }
        }
        Spacer(Modifier.height(Ds.s8))
        DebouncedTextField(
          value = customInput,
          onValueChange = {
            customInput = it.filter { ch -> ch.isDigit() }
            targetKb = customInput.toIntOrNull()?.coerceAtLeast(10)
          },
          label = "Custom target KB (optional)", keyboardType = KeyboardType.Number
        )
        if (targetUnreachable) {
          Spacer(Modifier.height(Ds.s8))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = ChamiColors.warningAmber, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Ds.s8))
            Text(
              "Smallest possible is about ${minPossibleKb} KB — this target cannot be reached.",
              fontSize = Ds.HINT_SP.sp, color = ChamiColors.warningAmber
            )
          }
        }
        Spacer(Modifier.height(Ds.s16))
        Text("Quality: $quality%", fontWeight = FontWeight.Bold, fontSize = Ds.BODY_SP.sp, color = theme.text)
        HintText("Used when no target size is set. Lower quality = smaller file.")
        Slider(
          value = quality.toFloat(),
          onValueChange = { quality = it.toInt() },
          valueRange = 10f..100f,
          colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary)
        )
        if (imageResults.isNotEmpty()) {
          Spacer(Modifier.height(Ds.s12))
          Column(verticalArrangement = Arrangement.spacedBy(Ds.s4)) {
            imageResults.forEach { line ->
              Text(line, fontSize = Ds.HINT_SP.sp, color = theme.text)
            }
          }
        }
        Spacer(Modifier.height(Ds.s16))
        if (isImageProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
          Spacer(Modifier.height(Ds.s8))
          Text(imageProgressText, fontSize = Ds.HINT_SP.sp, color = theme.text)
        }
      } else {
        ChamiSecondaryButton(
          text = if (pdfLoadFailed) "PDF can't be opened — pick another" else if (pdfUri != null) "PDF selected ($pdfTotalPages pages)" else "Select PDF",
          onClick = { vibrate(); pdfPicker.launch("application/pdf") },
          modifier = Modifier.fillMaxWidth()
        )
        // FIX 2026-11-18 (report #24): a protected/corrupt PDF says so
        // instead of showing "0 pages" with a disabled slide.
        if (pdfLoadFailed) {
          Spacer(Modifier.height(Ds.s8))
          Text(UNOPENABLE_PDF_MESSAGE, fontSize = Ds.BODY_SP.sp, color = ChamiColors.errorRed)
        }
        // Phase 5: empty state while no PDF is chosen yet.
        if (pdfUri == null) {
          Spacer(Modifier.height(Ds.s16))
          ToolEmptyState(
            icon = Icons.Filled.PictureAsPdf,
            text = LocaleHelper.t(ctx, "reason_pick_pdf_first"),
            theme = theme
          )
        }
        if (pdfUri != null) {
          Spacer(Modifier.height(Ds.s16))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(Ds.tileRadius))
              .background(theme.card)
              .padding(Ds.s16),
            verticalAlignment = Alignment.CenterVertically
          ) {
            if (pdfPreviewBmp != null) {
              Image(
                bitmap = pdfPreviewBmp!!.asImageBitmap(),
                contentDescription = "PDF Preview",
                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(Ds.s8)),
                contentScale = ContentScale.Fit
              )
              Spacer(Modifier.width(Ds.s12))
            } else {
              // Phase 5: grey placeholder until the first page renders.
              Box(
                Modifier
                  .size(120.dp)
                  .clip(RoundedCornerShape(Ds.s8))
                  .background(ChamiColors.skeletonBg),
                contentAlignment = Alignment.Center
              ) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = theme.textSecondary, modifier = Modifier.size(Ds.iconSize))
              }
              Spacer(Modifier.width(Ds.s12))
            }
            Column {
              val kb = pdfOrigBytes / 1024L
              Text("Original size: ${formatSizeText(pdfOrigBytes)}", fontSize = Ds.BODY_SP.sp, color = theme.text, fontWeight = FontWeight.Bold)
              Text("Total pages: $pdfTotalPages", fontSize = Ds.HINT_SP.sp, color = theme.primaryDark)
              val estKb = (kb * quality / 100L).coerceAtLeast(1)
              Text("Rough estimate: about $estKb KB", fontSize = Ds.HINT_SP.sp, color = theme.primaryDark)
            }
          }
          Spacer(Modifier.height(Ds.s16))
          Text("Quality: $quality%", fontWeight = FontWeight.Bold, fontSize = Ds.BODY_SP.sp, color = theme.text)
          HintText("Lower quality = smaller file. Pages are re-encoded as images, so text in the output becomes an image and is not selectable.")
          // FIX 2026-11-18 (report #18): warn that a text PDF can
          // come out larger — when it does, the original is kept.
          HintText("For text PDFs the result can be larger — when it is, the original file is kept instead.")
          Slider(
            value = quality.toFloat(),
            onValueChange = { quality = it.toInt() },
            valueRange = 10f..100f,
            colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary)
          )
        }
        Spacer(Modifier.height(Ds.s16))
        if (isPdfProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
          Spacer(Modifier.height(Ds.s8))
          Text(if (pdfProgressText.isNotEmpty()) pdfProgressText else "Compressing PDF pages...", fontSize = Ds.HINT_SP.sp, color = theme.text)
        }
      }
    }

    if (selectedTab == 0) {
      SlideToAction(
        label = LocaleHelper.t(ctx, "slide_compress"),
        theme = theme,
        enabled = !isImageProcessing && imageUris.isNotEmpty() && !targetUnreachable,
        disabledReason = when {
          imageUris.isEmpty() -> LocaleHelper.t(ctx, "reason_select_images_first")
          targetUnreachable -> "This target size cannot be reached"
          else -> null
        },
        onSlideComplete = { vibrate(); compressImages() }
      )
    } else {
      SlideToAction(
        label = LocaleHelper.t(ctx, "slide_compress"),
        theme = theme,
        enabled = !isPdfProcessing && pdfUri != null && !pdfLoadFailed && pdfTotalPages > 0,
        disabledReason = when {
          pdfUri == null -> LocaleHelper.t(ctx, "reason_pick_pdf_first")
          pdfLoadFailed -> UNOPENABLE_PDF_MESSAGE
          pdfTotalPages == 0 -> LocaleHelper.t(ctx, "reason_loading_pdf")
          else -> null
        },
        onSlideComplete = { vibrate(); compressPdf() }
      )
    }
  }
}

// Re-encodes an image file at the given JPEG/WebP quality (the engine's
// resizeConvert always writes quality 90, so the slider needs this).
// M18: the re-encode is written to a temp file first and only replaces
// the original on success — a failed re-encode never destroys a good
// output. The decoded bitmap is recycled on every path, including
// failure paths.
private fun reencodeAtQuality(ctx: Context, file: File, format: Bitmap.CompressFormat, quality: Int): Boolean {
  val bmp: Bitmap = try {
    BitmapFactory.decodeFile(file.absolutePath) ?: return false
  } catch (_: OutOfMemoryError) { return false } catch (_: Exception) { return false }
  val tmp = File(file.absolutePath + ".tmp")
  try {
    var ok = false
    try {
      FileOutputStream(tmp).use { ok = bmp.compress(format, quality.coerceIn(10, 100), it) }
    } catch (_: Exception) { return false }
    if (!ok) return false
    return try {
      if (tmp.renameTo(file)) true
      else {
        tmp.copyTo(file, overwrite = true)
        true
      }
    } catch (_: Exception) { false }
  } finally {
    try { bmp.recycle() } catch (_: Exception) {}
    try { if (tmp.exists()) tmp.delete() } catch (_: Exception) {}
  }
}

// L17: the source format of an image Uri, or null when it cannot be
// determined (no MIME type, no known extension). Callers must NOT
// treat an unknown format as JPG — an unknown file is never skipped
// as "already JPG" and is never labelled JPG in the UI.
private fun sourceFormatOf(ctx: Context, uri: Uri): String? {
  val mime = try { ctx.contentResolver.getType(uri) } catch (_: Exception) { null }
  val name = displayNameOf(ctx, uri) ?: ""
  return when {
    mime?.contains("png", ignoreCase = true) == true || name.endsWith(".png", ignoreCase = true) -> "PNG"
    mime?.contains("webp", ignoreCase = true) == true || name.endsWith(".webp", ignoreCase = true) -> "WEBP"
    mime?.contains("jpeg", ignoreCase = true) == true || mime?.contains("jpg", ignoreCase = true) == true ||
      name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> "JPG"
    else -> null
  }
}

// 8. IMAGE RESIZER & 10. FORMAT CONVERT — one shared screen; the title
// MUST be "Format Convert" when entered from its tile. Resizer: single
// image, presets, lock-ratio (on by default), percentage shortcuts,
// exact-KB target reported within ±5% on Done. Convert: batch,
// target JPG/PNG/WebP, files already in the target format are marked
// "Already JPG" and skipped, quality slider only for lossy targets,
// per-file progress + result list, one save folder for the batch.
@Composable
fun ResizeConvertScreen(theme: ChamiTheme, title: String = "Image Resizer", onOpenPdfInReader: ((File) -> Unit)? = null, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  val isBatch = title == "Format Convert"
  // Phase 6: motion gate for the staggered batch rows.
  val animOn = animationsEnabled(ctx)
  var imageUri by rememberSaveableUri()
  var imageUris by rememberSaveableUriList()
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var origDimensions by remember { mutableStateOf<Pair<Int, Int>?>(null) }
  var widthStr by rememberSaveable { mutableStateOf("800") }
  var heightStr by rememberSaveable { mutableStateOf("800") }
  var lockRatio by rememberSaveable { mutableStateOf(true) }
  var formatChoice by rememberSaveable { mutableStateOf("JPG") }
  // L17: true when the picked image's format could not be detected —
  // the file is never labelled JPG and never skipped as "already JPG".
  var sourceFormatUnknown by remember { mutableStateOf(false) }
  var quality by rememberSaveable { mutableStateOf(90) }
  var targetKbStr by rememberSaveable { mutableStateOf("") }
  var isProcessing by remember { mutableStateOf(false) }
  var progressText by remember { mutableStateOf("") }
  var batchResults by remember { mutableStateOf<List<String>>(emptyList()) }

  var doneFiles by remember { mutableStateOf<List<DoneFileInfo>?>(null) }
  var doneSummary by remember { mutableStateOf<List<String>>(emptyList()) }
  var showDone by remember { mutableStateOf(false) }
  var savedLocationText by remember { mutableStateOf<String?>(null) }
  val folderSave = rememberFolderSaver(
    onSaved = { files ->
      val mime = if (formatChoice == "PNG") "image/png" else if (formatChoice == "WEBP") "image/webp" else "image/jpeg"
      files.forEach { HistoryStore.record(ctx, it.name, if (isBatch) "convert" else "resize") }
      doneFiles = files.map { DoneFileInfo(it, mime, formatSizeText(it.length())) }
      showDone = true
    },
    onCancelled = { Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_save_cancelled_files"), Toast.LENGTH_SHORT).show() },
    onSavedToFolder = { uri -> savedLocationText = displayNameOf(ctx, uri) }
  )
  // FIX 2026-11-18 (Fix 4): the save picker MIME must match the image
  // being saved — rememberSingleFileSaver used to hardcode
  // "application/pdf", so a resized JPG/PNG/WEBP was offered as a PDF.
  val singleSave = rememberSingleFileSaver(
    onSavedToUri = { uri -> savedLocationText = displayNameOf(ctx, uri) },
    onSaved = { file ->
      val mime = if (formatChoice == "PNG") "image/png" else if (formatChoice == "WEBP") "image/webp" else "image/jpeg"
      HistoryStore.record(ctx, file.name, if (isBatch) "convert" else "resize")
      doneFiles = listOf(DoneFileInfo(file, mime, formatSizeText(file.length())))
      showDone = true
    },
    mimeType = if (formatChoice == "PNG") "image/png" else if (formatChoice == "WEBP") "image/webp" else "image/jpeg"
  )

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  fun applyRatioFromWidth(w: Int) {
    val (ow, oh) = origDimensions ?: return
    if (lockRatio && ow > 0) heightStr = ((w.toLong() * oh / ow).toInt().coerceAtLeast(1)).toString()
  }
  fun applyRatioFromHeight(h: Int) {
    val (ow, oh) = origDimensions ?: return
    if (lockRatio && oh > 0) widthStr = ((h.toLong() * ow / oh).toInt().coerceAtLeast(1)).toString()
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    imageUri = selected
    showDone = false
    doneFiles = null
    previewBitmap = null
    batchResults = emptyList()
    if (selected != null) {
      // L17: an undetectable format leaves the target format alone —
      // it is never silently relabelled JPG.
      val fmt = sourceFormatOf(ctx, selected)
      sourceFormatUnknown = fmt == null
      if (fmt != null) formatChoice = fmt
      scope.launch(Dispatchers.IO) {
        val bmp = PdfEngine.loadBitmap(ctx, selected, 1200)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
          ctx.contentResolver.openInputStream(selected)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {}
        withContext(Dispatchers.Main) {
          previewBitmap = bmp
          if (opts.outWidth > 0 && opts.outHeight > 0) {
            origDimensions = Pair(opts.outWidth, opts.outHeight)
            widthStr = "${opts.outWidth / 2}"
            heightStr = "${opts.outHeight / 2}"
          }
        }
      }
    }
  }

  val batchPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
    if (selected.isNotEmpty()) {
      imageUris = imageUris + selected
      showDone = false
      doneFiles = null
      batchResults = emptyList()
      val first = selected.firstOrNull()
      if (first != null && previewBitmap == null) {
        scope.launch(Dispatchers.IO) {
          val bmp = PdfEngine.loadBitmap(ctx, first, 1200)
          withContext(Dispatchers.Main) { previewBitmap = bmp }
        }
      }
    }
  }
  // FIX 2026-11-18 (report #47): a restored Uri (process death)
  // reloads its preview instead of showing an empty screen.
  LaunchedEffect(imageUri) {
    val u = imageUri
    if (u != null && previewBitmap == null) {
      scope.launch(Dispatchers.IO) {
        val bmp = PdfEngine.loadBitmap(ctx, u, 1200)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
          ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {}
        withContext(Dispatchers.Main) {
          previewBitmap = bmp
          if (opts.outWidth > 0 && opts.outHeight > 0) {
            origDimensions = Pair(opts.outWidth, opts.outHeight)
            widthStr = "${opts.outWidth / 2}"
            heightStr = "${opts.outHeight / 2}"
          }
        }
      }
    }
  }

  val widthInt = widthStr.toIntOrNull()
  val heightInt = heightStr.toIntOrNull()
  // H4: cap dimensions like the engine does (PdfEngine.resizeConvert):
  // 1..4096 px per side, 16,000,000 px total. A typo such as 10000x10000
  // is rejected here with a translated error instead of OOM-crashing.
  val dimsValid = widthInt != null && heightInt != null &&
    widthInt in 1..4096 && heightInt in 1..4096 &&
    widthInt.toLong() * heightInt.toLong() <= 16_000_000L
  val upscaleFactor: Float? = origDimensions?.let { (ow, oh) ->
    if (widthInt != null && heightInt != null && ow > 0 && oh > 0) {
      maxOf(widthInt.toFloat() / ow, heightInt.toFloat() / oh)
    } else null
  }
  val upscaleWarning = upscaleFactor != null && upscaleFactor > 2f

  fun convertSingle() {
    val u = imageUri ?: return
    if (!dimsValid) {
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_dims_range"), Toast.LENGTH_SHORT).show()
      return
    }
    val w = widthInt!!
    val h = heightInt!!
    val format = when (formatChoice) {
      "PNG" -> Bitmap.CompressFormat.PNG
      "WEBP" -> Bitmap.CompressFormat.WEBP
      else -> Bitmap.CompressFormat.JPEG
    }
    val ext = formatChoice.lowercase()
    val kbTarget = targetKbStr.toIntOrNull()
    isProcessing = true
    progressText = "Resizing..."
    scope.launch {
      val origKb = (PdfCompress.originalSizeBytes(ctx, u) / 1024L).coerceAtLeast(0)
      val tmp = PdfEngine.outFile(ctx, "Resized_${w}x${h}_${System.currentTimeMillis()}.$ext")
      var ok = withContext(Dispatchers.IO) { PdfEngine.resizeConvert(ctx, u, w, h, format, tmp) }
      var finalFile = tmp
      var kbNote: String? = null
      if (ok && formatChoice != "PNG" && quality != 90) {
        // L17: a failed re-encode fails the conversion — the
        // quality-90 file must not silently stand as the result.
        val reencoded = withContext(Dispatchers.IO) { reencodeAtQuality(ctx, tmp, format, quality) }
        if (!reencoded) ok = false
      }
      if (ok && kbTarget != null && kbTarget > 0) {
        if (formatChoice == "JPG") {
          val out2 = PdfEngine.outFile(ctx, "Resized_${kbTarget}KB_${System.currentTimeMillis()}.jpg")
          val ok2 = withContext(Dispatchers.IO) { PdfEngine.compressImageToKb(ctx, Uri.fromFile(tmp), kbTarget, out2) }
          if (ok2) {
            finalFile = out2
            val actualKb = out2.length() / 1024L
            val devPct = ((actualKb - kbTarget) * 100.0 / kbTarget)
            kbNote = if (kotlin.math.abs(devPct) <= 5.0) {
              "Exact-KB target ${kbTarget} KB — actual ${actualKb} KB (within ±5%)."
            } else {
              "Exact-KB target ${kbTarget} KB — actual ${actualKb} KB (outside ±5%, closest the engine could reach)."
            }
          }
        } else {
          withContext(Dispatchers.Main) {
            Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_exact_kb_jpg_only"), Toast.LENGTH_LONG).show()
          }
        }
      }
      isProcessing = false
      progressText = ""
      if (ok) {
        val afterKb = finalFile.length() / 1024L
        doneSummary = listOfNotNull(
          "Before: ${origDimensions?.let { "${it.first}x${it.second} px" } ?: "—"} (${origKb} KB).",
          "After: ${w}x${h} px (${afterKb} KB).",
          kbNote
        )
        singleSave(finalFile)
      } else {
        // L17: a failed resize/re-encode leaves no orphan output behind.
        try { tmp.delete() } catch (_: Exception) {}
        if (finalFile != tmp) { try { finalFile.delete() } catch (_: Exception) {} }
        Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_resize_failed"), Toast.LENGTH_SHORT).show()
      }
    }
  }

  fun convertBatch() {
    if (imageUris.isEmpty()) return
    if (!dimsValid) {
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_dims_range"), Toast.LENGTH_SHORT).show()
      return
    }
    val w = widthInt!!
    val h = heightInt!!
    val format = when (formatChoice) {
      "PNG" -> Bitmap.CompressFormat.PNG
      "WEBP" -> Bitmap.CompressFormat.WEBP
      else -> Bitmap.CompressFormat.JPEG
    }
    val ext = formatChoice.lowercase()
    isProcessing = true
    batchResults = emptyList()
    scope.launch {
      val outs = mutableListOf<File>()
      val lines = mutableListOf<String>()
      var done = 0
      var skipped = 0
      for ((idx, u) in imageUris.withIndex()) {
        withContext(Dispatchers.Main) { progressText = "File ${idx + 1} of ${imageUris.size}" }
        val srcFmt = sourceFormatOf(ctx, u)
        val name = displayNameOf(ctx, u) ?: "Image ${idx + 1}"
        if (srcFmt == formatChoice) {
          skipped++
          lines.add("$name: Already $formatChoice — skipped")
          continue
        }
        val out = PdfEngine.outFile(ctx, "Converted_${idx + 1}_${System.currentTimeMillis()}.$ext")
        val ok = withContext(Dispatchers.IO) { PdfEngine.resizeConvert(ctx, u, w, h, format, out) }
        if (ok && out.exists()) {
          // L17: a failed re-encode fails this file — the quality-90
          // file must not silently stand as the result.
          var fileOk = true
          if (formatChoice != "PNG" && quality != 90) {
            fileOk = withContext(Dispatchers.IO) { reencodeAtQuality(ctx, out, format, quality) }
          }
          if (fileOk) {
            outs.add(out)
            done++
            lines.add("$name: converted to $formatChoice (${formatSizeText(out.length())})")
          } else {
            try { out.delete() } catch (_: Exception) {}
            lines.add("$name: could not convert")
          }
        } else {
          lines.add("$name: could not convert")
        }
        withContext(Dispatchers.Main) { batchResults = lines.toList() }
      }
      isProcessing = false
      progressText = ""
      batchResults = lines
      if (outs.isNotEmpty()) {
        doneSummary = lines + listOf("$done converted, $skipped already $formatChoice and skipped.")
        folderSave(outs.map { it to if (formatChoice == "PNG") "image/png" else if (formatChoice == "WEBP") "image/webp" else "image/jpeg" })
      } else {
        Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_nothing_to_convert").replace("{format}", formatChoice), Toast.LENGTH_LONG).show()
      }
    }
  }

  if (showDone && doneFiles != null) {
    MultiDoneScreen(
      files = doneFiles!!,
      title = if (isBatch) "Converted" else "Resized",
      summaryLines = doneSummary,
      theme = theme,
      onBackHome = onBack,
      onOpen = {
        ShareHelper.openFile(ctx, it, if (formatChoice == "PNG") "image/png" else if (formatChoice == "WEBP") "image/webp" else "image/jpeg")
      },
      savedLocationText = savedLocationText,
      onOpenPdfInReader = onOpenPdfInReader
    )
    return
  }

  ToolScreenContainer(title, theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      if (isBatch) {
        ChamiSecondaryButton(
          text = if (imageUris.isNotEmpty()) "${imageUris.size} images chosen" else "Select images",
          onClick = { vibrate(); batchPicker.launch("image/*") },
          modifier = Modifier.fillMaxWidth()
        )
        if (imageUris.isNotEmpty()) {
          Spacer(Modifier.height(Ds.s12))
          // Phase 6: the picked files live in the shared card, one
          // staggered fade-up row each, instead of bare rows.
          ToolCard(theme) {
            Column(verticalArrangement = Arrangement.spacedBy(Ds.s8)) {
              imageUris.forEachIndexed { i, uri ->
                val srcFmt = sourceFormatOf(ctx, uri)
                // L17: an unknown format is never treated as "already" the
                // target — it is converted, not skipped.
                val already = srcFmt == formatChoice
                Row(
                  Modifier.fillMaxWidth().toolEnterFade(i, animOn),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    "${i + 1}. ${displayNameOf(ctx, uri) ?: "image"} (${srcFmt ?: LocaleHelper.t(ctx, "label_unknown_format")})",
                    fontSize = Ds.BODY_SP.sp, color = theme.text,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                  )
                  if (already) {
                    Text(
                      "Already $formatChoice",
                      fontSize = Ds.HINT_SP.sp, color = theme.primaryDark, fontWeight = FontWeight.SemiBold
                    )
                  }
                  ChamiIconButton(
                    icon = Icons.Filled.Close, description = "Remove",
                    onClick = { vibrate(); imageUris = imageUris.filterIndexed { idx, _ -> idx != i } }
                  )
                }
              }
            }
          }
          if (isProcessing || batchResults.isNotEmpty()) {
            Spacer(Modifier.height(Ds.s12))
            // Phase 6: batch progress / results in the shared card.
            ToolCard(theme) {
              Text(progressText.ifBlank { "Converted ${batchResults.size} files" }, fontSize = Ds.HINT_SP.sp, color = theme.primaryDark, fontWeight = FontWeight.Bold)
              if (batchResults.isNotEmpty()) {
                Spacer(Modifier.height(Ds.s8))
                Column(verticalArrangement = Arrangement.spacedBy(Ds.s4)) {
                  batchResults.forEach { line ->
                    Text(line, fontSize = Ds.HINT_SP.sp, color = theme.text)
                  }
                }
              }
            }
          }
        } else {
          Spacer(Modifier.height(Ds.s16))
          ToolEmptyState(Icons.Filled.Image, LocaleHelper.t(ctx, "reason_select_images_first"), theme)
        }
      } else {
        ChamiSecondaryButton(
          text = if (imageUri != null) "Image loaded" else "Select image",
          onClick = { vibrate(); picker.launch("image/*") },
          modifier = Modifier.fillMaxWidth()
        )
        if (imageUri == null) {
          Spacer(Modifier.height(Ds.s16))
          ToolEmptyState(Icons.Filled.Image, LocaleHelper.t(ctx, "reason_select_image_first"), theme)
        }
      }

      if (previewBitmap != null) {
        // Phase 6: shared section header + card, like the Phase 5 screens.
        ToolSectionHeader("Preview", theme.text)
        ToolCard(theme, padding = Ds.s12, horizontalAlignment = Alignment.CenterHorizontally) {
          Image(
            bitmap = previewBitmap!!.asImageBitmap(),
            contentDescription = "Preview",
            modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(Ds.tileRadius)),
            contentScale = ContentScale.Fit
          )
          Spacer(Modifier.height(Ds.s8))
          origDimensions?.let { (w, h) ->
            Text("Original: ${w}x$h px", fontSize = Ds.HINT_SP.sp, color = theme.text)
          }
          Text("Target: ${widthStr}x${heightStr} px ($formatChoice)", fontSize = Ds.HINT_SP.sp, color = theme.primaryDark, fontWeight = FontWeight.Bold)
        }
      } else if (imageUri != null || imageUris.isNotEmpty()) {
        // Phase 6: skeleton while the picked image decodes, so the
        // screen never sits blank under the select button.
        ToolSectionHeader("Preview", theme.text)
        Phase6SkeletonBox(320.dp)
      }

      // Phase 6: shared section header, like the Phase 5 screens.
      ToolSectionHeader("Presets", theme.text)
      // Compact 2-row grid (8dp gaps) — the old single Row was ~600dp
      // wide on a ~360dp screen, so presets ran off-screen. Same
      // presets, same behaviour. (Same screen as Image Resizer.)
      Column(verticalArrangement = Arrangement.spacedBy(Ds.s8)) {
        listOf(
          listOf("WhatsApp" to ("1080" to "1080"), "Instagram post" to ("1080" to "1350"), "Instagram story" to ("1080" to "1920")),
          listOf("Passport" to ("413" to "531"), "A4" to ("1240" to "1754"))
        ).forEach { row ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
            row.forEach { (label, wh) ->
              val isSel = widthStr == wh.first && heightStr == wh.second
              ChamiChip(label, isSel, {
                vibrate()
                widthStr = wh.first
                heightStr = wh.second
              }, modifier = Modifier.weight(1f))
            }
            // Keep both rows the same width: pad the short row.
            if (row.size < 3) Spacer(Modifier.weight(1f))
          }
        }
      }

      Spacer(Modifier.height(Ds.s12))
      // Phase 6: size controls in the shared card — lock row, the two
      // dimension fields (H4 range error stays inline under them),
      // scale chips and the upscale warning together.
      ToolCard(theme) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
          Text("Lock aspect ratio", fontSize = Ds.BODY_SP.sp, color = theme.text)
          Switch(checked = lockRatio, onCheckedChange = { lockRatio = it })
        }
        Spacer(Modifier.height(Ds.s12))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
          DebouncedTextField(
            value = widthStr,
            onValueChange = {
              widthStr = it.filter { ch -> ch.isDigit() }
              widthStr.toIntOrNull()?.let { w -> applyRatioFromWidth(w) }
            },
            label = "Width (px)", keyboardType = KeyboardType.Number,
            isError = !dimsValid, modifier = Modifier.weight(1f)
          )
          DebouncedTextField(
            value = heightStr,
            onValueChange = {
              heightStr = it.filter { ch -> ch.isDigit() }
              heightStr.toIntOrNull()?.let { h -> applyRatioFromHeight(h) }
            },
            label = "Height (px)", keyboardType = KeyboardType.Number,
            isError = !dimsValid, modifier = Modifier.weight(1f)
          )
        }
        if (!dimsValid) {
          Spacer(Modifier.height(Ds.s4))
          Text(LocaleHelper.t(ctx, "toast_dims_range"), fontSize = Ds.HINT_SP.sp, color = ChamiColors.errorRed)
        }
        Spacer(Modifier.height(Ds.s12))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
          listOf("25%" to 0.25f, "50%" to 0.5f, "75%" to 0.75f, "Original" to 1f).forEach { (label, pct) ->
            ChamiChip(label, false, {
              vibrate()
              origDimensions?.let { (ow, oh) ->
                widthStr = (ow * pct).toInt().coerceAtLeast(1).toString()
                heightStr = (oh * pct).toInt().coerceAtLeast(1).toString()
              }
            }, modifier = Modifier.weight(1f))
          }
        }
        if (upscaleWarning) {
          Spacer(Modifier.height(Ds.s12))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = ChamiColors.warningAmber, modifier = Modifier.size(Ds.iconSize))
            Spacer(Modifier.width(Ds.s8))
            Text(
              "Upscaling more than 2x will look blurry — the output is stretched, not sharper.",
              fontSize = Ds.HINT_SP.sp, color = ChamiColors.warningAmber
            )
          }
        }
      }

      // Phase 6: shared section header, like the Phase 5 screens.
      ToolSectionHeader("Format", theme.text)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
        listOf("JPG", "PNG", "WEBP").forEach { fmt ->
          ChamiChip(fmt, formatChoice == fmt, { vibrate(); formatChoice = fmt }, modifier = Modifier.weight(1f))
        }
      }
      if (sourceFormatUnknown) {
        Spacer(Modifier.height(Ds.s8))
        HintText(LocaleHelper.t(ctx, "resize_unknown_format").replace("{format}", formatChoice))
      }
      if (formatChoice != "PNG") {
        Spacer(Modifier.height(Ds.s12))
        // Phase 6: quality slider in the shared card.
        ToolCard(theme) {
          Text("Quality: $quality%", fontWeight = FontWeight.Bold, fontSize = Ds.BODY_SP.sp, color = theme.text)
          Slider(
            value = quality.toFloat(),
            onValueChange = { quality = it.toInt() },
            valueRange = 10f..100f,
            colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary)
          )
        }
      } else {
        Spacer(Modifier.height(Ds.s8))
        HintText("PNG is lossless — no quality slider.")
      }

      if (!isBatch) {
        Spacer(Modifier.height(Ds.s12))
        // Phase 6: exact-KB target in the shared card.
        ToolCard(theme) {
          DebouncedTextField(
            value = targetKbStr,
            onValueChange = { targetKbStr = it.filter { ch -> ch.isDigit() } },
            label = "Resize to exact KB (JPG only, optional)", keyboardType = KeyboardType.Number
          )
          Spacer(Modifier.height(Ds.s8))
          HintText("When set, quality is adjusted after resizing to land within ±5% of the target.")
        }
      }

      Spacer(Modifier.height(Ds.s16))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(Ds.s8))
        Text(progressText, fontSize = Ds.HINT_SP.sp, color = theme.text)
      }
    }

    if (isBatch) {
      SlideToAction(
        label = LocaleHelper.t(ctx, "slide_convert_all"),
        theme = theme,
        enabled = !isProcessing && imageUris.isNotEmpty() && dimsValid,
        disabledReason = when {
          imageUris.isEmpty() -> LocaleHelper.t(ctx, "reason_select_images_first")
          !dimsValid -> LocaleHelper.t(ctx, "toast_dims_range")
          else -> null
        },
        onSlideComplete = { vibrate(); convertBatch() }
      )
    } else {
      SlideToAction(
        label = LocaleHelper.t(ctx, "slide_resize"),
        theme = theme,
        enabled = !isProcessing && imageUri != null && dimsValid,
        disabledReason = when {
          imageUri == null -> LocaleHelper.t(ctx, "reason_select_image_first")
          !dimsValid -> LocaleHelper.t(ctx, "toast_dims_range")
          else -> null
        },
        onSlideComplete = { vibrate(); convertSingle() }
      )
    }
  }
}

// 9. OCR TEXT — FIX 2026-11-18 (Fix 5): NO language selection any
// more. The old English/Hindi/Spanish chips were fake options — the
// screen only ever ran the Latin recogniser. OCR now runs EVERY
// on-device recogniser (Latin + Devanagari, see
// OcrHelper.ocrImageAnyLanguage) and reads whatever text is on the
// image, in any supported language, with no picking. Result in a
// selectable editable text area with Copy + word count, Export TXT
// and Export searchable PDF as two explicit choices. Empty result
// shows "No text found" with a Retake action and says plainly which
// scripts are supported on-device, never a fake placeholder.
@Composable
fun OcrScreen(theme: ChamiTheme, onOpenPdfInReader: ((File) -> Unit)? = null, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  // Phase 6: motion gate for the result card's enter fade.
  val animOn = animationsEnabled(ctx)
  var imageUri by rememberSaveableUri()
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var extractedText by remember { mutableStateOf("") }
  var hasResult by remember { mutableStateOf(false) }
  var noTextFound by remember { mutableStateOf(false) }
  // M23: export runs on Dispatchers.IO; this flag shows progress and
  // blocks a second export while one is running.
  var isExporting by remember { mutableStateOf(false) }
  var exportProgressText by remember { mutableStateOf("") }

  var doneFile by remember { mutableStateOf<File?>(null) }
  var doneMime by remember { mutableStateOf("text/plain") }
  var showDone by remember { mutableStateOf(false) }
  var savedLocationText by remember { mutableStateOf<String?>(null) }
  val singleSaveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
    val file = doneFile
    if (uri != null && file != null) {
      scope.launch {
        val ok = withContext(Dispatchers.IO) { SaveLocation.copyToUri(ctx, file, uri) }
        if (ok) {
          savedLocationText = displayNameOf(ctx, uri)
          HistoryStore.record(ctx, file.name, "ocr")
          doneMime = "text/plain"; showDone = true
        }
        else Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_save_failed_file"), Toast.LENGTH_SHORT).show()
      }
    } else if (uri == null && file != null) {
      // FIX 2026-11-18 (report #33): same "kept in Chami folder"
      // toast the other tools show when the save picker is cancelled.
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_save_cancelled_file"), Toast.LENGTH_SHORT).show()
    }
  }
  val singleSavePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    val file = doneFile
    if (uri != null && file != null) {
      scope.launch {
        val ok = withContext(Dispatchers.IO) { SaveLocation.copyToUri(ctx, file, uri) }
        if (ok) {
          savedLocationText = displayNameOf(ctx, uri)
          HistoryStore.record(ctx, file.name, "ocr")
          doneMime = "application/pdf"; showDone = true
        }
        else Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_save_failed_file"), Toast.LENGTH_SHORT).show()
      }
    } else if (uri == null && file != null) {
      // FIX 2026-11-18 (report #33): same "kept in Chami folder"
      // toast the other tools show when the save picker is cancelled.
      Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_save_cancelled_file"), Toast.LENGTH_SHORT).show()
    }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    imageUri = selected
    extractedText = ""
    hasResult = false
    noTextFound = false
    showDone = false
    doneFile = null
    previewBitmap = null
    if (selected != null) {
      scope.launch(Dispatchers.IO) {
        val bmp = PdfEngine.loadBitmap(ctx, selected, 1200)
        withContext(Dispatchers.Main) { previewBitmap = bmp }
      }
    }
  }

  // FIX 2026-11-18 (report #47): a restored Uri (process death)
  // reloads its preview instead of showing an empty screen.
  LaunchedEffect(imageUri) {
    val u = imageUri
    if (u != null && previewBitmap == null) {
      scope.launch(Dispatchers.IO) {
        val bmp = PdfEngine.loadBitmap(ctx, u, 1200)
        withContext(Dispatchers.Main) { previewBitmap = bmp }
      }
    }
  }

  fun runOcr() {
    val u = imageUri ?: return
    isProcessing = true
    scope.launch {
      // No language picking: ocrImageAnyLanguage runs every on-device
      // recogniser (Latin + Devanagari) and reads whatever is there.
      val txt = withContext(Dispatchers.IO) { ocrImageAnyLanguage(ctx, u) }
      isProcessing = false
      if (txt.isBlank()) {
        extractedText = ""
        hasResult = false
        noTextFound = true
      } else {
        extractedText = txt
        hasResult = true
        noTextFound = false
      }
    }
  }

  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, doneMime) },
      savedLocationText = savedLocationText,
      onOpenPdfInReader = onOpenPdfInReader
    )
    return
  }

  ToolScreenContainer("OCR Text", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      ChamiSecondaryButton(
        text = if (imageUri != null) "Photo selected" else "Select photo",
        onClick = { vibrate(); picker.launch("image/*") },
        modifier = Modifier.fillMaxWidth()
      )

      Spacer(Modifier.height(Ds.s16))
      HintText("Reads any text on the image — Latin-script languages (English, Spanish, French, German…) and Devanagari (Hindi, Marathi, Nepali). Other scripts aren't recognised on-device.")

      // Phase 6: clear empty state until a photo is picked.
      if (imageUri == null) {
        Spacer(Modifier.height(Ds.s16))
        ToolEmptyState(Icons.Filled.Image, LocaleHelper.t(ctx, "reason_select_photo_first"), theme)
      }

      if (previewBitmap != null) {
        // Phase 6: shared section header + card, like the Phase 5 screens.
        ToolSectionHeader("Preview", theme.text)
        ToolCard(theme, padding = Ds.s12) {
          Image(
            bitmap = previewBitmap!!.asImageBitmap(),
            contentDescription = "OCR Preview",
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 384.dp)
              .clip(RoundedCornerShape(Ds.tileRadius)),
            contentScale = ContentScale.Fit
          )
        }
      } else if (imageUri != null) {
        // Phase 6: skeleton while the picked photo decodes.
        ToolSectionHeader("Preview", theme.text)
        Phase6SkeletonBox(240.dp)
      }

      Spacer(Modifier.height(Ds.s16))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(Ds.s8))
        Text("Running on-device OCR...", fontSize = Ds.HINT_SP.sp, color = theme.text)
        // Phase 6: skeleton reserves the result area while OCR runs.
        Spacer(Modifier.height(Ds.s12))
        Phase6SkeletonBox(200.dp)
      }

      if (noTextFound) {
        // Phase 6: shared card + enter fade, instead of the bare 8dp box.
        ToolCard(theme, modifier = Modifier.toolEnterFade(0, animOn), horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(Icons.Filled.Search, contentDescription = null, tint = theme.textSecondary, modifier = Modifier.size(Ds.iconSize))
          Spacer(Modifier.height(Ds.s8))
          Text("No text found", fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold, color = theme.text)
          Spacer(Modifier.height(Ds.s4))
          HintText("Try a clearer, brighter photo of the text. OCR reads Latin-script and Devanagari text on-device — other scripts aren't supported.")
          Spacer(Modifier.height(Ds.s12))
          ChamiSecondaryButton(text = "Retake / choose another photo", onClick = { vibrate(); picker.launch("image/*") }, modifier = Modifier.fillMaxWidth())
        }
      }

      if (hasResult) {
        val wordCount = extractedText.split(Regex("\\s+")).count { it.isNotBlank() }
        // Phase 6: shared card + enter fade, instead of the bare 8dp box.
        ToolCard(theme, modifier = Modifier.toolEnterFade(0, animOn)) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Recognised text", fontWeight = FontWeight.Bold, color = theme.text, fontSize = Ds.BODY_SP.sp)
            Text("$wordCount words", fontSize = Ds.HINT_SP.sp, color = theme.textSecondary)
          }
          Spacer(Modifier.height(Ds.s8))
          OutlinedTextField(
            value = extractedText,
            onValueChange = { extractedText = it },
            modifier = Modifier.fillMaxWidth().height(200.dp),
            shape = RoundedCornerShape(Ds.tileRadius)
          )
          Spacer(Modifier.height(Ds.s12))
          ChamiSecondaryButton(
            text = "Copy text",
            onClick = {
              vibrate()
              val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              cm.setPrimaryClip(ClipData.newPlainText("OCR Text", extractedText))
              Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_copied"), Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
          )
          Spacer(Modifier.height(Ds.s12))
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
            // M23: exports run on Dispatchers.IO — file IO and PDF
            // generation never block the main thread (long OCR text
            // used to freeze the UI / risk an ANR from onClick).
            ChamiPrimaryButton(
              text = "Export TXT",
              onClick = {
                vibrate()
                if (isExporting) return@ChamiPrimaryButton
                isExporting = true
                exportProgressText = LocaleHelper.t(ctx, "ocr_exporting_txt")
                scope.launch {
                  val f = withContext(Dispatchers.IO) {
                    try { saveTextToFile(ctx, extractedText, "OCR_${System.currentTimeMillis()}.txt") }
                    catch (_: Exception) { null }
                  }
                  isExporting = false
                  exportProgressText = ""
                  if (f != null) {
                    doneFile = f
                    singleSaveTxt.launch(f.name)
                  } else {
                    Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_ocr_export_failed"), Toast.LENGTH_SHORT).show()
                  }
                }
              },
              modifier = Modifier.weight(1f)
            )
            ChamiPrimaryButton(
              text = "Export searchable PDF",
              onClick = {
                vibrate()
                if (isExporting) return@ChamiPrimaryButton
                isExporting = true
                exportProgressText = LocaleHelper.t(ctx, "ocr_exporting_pdf")
                val textSnapshot = extractedText
                scope.launch {
                  val out = PdfEngine.outFile(ctx, "OCR_${System.currentTimeMillis()}.pdf")
                  val ok = withContext(Dispatchers.IO) {
                    try { textToPdf(ctx, "OCR Text", textSnapshot, outFile = out) }
                    catch (_: Exception) { false }
                  }
                  isExporting = false
                  exportProgressText = ""
                  if (ok) {
                    doneFile = out
                    singleSavePdf.launch(out.name)
                  } else {
                    try { out.delete() } catch (_: Exception) {}
                    Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_create_pdf_failed"), Toast.LENGTH_SHORT).show()
                  }
                }
              },
              modifier = Modifier.weight(1f)
            )
          }
          if (isExporting) {
            Spacer(Modifier.height(Ds.s8))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
            Spacer(Modifier.height(Ds.s4))
            Text(exportProgressText, fontSize = Ds.HINT_SP.sp, color = theme.text)
          }
          Spacer(Modifier.height(Ds.s8))
          HintText("The PDF holds the recognised text as selectable text (no image layer in this build).")
        }
      }
    }

    SlideToAction(
      label = LocaleHelper.t(ctx, "slide_extract_text"),
      theme = theme,
      enabled = !isProcessing && imageUri != null,
      disabledReason = if (imageUri == null) LocaleHelper.t(ctx, "reason_select_photo_first") else null,
      onSlideComplete = { vibrate(); runOcr() }
    )
  }
}


// 10. PDF READER — lazy per-page search with progress + cancel (one
// page is rendered and OCR'd at a time, never the whole document up
// front), bookmarks PERSISTED per file in SharedPreferences, page
// indicator "3 / 24" with tap-to-go-to-page, pinch zoom 1x-5x that
// re-renders sharper at the zoomed scale, overflow menu to open the
// file in Split / Merge / Compress. Keeps its custom header area but
// uses Ds typography/spacing throughout.
@Composable
fun PdfReaderScreen(
  theme: ChamiTheme,
  initialFile: File? = null,
  onOpenTool: ((String, Uri?) -> Unit)? = null,
  onBack: () -> Unit
) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pdfUri by rememberSaveableUri(initialFile?.let { Uri.fromFile(it) })
  var totalPages by remember { mutableStateOf(0) }
  var currentPage by remember { mutableStateOf(0) }
  // Render scale: 2.0x at fit; raised (debounced) while zoomed so the
  // zoomed page re-renders sharper instead of upscaling a bitmap.
  var scale by remember { mutableStateOf(2.0f) }
  var isNightMode by rememberSaveable { mutableStateOf(false) }
  var pageInput by remember { mutableStateOf("1") }
  var zoom by remember { mutableStateOf(1f) }
  // FIX 2026-11-18 (report #23): byte-budgeted cache (see
  // PageBitmapCache below) instead of a 12-page count cap.
  val pageBitmapCache = remember { PageBitmapCache() }
  // Continuous scroll (Google Drive style): the reader is one vertical
  // list of pages; the current page follows the first visible page.
  val listState = rememberLazyListState()
  var showSearchBar by remember { mutableStateOf(false) }
  var showBookmarksDialog by remember { mutableStateOf(false) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var searchPages by remember { mutableStateOf<List<Int>>(emptyList()) }
  var searchIndex by remember { mutableStateOf(0) }
  var searching by remember { mutableStateOf(false) }
  var searchProgress by remember { mutableStateOf("") }
  var searchJob by remember { mutableStateOf<Job?>(null) }
  // M17: bookmarks survive rotation — rememberSaveable with a list
  // saver (a plain remember list was lost on rotation, and the loader
  // LaunchedEffect does not re-fire for the same Uri).
  val bookmarks = rememberSaveable(
    saver = listSaver(
      save = { it.toList() },
      restore = { saved -> mutableStateListOf<Int>().apply { addAll(saved.filterIsInstance<Int>()) } }
    )
  ) { mutableStateListOf<Int>() }
  var showGoToPage by remember { mutableStateOf(false) }
  var goToInput by rememberSaveable { mutableStateOf("") }
  var showOverflow by remember { mutableStateOf(false) }
  // FIX 2026-11-18 (report #24): a PDF that cannot be opened.
  var pdfLoadFailed by remember { mutableStateOf(false) }
  // Same-PDF re-pick (report #24): the picker resets totalPages to 0
  // for the same Uri, which would not re-fire LaunchedEffect(pdfUri)
  // — this key forces the reload.
  var pdfReloadKey by remember { mutableStateOf(0) }
  // FIX 2026-11-18 (report #25): recognised page text, cached in
  // memory so repeat searches never re-OCR a page.
  // L19: capped at 100 pages (oldest evicted first) — the cache used
  // to grow one entry per searched page, unbounded, for the session.
  val searchTextCache = remember { mutableStateMapOf<Int, String>() }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  // FIX 2026-11-18 (report #27): the Uri handed to Split / Merge /
  // Compress. The picker's own content Uri is passed as-is; a file
  // opened from History/Done (a java.io.File) goes through
  // FileProvider so the target tool gets a content Uri it can read.
  fun readerContentUri(): Uri? {
    val u = pdfUri ?: return null
    val f = initialFile
    return if (f != null && u == Uri.fromFile(f)) {
      try {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
      } catch (_: Exception) {
        u
      }
    } else u
  }

  fun jumpToPage(page1Based: Int) {
    if (page1Based in 1..totalPages) {
      currentPage = page1Based - 1
      pageInput = "$page1Based"
      scope.launch {
        try { listState.scrollToItem(page1Based - 1) } catch (_: Exception) {}
      }
    }
  }

  fun loadBookmarks() {
    val u = pdfUri ?: return
    bookmarks.clear()
    bookmarks.addAll(getBookmarks(ctx, u))
  }

  fun toggleBookmark(page1Based: Int) {
    val u = pdfUri ?: return
    if (bookmarks.contains(page1Based)) {
      bookmarks.remove(page1Based)
      removeBookmark(ctx, u, page1Based)
    } else {
      bookmarks.add(page1Based)
      saveBookmark(ctx, u, page1Based)
    }
  }

  // Lazy search: render + OCR one page at a time, with progress and a
  // cancel button. The job is cancelled between pages, so cancelling
  // stops promptly instead of finishing the whole document.
  // FIX 2026-11-18 (report #25): OCR runs ocrBitmapAnyLanguage
  // (Latin + Devanagari, script-scored) straight on the rendered
  // bitmap — no search_page_*.jpg temp files are written at all, so
  // there is nothing to delete — and recognised text is cached in
  // memory, so a repeat search never re-OCRs a page.
  // FIX 2026-11-18 (report #26): "no matches" is only reported when
  // OCR actually read text on some page; when no page yielded any
  // text at all, the model is probably not downloaded yet.
  fun runSearch() {
    val q = searchQuery.trim()
    val u = pdfUri
    if (q.isEmpty() || u == null) return
    searchJob?.cancel()
    searching = true
    searchPages = emptyList()
    searchIndex = 0
    searchJob = scope.launch {
      val hits = mutableListOf<Int>()
      var anyTextFound = false
      var renderer: android.graphics.pdf.PdfRenderer? = null
      var pfd: android.os.ParcelFileDescriptor? = null
      try {
        // M7: an encrypted PDF has no searchable text — say so plainly
        // instead of silently returning empty results.
        val encrypted = withContext(Dispatchers.IO) {
          try { PdfProtector.isEncrypted(ctx, u) } catch (_: Exception) { false }
        }
        if (encrypted) {
          withContext(Dispatchers.Main) {
            searching = false
            Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_reader_encrypted"), Toast.LENGTH_LONG).show()
          }
          return@launch
        }
        pfd = withContext(Dispatchers.IO) { ctx.contentResolver.openFileDescriptor(u, "r") }
        if (pfd == null) {
          withContext(Dispatchers.Main) { searching = false }
          return@launch
        }
        renderer = android.graphics.pdf.PdfRenderer(pfd)
        val count = renderer.pageCount
        for (i in 0 until count) {
          if (searchJob?.isActive == false) break
          withContext(Dispatchers.Main) { searchProgress = "Searching page ${i + 1} of $count" }
          val cached = searchTextCache[i]
          val text = cached ?: withContext(Dispatchers.IO) {
            try {
              val page = renderer!!.openPage(i)
              val w = page.width; val h = page.height
              val maxSide = maxOf(w, h)
              val s = if (maxSide > 1200) 1200f / maxSide else 1.5f
              val bmp = Bitmap.createBitmap((w * s).toInt(), (h * s).toInt(), Bitmap.Config.ARGB_8888)
              bmp.eraseColor(AndroidColor.WHITE)
              page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              page.close()
              val t = ocrBitmapAnyLanguage(bmp)
              bmp.recycle()
              t
            } catch (_: OutOfMemoryError) { "" }
            catch (_: Exception) { "" }
          }
          if (cached == null) {
            // L19: cap the cache — evict the oldest entry first.
            if (searchTextCache.size >= 100) {
              searchTextCache.keys.firstOrNull()?.let { searchTextCache.remove(it) }
            }
            searchTextCache[i] = text
          }
          if (text.isNotBlank()) anyTextFound = true
          if (text.contains(q, ignoreCase = true)) hits.add(i + 1)
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        // M16: a cancelled search shows no results and no "No matches"
        // toast — clear the partial state and rethrow so the tail code
        // below never runs.
        searching = false
        searchPages = emptyList()
        throw e
      } catch (_: Exception) {
      } finally {
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
      }
      searching = false
      searchPages = hits
      searchIndex = 0
      if (hits.isNotEmpty()) jumpToPage(hits[0])
      else if (!anyTextFound) Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_search_unavailable"), Toast.LENGTH_LONG).show()
      else Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_search_no_matches").replace("{q}", q), Toast.LENGTH_SHORT).show()
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    if (selected != null) {
      pageBitmapCache.clear()
      totalPages = 0
      pdfLoadFailed = false
      pdfReloadKey++
      searchTextCache.clear()
      currentPage = 0
      pageInput = "1"
      searchPages = emptyList()
      bookmarks.clear()
      scope.launch { try { listState.scrollToItem(0) } catch (_: Exception) {} }
    }
  }

  LaunchedEffect(pdfUri, pdfReloadKey) {
    val u = pdfUri ?: return@LaunchedEffect
    if (totalPages == 0) {
      totalPages = withContext(Dispatchers.IO) { PdfEngine.pageCount(ctx, u) }
      // FIX 2026-11-18 (report #24): a 0-page count means the PDF
      // cannot be opened — show the error state, never a spinner.
      pdfLoadFailed = totalPages == 0
    }
    loadBookmarks()
  }

  // The current page follows the first visible page in the scroll.
  val firstVisiblePage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
  LaunchedEffect(firstVisiblePage) {
    if (totalPages > 0) {
      currentPage = firstVisiblePage.coerceIn(0, totalPages - 1)
      pageInput = "${currentPage + 1}"
    }
  }

  // Re-render sharper after the pinch ends (debounced): render scale
  // follows the display zoom, capped at 3x — FIX 2026-11-18 (report
  // #23): at 2.0x * 5x zoom one A4 page is ~50 MB, and 12 such pages
  // OOM a 4 GB phone. The byte-budgeted cache is the other half.
  LaunchedEffect(zoom) {
    delay(300)
    scale = (2.0f * zoom).coerceIn(2.0f, 3.0f)
  }

  androidx.compose.runtime.DisposableEffect(Unit) {
    onDispose {
      pageBitmapCache.clear()
    }
  }

  if (showGoToPage) {
    ChamiAlertDialog(theme,
      onDismissRequest = { showGoToPage = false },
      title = { Text("Go to page", fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold, color = theme.text) },
      text = {
        OutlinedTextField(
          value = goToInput,
          onValueChange = { goToInput = it.filter { ch -> ch.isDigit() } },
          label = { Text("Page 1–$totalPages", fontSize = Ds.HINT_SP.sp) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(Ds.tileRadius),
          isError = goToInput.toIntOrNull()?.let { it !in 1..totalPages } == true
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            goToInput.toIntOrNull()?.let { jumpToPage(it) }
            showGoToPage = false
          },
          modifier = Modifier.height(Ds.minTouch)
        ) { Text("Go", color = theme.primary, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold) }
      },
      dismissButton = {
        TextButton(onClick = { showGoToPage = false }, modifier = Modifier.height(Ds.minTouch)) { Text("Cancel", color = theme.text) }
      }
    )
  }

  // Bookmarks dialog (opened from the three-dot menu): tap a page to
  // jump to it, X to delete the bookmark.
  if (showBookmarksDialog) {
    ChamiAlertDialog(theme,
      onDismissRequest = { showBookmarksDialog = false },
      title = { Text("Bookmarks", fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold, color = theme.text) },
      text = {
        if (bookmarks.isEmpty()) {
          Text("No bookmarks yet — open the ⋮ menu on any page and tap \"Bookmark this page\".", fontSize = Ds.BODY_SP.sp, color = theme.textSecondary)
        } else {
          Column {
            bookmarks.sorted().forEach { b ->
              Row(
                Modifier.fillMaxWidth().heightIn(min = Ds.minTouch).clickable { showBookmarksDialog = false; jumpToPage(b) }.padding(vertical = Ds.s8),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text("Page $b", fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.Bold, color = theme.text, modifier = Modifier.weight(1f))
                ChamiIconButton(
                  icon = Icons.Filled.Close, description = "Delete bookmark",
                  onClick = { vibrate(); bookmarks.remove(b); pdfUri?.let { removeBookmark(ctx, it, b) } }
                )
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showBookmarksDialog = false }, modifier = Modifier.height(Ds.minTouch)) { Text("Close", color = theme.primary, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold) }
      }
    )
  }

  ToolScreenContainer("PDF Reader", theme, onBack) {
    if (pdfUri == null) {
      Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        // Phase 6: shared empty state, then the open button.
        ToolEmptyState(Icons.Filled.PictureAsPdf, LocaleHelper.t(ctx, "btn_select_pdf"), theme)
        Spacer(Modifier.height(Ds.s16))
        ChamiPrimaryButton(text = "Open PDF file", onClick = { vibrate(); picker.launch("application/pdf") }, modifier = Modifier.fillMaxWidth())
      }
    } else {
      Column(Modifier.fillMaxSize()) {
        // Phase 6: slim top bar (Google Drive style) in the shared
        // card — page indicator + the three-dot menu only.
        ToolCard(theme, padding = Ds.s8) {
          Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
          TextButton(
            onClick = { goToInput = "${currentPage + 1}"; showGoToPage = true },
            modifier = Modifier.height(Ds.minTouch)
          ) {
            Text(
              "${currentPage + 1} / $totalPages",
              fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.Bold, color = theme.text
            )
          }
          Box {
            ChamiIconButton(
              icon = Icons.Filled.MoreVert, description = "Reader options",
              onClick = { vibrate(); showOverflow = true }
            )
            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
              DropdownMenuItem(
                text = { Text("Search in PDF", fontSize = Ds.BODY_SP.sp) },
                onClick = { showOverflow = false; showSearchBar = true }
              )
              DropdownMenuItem(
                text = { Text(if (bookmarks.contains(currentPage + 1)) "Remove bookmark" else "Bookmark this page", fontSize = Ds.BODY_SP.sp) },
                onClick = { showOverflow = false; vibrate(); toggleBookmark(currentPage + 1) }
              )
              DropdownMenuItem(
                text = { Text("Bookmarks…", fontSize = Ds.BODY_SP.sp) },
                onClick = { showOverflow = false; showBookmarksDialog = true }
              )
              DropdownMenuItem(
                text = { Text("Go to page…", fontSize = Ds.BODY_SP.sp) },
                onClick = { showOverflow = false; goToInput = "${currentPage + 1}"; showGoToPage = true }
              )
              DropdownMenuItem(
                text = { Text("Zoom in", fontSize = Ds.BODY_SP.sp) },
                onClick = { showOverflow = false; vibrate(); zoom = (zoom + 0.25f).coerceIn(1f, 5f) }
              )
              DropdownMenuItem(
                text = { Text("Zoom out", fontSize = Ds.BODY_SP.sp) },
                onClick = { showOverflow = false; vibrate(); zoom = (zoom - 0.25f).coerceIn(1f, 5f) }
              )
              DropdownMenuItem(
                text = { Text("Fit to page", fontSize = Ds.BODY_SP.sp) },
                onClick = { showOverflow = false; vibrate(); zoom = 1f }
              )
              DropdownMenuItem(
                text = { Text(if (isNightMode) "Day mode" else "Night mode", fontSize = Ds.BODY_SP.sp) },
                onClick = { showOverflow = false; vibrate(); isNightMode = !isNightMode }
              )
              DropdownMenuItem(
                text = { Text("Open in Split", fontSize = Ds.BODY_SP.sp) },
                onClick = { showOverflow = false; onOpenTool?.invoke("split", readerContentUri()) }
              )
              DropdownMenuItem(
                text = { Text("Open in Merge", fontSize = Ds.BODY_SP.sp) },
                onClick = { showOverflow = false; onOpenTool?.invoke("merge", readerContentUri()) }
              )
              DropdownMenuItem(
                text = { Text("Open in Compress", fontSize = Ds.BODY_SP.sp) },
                onClick = { showOverflow = false; onOpenTool?.invoke("compress", readerContentUri()) }
              )
            }
          }
          } // top-bar Row
        } // ToolCard

        // Search bar — only when opened from the ⋮ menu.
        if (showSearchBar) {
          Spacer(Modifier.height(Ds.s8))
          Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ds.s8)
          ) {
            OutlinedTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              label = { Text("Search text in PDF", fontSize = Ds.HINT_SP.sp) },
              singleLine = true,
              modifier = Modifier.weight(1f).height(Ds.primaryButtonHeight),
              shape = RoundedCornerShape(Ds.tileRadius)
            )
            ChamiIconButton(
              icon = Icons.Filled.Search, description = "Search",
              onClick = { vibrate(); runSearch() }
            )
            ChamiIconButton(
              icon = Icons.Filled.Close, description = "Close search",
              onClick = { showSearchBar = false }
            )
          }
          if (searching) {
            Spacer(Modifier.height(Ds.s4))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
              Text(searchProgress, fontSize = Ds.HINT_SP.sp, color = theme.textSecondary)
              TextButton(onClick = { searchJob?.cancel(); searching = false }, modifier = Modifier.height(Ds.minTouch)) {
                Text("Cancel", color = ChamiColors.errorRed, fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold)
              }
            }
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
          } else if (searchPages.isNotEmpty()) {
            Spacer(Modifier.height(Ds.s4))
            Row(
              Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              // L20: truncate the page list — the first 10 pages plus a
              // "+N more" count, not one giant wrapping Text.
              val shownSearchPages = searchPages.take(10)
              val moreSearchPages = searchPages.size - shownSearchPages.size
              val foundText = LocaleHelper.t(ctx, "reader_found_on_pages")
                .replace("{pages}", shownSearchPages.joinToString(", ")) +
                if (moreSearchPages > 0) " " + LocaleHelper.t(ctx, "reader_found_more").replace("{n}", "$moreSearchPages") else ""
              Text(
                foundText,
                fontSize = Ds.HINT_SP.sp, color = theme.primaryDark, fontWeight = FontWeight.Bold
              )
              TextButton(
                onClick = {
                  vibrate()
                  val next = (searchIndex + 1) % searchPages.size
                  searchIndex = next
                  jumpToPage(searchPages[next])
                },
                modifier = Modifier.height(Ds.minTouch)
              ) {
                Text("Next match", fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold, color = theme.primary)
              }
            }
          }
        }

        // FULL SCROLL (Google Drive style): one continuous vertical
        // list, page after page — no page-by-page flipping, no Prev /
        // Next buttons, no page-number strip.
        if (pdfLoadFailed) {
          // FIX 2026-11-18 (report #24) + UI OVERHAUL 2026-11-18: a
          // protected/corrupt PDF shows a friendly banner with a way
          // out — never an infinite spinner.
          Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            ChamiErrorBanner(
              theme = theme,
              title = "Couldn't open this file",
              message = UNOPENABLE_PDF_MESSAGE
            )
            Spacer(Modifier.height(Ds.s16))
            ChamiPrimaryButton(text = "Back", onClick = { vibrate(); onBack() }, modifier = Modifier.fillMaxWidth())
          }
        } else if (totalPages == 0) {
          // UI OVERHAUL 2026-11-18: skeleton page + label instead of a
          // bare spinner on a blank screen while the document opens.
          Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            PageSkeletonLoader()
            Spacer(Modifier.height(Ds.s16))
            Text("Opening document…", fontSize = Ds.BODY_SP.sp, color = theme.textSecondary)
          }
        } else {
          LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Ds.s12),
            contentPadding = PaddingValues(vertical = Ds.s8)
          ) {
            // UI OVERHAUL 2026-11-18: keyed items so scroll position
            // and per-page state survive recomposition.
            items(totalPages, key = { it }) { idx ->
              ReaderPageView(
                theme = theme,
                uri = pdfUri!!,
                pageIndex = idx,
                scale = scale,
                zoom = zoom,
                onZoomChange = { zoom = it },
                isNightMode = isNightMode,
                cache = pageBitmapCache
              )
            }
          }
        }
      }
    }
  }
}

// FIX 2026-11-18 (report #23): byte-budgeted page-bitmap cache.
// The old cache kept up to 12 pages regardless of size — at 2.5x zoom
// one A4 page is ~50 MB, so 12 pages could reach ~600 MB and OOM a
// 4 GB phone. This cache caps TOTAL bytes (~150 MB) and evicts the
// page farthest from the one being rendered first.
private class PageBitmapCache(private val maxBytes: Long = 150L * 1024L * 1024L) {
  private val map = LinkedHashMap<Int, Bitmap>(16, 0.75f, true)
  private var bytes = 0L

  @Synchronized
  fun get(page: Int): Bitmap? = map[page]

  @Synchronized
  fun put(page: Int, bmp: Bitmap, currentPage: Int) {
    map[page]?.let { bytes -= it.allocationByteCount.toLong() }
    map[page] = bmp
    bytes += bmp.allocationByteCount.toLong()
    while (bytes > maxBytes && map.size > 1) {
      val farthest = map.keys.filter { it != page }.maxByOrNull { kotlin.math.abs(it - currentPage) } ?: break
      val evicted = map.remove(farthest) ?: break
      bytes -= evicted.allocationByteCount.toLong()
      try { evicted.recycle() } catch (_: Exception) {}
    }
  }

  @Synchronized
  fun clear() {
    map.values.forEach { try { it.recycle() } catch (_: Exception) {} }
    map.clear()
    bytes = 0L
  }
}

// One page of the continuous reader: renders lazily at the given
// scale and caches the bitmap (byte-budgeted cache, ~150 MB max,
// farthest page evicted first). FIX 2026-11-18 (Fix 10): pinch zoom
// works from 100% — the old code only attached its gesture detector
// while already zoomed (pointerInput(zoom > 1f)), so a two-finger
// pinch at 100% did nothing. The new detector is always on: two
// fingers down = pinch (takes priority over the list scroll, zoom is
// centred on the fingers); one finger while zoomed = pan the page;
// one finger at 100% = not consumed, so the list scrolls normally.
// Double-tap still toggles zoom, Fit in the ⋮ menu still resets it.
@Composable
private fun ReaderPageView(
  theme: ChamiTheme,
  uri: Uri,
  pageIndex: Int,
  scale: Float,
  zoom: Float,
  onZoomChange: (Float) -> Unit,
  isNightMode: Boolean,
  cache: PageBitmapCache
) {
  val ctx = LocalContext.current
  var bmp by remember(uri, pageIndex) { mutableStateOf<Bitmap?>(cache.get(pageIndex)) }
  var pan by remember { mutableStateOf(Offset.Zero) }
  var pageSize by remember { mutableStateOf(IntSize.Zero) }
  // FIX 2026-11-18 (Fix 10): pointerInput blocks run once and capture
  // stale values — the old code read the `zoom` parameter inside them,
  // so pinch math (and the double-tap toggle) used the zoom from first
  // composition. Read the live zoom through rememberUpdatedState.
  val currentZoom by rememberUpdatedState(zoom)
  LaunchedEffect(uri, pageIndex, scale) {
    cache.get(pageIndex)?.let { bmp = it; return@LaunchedEffect }
    val rendered = withContext(Dispatchers.IO) { PdfReader.renderPageBitmap(ctx, uri, pageIndex, scale) }
    if (rendered != null) {
      cache.put(pageIndex, rendered, pageIndex)
      bmp = rendered
    }
  }
  LaunchedEffect(zoom) { if (zoom == 1f) pan = Offset.Zero }
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(Ds.tileRadius))
        .background(Color(0xFF121212)),
      contentAlignment = Alignment.Center
    ) {
      val shown = bmp
      // PERFORMANCE (UI overhaul): wrap once — this page recomposes on
      // every zoom/pan frame and re-wrapping the bitmap each time
      // dropped frames on low-end phones.
      val shownImage = remember(shown) { shown?.asImageBitmap() }
      if (shown == null) {
        // UI OVERHAUL 2026-11-18: skeleton page instead of a bare spinner.
        PageSkeletonLoader(Modifier.fillMaxWidth())
      } else {
        val nightFilter = if (isNightMode) {
          androidx.compose.ui.graphics.ColorFilter.colorMatrix(
            androidx.compose.ui.graphics.ColorMatrix(
              floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
              )
            )
          )
        } else null
        Image(
          bitmap = shownImage!!,
          contentDescription = "Page ${pageIndex + 1}",
          colorFilter = nightFilter,
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(shown.width.toFloat() / shown.height.toFloat())
            .onSizeChanged { pageSize = it }
            .graphicsLayer(
              scaleX = zoom,
              scaleY = zoom,
              translationX = pan.x,
              translationY = pan.y
            )
            // FIX 2026-11-18 (Fix 10): pinch zoom, always on. Two
            // fingers = pinch (consumed, so the LazyColumn cannot
            // scroll underneath); one finger while zoomed = pan the
            // page (consumed); one finger at 100% = left unconsumed
            // so the list scrolls normally. Zoom is centred on the
            // fingers: pan is corrected so the point under the pinch
            // centroid stays under it, and pan is clamped so the page
            // can never be dragged off-screen entirely.
            .pointerInput(Unit) {
              awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                  val event = awaitPointerEvent()
                  val pressed = event.changes.count { it.pressed }
                  val canceled = event.changes.any { it.isConsumed }
                  if (!canceled) {
                    if (pressed >= 2) {
                      val zoomChange = event.calculateZoom()
                      val panChange = event.calculatePan()
                      val centroid = event.calculateCentroid(useCurrent = true)
                      val z = (currentZoom * zoomChange).coerceIn(1f, 5f)
                      if (z == 1f) {
                        onZoomChange(1f)
                        pan = Offset.Zero
                      } else {
                        val k = z / currentZoom
                        val centre = Offset(pageSize.width / 2f, pageSize.height / 2f)
                        val q = centroid - centre - pan
                        var next = pan + panChange + q * (1f - k)
                        if (pageSize.width > 0 && pageSize.height > 0) {
                          val maxX = pageSize.width * (z - 1f) / 2f
                          val maxY = pageSize.height * (z - 1f) / 2f
                          next = Offset(
                            next.x.coerceIn(-maxX, maxX),
                            next.y.coerceIn(-maxY, maxY)
                          )
                        }
                        pan = next
                        onZoomChange(z)
                      }
                      event.changes.forEach { it.consume() }
                    } else if (pressed == 1 && currentZoom > 1f) {
                      val panChange = event.calculatePan()
                      if (panChange != Offset.Zero) {
                        var next = pan + panChange
                        if (pageSize.width > 0 && pageSize.height > 0) {
                          val maxX = pageSize.width * (currentZoom - 1f) / 2f
                          val maxY = pageSize.height * (currentZoom - 1f) / 2f
                          next = Offset(
                            next.x.coerceIn(-maxX, maxX),
                            next.y.coerceIn(-maxY, maxY)
                          )
                        }
                        pan = next
                        event.changes.forEach { it.consume() }
                      }
                    }
                    // One finger at 100%: nothing consumed — the list scrolls.
                  }
                } while (event.changes.any { it.pressed })
              }
            }
            .pointerInput(Unit) {
              detectTapGestures(
                onDoubleTap = {
                  if (currentZoom > 1f) onZoomChange(1f)
                  else onZoomChange(2.5f)
                }
              )
            },
          contentScale = ContentScale.FillWidth
        )
      }
    }
    Spacer(Modifier.height(Ds.s4))
    Text("Page ${pageIndex + 1}", fontSize = Ds.HINT_SP.sp, color = theme.textSecondary)
  }
}


// 11. PROTECT PDF — Lock / Unlock segmented control, title stays
// "Protect PDF". Lock: password with a real vector eye, strength hint
// under the field, mismatch error under confirm as typed, three
// permission switches (printing / copying / editing, all ON by
// default) that are actually applied via PdfProtector.patchPermissions.
// Unlock distinguishes "wrong password" from "unsupported format".
// Output is always a NEW file (NAME_protected / NAME_unlocked); the
// original is never overwritten. NOTE: new protections use AES-128
// Standard encryption (PdfProtector, V=4/R=4); files protected by
// older versions with 40-bit RC4 still open and unlock.
@Composable
fun ProtectPdfScreen(theme: ChamiTheme, onOpenPdfInReader: ((File) -> Unit)? = null, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  // Phase 5: motion gate for the tab-row press-scale.
  val animOn = animationsEnabled(ctx)

  var mode by rememberSaveable { mutableStateOf("Protect") }

  var selectedUri by rememberSaveableUri()
  var selectedName by remember { mutableStateOf("") }
  var pageCount by remember { mutableStateOf(0) }
  var pdfLoadFailed by remember { mutableStateOf(false) }
  var loadedPdfUri by remember { mutableStateOf<Uri?>(null) }
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var password by rememberSaveable { mutableStateOf("") }
  var confirmPassword by rememberSaveable { mutableStateOf("") }
  var passwordVisible by remember { mutableStateOf(false) }
  var allowPrint by rememberSaveable { mutableStateOf(true) }
  var allowCopy by rememberSaveable { mutableStateOf(true) }
  var allowEdit by rememberSaveable { mutableStateOf(true) }

  var unlockUri by rememberSaveableUri()
  var unlockName by remember { mutableStateOf("") }
  var unlockPassword by rememberSaveable { mutableStateOf("") }
  var unlockPasswordVisible by remember { mutableStateOf(false) }
  var unlockError by remember { mutableStateOf<String?>(null) }

  var isProcessing by remember { mutableStateOf(false) }
  var progressStatus by remember { mutableStateOf("") }

  var doneFile by remember { mutableStateOf<File?>(null) }
  var showDone by remember { mutableStateOf(false) }
  var savedLocationText by remember { mutableStateOf<String?>(null) }
  val singleSave = rememberSingleFileSaver(
    onSavedToUri = { uri -> savedLocationText = displayNameOf(ctx, uri) }
  ) { file ->
    HistoryStore.record(ctx, file.name, "protect")
    doneFile = file
    showDone = true
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  // FIX 2026-11-18 (report #47): a restored Uri (process death)
  // reloads its page count/preview instead of showing an empty card.
  fun loadProtectPdf(uri: Uri) {
    loadedPdfUri = uri
    scope.launch(Dispatchers.IO) {
      val count = PdfEngine.pageCount(ctx, uri)
      val bmp = PdfReader.renderPageBitmap(ctx, uri, 0, 1.0f)
      withContext(Dispatchers.Main) {
        pageCount = count
        pdfLoadFailed = count == 0
        previewBitmap = bmp
        selectedName = baseNameOf(ctx, uri, "Document")
      }
    }
  }

  val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) {
      vibrate()
      selectedUri = uri
      selectedName = baseNameOf(ctx, uri, "Document")
      showDone = false
      doneFile = null
      pdfLoadFailed = false
      loadProtectPdf(uri)
    }
  }

  LaunchedEffect(selectedUri) {
    val u = selectedUri
    if (u != null && loadedPdfUri != u) loadProtectPdf(u)
  }

  val unlockPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) {
      vibrate()
      unlockUri = uri
      unlockName = baseNameOf(ctx, uri, "Document")
      unlockError = null
      showDone = false
      doneFile = null
    }
  }

  fun protect() {
    val uri = selectedUri ?: return
    isProcessing = true
    progressStatus = "Encrypting document..."
    scope.launch(Dispatchers.IO) {
      val out = PdfEngine.outFile(ctx, "${selectedName}_protected_${System.currentTimeMillis()}.pdf")
      val ok = PdfProtector.protectPdf(ctx, uri, password, out) { current, total ->
        progressStatus = "Encrypting page $current of $total..."
      }
      var patched = true
      if (ok && (!allowPrint || !allowCopy || !allowEdit)) {
        patched = PdfProtector.patchPermissions(out, password, allowPrint = allowPrint, allowCopy = allowCopy, allowEdit = allowEdit)
      }
      withContext(Dispatchers.Main) {
        isProcessing = false
        if (ok) {
          if (!patched) {
            Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_protect_permissions_failed"), Toast.LENGTH_LONG).show()
          }
          singleSave(out)
        } else {
          Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_encrypt_failed"), Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  fun unlock() {
    val uri = unlockUri ?: return
    if (unlockPassword.isBlank()) {
      unlockError = LocaleHelper.t(ctx, "unlock_enter_password")
      return
    }
    isProcessing = true
    unlockError = null
    scope.launch {
      val tmp = File(ctx.cacheDir, "unlock_in_${System.currentTimeMillis()}.pdf")
      val out = PdfEngine.outFile(ctx, "${unlockName}_unlocked_${System.currentTimeMillis()}.pdf")
      // H6: no whole-file read — the input is only copied to a temp
      // file (streamed), then PdfProtector.unlockPdf works on a
      // read-only memory-mapped buffer. The legacy unlockProtectedPdf
      // fallback (3x full-file copies) is deleted.
      val result: String? = withContext(Dispatchers.IO) {
        try {
          ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { o -> input.copyTo(o) }
          }
        } catch (_: Exception) { }
        if (!tmp.exists()) return@withContext LocaleHelper.t(ctx, "unlock_read_failed")
        if (!PdfProtector.isEncryptedFile(tmp)) return@withContext LocaleHelper.t(ctx, "unlock_not_protected")
        if (!PdfProtector.verifyPassword(tmp, unlockPassword)) {
          return@withContext LocaleHelper.t(ctx, "unlock_wrong_password")
        }
        val ok = try { PdfProtector.unlockPdf(tmp, out, unlockPassword) } catch (_: Exception) { false }
        if (!ok) {
          return@withContext LocaleHelper.t(ctx, "unlock_unsupported")
        }
        null // success
      }
      // L14: the unlock temp file is deleted after success AND on
      // failure; a failed unlock also leaves no partial output behind.
      try { tmp.delete() } catch (_: Exception) {}
      isProcessing = false
      if (result == null) {
        Toast.makeText(ctx, LocaleHelper.t(ctx, "toast_unlocked"), Toast.LENGTH_SHORT).show()
        singleSave(out)
      } else {
        try { out.delete() } catch (_: Exception) {}
        unlockError = result
      }
    }
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

  val strength = when {
    password.isEmpty() -> ""
    password.length < 4 -> "Too short — at least 4 characters"
    password.length < 8 && !(password.any { it.isDigit() } && password.any { it.isLetter() }) -> "Weak"
    password.length < 8 -> "OK"
    password.any { it.isDigit() } && password.any { it.isLetter() } && password.any { !it.isLetterOrDigit() } -> "Strong"
    password.any { it.isDigit() } && password.any { it.isLetter() } -> "Strong"
    else -> "OK"
  }
  val mismatch = confirmPassword.isNotEmpty() && password != confirmPassword
  val canEncrypt = selectedUri != null && !pdfLoadFailed && pageCount > 0 && password.length >= 4 && password == confirmPassword && !isProcessing

  ToolScreenContainer("Protect PDF", theme, onBack) {
    Column(
      // C1: weight(1f), NOT fillMaxSize() — a fillMaxSize child takes
      // the whole Column, pushing the SlideToAction below off-screen
      // so the tool could not be used at all. Every other tool screen
      // already uses weight(1f) here.
      Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(Ds.s16)
    ) {
      // Phase 5: shared segmented tab row (same as Compress).
      ToolTabRow(
        tabs = listOf("PROTECT", "UNLOCK"),
        selectedIndex = if (mode == "Protect") 0 else 1,
        onSelect = { mode = if (it == 0) "Protect" else "Unlock" },
        theme = theme,
        animOn = animOn,
        onTap = { vibrate() }
      )

      if (mode == "Unlock") {
        ToolCard(theme = theme) {
          ToolSectionHeader("Unlock a protected PDF", theme.text, topPadding = 0.dp)
          ChamiSecondaryButton(
            text = if (unlockUri == null) "Choose protected PDF" else "Change PDF file",
            onClick = { vibrate(); unlockPicker.launch("application/pdf") },
            modifier = Modifier.fillMaxWidth()
          )
          if (unlockUri != null) {
            Spacer(Modifier.height(Ds.s16))
            OutlinedTextField(
              value = unlockPassword,
              onValueChange = { unlockPassword = it; unlockError = null },
              label = { Text("Enter PDF password", fontSize = Ds.HINT_SP.sp) },
              singleLine = true,
              visualTransformation = if (unlockPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              trailingIcon = {
                ChamiIconButton(
                  icon = if (unlockPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                  description = if (unlockPasswordVisible) "Hide password" else "Show password",
                  onClick = { unlockPasswordVisible = !unlockPasswordVisible }
                )
              },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(Ds.buttonRadius),
              isError = unlockError != null
            )
            if (unlockError != null) {
              Spacer(Modifier.height(Ds.s4))
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = ChamiColors.errorRed, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(Ds.s8))
                Text(unlockError!!, fontSize = Ds.HINT_SP.sp, color = ChamiColors.errorRed)
              }
            }
            Spacer(Modifier.height(Ds.s8))
            HintText("Removes the password from PDFs protected by Chami (Standard encryption). The unlocked file is a new file — the original stays protected.")
          }
        }
        if (isProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        }
      } else {
        ToolCard(theme = theme) {
          ToolSectionHeader("1. Select PDF document", theme.text, topPadding = 0.dp)
          ChamiSecondaryButton(
            text = if (selectedUri == null) "Choose PDF file" else "Change PDF file",
            onClick = { vibrate(); pdfPicker.launch("application/pdf") },
            modifier = Modifier.fillMaxWidth()
          )
          if (selectedUri != null) {
            Spacer(Modifier.height(Ds.s16))
            Row(
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Ds.tileRadius))
                .background(theme.bg)
                .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
                .padding(Ds.s12),
              verticalAlignment = Alignment.CenterVertically
            ) {
              if (previewBitmap != null) {
                Image(
                  bitmap = previewBitmap!!.asImageBitmap(),
                  contentDescription = "Document page 1",
                  modifier = Modifier
                    .size(56.dp, 72.dp)
                    .clip(RoundedCornerShape(Ds.s8))
                    .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.s8)),
                  contentScale = ContentScale.Crop
                )
              } else {
                // Phase 5: grey placeholder until the first page renders.
                Box(
                  Modifier
                    .size(56.dp, 72.dp)
                    .clip(RoundedCornerShape(Ds.s8))
                    .background(ChamiColors.skeletonBg),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = theme.textSecondary, modifier = Modifier.size(24.dp))
                }
              }
              Spacer(Modifier.width(Ds.s12))
              Column(Modifier.weight(1f)) {
                Text(selectedName, fontWeight = FontWeight.Bold, fontSize = Ds.BODY_SP.sp, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Ds.s4))
                Text(
                  if (pdfLoadFailed) UNOPENABLE_PDF_MESSAGE else "Total pages: $pageCount",
                  fontSize = Ds.HINT_SP.sp,
                  color = if (pdfLoadFailed) ChamiColors.errorRed else theme.textSecondary
                )
              }
            }
          }
        }

        if (selectedUri != null) {
          ToolCard(theme = theme) {
            ToolSectionHeader("2. Set encryption password", theme.text, topPadding = 0.dp)
            Spacer(Modifier.height(Ds.s4))
            OutlinedTextField(
              value = password,
              onValueChange = { password = it },
              label = { Text("Enter document password", fontSize = Ds.HINT_SP.sp) },
              singleLine = true,
              visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              trailingIcon = {
                ChamiIconButton(
                  icon = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                  description = if (passwordVisible) "Hide password" else "Show password",
                  onClick = { passwordVisible = !passwordVisible }
                )
              },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(Ds.buttonRadius)
            )
            if (strength.isNotEmpty()) {
              Spacer(Modifier.height(Ds.s4))
              Text(
                "Strength: $strength",
                fontSize = Ds.HINT_SP.sp,
                color = if (strength.startsWith("Weak") || strength.startsWith("Too short")) ChamiColors.errorRed else theme.primary
              )
            }
            Spacer(Modifier.height(Ds.s12))
            OutlinedTextField(
              value = confirmPassword,
              onValueChange = { confirmPassword = it },
              label = { Text("Confirm document password", fontSize = Ds.HINT_SP.sp) },
              singleLine = true,
              visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(Ds.buttonRadius),
              isError = mismatch
            )
            if (mismatch) {
              Spacer(Modifier.height(Ds.s4))
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = ChamiColors.errorRed, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(Ds.s8))
                Text(LocaleHelper.t(ctx, "reason_passwords_mismatch"), fontSize = Ds.HINT_SP.sp, color = ChamiColors.errorRed)
              }
            }
            Spacer(Modifier.height(Ds.s16))
            ToolSectionHeader("Permissions", theme.text, topPadding = 0.dp)
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Ds.minTouch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Allow printing", fontSize = Ds.BODY_SP.sp, color = theme.text)
              Switch(checked = allowPrint, onCheckedChange = { allowPrint = it })
            }
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Ds.minTouch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Allow copying text", fontSize = Ds.BODY_SP.sp, color = theme.text)
              Switch(checked = allowCopy, onCheckedChange = { allowCopy = it })
            }
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Ds.minTouch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Allow editing", fontSize = Ds.BODY_SP.sp, color = theme.text)
              Switch(checked = allowEdit, onCheckedChange = { allowEdit = it })
            }
            Spacer(Modifier.height(Ds.s12))
            // H9: new protections are AES-128 (PdfProtector) — the old
            // "40-bit" note described the retired RC4 format. Legacy
            // RC4 files still open.
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Filled.Info, contentDescription = null, tint = theme.primary, modifier = Modifier.size(24.dp))
              Spacer(Modifier.width(Ds.s8))
              Text(
                LocaleHelper.t(ctx, "protect_encryption_aes_note"),
                fontSize = Ds.HINT_SP.sp, color = theme.textSecondary
              )
            }
            Spacer(Modifier.height(Ds.s8))
            // FIX 2026-11-18 (report #30): same rasterised-output note
            // the other tools carry.
            HintText("Protected pages are rendered as images — text in the protected file is not selectable.")
            Spacer(Modifier.height(Ds.s12))
            HintText("Keep this password safe — it cannot be recovered. The protected file is a new file; the original is never changed.")
          }
        }

        if (isProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
          Spacer(Modifier.height(Ds.s4))
          Text(if (progressStatus.isNotEmpty()) progressStatus else "Encrypting...", fontSize = Ds.HINT_SP.sp, color = theme.text)
        }
      }
    }

    if (mode == "Unlock") {
      SlideToAction(
        label = LocaleHelper.t(ctx, "slide_unlock"),
        theme = theme,
        enabled = !isProcessing && unlockUri != null && unlockPassword.isNotBlank(),
        disabledReason = when {
          unlockUri == null -> LocaleHelper.t(ctx, "reason_choose_protected_pdf_first")
          unlockPassword.isBlank() -> LocaleHelper.t(ctx, "unlock_enter_password")
          else -> null
        },
        onSlideComplete = { vibrate(); unlock() }
      )
    } else {
      SlideToAction(
        label = LocaleHelper.t(ctx, "slide_protect"),
        theme = theme,
        enabled = canEncrypt,
        disabledReason = when {
          selectedUri == null -> LocaleHelper.t(ctx, "reason_pick_pdf_first")
          pdfLoadFailed -> UNOPENABLE_PDF_MESSAGE
          pageCount == 0 -> LocaleHelper.t(ctx, "reason_loading_pdf")
          password.length < 4 -> LocaleHelper.t(ctx, "reason_password_min_length")
          password != confirmPassword -> LocaleHelper.t(ctx, "reason_passwords_mismatch")
          else -> null
        },
        onSlideComplete = { vibrate(); protect() }
      )
    }
  }
}
