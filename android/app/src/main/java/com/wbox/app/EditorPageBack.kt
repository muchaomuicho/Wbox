@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.wbox.app

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView

/** The toolbar and system edge gesture follow the same keyboard/draft back stack. */
@Composable internal fun editorPageBack(saving:Boolean=false,changed:Boolean=false,onLeave:()->Unit):()->Unit {
    val view=LocalView.current
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val imeVisible=WindowInsets.isImeVisible
    var confirmDiscard by remember{mutableStateOf(false)}
    val leave:()->Unit={
        if(imeVisible){
            keyboard?.hide()
            (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken,0)
            focus.clearFocus()
        }else if(!saving){
            if(changed)confirmDiscard=true else onLeave()
        }
    }
    BackHandler(onBack=leave)
    if(confirmDiscard)AlertDialog(
        onDismissRequest={confirmDiscard=false},
        title={Text("放弃未保存的修改？")},
        text={Text("返回后不会保留这次修改。")},
        confirmButton={TextButton(enabled=!saving,onClick={confirmDiscard=false;onLeave()}){Text("放弃修改")}},
        dismissButton={TextButton(onClick={confirmDiscard=false}){Text("继续编辑")}}
    )
    return leave
}
