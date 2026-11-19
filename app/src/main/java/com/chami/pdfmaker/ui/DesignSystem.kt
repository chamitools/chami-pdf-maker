package com.chami.pdfmaker.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.theme.ChamiTheme
import kotlinx.coroutines.launch

/** Executive Emerald — exact spec §2 values (the only theme). */
object ChamiColors {
    val brightGreen: Color = Color(0xFF22C55E)
    val darkGreen: Color = Color(0xFF16A34A)
    val deepGreen: Color = Color(0xFF14532D)
    val lightGreen: Color = Color(0xFFDCFCE7)
    val darkModeBg: Color = Color(0xFF052E16)
    val bodyText: Color = Color(0xFF1A2B1F)
    val secondaryText: Color = Color(0xFF5B6B5F)
    val errorRed: Color = Color(0xFFB91C1C)
    val warningAmber: Color = Color(0xFFB45309)
    // Phase 3: named tokens for colours that were literals in several
    // components (border grey, error banner, skeleton) — one source.
    val borderGrey: Color = Color(0xFFCBD5E1)
    val errorBg: Color = Color(0xFFFEE2E2)
    val errorBorder: Color = Color(0xFFFECACA)
    val skeletonBg: Color = Color(0xFFEDF2F7)
}

/**
 * Design system — Professional Spec List §2–§3 (19-page spec, 2026-11-14).
 * Single source of truth for spacing, typography, colours, radii and touch targets.
 * Every screen must use these tokens — no literal dp/sp values outside this file
 * except where a component genuinely needs a one-off size (document it inline).
 *
 * Spacing scale (4dp grid): 4 / 8 / 12 / 16 / 20 / 24 / 32dp only.
 * Screen side margin: 16dp everywhere. Minimum touch target: 48x48dp.
 * Typography: title 20sp bold, section 16sp bold, tile/body 14sp,
 * button 14sp semibold, hint/meta 12sp, nav label 12sp. Line height 1.4x.
 * Motion: one shared easing (Ds.sharedEasing) over Ds.ANIM_MS (250ms);
 * tiles stagger in at Ds.STAGGER_MS (30ms) apart; press-scale is
 * Ds.PRESS_SCALE (0.96). All motion is off when the system animator
 * duration scale is 0 — see [animationsEnabled].
 */
object Ds {
    val s4: Dp = 4.dp
    val s8: Dp = 8.dp
    val s12: Dp = 12.dp
    val s16: Dp = 16.dp
    val s20: Dp = 20.dp
    val s24: Dp = 24.dp
    val s32: Dp = 32.dp

    const val TITLE_SP = 20
    // FIX 2026-11-18 (Fix 7): the ONE font scale — screen title 20,
    // section title 16, body/option 14, caption/hint 12. WORDMARK_SP
    // is the single large display size (brand wordmark, big "+" glyph).
    // No other literal font sizes anywhere in the app.
    const val WORDMARK_SP = 32
    const val SECTION_SP = 16
    const val TILE_SP = 14
    const val BODY_SP = 14
    const val BUTTON_SP = 14
    const val HINT_SP = 12
    const val META_SP = 12
    const val NAV_SP = 12

    val tileRadius: Dp = 12.dp
    // Phase 3: was 10dp — off the 4dp grid. 12dp matches the card radius.
    val buttonRadius: Dp = 12.dp
    val dialogRadius: Dp = 16.dp
    val pillRadius: Dp = 50.dp
    // Phase 3: one icon size per role — no more literal 24dp / 40dp
    // scattered through the components.
    val iconSize: Dp = 24.dp
    val tileIconSize: Dp = 40.dp
    // Phase 3: soft elevation for cards/tiles/header (shadow, not border-only).
    val tileElevation: Dp = 2.dp
    // Phase 3: Home tile enter animation — fade + rise by this much.
    val enterOffsetY: Dp = 16.dp
    // Phase 3: the ONE shared easing and timing for every animation.
    val sharedEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
    const val ANIM_MS = 250
    const val STAGGER_MS = 30
    const val PRESS_SCALE = 0.96f
    val primaryButtonHeight: Dp = 52.dp
    val secondaryButtonHeight: Dp = 48.dp
    val minTouch: Dp = 48.dp
    val slideHeight: Dp = 56.dp
    val slideHandle: Dp = 48.dp
    val appBarHeight: Dp = 56.dp
    val bottomNavHeight: Dp = 64.dp
    val thumbStripHeight: Dp = 72.dp
    val listRowMinHeight: Dp = 56.dp
}

