package com.wbox.app

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Apply the same edge-to-edge surface to activity and full-screen editor windows. */
@Composable internal fun WboxSystemBars(m: MobileModel) {
    val view=LocalView.current
    val background=MaterialTheme.colorScheme.surface
    val dark=background.red<.5f
    SideEffect {
      // Dialog updates its window parameters after composing its content.
      // Apply after attachment and find the actual dialog window through all wrappers.
      view.post {
        val window=(view as? DialogWindowProvider)?.window
            ?: generateSequence(view.parent){it.parent}.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
            ?: m.activity.window
        WindowCompat.setDecorFitsSystemWindows(window,false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
        window.statusBarColor=background.toArgb()
        window.navigationBarColor=background.toArgb()
        if(Build.VERSION.SDK_INT>=29){
            window.isStatusBarContrastEnforced=false
            window.isNavigationBarContrastEnforced=false
        }
        WindowCompat.getInsetsController(window,view).apply{
            isAppearanceLightStatusBars=!dark
            isAppearanceLightNavigationBars=!dark
        }
      }
    }
}
