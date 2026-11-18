package com.chami.pdfmaker

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.theme.*
import com.chami.pdfmaker.ui.*
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
      var activeTool by remember { mutableStateOf<String?>(null) }
      var currentTab by remember { mutableStateOf("Home") }
      var readerFile by remember { mutableStateOf<java.io.File?>(null) }
      // FIX 2026-11-18 (report #27): the file the Reader hands to
      // Split / Merge / Compress, so the target tool opens with it
      // already selected instead of asking the user to re-pick it.
      var pendingToolFileUri by remember { mutableStateOf<Uri?>(null) }
      val haptic = LocalHapticFeedback.current
      val scope = rememberCoroutineScope()
      val homeGridState = rememberLazyGridState()
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
      // which now fills a full 3-tile row. 10 tools.
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
      val imagesReadingTools = listOf(
        ToolItem("img_pdf", "Images to PDF", R.drawable.tool_ic_01_images_to_pdf, "CREATE", "images_to_pdf"),
        ToolItem("resize", "Image Resizer", R.drawable.tool_ic_09_image_resizer, "PHOTO", "image_resizer"),
        ToolItem("reader", "PDF Reader", R.drawable.tool_ic_10_pdf_reader, "VIEW", "pdf_reader"),
      )
      // FIX 2026-11-18 (report #42): group headings are LocaleHelper
      // keys, not literal English — "group_images_reading" and
      // "group_fix_pdf" are added to the English map by the Locale
      // worker; "convert" already exists in every language.
      val toolGroups = listOf(
        "group_images_reading" to imagesReadingTools,
        "group_fix_pdf" to fixPdfTools,
        "convert" to convertTools,
      )

      @Composable
      fun ToolTile(t: ToolItem) {
        Column(
          Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Ds.tileRadius))
            .background(theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
            .clickable {
              vibrate()
              activeTool = t.id
            }
            .padding(Ds.s8),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Image(
            painter = painterResource(id = t.iconRes),
            contentDescription = t.title,
            modifier = Modifier.size(40.dp),
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
            textAlign = TextAlign.Center
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
              .padding(Ds.s24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            Icon(
              Icons.Filled.Lock,
              contentDescription = "Locked",
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
              "Enter your 4-digit PIN",
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
                "Wrong PIN — try again",
                fontSize = Ds.HINT_SP.sp,
                color = MaterialTheme.colorScheme.error
              )
            }
            Spacer(Modifier.height(Ds.s16))
            Button(
              onClick = {
                if (pinMatches(savedPinStored, pinInput)) {
                  // Migrate a legacy plain-text PIN to its SHA-256 hash.
                  if (!savedPinStored.startsWith("sha256:")) {
                    lockPrefs.edit().putString("app_pin", hashPin(pinInput)).apply()
                  }
                  unlocked = true
                } else {
                  pinError = true
                  pinInput = ""
                  Toast.makeText(ctx, "Wrong PIN", Toast.LENGTH_SHORT).show()
                }
              },
              enabled = pinInput.length == 4,
              colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
              shape = RoundedCornerShape(Ds.buttonRadius),
              modifier = Modifier.fillMaxWidth().height(Ds.secondaryButtonHeight)
            ) {
              Text(
                "UNLOCK",
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
                "Forgot PIN?",
                fontSize = Ds.HINT_SP.sp,
                color = theme.textSecondary
              )
            }
            // Forgot PIN: there is NO one-tap unlock. Resetting a
            // forgotten PIN requires reinstalling the app (stated at
            // setup in Settings). This dialog only says so.
            if (showForgotPin) {
              AlertDialog(
                onDismissRequest = { showForgotPin = false },
                title = {
                  Text(
                    "Forgot PIN?",
                    fontFamily = PixelFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = Ds.SECTION_SP.sp
                  )
                },
                text = {
                  Text(
                    "Resetting a forgotten PIN requires reinstalling the app. " +
                      "There is no other way to remove App Lock.",
                    fontSize = Ds.BODY_SP.sp,
                    lineHeight = (Ds.BODY_SP * 1.4).sp
                  )
                },
                confirmButton = {
                  TextButton(onClick = { showForgotPin = false }) {
                    Text("CLOSE", fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp)
                  }
                }
              )
            }
          }
        } else if (activeTool != null) {
          // 10 tools only. Dead routes are NOT wired: text_pdf (Word
          // editor), editor (PDF Editor), Passport Photo and PDF to Text
          // screens exist as dead code elsewhere but are never opened
          // from here — an unknown id just returns to Home.
          when (activeTool) {
            // FIX 2026-11-18 (report #4): every tool screen gets
            // onOpenPdfInReader, so a Done "Open" on a PDF goes to the
            // inbuilt Reader instead of an external viewer. Reader
            // hand-off (report #27): onOpenTool carries the current
            // PDF's Uri into Split / Merge / Compress via
            // pendingToolFileUri, consumed once by the target tool.
            "img_pdf" -> ImagesToPdfScreen(theme, onOpenPdfInReader = { f -> readerFile = f; activeTool = "reader" }) { activeTool = null }
            "protect" -> ProtectPdfScreen(theme, onOpenPdfInReader = { f -> readerFile = f; activeTool = "reader" }) { activeTool = null }
            "pdf_img" -> PdfToImagesScreen(theme, onOpenPdfInReader = { f -> readerFile = f; activeTool = "reader" }) { activeTool = null }
            "split" -> SplitPdfScreen(theme, initialPdfUri = pendingToolFileUri, onInitialPdfConsumed = { pendingToolFileUri = null }, onOpenPdfInReader = { f -> readerFile = f; activeTool = "reader" }) { activeTool = null }
            "merge" -> MergePdfScreen(theme, initialPdfUri = pendingToolFileUri, onInitialPdfConsumed = { pendingToolFileUri = null }, onOpenPdfInReader = { f -> readerFile = f; activeTool = "reader" }) { activeTool = null }
            "compress" -> CompressKbScreen(theme, initialPdfUri = pendingToolFileUri, onInitialPdfConsumed = { pendingToolFileUri = null }, onOpenPdfInReader = { f -> readerFile = f; activeTool = "reader" }) { activeTool = null }
            "resize" -> ResizeConvertScreen(theme, title = "Image Resizer", onOpenPdfInReader = { f -> readerFile = f; activeTool = "reader" }) { activeTool = null }
            "convert" -> ResizeConvertScreen(theme, title = "Format Convert", onOpenPdfInReader = { f -> readerFile = f; activeTool = "reader" }) { activeTool = null }
            "ocr" -> OcrScreen(theme, onOpenPdfInReader = { f -> readerFile = f; activeTool = "reader" }) { activeTool = null }
            "reader" -> PdfReaderScreen(theme, initialFile = readerFile, onOpenTool = { tool, uri -> pendingToolFileUri = uri; activeTool = tool }) { activeTool = null; readerFile = null }
            else -> { activeTool = null }
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
            Column(
              Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Ds.s16, vertical = Ds.s12)
            ) {
              // Header — CHAMI PRO kept (user confirmed). Nothing else:
              // no OFFLINE badge, no feature count, no subtext.
              Row(
                Modifier.fillMaxWidth(),
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
                    Spacer(Modifier.width(6.dp))
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
              Box(Modifier.weight(1f)) {
                when (currentTab) {
                  "History" -> HistoryScreen(
                    theme,
                    onOpenInReader = { file -> readerFile = file; activeTool = "reader" },
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
                      toolGroups.forEach { (heading, tools) ->
                        item(span = { GridItemSpan(3) }) {
                          Text(
                            LocaleHelper.t(ctx, heading),
                            fontFamily = PixelFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = Ds.SECTION_SP.sp,
                            lineHeight = (Ds.SECTION_SP * 1.4).sp,
                            color = theme.primaryDark,
                            modifier = Modifier.padding(top = Ds.s24, bottom = Ds.s12)
                          )
                        }
                        items(tools) { t -> ToolTile(t) }
                      }
                    }
                  }
                }
              }

              Spacer(Modifier.height(Ds.s12))

              // Bottom navigation — real Material vector icons, never
              // emoji. Home is a proper house, NOT a lightning bolt.
              // Bar 64dp + system inset, labels always visible (12sp, Ds.NAV_SP),
              // selected dark green / unselected grey.
              Row(
                Modifier
                  .fillMaxWidth()
                  .height(Ds.bottomNavHeight)
                  .navigationBarsPadding()
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
                  val isSelected = currentTab == tab
                  Column(
                    Modifier
                      .weight(1f)
                      .fillMaxHeight()
                      .clip(RoundedCornerShape(Ds.s12))
                      .background(if (isSelected) theme.primary.copy(alpha = 0.15f) else Color.Transparent)
                      .clickable {
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
                      modifier = Modifier.size(24.dp)
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
