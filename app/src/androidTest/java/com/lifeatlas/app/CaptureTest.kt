package com.lifeatlas.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CaptureTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun localChineseOcrReadsAmountAndDateWithoutProvider(){
        val context=rule.activity
        val bitmap=Bitmap.createBitmap(1100,600,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=64f}
        canvas.drawText("消费记录",60f,110f,paint);canvas.drawText("2026-11-04",60f,230f,paint);canvas.drawText("午餐 25.80 元",60f,350f,paint)
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"ocr-fixture.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LifeAtlasTests")})!!
        try{context.contentResolver.openOutputStream(uri)!!.use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};val text=ImageText.read(context,uri);assertTrue(text.contains("25.80"));assertTrue(text.contains("2026"));assertTrue(text.contains("午餐"))}finally{bitmap.recycle();context.contentResolver.delete(uri,null,null)}
    }
    @Test fun reviewShowsActualValuesAndConfirmHasReceipt(){
        val vm=ViewModelProvider(rule.activity)[AtlasModel::class.java]
        val metric=vm.actions.single("atlas.createObject",obj("type" to "METRIC","title" to "核对支出","config" to obj("kind" to "CHANGE","unit" to "CNY","role" to "expense","aggregate" to "SUM"))).getJSONObject("steps").getJSONObject("result").s("id")
        val plan=obj("requestId" to uid(),"title" to "核对图片记录","steps" to JSONArray().put(obj("stepId" to "r1","actionId" to "atlas.record","args" to obj("metricId" to metric,"value" to "25.80","date" to "2026-11-04"))))
        runBlocking{vm.work("预览"){obj("status" to "PREVIEW","plan" to plan)}.join()};rule.waitForIdle()
        rule.onNodeWithText("-25.8 元").performScrollTo().assertIsDisplayed();rule.onNodeWithText("2026-11-04").assertExists()
        rule.onAllNodesWithText("真实变化").assertCountEquals(0);rule.onAllNodesWithText("语音").assertCountEquals(0)
        rule.onNodeWithText("确认执行").performScrollTo().performClick();rule.waitUntil(10000){!vm.ui.value.busy && vm.ui.value.status=="已保存 1 条记录"}
        rule.onNodeWithTag("command-status").assertTextEquals("已保存 1 条记录")
        assertEquals(1,vm.store.facts(metric,"2026-11-04","2026-11-04").size)
        runBlocking{vm.execute(plan,true).join()};assertEquals(1,vm.store.facts(metric,"2026-11-04","2026-11-04").size)
        val input=rule.onNodeWithTag("command-input").fetchSemanticsNode().boundsInRoot;val send=rule.onNodeWithTag("command-send").fetchSemanticsNode().boundsInRoot
        assertTrue(send.center.x>input.center.x)
        capture("receipt-confirmed.png")
    }
    @Test fun failedConfirmKeepsPreviewAndVisibleRetry(){
        val vm=ViewModelProvider(rule.activity)[AtlasModel::class.java]
        val plan=obj("requestId" to uid(),"title" to "错误恢复测试","steps" to JSONArray().put(obj("stepId" to "r","actionId" to "atlas.record","args" to obj("metricId" to "missing-metric","value" to "5","date" to "2026-11-04"))))
        runBlocking{vm.work("预览"){obj("status" to "PREVIEW","plan" to plan)}.join()};rule.waitForIdle()
        rule.onNodeWithText("确认执行").performScrollTo().performClick();rule.waitUntil(10000){!vm.ui.value.busy && vm.ui.value.error.isNotBlank()}
        assertNotNull(vm.ui.value.pending);rule.onNodeWithTag("plan-error").performScrollTo().assertIsDisplayed();rule.onNodeWithText("重试保存").assertExists();capture("receipt-save-error.png")
    }
    private fun capture(name:String){
        rule.waitForIdle();val instrument=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();val bitmap=instrument.uiAutomation.takeScreenshot();val context=instrument.targetContext
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LifeAtlas")})!!
        context.contentResolver.openOutputStream(uri)!!.use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
