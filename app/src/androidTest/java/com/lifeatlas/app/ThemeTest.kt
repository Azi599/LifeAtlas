package com.lifeatlas.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThemeTest {
    @get:Rule val rule=createComposeRule()
    @Test fun arbitraryColorsKeepReadableControlsAndDynamicFields() {
        val config=ThemeDefaults.config();config.getJSONObject("light").put("primary","#FFFEEE").put("surface","#FFFFFF").put("text","#EEEEEE")
        var foreground=Color.White;var background=Color.White;var action=Color.White
        rule.setContent{AtlasTheme(config,false){SideEffect{foreground=MaterialTheme.colorScheme.onSurface;background=MaterialTheme.colorScheme.surface;action=MaterialTheme.colorScheme.primary};DynamicField(obj("id" to "hours","title" to "独立生成的长字段名称：本次观测的持续时间","type" to "decimal"),"12"){} }}
        rule.waitForIdle();assertTrue(contrastRatio(foreground,background)>=4.5);assertTrue(contrastRatio(action,background)>=4.5)
        rule.onNodeWithText("独立生成的长字段名称：本次观测的持续时间").assertExists();rule.onNodeWithText("12").assertExists()
    }
}