/** Previous theme: clean sans-serif (Executive Emerald).
 *  The embedded pixel font was removed at the user's request —
 *  [PixelFontFamily] is now plain [FontFamily.SansSerif], as it was before
 *  the fix-everything rebuild. No pixel font is embedded anywhere. */
val PixelFontFamily: FontFamily = FontFamily.SansSerif

/** Headings / big numbers in the clean sans-serif font.
 *  Use [PixelFontFamily] only for titles, group headings and the
 *  CHAMI PRO header, at 20sp / 16sp. */
@Composable
fun PixelTitle(text: String, sizeSp: Int = Ds.TITLE_SP, color: Color = ChamiColors.deepGreen) {
    Text(text, fontFamily = PixelFontFamily, fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.4).sp, color = color)
}

@Composable
fun TitleText(
    text: String,
    color: Color = ChamiColors.deepGreen,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE
) {
    Text(text, fontSize = Ds.TITLE_SP.sp, fontWeight = FontWeight.Bold,
        lineHeight = (Ds.TITLE_SP * 1.4).sp, color = color,
        modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun SectionText(text: String, color: Color = ChamiColors.deepGreen) {
    Text(text, fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold,
        lineHeight = (Ds.SECTION_SP * 1.4).sp, color = color)
}

@Composable
fun BodyText(text: String, color: Color = ChamiColors.bodyText) {
    Text(text, fontSize = Ds.BODY_SP.sp, lineHeight = (Ds.BODY_SP * 1.4).sp, color = color)
}

@Composable
fun HintText(text: String, color: Color = ChamiColors.secondaryText) {
    Text(text, fontSize = Ds.HINT_SP.sp, lineHeight = (Ds.HINT_SP * 1.4).sp, color = color)
}

@Composable
fun MetaText(text: String, color: Color = ChamiColors.secondaryText) {
    Text(text, fontSize = Ds.META_SP.sp, lineHeight = (Ds.META_SP * 1.4).sp, color = color)
}

/** Option chip — always 48dp tall (spec §3), fully rounded, dark-green outline
 *  when selected, light-green fill when selected. Replaces Phase6Chip (~27dp). */
@Composable
fun ChamiChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = Ds.minTouch).clickable(onClick = onClick),
        shape = RoundedCornerShape(Ds.pillRadius),
        color = if (selected) ChamiColors.lightGreen else Color.White,
        border = BorderStroke(1.5.dp, if (selected) ChamiColors.darkGreen else ChamiColors.borderGrey),
    ) {
        Box(Modifier.padding(horizontal = Ds.s16, vertical = Ds.s12), contentAlignment = Alignment.Center) {
            Text(label, fontSize = Ds.BODY_SP.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) ChamiColors.deepGreen else ChamiColors.bodyText, textAlign = TextAlign.Center)
        }
    }
}

/** 48dp icon button with a real vector icon (never emoji / text glyphs). */
@Composable
fun ChamiIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = ChamiColors.deepGreen,
) {
    IconButton(onClick = onClick, modifier = modifier.size(Ds.minTouch)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(Ds.iconSize))
    }
}

/** Small chip — 48dp, 12sp. Small toggle chip for option rows.
 *  FIX 2026-11-19 (M27): was 36dp, below the 48dp touch-target rule. */
@Composable
fun ChamiCompactChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = Ds.minTouch).clickable(onClick = onClick),
        shape = RoundedCornerShape(Ds.pillRadius),
        color = if (selected) ChamiColors.lightGreen else Color.White,
        border = BorderStroke(1.dp, if (selected) ChamiColors.darkGreen else ChamiColors.borderGrey),
    ) {
        Box(Modifier.padding(horizontal = Ds.s16, vertical = Ds.s8), contentAlignment = Alignment.Center) {
            Text(label, fontSize = Ds.HINT_SP.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) ChamiColors.deepGreen else ChamiColors.bodyText)
        }
    }
}

