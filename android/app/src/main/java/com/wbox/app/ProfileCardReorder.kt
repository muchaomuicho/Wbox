package com.wbox.app

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive

/** A long press belongs to the whole card; ordinary swipes still belong to the grid. */
@Stable internal class ProfileCardReorder(private val grid:LazyGridState) {
    var pendingOrder by mutableStateOf<List<String>?>(null);private set
    var dragging by mutableStateOf<String?>(null);private set
    private var floatingTopLeft by mutableStateOf(Offset.Zero)
    private var itemSize=IntSize.Zero
    private var initialOrder=emptyList<String>()
    private var pointerY=0f
    private var generation=0
    var edgeSpeed by mutableFloatStateOf(0f);private set
    var edgeDistance=1f
    var maxSpeed=1f
    var keys:()->List<String> = {emptyList()}
    var save:(List<String>,()->Unit)->Unit={_,complete->complete()}
    var haptic:()->Unit={}

    fun displayedKeys(current:List<String>):List<String> {
        val pending=pendingOrder?:return current
        val available=current.toSet()
        return pending.filter{it in available}+current.filter{it !in pending}
    }
    fun start(position:Offset) {
        val item=grid.layoutInfo.visibleItemsInfo.firstOrNull {Rect(it.offset.x.toFloat(),it.offset.y.toFloat(),(it.offset.x+it.size.width).toFloat(),(it.offset.y+it.size.height).toFloat()).contains(position)}?:return
        val current=displayedKeys(keys())
        if(item.index !in current.indices)return
        generation++;initialOrder=current;pendingOrder=current;dragging=current[item.index]
        floatingTopLeft=Offset(item.offset.x.toFloat(),item.offset.y.toFloat());itemSize=item.size;pointerY=position.y
        haptic();updateEdgeSpeed()
    }
    fun drag(delta:Offset) {
        if(dragging==null)return
        floatingTopLeft+=delta;pointerY+=delta.y;updateEdgeSpeed();moveToHoveredSlot()
    }
    private fun updateEdgeSpeed() {
        val bottom=grid.layoutInfo.viewportEndOffset.toFloat()
        edgeSpeed=when {
            pointerY<edgeDistance -> -maxSpeed*((edgeDistance-pointerY)/edgeDistance).coerceIn(0f,1f)
            pointerY>bottom-edgeDistance -> maxSpeed*((pointerY-bottom+edgeDistance)/edgeDistance).coerceIn(0f,1f)
            else -> 0f
        }
    }
    fun moveToHoveredSlot() {
        val id=dragging?:return
        val current=displayedKeys(keys());val from=current.indexOf(id)
        if(from<0){cancel();return}
        val info=grid.layoutInfo
        val visible=info.visibleItemsInfo
        if(visible.isEmpty())return
        val top=visible.minOf{it.offset.y}.coerceAtLeast(1).toFloat()
        val bottom=visible.maxOf{it.offset.y+it.size.height-1}.coerceAtMost(info.viewportEndOffset-1).coerceAtLeast(top.toInt()).toFloat()
        val center=Offset(floatingTopLeft.x+itemSize.width/2f,(floatingTopLeft.y+itemSize.height/2f).coerceIn(top,bottom))
        val slot=info.visibleItemsInfo.firstOrNull{Rect(it.offset.x.toFloat(),it.offset.y.toFloat(),(it.offset.x+it.size.width).toFloat(),(it.offset.y+it.size.height).toFloat()).contains(center)}?.index?:return
        // Use the slot index rather than an old frame's item key while Compose lays out a move.
        if(slot in current.indices&&from!=slot)pendingOrder=current.toMutableList().apply{add(slot,removeAt(from))}
    }
    fun finish() {
        if(dragging==null)return
        dragging=null;edgeSpeed=0f
        val order=displayedKeys(keys())
        if(order==initialOrder){pendingOrder=null;return}
        persist(order)
    }
    private fun persist(order:List<String>) {
        pendingOrder=order;val revision=++generation
        save(order){if(generation==revision)pendingOrder=null}
    }
    fun cancel() {generation++;dragging=null;edgeSpeed=0f;pendingOrder=null}
    fun moveAccessible(id:String,delta:Int):Boolean {
        val current=displayedKeys(keys());val from=current.indexOf(id);val to=from+delta
        if(from<0||to !in current.indices)return false
        persist(current.toMutableList().apply{add(to,removeAt(from))});return true
    }
    fun cardModifier(id:String):Modifier=Modifier.zIndex(if(dragging==id)1f else 0f).graphicsLayer {
        translationX=0f;translationY=0f;shadowElevation=0f;scaleX=1f;scaleY=1f
        if(dragging==id) {
            val item=grid.layoutInfo.visibleItemsInfo.firstOrNull{it.key==id}
            translationX=floatingTopLeft.x-(item?.offset?.x?:floatingTopLeft.x.toInt())
            translationY=floatingTopLeft.y-(item?.offset?.y?:floatingTopLeft.y.toInt())
            shadowElevation=8.dp.toPx();shape=RoundedCornerShape(20.dp);scaleX=1.02f;scaleY=1.02f
        }
    }.semantics {
        customActions=listOf(CustomAccessibilityAction("向前移动配置"){moveAccessible(id,-1)},CustomAccessibilityAction("向后移动配置"){moveAccessible(id,1)})
    }
}

@Composable internal fun rememberProfileCardReorder(grid:LazyGridState,keys:List<String>,save:(List<String>,()->Unit)->Unit):ProfileCardReorder {
    val state=remember(grid){ProfileCardReorder(grid)}
    val latestKeys=rememberUpdatedState(keys);val latestSave=rememberUpdatedState(save)
    val density=LocalDensity.current;val haptic=LocalHapticFeedback.current
    SideEffect {
        state.keys={latestKeys.value};state.save={ids,complete->latestSave.value(ids,complete)}
        state.edgeDistance=with(density){72.dp.toPx()};state.maxSpeed=with(density){900.dp.toPx()}
        state.haptic={haptic.performHapticFeedback(HapticFeedbackType.LongPress)}
    }
    LaunchedEffect(keys) {if(state.dragging!=null&&state.dragging !in keys)state.cancel()}
    LaunchedEffect(state.dragging,state.edgeSpeed) {
        if(state.dragging!=null&&state.edgeSpeed!=0f) {
            var previous=withFrameNanos{it}
            while(isActive&&state.dragging!=null) {
                val now=withFrameNanos{it};val elapsed=((now-previous)/1_000_000_000f).coerceAtMost(.05f);previous=now
                grid.scrollBy(state.edgeSpeed*elapsed);state.moveToHoveredSlot()
            }
        }
    }
    return state
}

internal fun Modifier.reorderProfileCards(state:ProfileCardReorder):Modifier=pointerInput(state) {
    detectDragGesturesAfterLongPress(onDragStart=state::start,onDragEnd=state::finish,onDragCancel=state::cancel) {change,amount->
        if(state.dragging!=null){change.consume();state.drag(amount)}
    }
}
