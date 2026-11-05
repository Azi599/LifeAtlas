package com.lifeatlas.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Rule
import org.junit.Test

class UiTest {
    @get:Rule(order=0) val rule=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=1) val failures=object:org.junit.rules.TestWatcher(){override fun failed(error:Throwable,description:org.junit.runner.Description){runCatching{capture("failure-"+description.methodName+".png")}}}
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
        if(vm.store.metrics().none{it.s("title")=="浇水量"}) {
            val m=obj("id" to "ui.seed","title" to "植物观察","version" to 1,"metrics" to JSONArray().put(obj("key" to "water","title" to "浇水量","kind" to "CHANGE","unit" to "ml","aggregate" to "SUM")))
            vm.actions.single("atlas.installPlugin",obj("manifest" to m),confirmed=true)
            val id=vm.store.metrics().first{it.s("title")=="浇水量"}.s("id");vm.actions.single("atlas.record",obj("metricId" to id,"value" to "250","date" to java.time.LocalDate.now().toString()))
        }
        runBlocking{vm.action("atlas.resetTheme",obj(),"默认主题").join()};rule.waitForIdle();capture("default-home.png")
        rule.onNodeWithText("记录").performClick();rule.onNodeWithTag("atlas-list").performScrollToNode(hasTestTag("record-item"));rule.onAllNodesWithTag("record-item").onFirst().assertIsDisplayed();capture("records.png")
        rule.onNodeWithText("显示：记录").performScrollTo().performClick();rule.onNodeWithText("时间轴").performClick();rule.onNodeWithTag("atlas-list").performScrollToNode(hasTestTag("timeline-item"));rule.onAllNodesWithTag("timeline-item").onFirst().assertIsDisplayed();capture("timeline.png")
        rule.onNodeWithText("分析").performClick();rule.onNodeWithText("选择指标：请选择").performScrollTo().performClick();rule.onNodeWithText("浇水量").performClick();rule.onNodeWithTag("trend-chart").performScrollTo().assertIsDisplayed();capture("analysis.png")
        rule.onNodeWithText("首页").performClick();rule.onNodeWithText("输入").performTextInput("创建新的生活项目");capture("keyboard.png");closeKeyboard()
        rule.onNodeWithText("我的").performClick();rule.onNodeWithText("外观").performScrollTo().performClick();rule.onNodeWithText("已保存主题：薰衣草").assertExists();capture("theme-editor.png")
        rule.onNodeWithText("主题名称").performScrollTo().performTextClearance();rule.onNodeWithText("主题名称").performTextInput("粉紫日记");closeKeyboard()
        rule.onNodeWithText("主色").performScrollTo().performClick();rule.onNodeWithText("调色盘").assertExists();capture("color-picker.png")
        rule.onNodeWithText("HEX").performTextClearance();rule.onNodeWithText("HEX").performTextInput("#BAA1D7");rule.onNodeWithText("选择").performClick()
        rule.onNodeWithText("背景：纯色").performScrollTo().performClick();rule.onNodeWithText("渐变").performClick();rule.onNodeWithText("添加色标").performScrollTo().performClick()
        rule.onNodeWithText("色标 3").performScrollTo().performClick();rule.onNodeWithText("HEX").performTextClearance();rule.onNodeWithText("HEX").performTextInput("#FBCBDF");rule.onNodeWithText("选择").performClick()
        rule.onNodeWithText("另存为新主题").performScrollTo().assertIsDisplayed();capture("gradient-editor.png");rule.onNodeWithText("另存为新主题").performClick()
        rule.waitUntil(10000){vm.ui.value.themeId!="lavender" && !vm.ui.value.busy}
        val savedId=vm.ui.value.themeId;val saved=org.json.JSONObject(vm.ui.value.themeConfig).getJSONObject("light")
        org.junit.Assert.assertEquals("#BAA1D7",saved.s("primary"));org.junit.Assert.assertTrue(saved.getBoolean("gradient"));org.junit.Assert.assertEquals(3,saved.getJSONArray("stops").length())
        rule.onNodeWithText("单色主题图标").performScrollTo().assertIsDisplayed();capture("icon-masks.png")
        rule.activityRule.scenario.recreate();vm=ViewModelProvider(rule.activity)[AtlasModel::class.java];runBlocking{vm.refresh().join()};rule.waitForIdle();org.junit.Assert.assertEquals(savedId,vm.ui.value.themeId)
        rule.onNodeWithText("首页").performClick();capture("custom-gradient-home.png")
        val manifest=obj("id" to "ui.preview"+System.currentTimeMillis(),"title" to "观鸟记录","version" to 1,"metrics" to JSONArray().put(obj("key" to "bird","title" to "观察数量","kind" to "CHANGE","unit" to "只","aggregate" to "SUM","fields" to JSONArray().put(obj("id" to "species","title" to "鸟类名称","type" to "text")))))
        runBlocking{vm.action("atlas.installPlugin",obj("manifest" to manifest),"预览观鸟记录").join()};rule.waitForIdle();rule.onNodeWithText("表单预览：观察数量").performScrollTo().assertIsDisplayed();capture("plugin-generation-preview.png")
        rule.onNodeWithText("鸟类名称").performScrollTo().assertIsDisplayed();capture("plugin-generation-form.png")
        vm.clearPending();runBlocking{vm.action("atlas.setAppearance",obj("mode" to "dark"),"深色").join()};rule.waitForIdle();capture("dark-home.png")
        runBlocking{vm.action("atlas.resetTheme",obj(),"默认主题").join()}
    }
    private fun closeKeyboard(){rule.activity.runOnUiThread{(rule.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(rule.activity.currentFocus?.windowToken,0)};Thread.sleep(600)}
    @org.junit.After fun restoreDefault(){runCatching{val vm=ViewModelProvider(rule.activity)[AtlasModel::class.java];runBlocking{vm.action("atlas.resetTheme",obj(),"默认主题").join()}}}
    private fun capture(name:String) {
        rule.waitForIdle()
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val committed=java.util.concurrent.CountDownLatch(1)
        rule.activity.runOnUiThread{val view=rule.activity.window.decorView;view.viewTreeObserver.registerFrameCommitCallback{committed.countDown()};view.invalidate()}
        committed.await(3,java.util.concurrent.TimeUnit.SECONDS);Thread.sleep(300)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        val context=instrumentation.targetContext
        val values=android.content.ContentValues().apply{put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,name);put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LifeAtlas")}
        val uri=context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
