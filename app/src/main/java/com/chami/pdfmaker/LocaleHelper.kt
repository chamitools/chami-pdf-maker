package com.chami.pdfmaker

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Phase 6 — app language support.
 * Language codes: en, hi, es, fr, de, pt, ar, zh, ru, ja.
 * The chosen language is stored in SharedPreferences "chami_prefs" under "app_lang".
 * Tool names on the Home tiles stay in English (they are product names);
 * section headers, bottom-nav labels and Settings titles use [t].
 */
object LocaleHelper {

  const val PREFS = "chami_prefs"
  const val KEY_LANG = "app_lang"

  val LANGUAGES: List<Pair<String, String>> = listOf(
    "en" to "English",
    "hi" to "हिन्दी",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "pt" to "Português",
    "ar" to "العربية",
    "zh" to "中文",
    "ru" to "Русский",
    "ja" to "日本語"
  )

  fun localeFor(code: String): Locale = when (code) {
    "hi" -> Locale("hi")
    "es" -> Locale("es")
    "fr" -> Locale("fr")
    "de" -> Locale("de")
    "pt" -> Locale("pt")
    "ar" -> Locale("ar")
    "zh" -> Locale.SIMPLIFIED_CHINESE
    "ru" -> Locale("ru")
    "ja" -> Locale.JAPANESE
    else -> Locale.ENGLISH
  }

  fun currentCode(context: Context): String {
    val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getString(KEY_LANG, "en") ?: "en"
    return if (LANGUAGES.any { it.first == code }) code else "en"
  }

  /** Apply the saved language to this context's resources (no recreate). Call in onCreate before setContent. */
  fun applyLocale(context: Context) {
    val locale = localeFor(currentCode(context))
    Locale.setDefault(locale)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    @Suppress("DEPRECATION")
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
  }

  /** Persist the language, apply it, and recreate the activity so every screen switches immediately. */
  fun setLocale(context: Context, languageCode: String) {
    val code = if (LANGUAGES.any { it.first == languageCode }) languageCode else "en"
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit().putString(KEY_LANG, code).apply()
    val locale = localeFor(code)
    Locale.setDefault(locale)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    @Suppress("DEPRECATION")
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
    if (context is Activity) context.recreate()
  }

