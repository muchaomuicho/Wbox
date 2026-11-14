@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wbox.app

import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.launch

/** Full-width sheets join the status-bar surface as they reach the top of the window. */
@Composable internal fun WboxBottomSheet(
    onDismissRequest:()->Unit,modifier:Modifier=Modifier,skipPartiallyExpanded:Boolean=true,
    confirmValueChange:(SheetValue)->Boolean={true},content:@Composable ColumnScope.()->Unit
) {
    val latestConfirm=rememberUpdatedState(confirmValueChange)
    val dismissIme=remember{arrayOf<()->Boolean>({false})}
    val confirm=remember{ {value:SheetValue->
        if(value==SheetValue.Hidden&&dismissIme[0]())false else latestConfirm.value(value)
    } }
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=skipPartiallyExpanded,confirmValueChange=confirm)
    val scope=rememberCoroutineScope()
    val latestDismiss=rememberUpdatedState(onDismissRequest)
    val back=rememberUpdatedState<()->Unit> {
        if(!dismissIme[0]())scope.launch {
            sheet.hide()
            if(!sheet.isVisible)latestDismiss.value()
        }
    }
    val density=LocalDensity.current
    val availableWidth=with(density){LocalConfiguration.current.screenWidthDp.dp.toPx()}
    var width by remember{mutableIntStateOf(0)}
    val radius by remember(sheet,density,availableWidth) {derivedStateOf {
        // The expanded state may still be a short sheet. Its actual offset determines docking.
        val offset=try{sheet.requireOffset()}catch(_:IllegalStateException){Float.POSITIVE_INFINITY}
        val radiusPx=with(density){24.dp.toPx()}
        with(density){if(width+1>=availableWidth)offset.coerceIn(0f,radiusPx).toDp()else 24.dp}
    }}
    // Material3 1.2.1 calls its back dismissal even when hide() was vetoed.
    ModalBottomSheet(onDismissRequest={if(!sheet.isVisible)onDismissRequest()},sheetState=sheet,
        properties=ModalBottomSheetProperties(SecureFlagPolicy.Inherit,true,false),
        modifier=modifier.onSizeChanged{width=it.width}.onPreviewKeyEvent {
            if(Build.VERSION.SDK_INT<33&&it.nativeKeyEvent.keyCode==android.view.KeyEvent.KEYCODE_BACK){
                if(it.nativeKeyEvent.action==android.view.KeyEvent.ACTION_UP)back.value()
                true
            }else false
        },
        shape=RoundedCornerShape(topStart=radius,topEnd=radius),
        containerColor=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface,
        tonalElevation=0.dp) {
        val view=LocalView.current
        val focus=LocalFocusManager.current
        val keyboard=LocalSoftwareKeyboardController.current
        val imeVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
        // The stock sheet registers PRIORITY_OVERLAY, which precedes the IME.
        // Default priority lets Android close the keyboard before dismissing a sheet.
        DisposableEffect(view) {
            if(Build.VERSION.SDK_INT>=33){
                val dispatcher=view.findOnBackInvokedDispatcher()
                val callback=OnBackInvokedCallback{back.value()}
                dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback)
                onDispose {dispatcher?.unregisterOnBackInvokedCallback(callback)}
            }else onDispose{}
        }
        SideEffect {dismissIme[0]={
            if(imeVisible||ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())==true){
                keyboard?.hide();focus.clearFocus();true
            }else false
        }}
        content()
    }
}