/** Primary button — 52dp, theme primary green (0xFF16A34A), white 14sp semibold (spec §5).
 *  FIX 2026-11-19 (L1): was brightGreen (0xFF22C55E); unified on
 *  theme.primary (ChamiColors.darkGreen, 0xFF16A34A) so every primary
 *  button is the same green. */
@Composable
fun ChamiPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(Ds.primaryButtonHeight),
        shape = RoundedCornerShape(Ds.buttonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = ChamiColors.darkGreen, disabledContainerColor = ChamiColors.lightGreen,
            contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.5f)),
    ) { Text(text, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold) }
}

/** Secondary button — 48dp, white fill, dark-green outline (spec §5). */
@Composable
fun ChamiSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, modifier = modifier.height(Ds.secondaryButtonHeight),
        shape = RoundedCornerShape(Ds.buttonRadius),
        border = BorderStroke(1.5.dp, ChamiColors.darkGreen),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ChamiColors.darkGreen),
    ) { Text(text, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold) }
}

/** Circular 48dp icon tile used for the left-corner preview actions
 *  (Crop / Delete / Filter / Adjust) — icon + spoken label, never a glyph.
 *  FIX 2026-11-18 (report #10): a tiny text label sits under the icon
 *  (derived from the description: "Crop", "Delete", "Filter", "Adjust"),
 *  and the white circle now has a visible grey border + shadow, so the
 *  buttons read on a white/light preview instead of floating as bare icons. */
@Composable
fun PreviewActionButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Short label: the words before " —" ("Crop — cut the image" -> "Crop"),
    // otherwise the first word ("Delete this page" -> "Delete").
    val shortLabel = description.substringBefore(" —").split(" ").firstOrNull()?.trim().orEmpty()
    Column(
        modifier = modifier.width(60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(Ds.minTouch)
                .shadow(4.dp, CircleShape)
                .background(Color.White, CircleShape)
                .border(1.dp, ChamiColors.borderGrey, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = description, tint = ChamiColors.deepGreen, modifier = Modifier.size(Ds.iconSize)) }
        Spacer(Modifier.height(Ds.s4))
        Text(
            shortLabel,
            fontSize = Ds.HINT_SP.sp,
            fontWeight = FontWeight.SemiBold,
            color = ChamiColors.deepGreen,
            maxLines = 1,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(Ds.s4))
                .padding(horizontal = Ds.s4)
        )
    }
}

/**
 * UI OVERHAUL 2026-11-18 (Indus Appstore): every dialog in the app
 * goes through here instead of a bare Material3 AlertDialog, so no
 * dialog ever shows the default system look (grey container, 28dp
 * corners). Same slots as AlertDialog — the only differences are the
 * branded container (theme card), the 16dp dialog radius and zero
 * tonal elevation. Behaviour and call-site logic are unchanged.
 */
@Composable
fun ChamiAlertDialog(
    theme: ChamiTheme,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = RoundedCornerShape(Ds.dialogRadius),
        containerColor = theme.card,
        tonalElevation = 0.dp
    )
}

/**
 * UI OVERHAUL 2026-11-18 (Indus Appstore): friendly inline error
 * banner — an icon + plain-language message + optional action — used
 * wherever a failure used to be a bare Toast or a silent no-op, so
 * the user always sees what went wrong and what to do next.
 */
@Composable
fun ChamiErrorBanner(
    theme: ChamiTheme,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.tileRadius))
            .background(ChamiColors.errorBg)
            .border(1.dp, ChamiColors.errorBorder, RoundedCornerShape(Ds.tileRadius))
            .padding(Ds.s16),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = ChamiColors.errorRed,
            modifier = Modifier.size(Ds.iconSize)
        )
        Spacer(Modifier.width(Ds.s12))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.Bold, color = theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Ds.s4))
            Text(message, fontSize = Ds.HINT_SP.sp, lineHeight = (Ds.HINT_SP * 1.4).sp, color = theme.textSecondary)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(Ds.s12))
            ChamiSecondaryButton(text = actionLabel, onClick = onAction, modifier = Modifier.defaultMinSize(minHeight = Ds.minTouch))
        }
    }
}

