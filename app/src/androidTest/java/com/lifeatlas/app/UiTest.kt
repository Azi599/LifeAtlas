package com.lifeatlas.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Rule
import org.junit.Test

class UiTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun manualProjectCreationWorksWithoutAi(){
        rule.onNodeWithText("手动创建").performClick()
        rule.onNodeWithText("名称").performTextInput("离线项目-${System.currentTimeMillis()}")
        rule.onNodeWithText("创建",useUnmergedTree=true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("手动创建").assertExists()
    }
    @Test fun dynamicPluginRendersAndAcceptsRecord(){
        val vm=ViewModelProvider(rule.activity)[AtlasModel::class.java]
        val manifest=obj("id" to "test.random${System.currentTimeMillis()}","title" to "植物养护测试","version" to 1,"metrics" to JSONArray().put(obj("key" to "water","title" to "浇水量","kind" to "CHANGE","unit" to "ml","aggregate" to "SUM")),"views" to JSONArray().put(obj("title" to "浇水趋势","metricKey" to "water","chart" to "bar")))
        val result=vm.actions.single("atlas.installPlugin",obj("manifest" to manifest),confirmed=true)
        val project=result.getJSONObject("steps").getJSONObject("result").s("id")
        val metric=vm.store.objects(project).first{it.s("type")=="METRIC"}
        vm.actions.single("atlas.record",obj("metricId" to metric.s("id"),"value" to "250","date" to java.time.LocalDate.now().toString()))
        runBlocking{vm.refresh().join()};rule.waitForIdle()
        rule.onNodeWithText("植物养护测试").performScrollTo().performClick()
        rule.onNodeWithText("浇水量").performScrollTo().performClick()
        rule.onNodeWithText("新增记录").assertExists()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/lifeatlas-project.png").close()
    }
}
