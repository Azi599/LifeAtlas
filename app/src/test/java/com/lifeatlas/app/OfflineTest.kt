package com.lifeatlas.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class OfflineTest {
    private val today = LocalDate.parse("2026-11-06")
    private fun metric(id:String,title:String,unit:String,kind:String="CHANGE",role:String="") = obj(
        "id" to id,"title" to title,"config" to obj("unit" to unit,"kind" to kind,"role" to role).toString()
    )
    private val study = metric("study","学习时间","分钟","ALLOCATION")
    private val sleep = metric("sleep","睡眠时长","分钟","ALLOCATION")
    private val meals = metric("meals","餐饮支出","元",role="expense")
    private val other = metric("other","交通支出","元",role="expense")
    private val pages = metric("pages","阅读页数","页")
    private fun args(plan:JSONObject) = plan.getJSONArray("steps").getJSONObject(0).getJSONObject("args")

    @Test fun durationMapsToStudyAndUsesYesterday() {
        val plan = Offline.plan("昨天学了半小时",listOf(study,sleep),today)!!
        assertEquals("study",args(plan).s("metricId"))
        assertEquals("小时",args(plan).s("unit"))
        assertEquals("0.5",args(plan).s("value"))
        assertEquals("2026-11-05",args(plan).s("date"))
    }

    @Test fun spokenExpenseShowsChoicesWithExactAmount() {
        val plan = Offline.plan("晚饭花了18块5",listOf(meals,other),today)!!
        assertTrue(plan.has("question"))
        assertFalse(plan.has("steps"))
        val option = plan.getJSONArray("options").getJSONObject(0).getJSONObject("plan")
        assertEquals("18.5",args(option).s("value"))
        assertEquals("meals",args(option).s("metricId"))
    }

    @Test fun exactMetricSupportsUserDefinedUnitsAndQueryPeriods() {
        val plan = Offline.plan("补记昨天阅读页数十五页",listOf(pages),today)!!
        assertEquals("15",args(plan).s("value"))
        val query = Offline.plan("统计近七天阅读页数",listOf(pages),today)!!
        assertEquals("2026-10-31",args(query).s("from"))
        assertEquals("2026-11-06",args(query).s("to"))
        assertEquals("atlas.aggregate",query.getJSONArray("steps").getJSONObject(0).s("actionId"))
    }

    @Test fun unsupportedOrUnclearRequestsCannotTurnIntoRecords() {
        assertNull(Offline.plan("昨天学习时间两公斤",listOf(study),today))
        assertNull(Offline.plan("学习时间两个小时吗？",listOf(study),today))
        assertNull(Offline.plan("学习和睡眠有没有关系",listOf(study,sleep),today))
        assertNull(Offline.plan("改一下刚才那条",listOf(study),today))
        assertEquals("history",args(Offline.plan("打开时间轴",emptyList(),today)!!).s("page"))
    }
}
