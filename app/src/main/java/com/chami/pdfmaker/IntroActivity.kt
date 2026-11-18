package com.chami.pdfmaker

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chami.pdfmaker.theme.ThemeManager
import com.chami.pdfmaker.ui.ChamiLogo
import com.chami.pdfmaker.ui.Ds
import kotlinx.coroutines.delay

class IntroActivity : ComponentActivity() {
  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    // Phase 4, point 4: screenshots / screen recording always blocked,
    // same as MainActivity. No toggle anywhere in the app.
    window.setFlags(
      WindowManager.LayoutParams.FLAG_SECURE,
      WindowManager.LayoutParams.FLAG_SECURE
    )
    val tm = ThemeManager(this)
    setContent {
      val theme = tm.current()
      var fill by remember { mutableStateOf(0f) }
      // FIX 2026-11-18 (report #48): tap-to-skip. `finished` guards
      // the navigation so a tap and the timer can never launch
      // MainActivity twice.
      var finished by remember { mutableStateOf(false) }

      fun goToMain() {
        if (finished) return
        finished = true
        startActivity(Intent(this@IntroActivity, MainActivity::class.java))
        finish()
      }

      LaunchedEffect(Unit) {
        // Fast, smooth, responsive 1-second sequence
        repeat(20) { i ->
          if (finished) return@LaunchedEffect
          fill = (i + 1) / 20f
          delay(50)
        }
        delay(150)
        goToMain()
      }

      Box(
        Modifier
          .fillMaxSize()
          .background(theme.bg)
          .clickable { goToMain() }
          .padding(Ds.s16),
        contentAlignment = Alignment.Center
      ) {
        ChamiLogo(
          theme = theme,
          fill = fill,
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 440.dp)
        )
      }
    }
  }
}
