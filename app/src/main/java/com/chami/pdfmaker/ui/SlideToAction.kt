package com.chami.pdfmaker.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.theme.ChamiTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Slide-to-action, used by EVERY tool instead of a tap button
 * (Slide to Convert / Merge / Split / Protect / Compress / Save PDF ...).
 * Drag the thumb to 90% of the track to confirm (spec §5).
 *
 * JANK FIX (audit §15/§18): the thumb offset is plain Float state
 * updated DIRECTLY in the drag callback — no coroutine is launched
 * per drag event (the old code launched one per pointer move).
 * The only coroutine is the single snap-back animation launched
 * once per gesture end / cancel.
 *
 * Haptics: light tick on slide start, ticks at 25% / 50% / 75%,
 * success haptic on complete. Releasing before 90% slides back
 * with no action.
 *
 * DISABLED STATE: pass [disabledReason] (e.g. "Select at least 2 PDFs
 * to merge") and it is shown under the track, so the user always knows
 * WHY the slide is unavailable. Call sites that do not pass one get a
 * generic "finish the steps above" line — pass a real reason wherever
 * the tool knows it.
 */
@Composable
fun SlideToAction(
  label: String,
  theme: ChamiTheme,
  enabled: Boolean = true,
  disabledReason: String? = null,
  onSlideComplete: () -> Unit,
  modifier: Modifier = Modifier
) {
  val haptic = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  // PHASE 2 (H8): in RTL layouts the thumb starts at the end side
  // and the drag mirrors (dragging toward the start side completes).
  // `LayoutDirection` is read here, never hardcoded to LTR.
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val endAlignment = if (isRtl) Alignment.CenterEnd else Alignment.CenterStart
  // Spec §3/§5: track 56dp, handle 48dp (Ds.slideHeight / Ds.slideHandle).
  val thumbSize = Ds.slideHandle
  val trackHeight = Ds.slideHeight
  val thumbPx = with(density) { thumbSize.toPx() }
  val edgePaddingPx = with(density) { Ds.s4.toPx() }
  var trackWidthPx by remember { mutableStateOf(0f) }
  val maxOffset = (trackWidthPx - thumbPx - edgePaddingPx * 2).coerceAtLeast(0f)
  // Plain state, updated synchronously on drag — never via a coroutine.
  var dragOffset by remember { mutableFloatStateOf(0f) }
  var lastTickMilestone by remember { mutableIntStateOf(0) }
  var completed by remember { mutableStateOf(false) }
  // FIX 2026-11-18 (report #45): tapping the track shows this hint
  // briefly ("Drag the circle") and nudges the thumb part-way, so a
  // tap is never a dead tap.
  var showTapHint by remember { mutableStateOf(false) }

  val trackColor = if (enabled) ChamiColors.lightGreen else theme.cardBorder
  val thumbColor = if (enabled) ChamiColors.brightGreen else ChamiColors.secondaryText.copy(alpha = 0.6f)
  val labelColor = if (enabled) ChamiColors.deepGreen else ChamiColors.secondaryText

  /** One snap-back animation per gesture end — the only coroutine here. */
  // PHASE 2 (M10): the previous offset animation is cancelled before
  // a new one starts, so two animations can never fight over the thumb.
  var animJob by remember { mutableStateOf<Job?>(null) }
  fun animateOffsetTo(target: Float) {
    val start = dragOffset
    animJob?.cancel()
    animJob = scope.launch {
      animate(start, target, animationSpec = tween(300)) { value, _ ->
        dragOffset = value
      }
    }
  }

  // PHASE 2 (M10): the genuine double-fire guard. `completed` stays
  // true for the whole action and is reset only after the action
  // returns (or throws), so a second slide / fling / accessibility
  // activation during the action is blocked instead of re-firing it.
  fun fireSlideComplete() {
    if (!enabled || completed) return
    completed = true
    try {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
      onSlideComplete()
    } finally {
      completed = false
    }
  }

  Column(modifier = modifier.fillMaxWidth()) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(trackHeight)
        .onSizeChanged { trackWidthPx = it.width.toFloat() }
        // Half the track height — the track is a pill, so the radius is
        // derived from its height, not a spacing token.
        .clip(RoundedCornerShape(trackHeight / 2))
        .background(trackColor)
        // FIX 2026-11-18 (report #45): tapping the track/label is not
        // a dead tap — when enabled it nudges the thumb ~35% toward
        // the end and shows the "Drag the circle" hint briefly, then
        // slides back. (When disabled the reason line below already
        // explains why, so a tap does nothing extra.)
        .clickable(enabled = enabled) {
          if (maxOffset > 0f) {
            showTapHint = true
            animateOffsetTo(maxOffset * 0.35f)
            scope.launch {
              delay(1500)
              showTapHint = false
              animateOffsetTo(0f)
            }
          }
        }
        // PHASE 2 (H8): the slide is every tool's only primary action,
        // so it must be usable without dragging. TalkBack / switch
        // access get a content description plus a custom action that
        // runs the tool directly (the tap above only nudges the thumb
        // and never completes, so the plain click action is not enough).
        // The visual slide is unchanged and stays the primary action.
        .semantics {
          contentDescription = label
          customActions = listOf(
            CustomAccessibilityAction(label) {
              fireSlideComplete()
              true
            }
          )
        },
      contentAlignment = Alignment.Center
    ) {
      // Green fill that grows behind the thumb as it slides.
      if (enabled && dragOffset > 0f) {
        Box(
          Modifier
            .align(endAlignment)
            .size(
              width = with(density) { (dragOffset + thumbPx + edgePaddingPx * 2).toDp() },
              height = trackHeight
            )
            .background(ChamiColors.brightGreen.copy(alpha = 0.25f))
        )
      }
      Text(
        text = label,
        fontSize = Ds.BUTTON_SP.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = (Ds.BUTTON_SP * 1.4).sp,
        color = labelColor,
        // UI OVERHAUL 2026-11-18: the label used to sit under the
        // sliding thumb and read as overlapped text — it now fades
        // (GPU alpha only) as the thumb covers it.
        modifier = Modifier.graphicsLayer {
          alpha = 1f - (dragOffset / maxOffset.coerceAtLeast(1f)) * 0.75f
        }
      )
      Box(
        modifier = Modifier
          .align(endAlignment)
          .padding(start = Ds.s4)
          // PHASE 2 (H8): in RTL the thumb starts at the end side and
          // the offset mirrors, so it travels toward the start side.
          .offset { IntOffset((if (isRtl) -dragOffset else dragOffset).roundToInt(), 0) }
          .size(thumbSize)
          .clip(CircleShape)
          .background(thumbColor)
          .pointerInput(enabled, maxOffset, isRtl) {
            if (!enabled) return@pointerInput
            detectDragGestures(
              onDragStart = {
                lastTickMilestone = 0
                try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
              },
              onDragEnd = {
                if (maxOffset > 0f && dragOffset >= 0.9f * maxOffset) {
                  fireSlideComplete()
                }
                animateOffsetTo(0f)
              },
              onDragCancel = {
                animateOffsetTo(0f)
              },
              onDrag = { change, dragAmount ->
                change.consume()
                // Direct state update — no coroutine per drag event.
                // In RTL, dragging toward the start side (negative x)
                // is progress; in LTR it is the other way round.
                val progress = if (isRtl) -dragAmount.x else dragAmount.x
                dragOffset = (dragOffset + progress).coerceIn(0f, maxOffset)
                if (maxOffset > 0f) {
                  val pct = (dragOffset / maxOffset * 100).toInt()
                  val milestone = when {
                    pct >= 75 -> 75
                    pct >= 50 -> 50
                    pct >= 25 -> 25
                    else -> 0
                  }
                  if (milestone > lastTickMilestone) {
                    lastTickMilestone = milestone
                    try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                  }
                }
              }
            )
          },
        contentAlignment = Alignment.Center
      ) {
        Icon(
          // PHASE 2 (H8): the chevron points in the slide direction.
          if (isRtl) Icons.Filled.ArrowBack else Icons.Filled.ArrowForward,
          // The track's semantics carry the label; the icon is decorative.
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(24.dp)
        )
      }
    }
    if (showTapHint && enabled) {
      Spacer(Modifier.height(Ds.s4))
      Text(
        text = "Drag the circle",
        fontSize = Ds.HINT_SP.sp,
        lineHeight = (Ds.HINT_SP * 1.4).sp,
        color = ChamiColors.deepGreen
      )
    }
    if (!enabled) {
      Spacer(Modifier.height(Ds.s4))
      Text(
        text = disabledReason ?: "Not ready yet — finish the steps above first.",
        fontSize = Ds.HINT_SP.sp,
        lineHeight = (Ds.HINT_SP * 1.4).sp,
        color = ChamiColors.secondaryText
      )
    }
  }
}
