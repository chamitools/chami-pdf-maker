package com.chami.pdfmaker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chami.pdfmaker.pdf.PdfEngine
import com.chami.pdfmaker.theme.ChamiTheme
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live in-app camera for Scan to PDF (CameraX, NOT the system camera
 * intent). Opens straight onto the viewfinder like Adobe Scan. After a
 * capture it STAYS on the camera so the user can batch-scan more pages;
 * the caller adds each Bitmap to the page list.
 *
 * CAPTURE PROOF (audit P0 #1): every successful capture gives four
 * signals at once — white flash overlay, shutter sound
 * ([MediaActionSound.SHUTTER_CLICK]), vibration (haptic LongPress),
 * and a "Page N captured" banner plus the captured-pages stack
 * (thumbnail + count badge) on the camera screen.
 *
 * NO EDGE FRAME / GRID: the viewfinder is a plain live camera — no
 * detected-edge frame, no guide rectangle, no status pill. The user
 * asked for just capture from the camera: point, tap the shutter,
 * capture proof confirms it. Edge detection still runs on the
 * preview screen after capture, where the border + handles live.
 * No auto-capture: only the shutter button takes a photo.
 */

@Composable
fun ScanCameraScreen(
  theme: ChamiTheme,
  onCaptured: (android.graphics.Bitmap) -> Unit,
  onGallery: () -> Unit,
  onClose: () -> Unit,
  /** Pages already captured before this camera open (ToolScreens can pass pages.size). */
  capturedCountOffset: Int = 0,
) {
  val ctx = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val haptic = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()

  var hasPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted -> hasPermission = granted }

  // PreviewView is created once with remember (never assigned inside
  // the AndroidView factory — writing state during composition is
  // not allowed) and handed to AndroidView below.
  val previewView = remember {
    PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
  }
  var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
  var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
  var cameraReady by remember { mutableStateOf(false) }
  var flashOn by remember { mutableStateOf(false) }
  var capturing by remember { mutableStateOf(false) }

  // Capture proof state.
  var sessionCaptures by remember { mutableStateOf(0) }
  var lastThumb by remember { mutableStateOf<Bitmap?>(null) }
  var showFlash by remember { mutableStateOf(false) }
  var captureMessage by remember { mutableStateOf<String?>(null) }
  var bannerJob by remember { mutableStateOf<Job?>(null) }
  val capturedCount = capturedCountOffset + sessionCaptures

  // Shutter sound: the system camera shutter click. Created once and
  // released on dispose. MediaActionSound is the API that actually
  // plays the camera shutter sound on-device (RingtoneManager has no
  // shutter sound); if the device is muted there is no click, which is
  // why flash + vibration + banner fire at the same time.
  val shutterSound = remember {
    try {
      MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    } catch (_: Exception) {
      null
    }
  }

  fun playCaptureProof(pageNumber: Int) {
    // (a) White flash overlay on the viewfinder.
    showFlash = true
    scope.launch { delay(150); showFlash = false }
    // (b) Shutter sound.
    try { shutterSound?.play(MediaActionSound.SHUTTER_CLICK) } catch (_: Exception) {}
    // (c) Vibration (haptic success).
    try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    // (d) "Page N captured" banner + stack counter (state below).
    bannerJob?.cancel()
    captureMessage = "Page $pageNumber captured"
    bannerJob = scope.launch { delay(1500); captureMessage = null }
  }

  // Local funs are only visible AFTER their declaration point, so
  // takePhoto / bindCamera are declared here, before the effects below.
  fun takePhoto() {
    val capture = imageCapture ?: return
    if (capturing) return
    capturing = true
    val tmp = File(ctx.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(tmp).build()
    capture.takePicture(
      options,
      ContextCompat.getMainExecutor(ctx),
      object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
          capturing = false
          // Downsample to ~1600 px max side: a full 12 MP capture is
          // ~48 MB per page and batch scanning would OOM (H7).
          val bmp = PdfEngine.loadBitmap(ctx, Uri.fromFile(tmp), 1600)
          if (bmp != null) {
            // Capture proof FIRST, then hand the page to the caller.
            // The camera stays open for the next page.
            sessionCaptures += 1
            val pageNumber = capturedCountOffset + sessionCaptures
            val oldThumb = lastThumb
            lastThumb = try {
              Bitmap.createScaledBitmap(
                bmp, 120, (120f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true
              )
            } catch (_: Exception) {
              null
            }
            try { oldThumb?.recycle() } catch (_: Exception) {}
            playCaptureProof(pageNumber)
            onCaptured(bmp)
          } else {
            Toast.makeText(ctx, "Capture failed — try again", Toast.LENGTH_SHORT).show()
          }
          try { tmp.delete() } catch (_: Exception) {}
        }

        override fun onError(e: ImageCaptureException) {
          capturing = false
          Toast.makeText(ctx, "Capture failed — try again", Toast.LENGTH_SHORT).show()
        }
      }
    )
  }

  fun bindCamera(view: PreviewView) {
    val providerFuture = ProcessCameraProvider.getInstance(ctx)
    providerFuture.addListener(
      {
        try {
          val provider = providerFuture.get()
          cameraProvider = provider
          val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(view.surfaceProvider)
          }
          val capture = ImageCapture.Builder()
            .setFlashMode(
              if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            )
            .build()
          imageCapture = capture
          provider.unbindAll()
          provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            capture
          )
          cameraReady = true
        } catch (_: Exception) {
          cameraReady = false
        }
      },
      ContextCompat.getMainExecutor(ctx)
    )
  }

  // Bind once the permission is granted (previewView already exists).
  LaunchedEffect(hasPermission) {
    if (hasPermission) bindCamera(previewView)
  }

  // Flash toggle applies live to the bound ImageCapture (no rebind).
  LaunchedEffect(flashOn) {
    imageCapture?.flashMode =
      if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
  }

  // Release the camera when leaving this screen, or it stays held
  // (battery drain, blocks other apps) until the Activity dies (H2).
  DisposableEffect(Unit) {
    onDispose {
      try { cameraProvider?.unbindAll() } catch (_: Exception) {}
      try { shutterSound?.release() } catch (_: Exception) {}
      try { lastThumb?.recycle() } catch (_: Exception) {}
    }
  }

  if (!hasPermission) {
    // Camera permission needed — simple screen with a request button.
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(theme.bg)
        .statusBarsPadding()
        .padding(horizontal = Ds.s16, vertical = Ds.s32),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Icon(
        Icons.Filled.CameraAlt,
        contentDescription = "Camera",
        tint = theme.primary,
        modifier = Modifier.size(Ds.s32 + Ds.s16)
      )
      Spacer(Modifier.height(Ds.s16))
      Text(
        "Camera permission needed",
        fontFamily = PixelFontFamily,
        fontSize = Ds.TITLE_SP.sp,
        fontWeight = FontWeight.Bold,
        color = theme.text,
        textAlign = TextAlign.Center
      )
      Spacer(Modifier.height(Ds.s8))
      Text(
        "Allow camera access to scan pages into a PDF.",
        fontSize = Ds.BODY_SP.sp,
        lineHeight = (Ds.BODY_SP * 1.4).sp,
        color = theme.textSecondary,
        textAlign = TextAlign.Center
      )
      Spacer(Modifier.height(Ds.s24))
      ChamiPrimaryButton(
        text = "ALLOW CAMERA",
        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        modifier = Modifier.fillMaxWidth()
      )
      Spacer(Modifier.height(Ds.s12))
      ChamiSecondaryButton(
        text = "CLOSE",
        onClick = onClose,
        modifier = Modifier.fillMaxWidth()
      )
      Spacer(Modifier.height(Ds.s12))
      ChamiSecondaryButton(
        text = "USE GALLERY INSTEAD",
        onClick = onGallery,
        modifier = Modifier.fillMaxWidth()
      )
    }
    return
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
  ) {
    AndroidView(
      factory = { previewView },
      modifier = Modifier.fillMaxSize()
    )

    // Top bar: close (left), flash toggle (right).
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(Ds.s16),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        modifier = Modifier
          .height(Ds.minTouch)
          .clip(RoundedCornerShape(Ds.s16))
          .background(Color.Black.copy(alpha = 0.45f))
          .clickable { onClose() }
          .padding(horizontal = Ds.s12),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(Icons.Filled.Close, contentDescription = "Close camera", tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(Ds.s4))
        Text("CLOSE", color = Color.White, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
      }
      Row(
        modifier = Modifier
          .height(Ds.minTouch)
          .clip(RoundedCornerShape(Ds.s16))
          .background(Color.Black.copy(alpha = 0.45f))
          .clickable { flashOn = !flashOn }
          .padding(horizontal = Ds.s12),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
          contentDescription = if (flashOn) "Flash on" else "Flash off",
          tint = Color.White,
          modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.size(Ds.s4))
        Text(
          if (flashOn) "FLASH ON" else "FLASH",
          color = Color.White,
          fontSize = Ds.HINT_SP.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    // Bottom controls: capture banner, then gallery | shutter |
    // pages stack. The banner is the first child
    // of this bottom-aligned column, so it grows upwards and never
    // moves the shutter button when it appears.
    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(horizontal = Ds.s16)
        .padding(bottom = Ds.s32),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // "Page N captured" banner — appears on every capture, then
      // fades after 1.5s. The camera stays open.
      AnimatedVisibility(
        visible = captureMessage != null,
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        Row(
          modifier = Modifier
            .clip(RoundedCornerShape(Ds.s16))
            .background(ChamiColors.brightGreen)
            .padding(horizontal = Ds.s16, vertical = Ds.s8),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
          Spacer(Modifier.size(Ds.s8))
          Text(
            captureMessage ?: "",
            color = Color.White,
            fontSize = Ds.BODY_SP.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
      if (captureMessage != null) Spacer(Modifier.height(Ds.s12))
      Spacer(Modifier.height(Ds.s12))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Gallery — small labelled pill, clearly a secondary option
        // next to the big shutter (Adobe-like: gallery is not the
        // main action).
        Row(
          modifier = Modifier
            .height(Ds.minTouch)
            .clip(RoundedCornerShape(Ds.s16))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onGallery() }
            .padding(horizontal = Ds.s12),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(Icons.Filled.PhotoLibrary, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.size(20.dp))
          Spacer(Modifier.size(Ds.s4))
          Text("Gallery", color = Color.White, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
        }
        // Large round shutter button — 72dp, centred. Plain shape
        // (a shutter is a shape, not an icon); tap = take photo.
        Box(
          modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(ChamiColors.brightGreen)
            .border(4.dp, Color.White, CircleShape)
            .clickable(enabled = cameraReady && !capturing, onClickLabel = "Take photo") { takePhoto() },
          contentAlignment = Alignment.Center
        ) {
          Box(
            Modifier
              .size(Ds.minTouch)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.25f))
          )
        }
        // Captured-pages stack: last captured thumbnail + count badge.
        // Tapping it closes the camera to the review screen.
        Box(
          modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(Ds.s12))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClickLabel = "View captured pages") { onClose() },
          contentAlignment = Alignment.Center
        ) {
          val thumb = lastThumb
          if (thumb != null) {
            androidx.compose.foundation.Image(
              bitmap = thumb.asImageBitmap(),
              contentDescription = "Captured pages",
              modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(Ds.s8))
            )
          } else {
            Icon(Icons.Filled.Image, contentDescription = "No pages captured yet", tint = Color.White, modifier = Modifier.size(24.dp))
          }
          Box(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .offset(x = 4.dp, y = (-4).dp)
              .size(24.dp)
              .clip(CircleShape)
              .background(ChamiColors.brightGreen),
            contentAlignment = Alignment.Center
          ) {
            Text(
              capturedCount.toString(),
              color = Color.White,
              fontSize = Ds.NAV_SP.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
    }

    // (a) White flash overlay — shown for ~150 ms on every capture.
    if (showFlash) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.White.copy(alpha = 0.9f))
      )
    }
  }
}