  private val STRINGS: Map<String, Map<String, String>> = mapOf(
    "en" to mapOf(
      "home" to "Home", "history" to "History", "settings" to "Settings",
      "simple_tools" to "SIMPLE TOOLS", "smart_tools" to "SMART TOOLS",
      "images_to_pdf" to "Images to PDF",
      "merge_pdf" to "Merge PDF", "split_pdf" to "Split PDF",
      "protect_pdf" to "Protect PDF", "compress_kb" to "Compress KB",
      "pdf_to_images" to "PDF to Images", "image_resizer" to "Image Resizer",
      "pdf_reader" to "PDF Reader", "format_convert" to "Format Convert",
      "ocr" to "OCR", "convert" to "Convert", "save_pdf" to "Save PDF",
      "done" to "Done", "language" to "Language",
      "contact_support" to "Contact Support", "check_updates" to "Check for Updates",
      "storage" to "Storage", "app_lock" to "App Lock",
      "font_size" to "Font Size", "clear_cache" to "Clear Cache",
      "privacy_policy" to "Privacy Policy",
      "slide_convert" to "Slide to Convert", "slide_extract" to "Slide to Extract",
      "slide_split" to "Slide to Split", "slide_merge" to "Slide to Merge",
      "slide_compress" to "Slide to Compress", "slide_convert_all" to "Slide to Convert All",
      "slide_resize" to "Slide to Resize", "slide_extract_text" to "Slide to Extract Text",
      "slide_unlock" to "Slide to Unlock PDF", "slide_protect" to "Slide to Protect PDF",
      "group_images_reading" to "Images & Reading", "group_fix_pdf" to "Fix a PDF",
      "toast_save_partial" to "Saved {saved} of {total} files — {failed} could not be saved to the chosen folder",
      "toast_save_failed_folder" to "Could not save to the chosen folder — files kept in Chami folder",
      "toast_save_failed_file" to "Could not save to chosen folder",
      "toast_save_cancelled_file" to "Save cancelled — file kept in Chami folder",
      "toast_save_cancelled_images" to "Save cancelled — images kept in Chami folder",
      "toast_edit_busy" to "Please wait — finishing the last edit",
      "toast_edit_failed" to "Could not apply that edit",
      "toast_select_image" to "Please select at least 1 image",
      "toast_create_pdf_failed" to "Failed to create PDF",
      "toast_range_check" to "Check the page range first",
      "toast_select_page_extract" to "Select at least 1 page to extract",
      "toast_extract_partial" to "{saved} of {total} images extracted — {failed} couldn't be extracted",
      "toast_extract_failed" to "Could not extract images — {failed} page(s) failed. Try again.",
      "toast_select_page_split" to "Select at least 1 page to keep",
      "toast_split_one_page" to "This PDF has only {n} page — nothing to split into parts",
      "toast_split_failed" to "Failed to split PDF",
      "toast_photo_skipped_one" to "1 photo couldn't be loaded and was skipped",
      "toast_photos_skipped" to "{n} photos couldn't be loaded and were skipped",
      "toast_images_pdf_oom" to "Not enough memory to build this PDF — try fewer or smaller photos",
      "btn_open_first_file" to "Open first file",
      "btn_share_first_file" to "Share first file",
      "btn_choose_images" to "Choose images",
      "btn_select_pdf" to "Select PDF",
      "btn_pdf_unopenable" to "PDF can't be opened — pick another",
      "btn_pdf_selected" to "PDF selected ({n} pages)",
      "btn_pdf_selected_split" to "PDF ({n} pages)",
      "btn_cancel" to "Cancel",
      "btn_apply" to "Apply",
      "btn_close" to "Close",
      "btn_reset" to "Reset",
      "btn_rotate_90" to "Rotate 90°",
      "btn_select_all" to "Select all",
      "btn_clear" to "Clear",
      "btn_add" to "Add",
      "dialog_filter_title" to "Filter",
      "dialog_brightness_title" to "Adjust brightness",
      "dialog_crop_title" to "Crop — cuts the image",
      "label_brightness_value" to "Brightness: {n}",
      "label_original" to "Original",
      "label_free" to "Free",
      "filter_name_bw" to "B&W",
      "filter_name_grayscale" to "Grayscale",
      "filter_name_enhance" to "Enhance",
      "label_no_page_yet" to "No page yet",
      "label_select_photos" to "Select photos from gallery",
      "label_page_of" to "Page {n} of {total}",
      "label_page_number" to "Page {n}",
      "hint_over_50_pages" to "Over 50 pages — processing may take longer.",
      "label_saved_to" to "Saved to: {location}",
      "hint_filter_page_only" to "Filter applies to this page only.",
      "hint_crop_drag" to "Drag the box to move it, or a corner / edge to resize it. With a ratio selected the box keeps that ratio. Everything outside the frame is removed.",
      "preview_action_crop" to "Crop — cut the image",
      "preview_action_delete" to "Delete this page",
      "preview_action_filter" to "Filter",
      "preview_action_adjust" to "Adjust brightness",
      "done_images_title" to "Images extracted",
      "done_images_summary" to "{n} images created as {format} at {resolution} DPI.",
      "done_images_saved" to "Saved to the folder you chose.",
      "done_split_title" to "PDF split",
      "done_split_summary" to "{n} parts created.",
      "done_split_saved" to "All parts saved to the folder you chose.",
      "label_pdf_unopenable" to "This PDF can't be opened (password-protected or damaged)",
      "label_preview_page_1" to "Preview (page 1 of {n})",
      "label_format" to "Format",
      "hint_png_larger" to "PNG files are larger than JPG.",
      "label_resolution" to "Resolution",
      "label_page_range" to "Page range",
      "label_from_page" to "From page",
      "label_to_page" to "To page",
      "label_range_error" to "Enter pages from 1 to {n}, From before To.",
      "label_image_will_create_one" to "1 image will be created",
      "label_images_will_create" to "{n} images will be created",
      "label_pages_tap_select" to "Pages — tap to select",
      "label_selected_pages_count" to "{selected}/{total} selected",
      "label_no_pages_selected" to "No pages selected — tap thumbnails to choose which pages to extract.",
      "label_split_mode" to "Split mode",
      "split_mode_selected" to "Selected",
      "split_mode_range" to "Range",
      "split_mode_every_n" to "Every N",
      "label_pages_per_part" to "Pages per part",
      "label_pages_per_part_error" to "Enter at least 1 page per part.",
      "label_part_pages" to "Part {n}: pages {from}–{to}",
      "label_pages_count" to "Pages ({selected}/{total})",
      "hint_split_tap_preview" to "Tap a page to preview it — tap the tick to select it.",
      "label_previewing_page" to "Previewing page {n}",
      "hint_split_rasterised" to "Split pages are rendered as images — text in the new file is not selectable.",
      "reason_select_image_first" to "Select at least 1 image first",
      "reason_pick_pdf_first" to "Pick a PDF first",
      "reason_loading_pdf" to "Loading PDF…",
      "reason_select_pages_first" to "Select pages first",
      "reason_split_one_page" to "This PDF has only 1 page — nothing to split",
      "share_failed" to "Could not share this file",
      "open_failed" to "No app can open this file",
      "pin_enter_prompt" to "Enter your 4-digit PIN",
      "pin_wrong_try_again" to "Wrong PIN — try again",
      "pin_wrong" to "Wrong PIN", "pin_unlock" to "UNLOCK",
      "pin_forgot" to "Forgot PIN?",
      "pin_forgot_body" to "Resetting a forgotten PIN requires reinstalling the app. There is no other way to remove App Lock.",
      "close" to "CLOSE", "intro_skip" to "Skip intro",
      "logo_subtitle" to "PROFESSIONAL PDF SUITE",
      "calculating_sizes" to "Calculating…",
      "done_open" to "Open", "done_share" to "Share",
      "done_pdf_created" to "PDF created successfully",
      "done_file_created" to "File created successfully",
      "done_page" to "page", "done_pages" to "pages",
      "done_chami_folder" to "Chami folder",
      "toast_merge_need_two" to "Select at least 2 PDFs to merge",
      "toast_merge_failed" to "Failed to merge PDFs",
      "toast_save_cancelled_files" to "Save cancelled — files kept in Chami folder",
      "toast_compress_failed" to "Compression failed",
      "toast_compress_pdf_failed" to "PDF compression failed",
      "toast_dims_range" to "Enter a width and height between 1 and 4096 px (max 16 million pixels total)",
      "toast_exact_kb_jpg_only" to "Exact KB target works with JPG — saved at chosen size instead",
      "toast_resize_failed" to "Failed to resize",
      "toast_nothing_to_convert" to "Nothing to convert — all files are already {format}",
      "label_unknown_format" to "Unknown format",
      "resize_unknown_format" to "Could not detect this image's format — saving as {format}.",
      "toast_copied" to "Copied to clipboard",
      "toast_ocr_export_failed" to "Could not export the OCR text — try again",
      "ocr_exporting_txt" to "Saving text file…",
      "ocr_exporting_pdf" to "Making PDF…",
      "toast_reader_encrypted" to "This PDF is encrypted — search can't read it. Unlock it first, then search.",
      "toast_search_unavailable" to "Search isn't available yet — the text-recognition model may still be downloading. Connect to the internet and try again.",
      "toast_search_no_matches" to "No matches for \"{q}\"",
      "reader_found_on_pages" to "Found on pages: {pages}",
      "reader_found_more" to "+{n} more",
      "unlock_enter_password" to "Enter the PDF password.",
      "unlock_read_failed" to "Could not read that file.",
      "unlock_not_protected" to "This file is not protected by a password.",
      "unlock_wrong_password" to "Wrong password — try again.",
      "unlock_unsupported" to "This file uses an encryption type this app can't unlock. Only Standard encryption (as used by Chami) is supported on-device.",
      "toast_unlocked" to "Unlocked",
      "toast_protect_permissions_failed" to "Protected, but permissions could not be applied",
      "toast_encrypt_failed" to "Failed to encrypt PDF",
      "protect_encryption_aes_note" to "Protected with AES-128 encryption. Files protected by older versions (40-bit) still open.",
      "reason_select_images_first" to "Select images first",
      "reason_select_image_first" to "Select an image first",
      "reason_select_photo_first" to "Select a photo first",
      "reason_passwords_mismatch" to "Passwords do not match",
      "reason_choose_protected_pdf_first" to "Choose a protected PDF first",
      "reason_password_min_length" to "Enter a password of at least 4 characters"
    ),
    "hi" to mapOf(
      "home" to "होम", "history" to "इतिहास", "settings" to "सेटिंग्स",
      "simple_tools" to "सरल टूल्स", "smart_tools" to "स्मार्ट टूल्स",
      "images_to_pdf" to "इमेज से PDF",
      "merge_pdf" to "PDF मिलाएँ", "split_pdf" to "PDF अलग करें",
      "protect_pdf" to "PDF सुरक्षित करें", "compress_kb" to "KB कम करें",
      "pdf_to_images" to "PDF से इमेज", "image_resizer" to "इमेज रिसाइज़र",
      "pdf_reader" to "PDF रीडर", "format_convert" to "फ़ॉर्मेट बदलें",
      "ocr" to "OCR", "convert" to "बदलें", "save_pdf" to "PDF सहेजें",
      "done" to "हो गया", "language" to "भाषा",
      "contact_support" to "सहायता से संपर्क करें", "check_updates" to "अपडेट देखें",
      "storage" to "स्टोरेज", "app_lock" to "ऐप लॉक",
      "font_size" to "फ़ॉन्ट आकार", "clear_cache" to "कैश साफ़ करें",
      "privacy_policy" to "गोपनीयता नीति",
      "slide_convert" to "स्लाइड करके बदलें", "slide_extract" to "स्लाइड करके निकालें",
      "slide_split" to "स्लाइड करके अलग करें", "slide_merge" to "स्लाइड करके मिलाएँ",
      "slide_compress" to "स्लाइड करके कंप्रेस करें", "slide_convert_all" to "स्लाइड करके सभी बदलें",
      "slide_resize" to "स्लाइड करके रिसाइज़ करें", "slide_extract_text" to "स्लाइड करके टेक्स्ट निकालें",
      "slide_unlock" to "स्लाइड करके PDF अनलॉक करें", "slide_protect" to "स्लाइड करके PDF सुरक्षित करें",
      "group_images_reading" to "इमेज और रीडिंग", "group_fix_pdf" to "PDF ठीक करें"
    ),
    "es" to mapOf(
      "home" to "Inicio", "history" to "Historial", "settings" to "Ajustes",
      "simple_tools" to "HERRAMIENTAS SIMPLES", "smart_tools" to "HERRAMIENTAS INTELIGENTES",
      "images_to_pdf" to "Imágenes a PDF",
      "merge_pdf" to "Unir PDF", "split_pdf" to "Dividir PDF",
      "protect_pdf" to "Proteger PDF", "compress_kb" to "Comprimir KB",
      "pdf_to_images" to "PDF a imágenes", "image_resizer" to "Redimensionar imagen",
      "pdf_reader" to "Lector de PDF", "format_convert" to "Convertir formato",
      "ocr" to "OCR", "convert" to "Convertir", "save_pdf" to "Guardar PDF",
      "done" to "Listo", "language" to "Idioma",
      "contact_support" to "Contactar soporte", "check_updates" to "Buscar actualizaciones",
      "storage" to "Almacenamiento", "app_lock" to "Bloqueo de app",
      "font_size" to "Tamaño de fuente", "clear_cache" to "Borrar caché",
      "privacy_policy" to "Política de privacidad",
      "slide_convert" to "Desliza para convertir", "slide_extract" to "Desliza para extraer",
      "slide_split" to "Desliza para dividir", "slide_merge" to "Desliza para unir",
      "slide_compress" to "Desliza para comprimir", "slide_convert_all" to "Desliza para convertir todo",
      "slide_resize" to "Desliza para redimensionar", "slide_extract_text" to "Desliza para extraer texto",
      "slide_unlock" to "Desliza para desbloquear PDF", "slide_protect" to "Desliza para proteger PDF",
      "group_images_reading" to "Imágenes y lectura", "group_fix_pdf" to "Arreglar PDF"
    ),
    "fr" to mapOf(
      "home" to "Accueil", "history" to "Historique", "settings" to "Réglages",
      "simple_tools" to "OUTILS SIMPLES", "smart_tools" to "OUTILS INTELLIGENTS",
      "images_to_pdf" to "Images en PDF",
      "merge_pdf" to "Fusionner PDF", "split_pdf" to "Diviser PDF",
      "protect_pdf" to "Protéger PDF", "compress_kb" to "Compresser Ko",
      "pdf_to_images" to "PDF en images", "image_resizer" to "Redimensionner image",
      "pdf_reader" to "Lecteur PDF", "format_convert" to "Convertir le format",
      "ocr" to "OCR", "convert" to "Convertir", "save_pdf" to "Enregistrer PDF",
      "done" to "Terminé", "language" to "Langue",
      "contact_support" to "Contacter l'assistance", "check_updates" to "Vérifier les mises à jour",
      "storage" to "Stockage", "app_lock" to "Verrouillage de l'app",
      "font_size" to "Taille de police", "clear_cache" to "Vider le cache",
      "privacy_policy" to "Politique de confidentialité",
      "slide_convert" to "Glisser pour convertir", "slide_extract" to "Glisser pour extraire",
      "slide_split" to "Glisser pour diviser", "slide_merge" to "Glisser pour fusionner",
      "slide_compress" to "Glisser pour compresser", "slide_convert_all" to "Glisser pour tout convertir",
      "slide_resize" to "Glisser pour redimensionner", "slide_extract_text" to "Glisser pour extraire le texte",
      "slide_unlock" to "Glisser pour déverrouiller le PDF", "slide_protect" to "Glisser pour protéger le PDF",
      "group_images_reading" to "Images et lecture", "group_fix_pdf" to "Réparer un PDF"
    ),
    "de" to mapOf(
      "home" to "Start", "history" to "Verlauf", "settings" to "Einstellungen",
      "simple_tools" to "EINFACHE TOOLS", "smart_tools" to "SMARTE TOOLS",
      "images_to_pdf" to "Bilder zu PDF",
      "merge_pdf" to "PDF zusammenführen", "split_pdf" to "PDF teilen",
      "protect_pdf" to "PDF schützen", "compress_kb" to "KB komprimieren",
      "pdf_to_images" to "PDF zu Bildern", "image_resizer" to "Bildgröße ändern",
      "pdf_reader" to "PDF-Reader", "format_convert" to "Format konvertieren",
      "ocr" to "OCR", "convert" to "Konvertieren", "save_pdf" to "PDF speichern",
      "done" to "Fertig", "language" to "Sprache",
      "contact_support" to "Support kontaktieren", "check_updates" to "Nach Updates suchen",
      "storage" to "Speicher", "app_lock" to "App-Sperre",
      "font_size" to "Schriftgröße", "clear_cache" to "Cache leeren",
      "privacy_policy" to "Datenschutzerklärung",
      "slide_convert" to "Wischen zum Konvertieren", "slide_extract" to "Wischen zum Extrahieren",
      "slide_split" to "Wischen zum Teilen", "slide_merge" to "Wischen zum Zusammenführen",
      "slide_compress" to "Wischen zum Komprimieren", "slide_convert_all" to "Wischen zum Konvertieren aller",
      "slide_resize" to "Wischen zum Skalieren", "slide_extract_text" to "Wischen zum Extrahieren von Text",
      "slide_unlock" to "Wischen zum Entsperren des PDFs", "slide_protect" to "Wischen zum Schützen des PDFs",
      "group_images_reading" to "Bilder & Lesen", "group_fix_pdf" to "PDF reparieren"
    ),
    "pt" to mapOf(
      "home" to "Início", "history" to "Histórico", "settings" to "Configurações",
      "simple_tools" to "FERRAMENTAS SIMPLES", "smart_tools" to "FERRAMENTAS INTELIGENTES",
      "images_to_pdf" to "Imagens para PDF",
      "merge_pdf" to "Unir PDF", "split_pdf" to "Dividir PDF",
      "protect_pdf" to "Proteger PDF", "compress_kb" to "Compactar KB",
      "pdf_to_images" to "PDF para imagens", "image_resizer" to "Redimensionar imagem",
      "pdf_reader" to "Leitor de PDF", "format_convert" to "Converter formato",
      "ocr" to "OCR", "convert" to "Converter", "save_pdf" to "Salvar PDF",
      "done" to "Concluído", "language" to "Idioma",
      "contact_support" to "Falar com o suporte", "check_updates" to "Verificar atualizações",
      "storage" to "Armazenamento", "app_lock" to "Bloqueio do app",
      "font_size" to "Tamanho da fonte", "clear_cache" to "Limpar cache",
      "privacy_policy" to "Política de privacidade",
      "slide_convert" to "Deslize para converter", "slide_extract" to "Deslize para extrair",
      "slide_split" to "Deslize para dividir", "slide_merge" to "Deslize para unir",
      "slide_compress" to "Deslize para compactar", "slide_convert_all" to "Deslize para converter tudo",
      "slide_resize" to "Deslize para redimensionar", "slide_extract_text" to "Deslize para extrair texto",
      "slide_unlock" to "Deslize para desbloquear o PDF", "slide_protect" to "Deslize para proteger o PDF",
      "group_images_reading" to "Imagens e leitura", "group_fix_pdf" to "Corrigir PDF"
    ),
    "ar" to mapOf(
      "home" to "الرئيسية", "history" to "السجل", "settings" to "الإعدادات",
      "simple_tools" to "أدوات بسيطة", "smart_tools" to "أدوات ذكية",
      "images_to_pdf" to "صور إلى PDF",
      "merge_pdf" to "دمج PDF", "split_pdf" to "تقسيم PDF",
      "protect_pdf" to "حماية PDF", "compress_kb" to "ضغط KB",
      "pdf_to_images" to "PDF إلى صور", "image_resizer" to "تغيير حجم الصورة",
      "pdf_reader" to "قارئ PDF", "format_convert" to "تحويل الصيغة",
      "ocr" to "OCR", "convert" to "تحويل", "save_pdf" to "حفظ PDF",
      "done" to "تم", "language" to "اللغة",
      "contact_support" to "الاتصال بالدعم", "check_updates" to "التحقق من التحديثات",
      "storage" to "التخزين", "app_lock" to "قفل التطبيق",
      "font_size" to "حجم الخط", "clear_cache" to "مسح ذاكرة التخزين المؤقت",
      "privacy_policy" to "سياسة الخصوصية",
      "slide_convert" to "مرّر للتحويل", "slide_extract" to "مرّر للاستخراج",
      "slide_split" to "مرّر للتقسيم", "slide_merge" to "مرّر للدمج",
      "slide_compress" to "مرّر للضغط", "slide_convert_all" to "مرّر لتحويل الكل",
      "slide_resize" to "مرّر لتغيير الحجم", "slide_extract_text" to "مرّر لاستخراج النص",
      "slide_unlock" to "مرّر لإلغاء قفل PDF", "slide_protect" to "مرّر لحماية PDF",
      "group_images_reading" to "الصور والقراءة", "group_fix_pdf" to "إصلاح PDF"
    ),
    "zh" to mapOf(
      "home" to "首页", "history" to "历史记录", "settings" to "设置",
      "simple_tools" to "简单工具", "smart_tools" to "智能工具",
      "images_to_pdf" to "图片转 PDF",
      "merge_pdf" to "合并 PDF", "split_pdf" to "拆分 PDF",
      "protect_pdf" to "保护 PDF", "compress_kb" to "压缩 KB",
      "pdf_to_images" to "PDF 转图片", "image_resizer" to "图片尺寸调整",
      "pdf_reader" to "PDF 阅读器", "format_convert" to "格式转换",
      "ocr" to "OCR", "convert" to "转换", "save_pdf" to "保存 PDF",
      "done" to "完成", "language" to "语言",
      "contact_support" to "联系客服", "check_updates" to "检查更新",
      "storage" to "存储空间", "app_lock" to "应用锁",
      "font_size" to "字体大小", "clear_cache" to "清除缓存",
      "privacy_policy" to "隐私政策",
      "slide_convert" to "滑动转换", "slide_extract" to "滑动提取",
      "slide_split" to "滑动拆分", "slide_merge" to "滑动合并",
      "slide_compress" to "滑动压缩", "slide_convert_all" to "滑动全部转换",
      "slide_resize" to "滑动调整大小", "slide_extract_text" to "滑动提取文字",
      "slide_unlock" to "滑动解锁 PDF", "slide_protect" to "滑动保护 PDF",
      "group_images_reading" to "图片与阅读", "group_fix_pdf" to "修复 PDF"
    ),
    "ru" to mapOf(
      "home" to "Главная", "history" to "История", "settings" to "Настройки",
      "simple_tools" to "ПРОСТЫЕ ИНСТРУМЕНТЫ", "smart_tools" to "УМНЫЕ ИНСТРУМЕНТЫ",
      "images_to_pdf" to "Изображения в PDF",
      "merge_pdf" to "Объединить PDF", "split_pdf" to "Разделить PDF",
      "protect_pdf" to "Защитить PDF", "compress_kb" to "Сжать KB",
      "pdf_to_images" to "PDF в изображения", "image_resizer" to "Изменить размер",
      "pdf_reader" to "Чтение PDF", "format_convert" to "Сменить формат",
      "ocr" to "OCR", "convert" to "Конвертировать", "save_pdf" to "Сохранить PDF",
      "done" to "Готово", "language" to "Язык",
      "contact_support" to "Связаться с поддержкой", "check_updates" to "Проверить обновления",
      "storage" to "Хранилище", "app_lock" to "Блокировка приложения",
      "font_size" to "Размер шрифта", "clear_cache" to "Очистить кэш",
      "privacy_policy" to "Политика конфиденциальности",
      "slide_convert" to "Проведите, чтобы конвертировать", "slide_extract" to "Проведите, чтобы извлечь",
      "slide_split" to "Проведите, чтобы разделить", "slide_merge" to "Проведите, чтобы объединить",
      "slide_compress" to "Проведите, чтобы сжать", "slide_convert_all" to "Проведите, чтобы конвертировать всё",
      "slide_resize" to "Проведите, чтобы изменить размер", "slide_extract_text" to "Проведите, чтобы извлечь текст",
      "slide_unlock" to "Проведите, чтобы разблокировать PDF", "slide_protect" to "Проведите, чтобы защитить PDF",
      "group_images_reading" to "Изображения и чтение", "group_fix_pdf" to "Исправить PDF"
    ),
    "ja" to mapOf(
      "home" to "ホーム", "history" to "履歴", "settings" to "設定",
      "simple_tools" to "シンプルツール", "smart_tools" to "スマートツール",
      "images_to_pdf" to "画像をPDFに",
      "merge_pdf" to "PDFを結合", "split_pdf" to "PDFを分割",
      "protect_pdf" to "PDFを保護", "compress_kb" to "KBを圧縮",
      "pdf_to_images" to "PDFを画像に", "image_resizer" to "画像リサイズ",
      "pdf_reader" to "PDFリーダー", "format_convert" to "形式変換",
      "ocr" to "OCR", "convert" to "変換", "save_pdf" to "PDFを保存",
      "done" to "完了", "language" to "言語",
      "contact_support" to "サポートに連絡", "check_updates" to "アップデートを確認",
      "storage" to "ストレージ", "app_lock" to "アプリロック",
      "font_size" to "フォントサイズ", "clear_cache" to "キャッシュを消去",
      "privacy_policy" to "プライバシーポリシー",
      "slide_convert" to "スライドして変換", "slide_extract" to "スライドして抽出",
      "slide_split" to "スライドして分割", "slide_merge" to "スライドして結合",
      "slide_compress" to "スライドして圧縮", "slide_convert_all" to "スライドしてすべて変換",
      "slide_resize" to "スライドしてリサイズ", "slide_extract_text" to "スライドしてテキスト抽出",
      "slide_unlock" to "スライドしてPDFのロック解除", "slide_protect" to "スライドしてPDFを保護",
      "group_images_reading" to "画像と閲覧", "group_fix_pdf" to "PDFを修正"
    )
  )

  /** Translated string for [key] in the current language; falls back to English, then the key itself. */
  fun t(context: Context, key: String): String {
    val code = currentCode(context)
    return STRINGS[code]?.get(key) ?: STRINGS["en"]?.get(key) ?: key
  }
}
