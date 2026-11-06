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
        rule.onNodeWithTag("tab-settings").performClick()
        rule.onNodeWithText("空间管理").performClick()
        rule.onNodeWithText("新建").performClick()
        rule.onNodeWithText("名称").performTextInput("离线项目-${System.currentTimeMillis()}")
        rule.onNodeWithText("创建",useUnmergedTree=true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("新建").assertExists()
    }
    @Test fun dynamicPluginRendersAndAcceptsRecord(){
        val vm=ViewModelProvider(rule.activity)[AtlasModel::class.java]
        val manifest=obj("id" to "test.random${System.currentTimeMillis()}","title" to "植物养护测试","version" to 1,"metrics" to JSONArray().put(obj("key" to "water","title" to "浇水量","kind" to "CHANGE","unit" to "ml","aggregate" to "SUM")),"views" to JSONArray().put(obj("title" to "浇水趋势","metricKey" to "water","chart" to "bar")))
        val result=vm.actions.single("atlas.installPlugin",obj("manifest" to manifest),confirmed=true)
        val project=result.getJSONObject("steps").getJSONObject("result").s("id")
        val metric=vm.store.objects(project).first{it.s("type")=="METRIC"}
        vm.actions.single("atlas.record",obj("metricId" to metric.s("id"),"value" to "250","date" to java.time.LocalDate.now().toString()))
        runBlocking{vm.refresh().join()};rule.waitForIdle()
        rule.onNodeWithTag("tab-settings").performClick()
        rule.onNodeWithText("空间管理").performClick()
        rule.onNodeWithTag("atlas-list").performScrollToNode(hasText("植物养护测试"))
        rule.onNodeWithText("植物养护测试").performScrollTo().performClick()
        rule.onNodeWithText("浇水量").performScrollTo().performClick()
        rule.onNodeWithText("新增记录").assertExists()
        capture("lifeatlas-project.png")
    }
    @Test fun ambiguousRecordCanChangeMetricBeforeSaving() {
        val vm = ViewModelProvider(rule.activity)[AtlasModel::class.java]
        val date = java.time.LocalDate.now().toString()
        fun create(title: String, unit: String): String {
            val config = obj("kind" to "ALLOCATION", "unit" to unit, "aggregate" to "SUM")
            return vm.actions.single("atlas.createObject", obj("type" to "METRIC", "title" to title, "config" to config))
                .getJSONObject("steps").getJSONObject("result").getString("id")
        }
        val wrong = create("舞蹈练习", "小时")
        val right = create("吉他练习", "分钟")
        val args = obj("metricId" to wrong, "value" to "0.5", "date" to date)
        val step = obj("stepId" to "s1", "actionId" to "atlas.record", "args" to args)
        val plan = obj("title" to "记下半小时练习", "ai" to true, "steps" to JSONArray().put(step))
        PlanReview.assignMetricChoices(plan, "刚练了半小时", vm.store.objects(all = true))
        runBlocking { vm.execute(plan, true).join() }
        org.junit.Assert.assertTrue(vm.store.facts(wrong).isEmpty())
        org.junit.Assert.assertTrue(vm.ui.value.error.contains("归属"))
        runBlocking { vm.execute(plan.put("fromChoice", true), true).join() }
        rule.onNodeWithText("确认执行").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("记到：请选择").performScrollTo().performClick()
        rule.onNodeWithText("吉他练习").performScrollTo().performClick()
        rule.onNodeWithText("30 分钟").performScrollTo().assertIsDisplayed()
        capture("record-metric-review.png")
        rule.onNodeWithText("确认执行").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(10000) { !vm.ui.value.busy && vm.ui.value.pending == null }
        rule.onNodeWithTag("command-status").assertIsDisplayed()
        org.junit.Assert.assertTrue(vm.store.facts(wrong).isEmpty())
        org.junit.Assert.assertEquals(0, vm.store.facts(right).single().s("value").toBigDecimal().compareTo(30.toBigDecimal()))
    }

    @Test fun themeEditorPreviewAndRestartRender() {
        var vm=ViewModelProvider(rule.activity)[AtlasModel::class.java]
        if(vm.store.metrics().none{it.s("title")=="浇水量"}) {
            val m=obj("id" to "ui.seed","title" to "植物观察","version" to 1,"metrics" to JSONArray().put(obj("key" to "water","title" to "浇水量","kind" to "CHANGE","unit" to "ml","aggregate" to "SUM")))
            vm.actions.single("atlas.installPlugin",obj("manifest" to m),confirmed=true)
            val id=vm.store.metrics().first{it.s("title")=="浇水量"}.s("id");vm.actions.single("atlas.record",obj("metricId" to id,"value" to "250","date" to java.time.LocalDate.now().toString()))
        }
        runBlocking{vm.action("atlas.resetTheme",obj(),"默认主题").join()};rule.waitForIdle();capture("default-home.png")
        rule.onNodeWithTag("tab-history").performClick()
        rule.onNodeWithTag("atlas-list").performScrollToNode(hasTestTag("timeline-item"))
        rule.onAllNodesWithTag("timeline-item").onFirst().assertIsDisplayed()
        capture("timeline.png")
        rule.onNodeWithTag("tab-analytics").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("composition-pie").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("composition-pie").performScrollTo().assertIsDisplayed()
        capture("analysis-pie.png")
        rule.waitUntil(10000){rule.onAllNodesWithTag("trend-chart").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("trend-chart").performScrollTo().assertIsDisplayed()
        capture("analysis-bars.png")
        rule.onNodeWithTag("tab-workspace").performClick()
        rule.onNodeWithTag("command-input").performClick().performTextInput("创建新的生活项目")
        rule.waitUntil(10000){androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}
        capture("keyboard.png");closeKeyboard()
        rule.onNodeWithTag("tab-settings").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()};rule.waitUntil(10000){rule.onAllNodesWithText("外观").fetchSemanticsNodes().isNotEmpty()};rule.onNodeWithText("外观").performScrollTo().performClick();rule.onNodeWithText("已保存主题：薰衣草").assertExists();capture("theme-editor.png")
        rule.onNodeWithText("主题名称").performScrollTo().performTextClearance();rule.onNodeWithText("主题名称").performTextInput("粉紫日记");closeKeyboard()
        rule.onNodeWithText("主色").performScrollTo().performClick();rule.onNodeWithText("调色盘").assertExists();capture("color-picker.png")
        rule.onNodeWithText("HEX").performTextClearance();rule.onNodeWithText("HEX").performTextInput("#BAA1D7");rule.onNodeWithText("选择").performClick();closeKeyboard()
        rule.onNodeWithText("背景：纯色").performScrollTo().performClick();rule.onNodeWithText("渐变").performClick();rule.onNodeWithText("添加色标").performScrollTo().performClick()
        rule.onNodeWithText("色标 3").performScrollTo().performClick();rule.onNodeWithText("HEX").performTextClearance();rule.onNodeWithText("HEX").performTextInput("#FBCBDF");rule.onNodeWithText("选择").performClick();closeKeyboard()
        rule.onNodeWithText("另存为新主题").performScrollTo().assertIsDisplayed();capture("gradient-editor.png");rule.onNodeWithText("另存为新主题").performClick()
        rule.waitUntil(10000){vm.ui.value.themeId!="lavender" && !vm.ui.value.busy}
        val savedId=vm.ui.value.themeId;val saved=org.json.JSONObject(vm.ui.value.themeConfig).getJSONObject("light")
        org.junit.Assert.assertEquals("#BAA1D7",saved.s("primary"));org.junit.Assert.assertTrue(saved.getBoolean("gradient"));org.junit.Assert.assertEquals(3,saved.getJSONArray("stops").length())
        rule.onNodeWithText("单色主题图标").performScrollTo().assertIsDisplayed();capture("icon-masks.png")
        rule.activityRule.scenario.recreate();vm=ViewModelProvider(rule.activity)[AtlasModel::class.java];runBlocking{vm.refresh().join()};rule.waitForIdle();org.junit.Assert.assertEquals(savedId,vm.ui.value.themeId)
        rule.onNodeWithTag("tab-workspace").performClick();capture("custom-gradient-home.png")
        val manifest=obj("id" to "ui.preview"+System.currentTimeMillis(),"title" to "观鸟记录","version" to 1,"metrics" to JSONArray().put(obj("key" to "bird","title" to "观察数量","kind" to "CHANGE","unit" to "只","aggregate" to "SUM","fields" to JSONArray().put(obj("id" to "species","title" to "鸟类名称","type" to "text")))))
        runBlocking{vm.action("atlas.installPlugin",obj("manifest" to manifest),"预览观鸟记录").join()};rule.waitForIdle();rule.onNodeWithText("表单预览：观察数量").performScrollTo().assertIsDisplayed();capture("plugin-generation-preview.png")
        rule.onNodeWithText("鸟类名称").performScrollTo().assertIsDisplayed();capture("plugin-generation-form.png")
        vm.clearPending();runBlocking{vm.action("atlas.setAppearance",obj("mode" to "dark"),"深色").join()};rule.waitForIdle();capture("dark-home.png")
        runBlocking{vm.action("atlas.resetTheme",obj(),"默认主题").join()}
    }
    @Test fun localInputAndTimelineEditWorkAcrossFourTabs() {
        val vm=ViewModelProvider(rule.activity)[AtlasModel::class.java]
        val title="测试咖啡杯数"
        val metric = vm.actions.single("atlas.createObject",obj("type" to "METRIC","title" to title,
            "config" to obj("kind" to "CHANGE","unit" to "杯","aggregate" to "SUM")))
            .getJSONObject("steps").getJSONObject("result").s("id")
        runBlocking { vm.refresh().join() }
        listOf("workspace","history","analytics","settings").forEach { id -> rule.onNodeWithTag("tab-$id").assertIsDisplayed() }
        rule.onNodeWithText("空间管理").assertDoesNotExist()
        rule.onNodeWithTag("command-input").performTextReplacement("记录今天${title}两杯")
        rule.onNodeWithTag("command-send").performClick()
        rule.waitUntil(10000) { vm.store.facts(metric).isNotEmpty() && !vm.ui.value.busy }
        closeKeyboard()
        rule.onNodeWithTag("tab-history").performClick()
        rule.onNodeWithTag("command-input").assertDoesNotExist()
        val row = hasTestTag("timeline-item") and hasAnyDescendant(hasText(title))
        rule.onNodeWithTag("atlas-list").performScrollToNode(row)
        rule.onNode(hasText("修改") and hasAnyAncestor(row)).performScrollTo().performClick()
        rule.onNodeWithText("数值").performTextReplacement("3")
        rule.onNodeWithText("保存").assertIsDisplayed().performClick()
        rule.waitUntil(10000) { vm.store.facts(metric).single().s("value")=="3" && !vm.ui.value.busy }
        rule.onNode(hasText("3 杯") and hasAnyAncestor(row)).assertIsDisplayed()
    }

    private fun closeKeyboard(){
        rule.activity.runOnUiThread{
            rule.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime())
            val token=rule.activity.currentFocus?.windowToken
            rule.activity.currentFocus?.clearFocus()
            (rule.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(token,0)
        }
        rule.waitUntil(10000){androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true}
        rule.waitForIdle();Thread.sleep(300)
    }

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
