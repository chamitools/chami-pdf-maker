package com.chami.pdfmaker.pdf
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Scripts the on-device OCR can read. Only Hindi changes the
// recogniser: ML Kit has one recogniser per SCRIPT, not per language.
// Hindi (plus Marathi / Nepali) reads Devanagari; English, Spanish,
// French and German all read the Latin script, so they share the
// Latin recogniser. There is no Spanish-only recogniser — Spanish
// works because it is written in Latin script, not because a Spanish
// model is loaded. The UI offers NO language choice any more (FIX
// 2026-11-18, Fix 5) — ocrImageAnyLanguage below runs both.
val SUPPORTED_OCR_LANGUAGES = listOf("English", "Hindi", "Spanish")

// True when the language needs the Devanagari recogniser.
fun isDevanagariLanguage(language: String): Boolean = when (language.trim().lowercase()) {
  "hindi", "हिन्दी", "devanagari", "marathi", "nepali" -> true
  else -> false
}

// The recogniser options for a language name. "Hindi" (and Marathi /
// Nepali) -> Devanagari recogniser; everything else -> Latin.
// Unknown names fall back to Latin, never to a silent empty result.
fun recognizerOptionsFor(language: String): TextRecognizerOptionsInterface =
  if (isDevanagariLanguage(language)) {
    DevanagariTextRecognizerOptions.Builder().build()
  } else {
    TextRecognizerOptions.DEFAULT_OPTIONS
  }

// OCR a Bitmap with the recogniser for `language`. Returns "" on any
// failure (same contract as before — callers treat blank as no text).
suspend fun ocrBitmap(bmp: Bitmap, language: String = "English"): String =
  suspendCancellableCoroutine { cont ->
    val client = TextRecognition.getClient(recognizerOptionsFor(language))
    try {
      val image = InputImage.fromBitmap(bmp, 0)
      client.process(image)
        .addOnSuccessListener { result ->
          try { client.close() } catch (_: Exception) {}
          if (cont.isActive) cont.resume(result.text)
        }
        .addOnFailureListener {
          try { client.close() } catch (_: Exception) {}
          if (cont.isActive) cont.resume("")
        }
    } catch (e: Exception) {
      try { client.close() } catch (_: Exception) {}
      if (cont.isActive) cont.resume("")
    }
  }

// OCR an image Uri. Language-aware overload — this is the one the OCR
// screen must call with the user's picked language.
suspend fun ocrImage(ctx: Context, uri: Uri, language: String): String =
  suspendCancellableCoroutine { cont ->
    val client = TextRecognition.getClient(recognizerOptionsFor(language))
    try {
      val image = InputImage.fromFilePath(ctx, uri)
      client.process(image)
        .addOnSuccessListener { result ->
          try { client.close() } catch (_: Exception) {}
          if (cont.isActive) cont.resume(result.text)
        }
        .addOnFailureListener {
          try { client.close() } catch (_: Exception) {}
          if (cont.isActive) cont.resume("")
        }
    } catch (e: Exception) {
      try { client.close() } catch (_: Exception) {}
      if (cont.isActive) cont.resume("")
    }
  }

// Backwards-compatible overload: Latin recogniser (English default),
// as before. Existing callers (Reader search) keep compiling.
suspend fun ocrImage(ctx: Context, uri: Uri): String = ocrImage(ctx, uri, "English")

// OCR with EVERY on-device recogniser, no language picking (FIX
// 2026-11-18, Fix 5): Latin first, then Devanagari, and the results
// are SCORED, not blindly concatenated (#32). Latin covers English,
// Spanish, French, German and other Latin-script languages;
// Devanagari covers Hindi, Marathi and Nepali. A genuinely
// mixed-script page (e.g. Hindi + English) returns both parts.
// Scripts with no on-device recogniser return nothing here —
// callers must say so plainly instead of failing silently.
suspend fun ocrImageAnyLanguage(ctx: Context, uri: Uri): String {
  val latin = ocrImage(ctx, uri, "English")
  val devanagari = ocrImage(ctx, uri, "Hindi")
  return pickBetterOcrResult(latin, devanagari)
}

// Same script-scored OCR for a Bitmap (Reader search renders page
// bitmaps and needs this directly — it cannot go through a Uri).
suspend fun ocrBitmapAnyLanguage(bmp: Bitmap): String {
  val latin = ocrBitmap(bmp, "English")
  val devanagari = ocrBitmap(bmp, "Hindi")
  return pickBetterOcrResult(latin, devanagari)
}

// Cleanliness score for an OCR result (#32): the fraction of
// characters that are letters, digits, whitespace or common
// punctuation. The wrong-script recogniser on a single-script page
// returns gibberish — stray symbols, broken fragments — so it scores
// low, while the right recogniser's real words score near 1.0.
// Blank text scores 0.0. Letters are Unicode letters, so Devanagari
// (and other scripts) count as letters, not garbage.
fun ocrCleanScore(text: String): Double {
  if (text.isBlank()) return 0.0
  var good = 0
  for (ch in text) {
    if (ch.isLetterOrDigit() || ch.isWhitespace() ||
      ch in ".,;:!?'-\"'()[]{}…—–/\\@#%&*+=<>") good++
  }
  return good.toDouble() / text.length
}

// Pick between the Latin and Devanagari OCR results for one image
// (#32). The better-scoring result wins. Both are merged ONLY when
// both are clean (score >= 0.85) AND distinct (neither is blank, they
// are not equal, and neither contains the other) — a genuinely
// mixed-script page. Otherwise the single-script gibberish from the
// wrong recogniser is dropped instead of landing in the output.
fun pickBetterOcrResult(latin: String, devanagari: String): String {
  val lat = latin.trim()
  val dev = devanagari.trim()
  if (lat.isEmpty()) return dev
  if (dev.isEmpty()) return lat
  val latScore = ocrCleanScore(lat)
  val devScore = ocrCleanScore(dev)
  val bothClean = latScore >= 0.85 && devScore >= 0.85
  val distinct = lat != dev && !lat.contains(dev) && !dev.contains(lat)
  if (bothClean && distinct) return "$lat\n$dev"
  // Tie: prefer Latin (deterministic; matches the old order).
  return if (devScore > latScore) dev else lat
}
