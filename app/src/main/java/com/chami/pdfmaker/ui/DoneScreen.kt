package com.chami.pdfmaker.ui

import android.graphics.PathMeasure
import android.graphics.pdf.PdfRenderer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.LocaleHelper
import com.chami.pdfmaker.pdf.ShareHelper
import com.chami.pdfmaker.theme.ChamiTheme
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun formatFileSize(bytes: Long): String {
  return when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "%d B".format(bytes)
  }
}

private fun mimeForFile(file: File): String = when (file.extension.lowercase()) {
  "pdf" -> "application/pdf"
  "png" -> "image/png"
  "jpg", "jpeg" -> "image/jpeg"
  "webp" -> "image/webp"
  "txt" -> "text/plain"
  else -> "application/octet-stream"
}

private data class ConfettiPiece(
  val startX: Float, // 0..1 across the confetti area
  val startY: Float, // 0..1 down the confetti area
  val fall: Float, // how far it falls, as a fraction of the area height
  val size: Float, // px
  val color: Color,
  val rotation: Float,
  val spin: Float,
  val drift: Float,
  val isDot: Boolean
)

private val CONFETTI_COLORS = listOf(
  Color(0xFF22C55E), Color(0xFF16A34A), Color(0xFFFACC15),
  Color(0xFF38BDF8), Color(0xFFF472B6), Color(0xFFFB923C)
)

// L2: reused tick-geometry holder — the check path, its measure and
// the trimmed segment are allocated once and rebuilt only when the
// canvas size changes, never per animation frame.
private class TickPathCache {
  val path = Path()
  val measure = PathMeasure()
  val segment = Path()
  var size = 0f
}

/**
 * Success screen shown right after a file is made — same after every tool.
 * Tick draws in (600ms), confetti bursts ONCE, success sound plays ONCE
 * and a success vibration fires ONCE, all on entry. The file card shows
 * name, size, page count and folder; actions are Open / Share / Done,
 * in that order (max 3). The created file is already in History.
 *
 * Signature is unchanged from Phase 6 so every ToolScreens call site
 * keeps working: [onShare] and [pageCount] are optional. When [pageCount]
 * is not supplied, PDFs are counted here with PdfRenderer (off the main
 * thread); non-PDF files show no page line. When [onShare] is not
 * supplied, sharing falls back to [ShareHelper.shareFile].
 * FIX 2026-11-18: [savedLocationText] — when non-null, the card shows
 * "Saved to: $savedLocationText" (the folder the user actually picked in
 * the save picker) instead of the file's parent folder name. [onOpenPdfInReader]
 * — when non-null and the file is a PDF, Open goes to the inbuilt Reader
 * instead of [onOpen] (the external viewer).
 */
