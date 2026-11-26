package com.chami.pdfmaker

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.theme.*
import com.chami.pdfmaker.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ToolItem(val id: String, val title: String, val iconRes: Int, val category: String = "CORE", val titleKey: String = "")

class MainActivity : ComponentActivity() {
  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    // Screenshots / screen recording are always blocked. No toggle, no
    // option in the app — FLAG_SECURE is always on.
    window.setFlags(
      WindowManager.LayoutParams.FLAG_SECURE,
      WindowManager.LayoutParams.FLAG_SECURE
    )
    // ALL ADS REMOVED (user decision 2026-11-13) — no interstitial, no
    // banner, no AdMob at all in this build.
    // THEME LOCK: Executive Emerald (emerald_pro) is the only theme —
    // there is no theme picker any more. Force it here, before
    // ThemeManager is read anywhere else.
    val tm = ThemeManager(this)
    if (tm.current().id != "emerald_pro") tm.set("emerald_pro")
    LocaleHelper.applyLocale(this)
    val lockPrefs = getSharedPreferences(LocaleHelper.PREFS, Context.MODE_PRIVATE)
    val appLockOn = lockPrefs.getBoolean("app_lock", false)
    val savedPinStored = lockPrefs.getString("app_pin", "") ?: ""
    setContent {
      val ctx = LocalContext.current
      var theme by remember { mutableStateOf(tm.current()) }
      // M12 (Phase 2): navigation state is rememberSaveable, so a
      // language change (activity recreation) no longer kicks the user
      // out of the tool they were in. String and java.io.File
      // (Serializable) save directly; the Uri uses the String-based
      // rememberSaveableUri() from the ui package.
      var activeTool by rememberSaveable { mutableStateOf<String?>(null) }
      var currentTab by rememberSaveable { mutableStateOf("Home") }
      // File to pre-select in Split / Merge / Compress (their
      // initialPdfUri, consumed once). Nothing sets it now that the
      // Reader hand-off is gone (Reader removed 2026-11-19).
      var pendingToolFileUri by rememberSaveableUri()
      val haptic = LocalHapticFeedback.current
      val scope = rememberCoroutineScope()
      val homeGridState = rememberLazyGridState()
      // Phase 3 polish: Home tiles stagger in once (first composition),
      // and all motion honours the system "remove animations" setting.
      // enteredTiles remembers which tiles already played, so a tile
      // never re-animates when it scrolls back into view.
      // PHASE 2: animations are always on (no toggle, no system gate).
      val animationsOn = remember { animationsEnabled(ctx) }
      val enteredTiles = remember { mutableStateOf(setOf<String>()) }
      // History keeps its scroll position across tab switches: the list
      // state lives here, not inside HistoryScreen.
      val historyListState = remember { androidx.compose.foundation.lazy.LazyListState() }
      // App Lock: if a PIN is set, Home stays locked until it is entered.
      // A stored PIN is a "sha256:…" hash (see hashPin); a legacy plain
      // 4-digit PIN is still accepted once and then re-hashed on unlock.
      var unlocked by remember { mutableStateOf(!appLockOn || savedPinStored.isBlank()) }
      var pinInput by remember { mutableStateOf("") }
      var pinError by remember { mutableStateOf(false) }
      var showForgotPin by remember { mutableStateOf(false) }

      fun vibrate() {
        if (tm.hapticOn()) {
          try {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
          } catch (_: Exception) {}
        }
      }

      // Home grouping — Option A (user decision 2026-11-14), replacing
      // the rejected Simple Tools (7) / Smart Tools (4) split.
      // FIX 2026-11-18: Scan to PDF REMOVED (user decision) — the
      // "Make a PDF" group would have been left with a single tile, so
      // it is gone too: Images to PDF moves into "Images & Reading",
      // which now fills a full 3-tile row. 9 tools.
      // FIX 2026-11-18 (user feedback on Run #150): Images to PDF is
      // the main tool — "Images & Reading" goes first, so Images to PDF
      // is the very first tile on Home. Group order below is deliberate.
      val fixPdfTools = listOf(
        ToolItem("merge", "Merge PDF", R.drawable.tool_ic_05_merge_pdf, "EDIT", "merge_pdf"),
        ToolItem("split", "Split PDF", R.drawable.tool_ic_06_split_pdf, "EDIT", "split_pdf"),
        ToolItem("protect", "Protect PDF", R.drawable.tool_ic_03_protect_pdf, "SECURITY", "protect_pdf"),
        ToolItem("compress", "Compress KB", R.drawable.tool_ic_04_compress_kb, "OPTIMIZE", "compress_kb"),
      )
      val convertTools = listOf(
        ToolItem("pdf_img", "PDF to Images", R.drawable.tool_ic_07_pdf_to_images, "CONVERT", "pdf_to_images"),
        ToolItem("convert", "Format Convert", R.drawable.tool_ic_14_format_convert, "PHOTO", "format_convert"),
        ToolItem("ocr", "OCR Text", R.drawable.tool_ic_11_ocr_text, "EXTRACT", "ocr"),
      )
      val imagesTools = listOf(
        ToolItem("img_pdf", "Images to PDF", R.drawable.tool_ic_01_images_to_pdf, "CREATE", "images_to_pdf"),
        ToolItem("resize", "Image Resizer", R.drawable.tool_ic_09_image_resizer, "PHOTO", "image_resizer"),
      )
      // FIX 2026-11-18 (report #42): group headings are LocaleHelper
      // keys, not literal English — "group_images" and "group_fix_pdf"
      // are in the English map (other languages fall back to English);
      // "convert" already exists in every language.
      val toolGroups = listOf(
        "group_images" to imagesTools,
        "group_fix_pdf" to fixPdfTools,
        "convert" to convertTools,
      )

      @Composable
      fun ToolTile(t: ToolItem, enterDelayMs: Int, animateIn: Boolean, onEntered: (String) -> Unit) {
        val interactionSource = remember { MutableInteractionSource() }
        // Phase 3: staggered fade-up on first composition only. One
        // progress value drives both alpha and rise, on the shared
        // easing; tiles that already played (or animations off) start
        // at full opacity. Input is never blocked while it runs.
        val enterOffsetPx = with(LocalDensity.current) { Ds.enterOffsetY.toPx() }
        val enterProgress = remember { Animatable(if (animateIn) 0f else 1f) }
        LaunchedEffect(Unit) {
          if (animateIn) {
            delay(enterDelayMs.toLong())
            enterProgress.animateTo(1f, tween(Ds.ANIM_MS, easing = Ds.sharedEasing))
            onEntered(t.id)
          }
        }
        Column(
          Modifier
            .aspectRatio(1f)
            .shadow(Ds.tileElevation, RoundedCornerShape(Ds.tileRadius))
            .clip(RoundedCornerShape(Ds.tileRadius))
            .background(theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
            .pressScale(animationsOn, interactionSource)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current) {
              vibrate()
              activeTool = t.id
            }
            .graphicsLayer {
              alpha = enterProgress.value
              translationY = (1f - enterProgress.value) * enterOffsetPx
            }
            .padding(Ds.s8),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Image(
            painter = painterResource(id = t.iconRes),
            contentDescription = t.title,
            modifier = Modifier.size(Ds.tileIconSize),
            contentScale = ContentScale.Fit
          )
          Spacer(Modifier.height(Ds.s8))
          // FIX 2026-11-18 (report #42): tile titles use the translated
          // tool-name keys; the English title stays as the fallback
          // when a key has no translation.
          Text(
            if (t.titleKey.isNotBlank()) LocaleHelper.t(ctx, t.titleKey) else t.title,
            fontSize = Ds.TILE_SP.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = (Ds.TILE_SP * 1.4).sp,
            color = theme.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
          )
        }
      }

      // Phase 3: section header with a small green accent bar — the
      // heading text, order and translations are unchanged.
      @Composable
      fun SectionHeader(heading: String) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(top = Ds.s24, bottom = Ds.s12)
        ) {
          Box(
            Modifier
              .width(Ds.s4)
              .height(Ds.s20)
              .clip(RoundedCornerShape(Ds.pillRadius))
              .background(theme.primary)
          )
          Spacer(Modifier.width(Ds.s8))
          Text(
            LocaleHelper.t(ctx, heading),
            fontFamily = PixelFont,
            fontWeight = FontWeight.Bold,
            fontSize = Ds.SECTION_SP.sp,
            lineHeight = (Ds.SECTION_SP * 1.4).sp,
            color = theme.primaryDark
          )
        }
      }

      Surface(modifier = Modifier.fillMaxSize(), color = theme.bg) {
        if (!unlocked) {
          // App Lock PIN overlay — shown before any Home content.
          Column(
            Modifier
              .fillMaxSize()
              .background(theme.bg)
              .statusBarsPadding()
              .navigationBarsPadding()
              .padding(Ds.s24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            Icon(
              Icons.Filled.Lock,
              contentDescription = LocaleHelper.t(ctx, "app_lock"),
              tint = theme.primary,
              modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(Ds.s16))
            Text(
              LocaleHelper.t(ctx, "app_lock"),
              fontFamily = PixelFont,
              fontWeight = FontWeight.ExtraBold,
              fontSize = Ds.TITLE_SP.sp,
              lineHeight = (Ds.TITLE_SP * 1.4).sp,
              color = theme.text
            )
            Spacer(Modifier.height(Ds.s8))
            Text(
              LocaleHelper.t(ctx, "pin_enter_prompt"),
              fontSize = Ds.HINT_SP.sp,
              lineHeight = (Ds.HINT_SP * 1.4).sp,
              color = theme.textSecondary
            )
            Spacer(Modifier.height(Ds.s20))
            OutlinedTextField(
              value = pinInput,
              onValueChange = {
                pinInput = it.filter { c -> c.isDigit() }.take(4)
                pinError = false
              },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
              isError = pinError,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(Ds.buttonRadius)
            )
            if (pinError) {
              Spacer(Modifier.height(Ds.s8))
              Text(
                LocaleHelper.t(ctx, "pin_wrong_try_again"),
                fontSize = Ds.HINT_SP.sp,
                color = MaterialTheme.colorScheme.error
              )
            }
            Spacer(Modifier.height(Ds.s16))
            Button(
              onClick = {
                if (pinMatches(savedPinStored, pinInput)) {
                  // Migrate legacy plain-text and unsalted-hash PINs to salted hash.
                  if (!savedPinStored.startsWith("sha256:") || isLegacyPinHash(savedPinStored)) {
                    lockPrefs.edit().putString("app_pin", hashPin(pinInput)).apply()
                  }
                  unlocked = true
                } else {
                  pinError = true
                  pinInput = ""
                  Toast.makeText(ctx, LocaleHelper.t(ctx, "pin_wrong"), Toast.LENGTH_SHORT).show()
                }
              },
              enabled = pinInput.length == 4,
              colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
              shape = RoundedCornerShape(Ds.buttonRadius),
              modifier = Modifier.fillMaxWidth().height(Ds.secondaryButtonHeight)
            ) {
              Text(
                LocaleHelper.t(ctx, "pin_unlock"),
                fontFamily = PixelFont,
                color = theme.textOnPrimary,
                fontSize = Ds.BODY_SP.sp,
                fontWeight = FontWeight.Bold
              )
            }
            Spacer(Modifier.height(Ds.s8))
            TextButton(
              onClick = { showForgotPin = true },
              modifier = Modifier.defaultMinSize(minHeight = Ds.minTouch)
            ) {
              Text(
                LocaleHelper.t(ctx, "pin_forgot"),
                fontSize = Ds.HINT_SP.sp,
                color = theme.textSecondary
              )
            }
            // Forgot PIN: there is NO one-tap unlock. Resetting a
            // forgotten PIN requires reinstalling the app (stated at
            // setup in Settings). This dialog only says so.
            if (showForgotPin) {
              ChamiAlertDialog(theme,
                onDismissRequest = { showForgotPin = false },
                title = {
                  Text(
                    LocaleHelper.t(ctx, "pin_forgot"),
                    fontFamily = PixelFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = Ds.SECTION_SP.sp
                  )
                },
                text = {
                  Text(
                    LocaleHelper.t(ctx, "pin_forgot_body"),
                    fontSize = Ds.BODY_SP.sp,
                    lineHeight = (Ds.BODY_SP * 1.4).sp
                  )
                },
                confirmButton = {
                  TextButton(onClick = { showForgotPin = false }) {
                    Text(LocaleHelper.t(ctx, "close"), fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp)
                  }
                }
              )
            }
          }
        } else if (activeTool != null) {
          // Phase 3: tool screens fade in on the shared easing; the
          // transition is enter-only (see ChamiScreenTransition).
          ChamiScreenTransition(targetState = activeTool, enabled = animationsOn) { tool ->
          // 9 tools only. Dead routes are NOT wired: text_pdf (Word
          // editor), editor (PDF Editor), Passport Photo and PDF to Text
          // screens exist as dead code elsewhere but are never opened
          // from here — an unknown id just returns to Home.
          when (tool) {
            // Done "Open" on a PDF uses the system viewer (the inbuilt
            // Reader was removed 2026-11-19, so onOpenPdfInReader is gone).
            "img_pdf" -> ImagesToPdfScreen(theme) { activeTool = null }
            "protect" -> ProtectPdfScreen(theme) { activeTool = null }
            "pdf_img" -> PdfToImagesScreen(theme) { activeTool = null }
            "split" -> SplitPdfScreen(theme, initialPdfUri = pendingToolFileUri, onInitialPdfConsumed = { pendingToolFileUri = null }) { activeTool = null }
            "merge" -> MergePdfScreen(theme, initialPdfUri = pendingToolFileUri, onInitialPdfConsumed = { pendingToolFileUri = null }) { activeTool = null }
            "compress" -> CompressKbScreen(theme, initialPdfUri = pendingToolFileUri, onInitialPdfConsumed = { pendingToolFileUri = null }) { activeTool = null }
            "resize" -> ResizeConvertScreen(theme, title = "Image Resizer") { activeTool = null }
            "convert" -> ResizeConvertScreen(theme, title = "Format Convert") { activeTool = null }
            "ocr" -> OcrScreen(theme) { activeTool = null }
            else -> { activeTool = null }
          }
          }
        } else {
          BackHandler(enabled = currentTab != "Home") {
            vibrate()
            currentTab = "Home"
          }

          Box(
            Modifier
              .fillMaxSize()
              .background(theme.bg)
          ) {
            // PHASE 2 + 6: Particles live wallpaper on Home, History and
            // Settings. It is composed before the Column, so every tile,
            // label and button is drawn on top of it. Home keeps full
            // strength; History (long list) is the faintest.
            ParticlesBackground(
              fade = when (currentTab) {
                "Home" -> 1f
                "History" -> 0.45f
                else -> 0.6f
              }
            )
            Column(
              Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Ds.s16, vertical = Ds.s12)
            ) {
              // Header — CHAMI PRO kept (user confirmed). Nothing else:
              // no OFFLINE badge, no feature count, no subtext.
              // Phase 3: the same content in a soft card so the header
              // reads as one friendly unit with the tiles below it.
              Row(
                Modifier
                  .fillMaxWidth()
                  .shadow(Ds.tileElevation, RoundedCornerShape(Ds.tileRadius))
                  .clip(RoundedCornerShape(Ds.tileRadius))
                  .background(theme.card)
                  .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
                  .padding(Ds.s12),
                verticalAlignment = Alignment.CenterVertically
              ) {
                ChamiAppIcon(theme)
                Spacer(Modifier.width(Ds.s12))
                Column {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                      "CHAMI",
                      fontFamily = PixelFont,
                      fontWeight = FontWeight.ExtraBold,
                      fontSize = Ds.TITLE_SP.sp,
                      lineHeight = (Ds.TITLE_SP * 1.4).sp,
                      color = theme.text
                    )
                    Spacer(Modifier.width(Ds.s8))
                    Box(
                      Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(theme.primary)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
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
                  Text(
                    "Professional PDF Toolkit",
                    fontSize = Ds.HINT_SP.sp,
                    lineHeight = (Ds.HINT_SP * 1.4).sp,
                    color = theme.textSecondary
                  )
                }
              }

              // Body based on selected bottom tab
              // Phase 3: tab switches fade in on the shared easing.
              Box(Modifier.weight(1f)) {
                ChamiScreenTransition(targetState = currentTab, enabled = animationsOn) { tab ->
                when (tab) {
                  "History" -> HistoryScreen(
                    theme,
                    listState = historyListState
                  )
                  "Settings" -> SettingsScreen(theme, tm) { theme = it }
                  else -> {
                    LazyVerticalGrid(
                      columns = GridCells.Fixed(3),
                      state = homeGridState,
                      horizontalArrangement = Arrangement.spacedBy(Ds.s12),
                      verticalArrangement = Arrangement.spacedBy(Ds.s12),
                      contentPadding = PaddingValues(bottom = Ds.s24),
                      modifier = Modifier.fillMaxSize()
                    ) {
                      // Phase 3: stagger delay from the tile's position
                      // in the flat 10-tile order (30ms apart).
                      val flatTools = toolGroups.flatMap { it.second }
                      toolGroups.forEach { (heading, tools) ->
                        item(span = { GridItemSpan(3) }) { SectionHeader(heading) }
                        items(tools, key = { it.id }) { t ->
                          ToolTile(
                            t,
                            enterDelayMs = flatTools.indexOf(t) * Ds.STAGGER_MS,
                            animateIn = animationsOn && !enteredTiles.value.contains(t.id),
                            onEntered = { enteredTiles.value += it }
                          )
                        }
                      }
                    }
                  }
                }
                }
              }

              Spacer(Modifier.height(Ds.s12))

              // Bottom navigation — real Material vector icons, never
              // emoji. Home is a proper house, NOT a lightning bolt.
              // UI OVERHAUL 2026-11-18: the bar is exactly 64dp and the
              // gesture-navigation inset sits BELOW it (on this wrapper),
              // so the inset never squeezes the icons/labels inside the
              // bar on gesture-nav phones. Labels always visible (12sp,
              // Ds.NAV_SP), selected dark green / unselected grey.
              Box(
                Modifier
                  .fillMaxWidth()
                  .navigationBarsPadding()
              ) {
                Row(
                  Modifier
                    .fillMaxWidth()
                    .height(Ds.bottomNavHeight)
                    .clip(RoundedCornerShape(Ds.s16))
                  .background(theme.card)
                  .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.s16)),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
              ) {
                val navItems = listOf(
                  Triple("Home", Icons.Filled.Home, LocaleHelper.t(ctx, "home")),
                  Triple("History", Icons.Filled.History, LocaleHelper.t(ctx, "history")),
                  Triple("Settings", Icons.Filled.Settings, LocaleHelper.t(ctx, "settings"))
                )
                navItems.forEach { (tab, icon, label) ->
                  // Phase 3: key() so each item's press interaction
                  // source belongs to that tab, not its loop position.
                  key(tab) {
                  val navInteraction = remember { MutableInteractionSource() }
                  val isSelected = currentTab == tab
                  Column(
                    Modifier
                      .weight(1f)
                      .fillMaxHeight()
                      .clip(RoundedCornerShape(Ds.s12))
                      .background(if (isSelected) theme.primary.copy(alpha = 0.15f) else Color.Transparent)
                      .pressScale(animationsOn, navInteraction)
                      .clickable(interactionSource = navInteraction, indication = LocalIndication.current) {
                        vibrate()
                        if (tab == "Home" && currentTab == "Home") {
                          scope.launch { homeGridState.scrollToItem(0) }
                        } else {
                          currentTab = tab
                        }
                      }
                      .padding(vertical = Ds.s8),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                  ) {
                    Icon(
                      icon,
                      contentDescription = label,
                      tint = if (isSelected) theme.primary else theme.textSecondary,
                      modifier = Modifier.size(Ds.iconSize)
                    )
                    Spacer(Modifier.height(Ds.s4))
                    Text(
                      text = label,
                      color = if (isSelected) theme.primary else theme.textSecondary,
                      fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                      fontSize = Ds.NAV_SP.sp,
                      lineHeight = (Ds.NAV_SP * 1.4).sp,
                      maxLines = 1
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
    }
  }
}
