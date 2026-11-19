package com.chami.pdfmaker.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.LocaleHelper
import com.chami.pdfmaker.R
import com.chami.pdfmaker.theme.ChamiTheme

// Previous theme: clean sans-serif font, as before the rebuild.
val PixelFont = FontFamily.SansSerif

@Composable
fun ChamiLogo(theme: ChamiTheme, fill: Float, modifier: Modifier = Modifier) {
  val ctx = LocalContext.current
  // Final-audit fix: motion gate — the fill animation uses the
  // default spring when animations are on and snaps instantly to the
  // filled state when animations are off (same animationsEnabled
  // pattern as the tool screens).
  val animOn = animationsEnabled(ctx)
  val animatedFill by animateFloatAsState(
    targetValue = fill,
    animationSpec = if (animOn) spring() else snap(),
    label = "progress"
  )

  val statusText = when {
    fill < 0.35f -> LocaleHelper.t(ctx, "splash_initializing")
    fill < 0.75f -> LocaleHelper.t(ctx, "splash_loading_modules")
    fill < 1.0f -> LocaleHelper.t(ctx, "splash_preparing")
    else -> LocaleHelper.t(ctx, "splash_ready")
  }

  Box(
    modifier = modifier
      .clip(RoundedCornerShape(24.dp))
      .background(theme.card)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(24.dp))
      .padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      // Official Mascot Logo from user upload
      Box(
        Modifier
          .size(92.dp)
          .clip(RoundedCornerShape(22.dp))
          .background(theme.bg)
          .border(1.5.dp, theme.primary.copy(alpha = 0.5f), RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center
      ) {
        Image(
          painter = painterResource(id = R.drawable.chami_logo_asset),
          contentDescription = "Chami Mascot Logo",
          modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp)),
          contentScale = ContentScale.Crop
        )
      }

      Spacer(Modifier.height(16.dp))

      // Wordmark
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "CHAMI",
          fontFamily = PixelFont,
          fontWeight = FontWeight.ExtraBold,
          fontSize = Ds.WORDMARK_SP.sp,
          letterSpacing = 1.sp,
          color = theme.text
        )
        Spacer(Modifier.width(8.dp))
        Box(
          Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(theme.primary)
            .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
          Text(
            "PRO",
            fontFamily = PixelFont,
            fontWeight = FontWeight.Bold,
            fontSize = Ds.HINT_SP.sp,
            color = theme.textOnPrimary
          )
        }
      }

      Spacer(Modifier.height(6.dp))
      // M29 (Phase 2): no scanner claim — Scan was removed and there
      // is no camera. Subtitle is a LocaleHelper key ("logo_subtitle").
      Text(
        LocaleHelper.t(ctx, "logo_subtitle"),
        fontFamily = PixelFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = Ds.HINT_SP.sp,
        letterSpacing = 1.2.sp,
        color = theme.textSecondary
      )

      Spacer(Modifier.height(20.dp))

      // Progress bar
      Box(
        Modifier
          .fillMaxWidth()
          .height(8.dp)
          .clip(CircleShape)
          .background(theme.bg)
          .border(1.dp, theme.cardBorder, CircleShape)
      ) {
        Box(
          Modifier
            .fillMaxWidth(animatedFill.coerceIn(0f, 1f))
            .fillMaxHeight()
            .clip(CircleShape)
            .background(theme.primary)
        )
      }

      Spacer(Modifier.height(10.dp))

      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          statusText,
          fontFamily = PixelFont,
          fontSize = Ds.HINT_SP.sp,
          color = theme.textSecondary
        )
        Text(
          "${(fill * 100).toInt()}%",
          fontFamily = PixelFont,
          fontWeight = FontWeight.Bold,
          fontSize = Ds.HINT_SP.sp,
          // UI OVERHAUL 2026-11-18: primaryDark — 12sp #16A34A on white
          // is ~3:1 and fails WCAG AA (4.5:1); #14532D passes.
          color = theme.primaryDark
        )
      }
    }
  }
}

@Composable
fun ChamiAppIcon(theme: ChamiTheme) {
  Box(
    Modifier
      .size(40.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(theme.card)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(12.dp)),
    contentAlignment = Alignment.Center
  ) {
    Image(
      painter = painterResource(id = R.drawable.chami_logo_asset),
      contentDescription = "Chami Icon",
      modifier = Modifier
        .fillMaxSize()
        .clip(RoundedCornerShape(12.dp)),
      contentScale = ContentScale.Crop
    )
  }
}
