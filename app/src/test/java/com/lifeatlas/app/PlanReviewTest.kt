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
}
