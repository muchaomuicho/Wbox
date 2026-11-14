@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.ui.test.ExperimentalTestApi::class)
package com.wbox.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,34],qualifiers="w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetBackUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun backKeyKeepsVetoedSheetAndDismissesAfterProtectionEnds() {
        val allow=mutableStateOf(false)
        val visible=mutableStateOf(true)
        var dismissed=0
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    if(visible.value)WboxBottomSheet(
                        onDismissRequest={dismissed++;visible.value=false},
                        confirmValueChange={it!=SheetValue.Hidden||allow.value}
                    ) {
                        OutlinedTextField(value="",onValueChange={},modifier=Modifier.testTag("sheet-back-field"))
                    }
                }
            }
        }
        ui.onNodeWithTag("sheet-back-field").performClick()
        ui.onNodeWithTag("sheet-back-field").performKeyInput {pressKey(Key.Back)}
        ui.waitForIdle()
        ui.onNodeWithTag("sheet-back-field").assertIsDisplayed()
        ui.runOnIdle {assertEquals(0,dismissed);allow.value=true}
        ui.onNodeWithTag("sheet-back-field").performKeyInput {pressKey(Key.Back)}
        ui.waitUntil(10000){!visible.value}
        ui.runOnIdle {assertEquals(1,dismissed)}
    }
}
