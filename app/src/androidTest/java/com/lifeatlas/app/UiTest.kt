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
        capture("lifeatlas-project.png")
    }
    @Test fun themeEditorPreviewAndRestartRender() {
        var vm=ViewModelProvider(rule.activity)[AtlasModel::class.java]
        runBlocking{vm.action("atlas.resetTheme",obj(),"默认主题").join()};rule.waitForIdle();capture("default-home.png")
        rule.onNodeWithText("我的").performClick();rule.onNodeWithText("外观").performScrollTo().performClick();rule.onNodeWithText("已保存主题：薰衣草").assertExists();capture("theme-editor.png")
        rule.onNodeWithText("主色").performScrollTo().performClick();rule.onNodeWithText("调色盘").assertExists();capture("color-picker.png");rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("应用图标").performScrollTo();capture("icon-masks.png")
        val config=ThemeDefaults.config();config.getJSONObject("light").put("gradient",true).put("angle",30).put("stops",JSONArray().put(obj("color" to "#D8BFF2","position" to 0)).put(obj("color" to "#FAC9DE","position" to 0.5)).put(obj("color" to "#E8DDF9","position" to 1)))
        runBlocking{vm.action("atlas.saveTheme",obj("id" to "ui-gradient","title" to "粉紫渐变","config" to config),"保存主题").join()}
        rule.activityRule.scenario.recreate();vm=ViewModelProvider(rule.activity)[AtlasModel::class.java];runBlocking{vm.refresh().join()};rule.waitForIdle();org.junit.Assert.assertEquals("ui-gradient",vm.ui.value.themeId);capture("custom-gradient-home.png")
        val manifest=obj("id" to "ui.preview"+System.currentTimeMillis(),"title" to "观鸟记录","version" to 1,"metrics" to JSONArray().put(obj("key" to "bird","title" to "观察数量","kind" to "CHANGE","unit" to "只","aggregate" to "SUM","fields" to JSONArray().put(obj("id" to "species","title" to "鸟类名称","type" to "text")))))
        runBlocking{vm.action("atlas.installPlugin",obj("manifest" to manifest),"预览观鸟记录").join()};rule.waitForIdle();rule.onNodeWithText("表单预览：观察数量").performScrollTo();capture("plugin-generation-preview.png")
        vm.clearPending();runBlocking{vm.action("atlas.setAppearance",obj("mode" to "dark"),"深色").join()};rule.waitForIdle();capture("dark-home.png")
        runBlocking{vm.action("atlas.resetTheme",obj(),"默认主题").join()}
    }
    private fun capture(name:String) {
        rule.waitForIdle()
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync();Thread.sleep(600)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        val context=instrumentation.targetContext
        val values=android.content.ContentValues().apply{put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name);put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LifeAtlas")}
        val uri=context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
