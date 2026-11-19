package com.chami.pdfmaker.theme

import android.content.Context
import androidx.compose.ui.graphics.Color

data class ChamiTheme(
  val id: String,
  val name: String,
  val subtitle: String,
  val bg: Color,
  val card: Color,
  val cardBorder: Color,
  val primary: Color,
  val primaryDark: Color,
  val text: Color,
  val textSecondary: Color,
  val textOnPrimary: Color,
  val accent: Color,
  val isDark: Boolean
)

object ChamiThemes {
  val EMERALD_PRO = ChamiTheme(
    id = "emerald_pro",
    name = "Executive Emerald",
    subtitle = "Clean light slate with forest emerald accents",
    bg = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE2E8F0),
    primary = Color(0xFF16A34A),
    primaryDark = Color(0xFF14532D),
    text = Color(0xFF1A2B1F),
    textSecondary = Color(0xFF5B6B5F),
    textOnPrimary = Color.White,
    accent = Color(0xFF22C55E),
    isDark = false
  )

  // L8 (Phase 2): the dead Obsidian AMOLED theme and the ALL list
  // were removed — Executive Emerald is the only theme. Any id,
  // including legacy dark-theme ids, maps to Emerald Pro.
  fun byId(id: String): ChamiTheme = EMERALD_PRO
}

// Phase 6 — THEME LOCK: Executive Emerald is the only theme.
// current() always returns emerald_pro no matter what is saved, and
// set() forces emerald_pro instead of storing the requested id.
// The Obsidian theme object was deleted in Phase 2 (L8): no code
// referenced it any more, and it can no longer be selected.
class ThemeManager(ctx: Context) {
  private val p = ctx.getSharedPreferences("chami_theme", Context.MODE_PRIVATE)

  fun current(): ChamiTheme = ChamiThemes.EMERALD_PRO

  fun set(id: String) {
    // No-op by design (Phase 6): theme is locked to Executive Emerald.
    p.edit().putString("theme_id", "emerald_pro").apply()
  }

  // Phase 5: haptic is ALWAYS ON — there is no toggle in Settings any
  // more, so this always returns true (a user who turned it off in an
  // older version gets haptic back). setHaptic is kept only so old call
  // sites still compile; it intentionally does nothing now.
  fun hapticOn(): Boolean = true
  fun setHaptic(v: Boolean) {
    // No-op by design (Phase 5): haptic cannot be turned off.
  }

}
