package com.wbox.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

/** ModalBottomSheet's popup window cannot create Android's floating ActionMode. */
internal class SelectionToolbar : TextToolbar {
    private data class Selection(val rect:Rect,val actions:List<Pair<String,()->Unit>>)
    private var selection by mutableStateOf<Selection?>(null)
    override val status get()=if(selection==null)TextToolbarStatus.Hidden else TextToolbarStatus.Shown
    override fun hide(){selection=null}
    override fun showMenu(rect:Rect,onCopyRequested:(()->Unit)?,onPasteRequested:(()->Unit)?,onCutRequested:(()->Unit)?,onSelectAllRequested:(()->Unit)?) {
        selection=Selection(rect,listOf("剪切" to onCutRequested,"复制" to onCopyRequested,"粘贴" to onPasteRequested,"全选" to onSelectAllRequested).mapNotNull{(label,action)->action?.let{label to it}})
    }
    @Composable fun Menu() {
        val current=selection ?: return
        val position=remember(current.rect){object:PopupPositionProvider {
            override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset {
                val x=current.rect.left.toInt().coerceIn(8,(windowSize.width-popupContentSize.width-8).coerceAtLeast(8))
                val y=(current.rect.top.toInt()-popupContentSize.height-16).coerceIn(8,(windowSize.height-popupContentSize.height-8).coerceAtLeast(8))
                return IntOffset(x,y)
            }
        }}
        Popup(popupPositionProvider=position,onDismissRequest={hide()},properties=PopupProperties(focusable=false)) {
            Surface(shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,shadowElevation=6.dp) {
                Row(Modifier.padding(horizontal=6.dp)){current.actions.forEach{(label,action)->TextButton(onClick={action();if(label!="全选")hide()}){Text(label)}}}
            }
        }
    }
}
