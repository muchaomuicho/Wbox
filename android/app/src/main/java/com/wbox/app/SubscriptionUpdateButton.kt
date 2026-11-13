package com.wbox.app

import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Dedicated update control; keep short requests visible without slowing the request. */
@Composable internal fun SubscriptionUpdateButton(
    updating:Boolean,description:String,onClick:()->Unit,enabled:Boolean=true,modifier:Modifier=Modifier
) {
    var visible by remember { mutableStateOf(updating) }
    var began by remember { mutableLongStateOf(0L) }
    LaunchedEffect(updating) {
        if(updating){began=SystemClock.elapsedRealtime();visible=true}
        else if(visible){delay((450L-(SystemClock.elapsedRealtime()-began)).coerceAtLeast(0));visible=false}
    }
    val spinning=updating||visible
    val color=MaterialTheme.colorScheme.primary
    FilledTonalIconButton(onClick=onClick,enabled=enabled&&!spinning,
        modifier=modifier.size(40.dp).semantics {
            contentDescription=description
            stateDescription=if(spinning)"正在更新" else "可更新"
            if(spinning)progressBarRangeInfo=ProgressBarRangeInfo.Indeterminate
        },colors=IconButtonDefaults.filledTonalIconButtonColors(
            containerColor=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.55f),
            contentColor=color,disabledContainerColor=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.55f),disabledContentColor=color)) {
        if(spinning) {
            val transition=rememberInfiniteTransition(label="subscription-update")
            val angle by transition.animateFloat(0f,360f,infiniteRepeatable(tween(900,easing=LinearEasing)),label="rotation")
            Canvas(Modifier.size(22.dp)) {
                val stroke=Stroke(width=2.5.dp.toPx(),cap=StrokeCap.Round)
                drawCircle(color.copy(alpha=.16f),style=stroke)
                drawArc(color,angle,260f,useCenter=false,style=stroke)
            }
        } else Icon(Icons.Outlined.Sync,null,Modifier.size(22.dp))
    }
}