/**
 * UI OVERHAUL 2026-11-18 (Indus Appstore): skeleton loader shaped
 * like an A4 page — used instead of a bare spinner while a document
 * or page renders, so loading never looks like a blank white screen.
 * The pulse is a GPU alpha animation only (no layout work per frame).
 * Final-audit fix: the pulse honours [animationsEnabled] — with
 * animations off the bars sit at a static mid grey (alpha 0.5) and no
 * animation runs. Same signature, so existing callers are unchanged.
 */
@Composable
fun PageSkeletonLoader(modifier: Modifier = Modifier) {
    // Same gate pattern as ChamiScreenTransition below: one Animatable
    // driven by a LaunchedEffect, so the composable calls stay
    // unconditional and only the motion is gated.
    val animOn = animationsEnabled(LocalContext.current)
    val pulseAnim = remember { Animatable(0.5f) }
    LaunchedEffect(animOn) {
        if (animOn) {
            while (true) {
                pulseAnim.animateTo(0.7f, tween(900))
                pulseAnim.animateTo(0.35f, tween(900))
            }
        } else {
            pulseAnim.snapTo(0.5f)
        }
    }
    val pulse = pulseAnim.value
    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f / 1.414f)
            .clip(RoundedCornerShape(Ds.s8))
            .background(ChamiColors.skeletonBg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(Ds.s24))
        Box(
            Modifier
                .fillMaxWidth(0.7f)
                .height(Ds.s12)
                .graphicsLayer { alpha = pulse }
                .clip(RoundedCornerShape(Ds.s4))
                .background(ChamiColors.borderGrey)
        )
        Spacer(Modifier.height(Ds.s12))
        repeat(4) {
            Box(
                Modifier
                    .fillMaxWidth(0.85f)
                    .height(Ds.s8)
                    .graphicsLayer { alpha = pulse }
                    .clip(RoundedCornerShape(Ds.s4))
                    .background(ChamiColors.borderGrey)
            )
            Spacer(Modifier.height(Ds.s8))
        }
    }
}

/**
 * Phase 3 — motion gate. True unless the system "remove animations"
 * setting is on (animator duration scale 0). Every Phase 3 animation
 * honours this; with it off, content appears instantly and no motion
 * runs. No permission is needed to read Settings.Global.
 */
fun animationsEnabled(context: Context): Boolean {
    return try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) != 0f
    } catch (_: Exception) {
        true
    }
}

/**
 * Phase 3 — press-scale effect for tiles and buttons. The pressed
 * component shrinks to Ds.PRESS_SCALE (0.96) over 150ms on the shared
 * easing and springs back on release. The caller owns the
 * [MutableInteractionSource] and passes the SAME instance to its
 * clickable, so the press state is shared. When [enabled] is false
 * (animations off) the scale stays 1 — no motion at all. GPU
 * scale only; input is never blocked while it runs.
 */
@Composable
fun Modifier.pressScale(enabled: Boolean, interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) Ds.PRESS_SCALE else 1f,
        animationSpec = tween(150, easing = Ds.sharedEasing),
        label = "pressScale"
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Phase 3 — shared screen transition. Enter-only fade + a slight
 * scale-up over Ds.ANIM_MS (250ms) on Ds.sharedEasing, using
 * animation-core only (no extra dependency).
 *
 * Enter-only is deliberate: the outgoing screen is removed at once,
 * so the two screens are never composed together — HomeJourneyTest
 * asserts CHAMI appears exactly once, and a crossfade would briefly
 * show it twice. When [enabled] is false the new screen appears
 * instantly. The first composition also fades in (app launch).
 */
@Composable
fun <T> ChamiScreenTransition(
    targetState: T,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    val alpha = remember { Animatable(1f) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(targetState) {
        if (enabled) {
            alpha.snapTo(0f)
            scale.snapTo(0.98f)
            launch { alpha.animateTo(1f, tween(Ds.ANIM_MS, easing = Ds.sharedEasing)) }
            launch { scale.animateTo(1f, tween(Ds.ANIM_MS, easing = Ds.sharedEasing)) }
        } else {
            alpha.snapTo(1f)
            scale.snapTo(1f)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value; scaleX = scale.value; scaleY = scale.value }
    ) { content(targetState) }
}
