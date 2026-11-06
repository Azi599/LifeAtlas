package com.lifeatlas.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

class PlanReviewTest {
    private fun plan()=obj("steps" to JSONArray()
        .put(obj("stepId" to "metric","actionId" to "atlas.createObject","args" to obj("type" to "METRIC","title" to "支出","config" to obj("kind" to "CHANGE","unit" to "CNY","role" to "expense","aggregate" to "SUM"))))
        .put(obj("stepId" to "r1","actionId" to "atlas.record","args" to obj("metricId" to obj("\$ref" to "metric.id"),"value" to "25.80","date" to "2026-11-04","sourceExcerpt" to "午餐25.80")))
        .put(obj("stepId" to "r2","actionId" to "atlas.record","args" to obj("metricId" to obj("\$ref" to "metric.id"),"value" to "10.20","date" to "2026-11-04","sourceExcerpt" to "咖啡10.20"))))
    @Test fun reviewResolvesReferencesAndExactExpenseTotal(){val p=plan();val a=p.getJSONArray("steps").getJSONObject(1).getJSONObject("args");val m=PlanReview.metric(p,a,emptyList());assertEquals("支出",m.s("title"));assertEquals("元",PlanReview.unit(m));assertEquals("-25.8",PlanReview.value(m,a));assertEquals(listOf("支出 · 元：-36（2笔）"),PlanReview.totals(p,emptyList()))}
    @Test fun imageImportKeepsEvidence(){PlanReview.validateImageImport(plan(),"2026-11-04\n午餐25.80\n咖啡10.20")}
    @Test(expected=IllegalArgumentException::class) fun imageCannotInventEvidence(){PlanReview.validateImageImport(plan(),"完全不同的图片")}
    @Test fun missingAmountRemainsMissingAndExcluded(){val p=plan();p.getJSONArray("steps").getJSONObject(1).getJSONObject("args").put("value",org.json.JSONObject.NULL);val a=p.getJSONArray("steps").getJSONObject(1).getJSONObject("args");assertEquals("待补数值",PlanReview.value(PlanReview.metric(p,a,emptyList()),a));assertTrue(PlanReview.totals(p,emptyList()).isEmpty());PlanReview.validateImageImport(p,"午餐25.80\n咖啡10.20")}
    @Test(expected=IllegalArgumentException::class) fun imageImportCannotAddUnrequestedCapabilities(){val p=plan();p.getJSONArray("steps").put(obj("actionId" to "atlas.createView","args" to obj("title" to "无关图表")));PlanReview.validateImageImport(p,"午餐25.80\n咖啡10.20")}

    @Test fun previewUsesSameConversionAndSignAsSave() {
        val minute = obj("config" to obj("kind" to "ALLOCATION", "unit" to "分钟").toString())
        assertEquals("30", PlanReview.value(minute, obj("value" to "0.5", "unit" to "小时")))
        val interval = obj("startAt" to "2026-11-04T15:40:00Z", "endAt" to "2026-11-04T16:20:00Z")
        assertEquals("40", PlanReview.value(minute, interval))
        assertEquals("请核对数值", PlanReview.value(minute, interval.put("value", "20")))
        val expense = obj("config" to obj("role" to "expense", "unit" to "元").toString())
        assertEquals("2.5", PlanReview.value(expense, obj("value" to "250", "unit" to "分", "fields" to obj("flow" to "refund"))))
    }

    @Test fun unnamedMetricNeedsExplicitChoice() {
        fun metric(id: String, title: String, unit: String, role: String = "") = obj(
            "id" to id, "type" to "METRIC", "title" to title,
            "config" to obj("kind" to "ALLOCATION", "unit" to unit, "role" to role).toString()
        )
        val metrics = listOf(
            metric("sleep", "睡眠时长", "小时", "sleep"),
            metric("study", "学习时间", "分钟"),
            metric("archived", "旧记录", "分钟").put("archived", 1)
        )
        val args = obj("metricId" to "sleep", "value" to "0.5")
        val step = obj("actionId" to "atlas.record", "args" to args, "metricConfirmed" to true)
        val plan = obj("steps" to JSONArray().put(step))
        PlanReview.assignMetricChoices(plan, "学了半小时", metrics)
        assertEquals(listOf("sleep", "study"), PlanReview.metricChoices(step))
        assertTrue(PlanReview.metricSelectionPending(step))
        assertEquals("小时", args.s("unit"))
        step.put("metricConfirmed", true)
        args.put("metricId", "forged")
        assertTrue(PlanReview.metricSelectionPending(step))
        args.put("metricId", "study")
        assertFalse(PlanReview.metricSelectionPending(step))
        PlanReview.assignMetricChoices(plan, "记到学习时间", metrics)
        assertFalse(PlanReview.metricSelectionPending(step))
    }

    @Test fun acceptsOneFencedPlan() {
        assertEquals("补什么？", parsePlanObject("```json\n{\"question\":\"补什么？\"}\n```").s("question"))
    }

    @Test(expected=IllegalArgumentException::class) fun rejectsConcatenatedPlans() {
        parsePlanObject("""{"steps":[]} {"question":"补什么？"}""")
    }
}
