package com.lifeatlas.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.LocalDate
import java.time.ZoneId
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Runs against an actual model in a clean emulator. Only fixture records are used. */
class LanguageAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: AtlasModel
    private val results = JSONArray()
    private val today = LocalDate.now()
    private val yesterday = today.minusDays(1)
    private val selectedCases = InstrumentationRegistry.getArguments().getString("atlasAcceptanceCases", "")
        .split(',').filter { it.isNotBlank() }.toSet()

    @Test fun naturalLanguageFromHomeThroughStorageAndPages() {
        val certificate = InstrumentationRegistry.getArguments().getString("atlasAcceptanceCert")
        assumeTrue("Run with the isolated acceptance model", !certificate.isNullOrBlank())
        val originalFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(testTls(certificate!!).socketFactory)
        model = ViewModelProvider(rule.activity)[AtlasModel::class.java]
        try {
            model.settings.saveAi(
                "https://127.0.0.1:9443/v1/chat/completions",
                "lifeatlas-acceptance", "local-test-provider"
            )
            val weight = metric("晨间体重", "SNAPSHOT", "kg", "LAST_SNAPSHOT")
            val expense = metric("生活支出", "CHANGE", "元", "SUM", "expense")
            val study = metric("学习时间", "ALLOCATION", "分钟", "SUM")
            val sleep = metric("睡眠时长", "ALLOCATION", "分钟", "SUM", "sleep")
            runBlocking { model.refresh().join() }

            scenario("project", "开个叫复习安排的项目，先建个空项目就行。") {
                val projects = model.store.objects(all = true).filter { it.s("type") == "PROJECT" }
                val project = projects.single { it.s("title") == "复习安排" }
                assertTrue(model.store.objects(project.s("id")).isEmpty())
                show("复习安排")
                go("空间")
                show("复习安排")
            }
            scenario("weight-yesterday", "昨早秤了一下，晨间体重是65.2公斤。") {
                val row = record(weight, yesterday, "65.2")
                assertEquals("SNAPSHOT", row.s("kind"))
                assertEquals("DATE", row.s("precision"))
                assertTrue(row.isNull("occurred_at"))
                show("65.2 kg")
            }
            scenario("weight-paraphrase", "今天称出来六十五公斤整，给我记到晨间体重。") {
                record(weight, today, "65")
                go("记录")
                show("65", substring = true)
                rule.onNodeWithText("显示：记录").performScrollTo().performClick()
                rule.onNodeWithText("时间轴").performClick()
                rule.onNodeWithTag("atlas-list").performScrollToNode(hasTestTag("timeline-item"))
                show("晨间体重")
            }
            scenario("mixed", "昨天晚饭花了18块5，另外学了半小时。没有具体开始时间，也不知道用哪个账户付的。") { evidence ->
                chooseMetric(study, "学习时间", "分钟", evidence)
                confirmIfNeeded()
                val spent = record(expense, yesterday, "-18.50")
                val learned = record(study, yesterday, "30")
                assertEquals("DATE", spent.s("precision"))
                assertEquals("DATE", learned.s("precision"))
                assertTrue(learned.isNull("start_at"))
                assertTrue(JSONObject(spent.s("fields_json")).s("accountId").isBlank())
                assertTrue(model.store.facts(sleep).isEmpty())
                show("-18.50 元")
                show("${learned.s("value")} 分钟")
                capture("mixed-receipt")
                go("记录")
                if (rule.onAllNodesWithText("显示：时间轴").fetchSemanticsNodes().isNotEmpty()) {
                    rule.onNodeWithText("显示：时间轴").performScrollTo().performClick()
                    rule.onNode(hasText("记录") and hasAnyAncestor(isDialog())).performClick()
                }
                if (yesterday.month != today.month) {
                    val before = model.ui.value
                    rule.onNodeWithText("开始日期").performScrollTo().performTextReplacement(yesterday.toString())
                    hideKeyboard()
                    rule.onNodeWithText("查询").performScrollTo().performClick()
                    waitForWork(before)
                }
                show("-18.50 · $yesterday")
                show("${learned.s("value")} · $yesterday")
                capture("mixed-history")
                go("分析")
                val picker = rule.onNodeWithText("选择指标：请选择")
                picker.performScrollTo().performClick()
                rule.onNode(hasText("学习时间") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
                if (yesterday.month != today.month) {
                    rule.onNodeWithText("分析月份 YYYY-MM").performScrollTo().performTextReplacement(yesterday.toString().take(7))
                    hideKeyboard()
                    rule.onNodeWithText("查看月份").performScrollTo().performClick()
                }
                val total = model.store.aggregate(study, yesterday.toString(), yesterday.toString()).s("value")
                assertEquals(0, total.toBigDecimal().compareTo(30.toBigDecimal()))
                rule.waitUntil(10000) { rule.onAllNodesWithText("$total 分钟").fetchSemanticsNodes().isNotEmpty() }
                show("$total 分钟")
                rule.onNodeWithTag("trend-chart").performScrollTo().assertIsDisplayed()
                capture("mixed-analysis")
            }
            scenario("edit-history", "把昨天的晨间体重从65.2更正为64.9公斤，只改那一条。") {
                assertNotNull("更正应先给核对", model.ui.value.pending)
                record(weight, yesterday, "65.2")
                show("65.2 → 64.9", substring = true)
                confirmIfNeeded()
                assertTrue(record(weight, yesterday, "64.9").optLong("revision") > 1)
                record(weight, today, "65")
                assertEquals(1, model.store.facts(weight, yesterday.toString(), yesterday.toString()).size)
            }
            scenario("midnight", "昨天晚上23点40分睡着，今天零点20分起来，记一下这次睡眠。") { evidence ->
                chooseMetric(sleep, "睡眠时长", "分钟", evidence)
                confirmIfNeeded()
                val row = record(sleep, yesterday, "40")
                assertEquals("INTERVAL", row.s("precision"))
                val start = java.time.Instant.parse(row.s("start_at")).atZone(ZoneId.systemDefault())
                val end = java.time.Instant.parse(row.s("end_at")).atZone(ZoneId.systemDefault())
                assertEquals(yesterday.atTime(23,40), start.toLocalDateTime())
                assertEquals(today.atTime(0,20), end.toLocalDateTime())
            }
            val beforeAmbiguity = model.store.query("SELECT id,value,revision FROM atlas_facts ORDER BY id").map { it.toString() }
            scenario("ambiguous-edit", "刚才那条，帮我改一下。") {
                val pending = model.ui.value.pending ?: error("应追问具体改什么")
                assertTrue(pending.s("question").isNotBlank())
                assertEquals(beforeAmbiguity, model.store.query("SELECT id,value,revision FROM atlas_facts ORDER BY id").map { it.toString() })
                show(pending.s("question"))
            }
            val previousTotal = model.store.aggregate(expense, yesterday.toString(), today.toString()).s("value").toBigDecimalOrNull()
                ?: java.math.BigDecimal.ZERO
            scenario("report", "今天买面包6元，记入生活支出。再把昨天到今天的支出汇总，生成一个叫两日支出核对的报告页。") {
                confirmIfNeeded()
                val report = model.store.objects(all = true).single { it.s("title") == "两日支出核对" }
                assertEquals("VIEW", report.s("type"))
                val config = JSONObject(report.s("config"))
                assertEquals("report", config.s("component"))
                assertEquals(yesterday.toString(), config.s("from"))
                assertEquals(today.toString(), config.s("to"))
                show("打开两日支出核对")
                rule.onNodeWithText("打开两日支出核对").performClick()
                record(expense, today, "-6.00")
                val expected = (previousTotal - java.math.BigDecimal("6.00")).stripTrailingZeros().toPlainString()
                rule.waitUntil(10000) { rule.onAllNodesWithText("$expected 元").fetchSemanticsNodes().isNotEmpty() }
                show("$expected 元")
            }
            scenario("new-feature", "做个城市观鸟功能，记录鸟名、看到的只数、有没有听到叫声。我要录入入口、历史记录和趋势图。") {
                val draft = model.ui.value.pending ?: error("新功能应先给预览")
                val manifest = draft.getJSONArray("steps").objects().first { it.s("actionId") == "atlas.installPlugin" }
                    .getJSONObject("args").getJSONObject("manifest")
                show(manifest.s("title"))
                confirmIfNeeded()
                val plugin = model.store.query("SELECT manifest FROM atlas_plugins WHERE plugin_id=? AND status='ACTIVE'", arrayOf(manifest.s("id"))).single()
                val installed = JSONObject(plugin.s("manifest"))
                val fields = installed.getJSONArray("metrics").objects().flatMap { it.optJSONArray("fields")?.objects().orEmpty() }
                assertTrue("鸟名字段", fields.any { it.s("title").contains("鸟") && it.s("type") == "text" })
                assertTrue("是否听到叫声字段", fields.any { it.s("title").contains("叫声") && it.s("type") == "boolean" })
                show("打开${manifest.s("title")}")
                rule.onNodeWithText("打开${manifest.s("title")}").performClick()
                show(manifest.s("title"))
                val pages = model.store.query("SELECT config FROM atlas_objects WHERE plugin_id=? AND type='VIEW' AND archived=0", arrayOf(manifest.s("id")))
                val components = pages.flatMap { row -> JSONObject(row.s("config")).optJSONArray("components")?.objects().orEmpty() }
                val types = components.map { it.s("component") }.toSet()
                assertTrue("实际页面缺录入、历史或趋势：$types", types.containsAll(setOf("form", "history", "chart")))
                checkGeneratedFeature(manifest.s("id"), fields)
            }
            scenario("navigation", "打开分析页，我要看看统计。") {
                show("选择指标：请选择")
                rule.onNodeWithText("选择指标：请选择").performClick()
                rule.onNode(hasText("晨间体重") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
                rule.onNodeWithTag("atlas-list").performScrollToNode(hasTestTag("trend-chart"))
                rule.onNodeWithTag("trend-chart").assertIsDisplayed()
            }
            scenario("appearance", "晚上太亮了，切到深色模式。") {
                assertEquals("dark", model.ui.value.theme)
                rule.onNodeWithTag("command-status").assertIsDisplayed()
            }
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory)
            writeEvidence()
        }
        val failed = results.objects().filter { !it.optBoolean("passed") }
        assertTrue(failed.joinToString("\n") { "${it.s("id")}: ${it.s("failure")}" }, failed.isEmpty())
    }

    private fun checkGeneratedFeature(pluginId: String, fields: List<JSONObject>) {
        val project = model.store.query("SELECT id FROM atlas_objects WHERE plugin_id=? AND type='PROJECT'", arrayOf(pluginId)).single()
        val pages = model.store.objects(project.s("id")).filter { it.s("type") == "VIEW" }
        var openView = ""
        fun openComponent(type: String): JSONObject {
            val view = pages.first { page ->
                JSONObject(page.s("config")).optJSONArray("components")?.objects()?.any { it.s("component") == type } == true
            }
            if (openView != view.s("id")) {
                if (openView.isNotBlank()) {
                    show("返回上级")
                    rule.onNodeWithText("返回上级").performClick()
                }
                show(view.s("title"))
                rule.onNode(hasText(view.s("title")) and hasClickAction()).performClick()
                openView = view.s("id")
            }
            return JSONObject(view.s("config")).getJSONArray("components").objects().first { it.s("component") == type }
        }
        val form = openComponent("form")
        val metric = model.store.one("atlas_objects", form.s("metricId")) ?: error("录入入口缺指标")
        val config = JSONObject(metric.s("config"))
        val birdField = fields.first { it.s("title").contains("鸟") && it.s("type") == "text" }
        val soundField = fields.first { it.s("title").contains("叫声") && it.s("type") == "boolean" }
        val button = form.s("title").ifBlank { "记录" + metric.s("title") }
        show(button)
        rule.onNodeWithText(button).performClick()
        rule.onNodeWithText("数值 · " + config.s("unit")).performTextReplacement("3")
        val birdLabel = birdField.s("title") + if (birdField.optBoolean("required")) " *" else ""
        rule.onNodeWithText(birdLabel).performScrollTo().performTextReplacement("麻雀")
        hideKeyboard()
        val soundLabel = soundField.s("title") + if (soundField.optBoolean("required")) " *" else ""
        rule.onNodeWithText("$soundLabel：请选择").performScrollTo().performClick()
        rule.onNodeWithText("是").performClick()
        val before = model.ui.value
        rule.onNodeWithText("保存").performClick()
        waitForWork(before)
        val saved = record(metric.s("id"), today, "3")
        val values = JSONObject(saved.s("fields_json"))
        assertEquals("麻雀", values.s(birdField.s("id")))
        assertEquals("true", values.s(soundField.s("id")))
        openComponent("history")
        show("麻雀", substring = true)
        openComponent("chart")
        rule.onNodeWithTag("atlas-list").performScrollToNode(hasTestTag("trend-chart"))
        rule.onNodeWithTag("trend-chart").assertIsDisplayed()
    }

    private fun metric(title: String, kind: String, unit: String, aggregate: String, role: String = ""): String {
        val config = obj("kind" to kind, "unit" to unit, "aggregate" to aggregate, "role" to role)
        return model.actions.single("atlas.createObject", obj("type" to "METRIC", "title" to title, "config" to config))
            .getJSONObject("steps").getJSONObject("result").s("id")
    }

    private fun record(metricId: String, date: LocalDate, value: String): JSONObject {
        val rows = model.store.facts(metricId, date.toString(), date.toString())
        val actual = rows.map { it.s("value", "空") }
        assertEquals("$date 应保存一条 $value，实际 $actual", 1, rows.size)
        assertEquals("$date 应保存 $value，实际 $actual", 0, actual.single().toBigDecimal().compareTo(value.toBigDecimal()))
        return rows.single()
    }

    private fun scenario(id: String, input: String, verify: (JSONObject) -> Unit) {
        if (selectedCases.isNotEmpty() && id !in selectedCases) return
        val evidence = obj("id" to id, "input" to input)
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            go("首页")
            model.clearPending()
            val field = rule.onNodeWithText("输入")
            field.performClick().performTextReplacement(input)
            val before = model.ui.value
            rule.onNodeWithTag("command-send").performClick()
            waitForWork(before)
            hideKeyboard()
            val state = model.ui.value
            evidence.put("response", state.result?.takeUnless { it === before.result } ?: JSONObject.NULL)
            evidence.put("preview", state.pending ?: JSONObject.NULL)
            assertTrue(state.status, state.error.isBlank())
            verify(evidence)
            evidence.put("passed", true)
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            evidence.put("passed", false).put("failure", error.message?.take(500) ?: error.javaClass.simpleName)
        } finally {
            val state = model.ui.value
            evidence.put("status", state.status).put("error", state.error)
            evidence.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start)
            results.put(evidence)
            android.util.Log.i("AtlasAcceptance", obj("id" to id, "passed" to evidence.optBoolean("passed"),
                "status" to state.status, "failure" to evidence.opt("failure"), "elapsedMs" to evidence.opt("elapsedMs")).toString())
            runCatching { capture(id) }
        }
    }

    private fun chooseMetric(metricId: String, title: String, unit: String, evidence: JSONObject) {
        val pending = model.ui.value.pending
        if (pending == null) {
            val steps = model.ui.value.result?.optJSONObject("steps") ?: JSONObject()
            evidence.put("modelTargetCorrect", steps.keys().asSequence().any { steps.optJSONObject(it)?.s("metricId") == metricId })
            return
        }
        check(!pending.has("question")) { pending.s("question") }
        val steps = pending.getJSONArray("steps").objects()
        val candidates = steps.withIndex().filter { (_, step) ->
            step.s("actionId") == "atlas.record" &&
                PlanReview.unit(PlanReview.metric(pending, step.getJSONObject("args"), model.ui.value.objects)) == unit
        }
        val (index, proposed) = candidates.single()
        evidence.put("modelTargetCorrect", proposed.getJSONObject("args").s("metricId") == metricId)
        if (metricId !in PlanReview.metricChoices(proposed)) return
        assertTrue("归属待确认时没有写入", model.store.facts(metricId).isEmpty())
        rule.onNodeWithText("确认执行").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("plan-record-$index").performScrollTo()
        rule.onNodeWithText("记到：请选择").performScrollTo().performClick()
        rule.onNode(hasText(title) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        rule.onNodeWithText("记到：$title").assertExists()
        evidence.put("metricSelectedInUi", true)
    }

    private fun confirmIfNeeded() {
        val pending = model.ui.value.pending ?: return
        check(!pending.has("question")) { pending.s("question") }
        val before = model.ui.value
        rule.onNodeWithText("确认执行").performScrollTo().performClick()
        waitForWork(before)
        assertTrue(model.ui.value.status, model.ui.value.error.isBlank())
    }

    private fun waitForWork(before: AtlasState) {
        rule.waitUntil(10000) {
            val current = model.ui.value
            current.busy || current.result !== before.result || current.pending !== before.pending || current.status != before.status
        }
        rule.waitUntil(240000) { !model.ui.value.busy }
        rule.waitForIdle()
    }

    private fun show(text: String, substring: Boolean = false) {
        rule.onNodeWithTag("atlas-list").performScrollToNode(hasText(text, substring = substring))
        rule.onAllNodesWithText(text, substring = substring).onFirst().assertIsDisplayed()
    }

    private fun go(tab: String) {
        hideKeyboard()
        val item = hasText(tab) and hasClickAction() and hasAnyAncestor(hasTestTag("atlas-navigation"))
        rule.waitUntil(10000) { rule.onAllNodes(item).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(item).performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitForIdle()
    }

    private fun hideKeyboard() {
        rule.activity.runOnUiThread {
            rule.activity.window.insetsController?.hide(WindowInsets.Type.ime())
            val token = rule.activity.currentFocus?.windowToken
            rule.activity.currentFocus?.clearFocus()
            rule.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(token, 0)
        }
        rule.waitUntil(10000) {
            ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        rule.waitForIdle()
    }

    private fun capture(id: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val resolver = rule.activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$id.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LifeAtlasAcceptance")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun writeEvidence() {
        val resolver = rule.activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "language-results.json")
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/LifeAtlasAcceptance")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(results.toString(2)) }
    }

    private fun testTls(encodedCertificate: String): SSLContext {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            Base64.decode(encodedCertificate, Base64.DEFAULT).inputStream()
        )
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("acceptance", certificate)
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(store)
        }
        return SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
    }
}
