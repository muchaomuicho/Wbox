@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.wbox.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetFlingUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val visible=mutableStateOf(true)
    private var dismissed=0
    private lateinit var listState:LazyListState
    private var pageOverscroll=true
    private var contentOverscroll=true
    private val dispatcher=NestedScrollDispatcher()
    private lateinit var scope:CoroutineScope

    private fun show() {
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    pageOverscroll=LocalOverscrollConfiguration.current!=null
                    if(visible.value)WboxBottomSheet(
                        onDismissRequest={dismissed++;visible.value=false},
                        modifier=Modifier.testTag("fling-sheet")
                    ) {
                        contentOverscroll=LocalOverscrollConfiguration.current!=null
                        scope=rememberCoroutineScope()
                        Text("Fling boundary",Modifier.height(48.dp))
                        listState=rememberLazyListState()
                        val dispatchConnection=remember{object:NestedScrollConnection{}}
                        LazyColumn(Modifier.height(480.dp).fillMaxWidth().testTag("fling-list")
                            .nestedScroll(dispatchConnection,dispatcher),
                            state=listState) {
                            items(100){Text("row $it",Modifier.fillMaxWidth().height(48.dp))}
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
        ui.onNodeWithText("Fling boundary").assertIsDisplayed()
    }

    @Test fun remainingDownwardContentVelocityAtTopDoesNotMoveSettledSheet() {
        show()
        val initialTop=ui.onNodeWithText("Fling boundary").fetchSemanticsNode().positionInRoot.y
        ui.mainClock.autoAdvance=false
        // A list that reaches its top after pointer-up hands its remaining velocity to the
        // sheet via post-fling. No finger drag has displaced the sheet from its anchor.
        ui.runOnIdle {scope.launch{dispatcher.dispatchPostFling(Velocity.Zero,Velocity(0f,8000f))}}
        val tops=mutableListOf<Float>()
        repeat(45) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            tops+=ui.onNodeWithText("Fling boundary").fetchSemanticsNode().positionInRoot.y
        }
        ui.mainClock.autoAdvance=true
        assertTrue("Remaining list velocity kicked the settled sheet: initial=$initialTop max=${tops.maxOrNull()}",
            tops.all{it<=initialTop+1f})
        assertEquals(0,dismissed)
    }

    private fun directUpFlingStaysInsideWindow(fromHandle:Boolean) {
        show()
        val title=ui.onNodeWithText("Fling boundary")
        val initialTop=title.fetchSemanticsNode().positionInRoot.y
        ui.mainClock.autoAdvance=false
        title.performTouchInput {
            // The default handle occupies the 48dp immediately above this 48dp title.
            // Inject from its center for the handle case, or from inside the title itself.
            val start=Offset(center.x,if(fromHandle)-height*.5f else center.y)
            swipe(start,start+Offset(0f,-160f),durationMillis=64)
        }
        val tops=mutableListOf<Float>()
        repeat(45) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            tops+=title.fetchSemanticsNode().positionInRoot.y
        }
        ui.mainClock.autoAdvance=true
        assertTrue("Direct ${if(fromHandle)"handle" else "header"} fling crossed expanded boundary: initial=$initialTop min=${tops.minOrNull()}",
            tops.all{it>=initialTop-1f})
        assertEquals(0,dismissed)
    }

    @Test fun fastHeaderUpFlingDoesNotCrossExpandedBoundary()=directUpFlingStaysInsideWindow(false)

    @Test fun fastHandleUpFlingDoesNotCrossExpandedBoundary()=directUpFlingStaysInsideWindow(true)

    @Test fun edgeStretchIsDisabledForSheetContentAndRemainsEnabledForMainPage() {
        show()
        assertTrue(pageOverscroll)
        assertFalse(contentOverscroll)
    }

    @Test fun normalListFlingKeepsItsMomentum() {
        show()
        ui.mainClock.autoAdvance=false
        ui.onNodeWithTag("fling-list").performTouchInput {
            swipe(Offset(center.x,height*.8f),Offset(center.x,height*.2f),durationMillis=96)
        }
        var released=0
        ui.runOnIdle {released=listState.firstVisibleItemIndex}
        repeat(30){ui.mainClock.advanceTimeByFrame();ui.waitForIdle()}
        ui.runOnIdle {assertTrue("Content lost its normal fling",listState.firstVisibleItemIndex>released)}
        ui.mainClock.autoAdvance=true
        assertEquals(0,dismissed)
    }

    @Test fun fastListFlingAtBottomDoesNotPullExpandedSheetAboveItsBoundary() {
        show()
        ui.onNodeWithTag("fling-list").performScrollToIndex(99)
        ui.waitForIdle()
        val initialTop=ui.onNodeWithText("Fling boundary").fetchSemanticsNode().positionInRoot.y
        ui.mainClock.autoAdvance=false
        ui.onNodeWithTag("fling-list").performTouchInput {
            swipe(Offset(center.x,height*.8f),Offset(center.x,height*.2f),durationMillis=64)
        }
        val tops=mutableListOf<Float>()
        repeat(45) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            tops+=ui.onNodeWithText("Fling boundary").fetchSemanticsNode().positionInRoot.y
        }
        ui.mainClock.autoAdvance=true
        assertTrue("Fling moved sheet above its anchor: initial=$initialTop min=${tops.minOrNull()}",
            tops.all{it>=initialTop-1f})
        assertEquals(0,dismissed)
    }

    @Test fun downwardDragFromListTopStillDismissesExactlyOnce() {
        show()
        ui.onNodeWithTag("fling-list").performTouchInput {
            swipe(Offset(center.x,height*.1f),Offset(center.x,height*.9f),durationMillis=96)
        }
        ui.waitUntil(10000){!visible.value}
        ui.waitForIdle()
        assertEquals(1,dismissed)
        ui.onNodeWithTag("fling-sheet").assertDoesNotExist()
    }
}