@Composable
fun DoneScreen(
  file: File,
  theme: ChamiTheme,
  onBackHome: () -> Unit,
  onOpen: (File) -> Unit,
  onShare: ((File) -> Unit)? = null,
  pageCount: Int? = null,
  savedLocationText: String? = null,
  onOpenPdfInReader: ((File) -> Unit)? = null
) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  // Phase 6: motion gate — the gentle content fade-in below is off
  // when the system "remove animations" setting is on.
  val animOn = animationsEnabled(ctx)
  var started by remember { mutableStateOf(false) }

  // PHASE 2 (H1): system Back on a single-file Done screen goes Home
  // instead of exiting the app (this screen early-returns before
  // ToolScreenContainer, so it never gets that container's BackHandler).
  BackHandler { onBackHome() }

  // L2: keep a handle to the success ringtone so it can be stopped
  // when this screen leaves composition (it used to keep playing
  // after navigation if the sound was still ringing).
  var successRingtone by remember { mutableStateOf<Ringtone?>(null) }
  DisposableEffect(Unit) {
    onDispose {
      try { successRingtone?.stop() } catch (_: Exception) {}
    }
  }

  // L2: the check path / measure / segment are allocated once here
  // and rebuilt only when the tick canvas size changes (see below);
  // the per-frame draw only trims the reused segment.
  val tickCache = remember { TickPathCache() }

  // Page count for PDFs, counted once off the main thread when the
  // caller did not supply one. Non-PDF files keep no page line.
  var countedPages by remember { mutableStateOf<Int?>(pageCount) }
  LaunchedEffect(file) {
    if (pageCount == null && file.extension.lowercase() == "pdf") {
      countedPages = withContext(Dispatchers.IO) {
        try {
          val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
          val renderer = PdfRenderer(pfd)
          val count = renderer.pageCount
          renderer.close()
          pfd.close()
          count
        } catch (_: Exception) {
          null
        }
      }
    }
  }

  // Success sound + vibration, played/fired ONCE on entry. The sound
  // is the system notification sound (no bundled audio); on a silent
  // or vibrate phone there is no sound, which is why the vibration
  // and the tick/confetti fire at the same time.
  LaunchedEffect(Unit) {
    started = true
    try {
      val ringtone = RingtoneManager.getRingtone(
        ctx,
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      )
      successRingtone = ringtone
      ringtone?.play()
    } catch (_: Exception) {
      // No sound on this device — the tick + confetti + vibration still show.
    }
    try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
  }

  // Spec §5: tick draws in over 600ms.
  val tickProgress by animateFloatAsState(
    targetValue = if (started) 1f else 0f,
    animationSpec = tween(durationMillis = 600),
    label = "tick"
  )
  // Confetti bursts once (1800ms) — `started` only ever goes false -> true.
  val confettiProgress by animateFloatAsState(
    targetValue = if (started) 1f else 0f,
    animationSpec = tween(durationMillis = 1800),
    label = "confetti"
  )

  // ~40 confetti pieces, fixed random layout per composition.
  val pieces = remember {
    val rnd = Random(42)
    List(40) { i ->
      ConfettiPiece(
        startX = rnd.nextFloat(),
        startY = rnd.nextFloat() * 0.35f,
        fall = 0.45f + rnd.nextFloat() * 0.55f,
        size = 8f + rnd.nextFloat() * 10f,
        color = CONFETTI_COLORS[i % CONFETTI_COLORS.size],
        rotation = rnd.nextFloat() * 360f,
        spin = (rnd.nextFloat() - 0.5f) * 720f,
        drift = (rnd.nextFloat() - 0.5f) * 120f,
        isDot = rnd.nextBoolean()
      )
    }
  }

  val isPdf = file.extension.lowercase() == "pdf"
  // FIX 2026-11-18 (#3): show the folder the user actually picked
  // (savedLocationText) when the caller supplies it; fall back to the
  // file's parent folder name as before.
  val folderName = savedLocationText ?: (file.parentFile?.name ?: LocaleHelper.t(ctx, "done_chami_folder"))
  val pages = countedPages
  val fileMeta = buildString {
    append(formatFileSize(file.length()))
    if (pages != null) append("  •  $pages ${if (pages == 1) LocaleHelper.t(ctx, "done_page") else LocaleHelper.t(ctx, "done_pages")}")
  }
  val shareFile: (File) -> Unit = onShare ?: { f -> ShareHelper.shareFile(ctx, f, mimeForFile(f)) }

  // Phase 6: the Done content fades in gently (250ms shared easing,
  // same toolEnterFade as the Phase 5 tool screens); instant when
  // animations are off. The tick (600ms) and confetti (1800ms) keep
  // their spec timings and behaviour exactly.
  Column(
    modifier = Modifier
      .fillMaxSize()
      .toolEnterFade(0, animOn)
      .background(theme.bg)
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(horizontal = Ds.s16, vertical = Ds.s16),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Tick + confetti area (fixed height, confetti drawn around the tick).
    Box(
      modifier = Modifier
        .fillMaxWidth()
        // One-off size: the success animation area, not a spacing token.
        .height(200.dp),
      contentAlignment = Alignment.Center
    ) {
      Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        for (piece in pieces) {
          val x = piece.startX * w + piece.drift * confettiProgress
          val y = piece.startY * h + piece.fall * h * confettiProgress
          val alpha = (1f - confettiProgress).coerceIn(0f, 1f)
          rotate(piece.rotation + piece.spin * confettiProgress, Offset(x, y)) {
            if (piece.isDot) {
              drawCircle(piece.color.copy(alpha = alpha), piece.size / 2f, Offset(x, y))
            } else {
              drawRect(
                piece.color.copy(alpha = alpha),
                topLeft = Offset(x - piece.size / 2f, y - piece.size / 4f),
                size = androidx.compose.ui.geometry.Size(piece.size, piece.size / 2f)
              )
            }
          }
        }
      }
      // Animated green success tick: circle scales in, checkmark draws in.
      val scale = 0.6f + 0.4f * tickProgress
      Canvas(
        Modifier
          // One-off size: the success tick, not a spacing token.
          .size(120.dp)
          .graphicsLayer(scaleX = scale, scaleY = scale, alpha = tickProgress.coerceIn(0.05f, 1f))
      ) {
        drawCircle(theme.primary, size.minDimension / 2f, center)
        // L2: rebuild the check path only when the canvas size
        // changes; each frame only trims the reused segment.
        val s = size.minDimension
        if (s != tickCache.size) {
          tickCache.size = s
          tickCache.path.apply {
            reset()
            moveTo(s * 0.28f, s * 0.52f)
            lineTo(s * 0.45f, s * 0.68f)
            lineTo(s * 0.74f, s * 0.34f)
          }
          tickCache.measure.setPath(tickCache.path.asAndroidPath(), false)
        }
        tickCache.segment.reset()
        tickCache.measure.getSegment(
          0f, tickCache.measure.length * tickProgress,
          tickCache.segment.asAndroidPath(), true
        )
        drawPath(
          tickCache.segment,
          Color.White,
          style = Stroke(width = size.minDimension * 0.09f, cap = StrokeCap.Round)
        )
      }
    }

    Text(
      LocaleHelper.t(ctx, "done"),
      fontFamily = PixelFontFamily,
      fontSize = Ds.TITLE_SP.sp,
      fontWeight = FontWeight.Bold,
      lineHeight = (Ds.TITLE_SP * 1.4).sp,
      color = theme.text
    )
    Spacer(Modifier.height(Ds.s4))
    Text(
      if (isPdf) LocaleHelper.t(ctx, "done_pdf_created") else LocaleHelper.t(ctx, "done_file_created"),
      fontSize = Ds.BODY_SP.sp,
      lineHeight = (Ds.BODY_SP * 1.4).sp,
      color = theme.textSecondary
    )
    Spacer(Modifier.height(Ds.s24))

    // Created file card — immediately visible, no scrolling needed.
    // Name, size, page count and the folder it was saved to.
    // Phase 6: soft elevation (Ds.tileElevation) like the Phase 5 cards.
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(Ds.tileRadius),
      colors = CardDefaults.cardColors(containerColor = theme.card),
      border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
      elevation = CardDefaults.cardElevation(defaultElevation = Ds.tileElevation)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(Ds.s16),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .size(Ds.minTouch)
            .background(ChamiColors.lightGreen, CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            Icons.Filled.Description,
            contentDescription = null,
            tint = ChamiColors.deepGreen,
            modifier = Modifier.size(Ds.iconSize)
          )
        }
        Column(
          modifier = Modifier
            .weight(1f)
            .padding(horizontal = Ds.s12)
        ) {
          Text(
            file.name,
            fontWeight = FontWeight.Bold,
            fontSize = Ds.BODY_SP.sp,
            lineHeight = (Ds.BODY_SP * 1.4).sp,
            color = theme.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(Modifier.height(Ds.s4))
          Text(
            fileMeta,
            fontSize = Ds.META_SP.sp,
            lineHeight = (Ds.META_SP * 1.4).sp,
            color = theme.textSecondary
          )
          Spacer(Modifier.height(Ds.s4))
          Text(
            LocaleHelper.t(ctx, "label_saved_to").replace("{location}", folderName),
            fontSize = Ds.META_SP.sp,
            lineHeight = (Ds.META_SP * 1.4).sp,
            color = theme.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }

    Spacer(Modifier.weight(1f))

    // Actions, in spec order: Open / Share / Done (max 3).
    // FIX 2026-11-18 (#4): a PDF opens in the inbuilt Reader when the
    // caller supplies onOpenPdfInReader; otherwise Open behaves as before.
    // Phase 6: press-scale on every button (Phase 3 pattern — the same
    // interactionSource drives the button and the scale, input never
    // blocked); primary uses theme.primary, the one primary green
    // (was brightGreen, which no other primary button uses).
    val openPress = remember { MutableInteractionSource() }
    val sharePress = remember { MutableInteractionSource() }
    val donePress = remember { MutableInteractionSource() }
    Button(
      onClick = {
        if (isPdf && onOpenPdfInReader != null) onOpenPdfInReader(file) else onOpen(file)
      },
      modifier = Modifier
        .fillMaxWidth()
        .pressScale(animOn, openPress)
        .height(Ds.primaryButtonHeight),
      interactionSource = openPress,
      colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
      shape = RoundedCornerShape(Ds.buttonRadius)
    ) {
      Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(Ds.iconSize))
      Spacer(Modifier.size(Ds.s8))
      Text(LocaleHelper.t(ctx, "done_open"), fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
    Spacer(Modifier.height(Ds.s12))
    Button(
      onClick = { shareFile(file) },
      modifier = Modifier
        .fillMaxWidth()
        .pressScale(animOn, sharePress)
        .height(Ds.secondaryButtonHeight),
      interactionSource = sharePress,
      colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ChamiColors.darkGreen),
      border = androidx.compose.foundation.BorderStroke(1.5.dp, ChamiColors.darkGreen),
      shape = RoundedCornerShape(Ds.buttonRadius)
    ) {
      Icon(Icons.Filled.Share, contentDescription = null, tint = ChamiColors.darkGreen, modifier = Modifier.size(Ds.iconSize))
      Spacer(Modifier.size(Ds.s8))
      Text(LocaleHelper.t(ctx, "done_share"), fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold, color = ChamiColors.darkGreen)
    }
    Spacer(Modifier.height(Ds.s12))
    Button(
      onClick = onBackHome,
      modifier = Modifier
        .fillMaxWidth()
        .pressScale(animOn, donePress)
        .height(Ds.secondaryButtonHeight),
      interactionSource = donePress,
      // UI OVERHAUL 2026-11-18: the old transparent button read as a
      // dead label next to the two solid buttons — same secondary
      // outline style as Share, so all three actions look tappable.
      colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ChamiColors.darkGreen),
      border = androidx.compose.foundation.BorderStroke(1.5.dp, ChamiColors.darkGreen),
      shape = RoundedCornerShape(Ds.buttonRadius)
    ) {
      Icon(Icons.Filled.Home, contentDescription = null, tint = ChamiColors.darkGreen, modifier = Modifier.size(Ds.iconSize))
      Spacer(Modifier.size(Ds.s8))
      Text(LocaleHelper.t(ctx, "done"), fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold, color = ChamiColors.darkGreen)
    }
  }
}
