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
        val plan = Offline.plan("花了18块5",listOf(meals,other),today)!!
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

    @Test fun colloquialSpendingAndFollowUpShareTheCorrectDates() {
        val reading=metric("reading","阅读时间","分钟","ALLOCATION")
        val statement=Offline.plan("今儿中午饭十九块六毛，帮我记上",listOf(meals),today)!!
        assertEquals("19.6",args(statement).s("value"))
        val plan=Offline.plan("昨天下午茶不统计",listOf(meals,reading),today)
        assertNull(plan)
        val combined=Offline.plan("昨天饮料9块，晚上又看书半小时，顺便查查昨天总开销",listOf(meals,reading),today)!!
        val steps=combined.getJSONArray("steps").objects()
        assertEquals(listOf("atlas.record","atlas.record","finance.cashflow"),steps.map{it.s("actionId")})
        assertEquals(listOf("2026-11-05","2026-11-05"),steps.take(2).map{it.getJSONObject("args").s("date")})
    }

    @Test fun chartRequestUsesExplicitRangeWithoutCreatingAView() {
        val weight=metric("weight","晨间体重","kg","SNAPSHOT")
        val plan=Offline.plan("画一张最近14天体重的折线图",listOf(weight),today)!!
        assertEquals("atlas.chart",plan.getJSONArray("steps").getJSONObject(0).s("actionId"))
        assertEquals("weight",args(plan).s("metricId"))
        assertEquals("2026-10-24",args(plan).s("from"))
        assertEquals("2026-11-06",args(plan).s("to"))
    }

    @Test fun timeZoneConversionKeepsTheOriginalDateAndAsksForMissingContent() {
        val plan=Offline.plan("现在是东京时间9月18日00:20，记录刚刚发生的事，在北京时间算哪一天？",emptyList(),today)!!
        assertTrue(plan.s("question").contains("2026-09-17"))
        assertFalse(plan.has("steps"))
        val saved=Offline.followUp("内容是：夜间观察结束",plan)!!
        assertEquals("2026-09-18",args(saved).s("date"))
        assertEquals("Asia/Tokyo",args(saved).s("zone"))
        assertEquals("2026-09-17T15:20:00Z",args(saved).s("occurredAt"))
        assertEquals("夜间观察结束",args(saved).s("text"))
        assertNull(Offline.followUp("查看时间轴",plan))
    }

    @Test fun unsupportedOrUnclearRequestsCannotTurnIntoRecords() {
        assertNull(Offline.plan("昨天学习时间两公斤",listOf(study),today))
        assertNull(Offline.plan("学习时间两个小时吗？",listOf(study),today))
        assertNull(Offline.plan("学习和睡眠有没有关系",listOf(study,sleep),today))
        assertTrue(Offline.plan("改一下刚才那条",listOf(study),today)!!.has("question"))
        assertEquals("history",args(Offline.plan("打开时间轴",emptyList(),today)!!).s("page"))
    }
    @Test fun independentFactsCommitTogetherAndUseTheStatedDay() {
        val plan = Offline.plan("昨天晚饭花了18块5，另外学了半小时",listOf(meals,study,sleep),today)!!
        val steps = plan.getJSONArray("steps").objects()
        assertEquals(listOf("meals","study"),steps.map { it.getJSONObject("args").s("metricId") })
        assertEquals(listOf("2026-11-05","2026-11-05"),steps.map { it.getJSONObject("args").s("date") })
        assertNull(Offline.plan("昨天晚饭花了18块5，分析账户投资风险",listOf(meals),today))
    }

    @Test fun localEditResolvesTheRecordAndRequiresReview() {
        val weight = metric("weight","晨间体重","kg","SNAPSHOT")
        val plan = Offline.plan("把昨天的晨间体重改成130斤",listOf(weight),today,
            records={ id, date ->
                assertEquals("weight",id)
                assertEquals("2026-11-05",date)
                listOf(obj("id" to "fact-1","value" to "64","local_date" to date))
            })!!
        assertTrue(plan.optBoolean("review"))
        assertEquals("fact-1",args(plan).s("id"))
        assertEquals(0,args(plan).s("value").toBigDecimal().compareTo("65".toBigDecimal()))
    }

    @Test fun folderAndTimerUseConcreteTargets() {
        val folder = Offline.plan("建个叫寒假计划的目录",emptyList(),today)!!
        assertEquals("FOLDER",args(folder).s("type"))
        assertEquals("寒假计划",args(folder).s("title"))
        val start = Offline.plan("开始学习计时",listOf(study,sleep),today)!!
        assertEquals("study",args(start).s("metricId"))
        val stop = Offline.plan("结束计时",listOf(study),today,
            timers={ listOf(obj("id" to "timer-1","metric_id" to "study","state" to "RUNNING")) })!!
        assertEquals("timer-1",args(stop).s("timerId"))
    }
    @Test fun structuredReceiptKeepsExactAmountsAndIgnoresPrintedTotal() {
        val source="支付明细\n2026-11-04\n12:15 午餐 28.60 元\n16:40 咖啡 12.30 元\n合计 40.90 元"
        val plan=OfflineReceipt.plan(source,"",listOf(meals),java.time.ZoneId.of("Asia/Shanghai"))!!
        val steps=plan.getJSONArray("steps").objects().map{it.getJSONObject("args")}
        assertEquals(listOf("28.60","12.30"),steps.map{it.s("value")})
        assertEquals(listOf("2026-11-04T04:15:00Z","2026-11-04T08:40:00Z"),steps.map{it.s("occurredAt")})
        assertTrue(plan.optBoolean("review"))
        val ocr = "支付明细\n\n2026-11-04\n\n12:15年餐 28.60元\n16:40 咖啡12.30元"
        val extracted = OfflineReceipt.plan(ocr,"",listOf(meals),java.time.ZoneId.of("Asia/Shanghai"))!!
        assertEquals(steps.map{it.s("value")},extracted.getJSONArray("steps").objects().map{it.getJSONObject("args").s("value")})
        assertNull(OfflineReceipt.plan(source.replace("午餐","退款"),"",listOf(meals)))
        assertNull(OfflineReceipt.plan(source.replace("2026-11-04",""),"",listOf(meals)))
    }
    @Test fun localClockRecordsExactTimeAndCrossMidnightInterval() {
        val zone = java.time.ZoneId.systemDefault()
        val timed = args(Offline.plan("昨天16:20学习一个半小时",listOf(study),today)!!)
        val occurred = java.time.Instant.parse(timed.s("occurredAt")).atZone(zone)
        assertEquals("2026-11-05T16:20",occurred.toLocalDateTime().toString())
        assertEquals("1.5",timed.s("value"))
        val overnight = args(Offline.plan("昨天23:40到今天00:20睡眠",listOf(study,sleep),today)!!)
        assertEquals("sleep",overnight.s("metricId"))
        val from=java.time.Instant.parse(overnight.s("startAt"))
        val to=java.time.Instant.parse(overnight.s("endAt"))
        assertEquals(40L,java.time.Duration.between(from,to).toMinutes())
        assertEquals("2026-11-05",overnight.s("date"))
        val mixed = args(Offline.plan("学习1小时30分钟",listOf(study),today)!!)
        assertEquals("5400",mixed.s("value"))
        assertEquals("秒",mixed.s("unit"))
    }
    @Test fun unsupportedCompoundCreationDoesNotExecuteAPrefix() {
        val parent=obj("id" to "parent","type" to "FOLDER","title" to "测试目录")
        assertNull(Offline.plan("在“测试目录”下面建“项目甲”，再写一份报告",emptyList(),today,spaces={listOf(parent)}))
        assertNull(Offline.plan("把按钮颜色改成蓝色",listOf(study),today))
    }

    @Test fun weightedScoreIsReadOnlyAndUsesCounts() {
        val plan=Offline.plan("假设有4次练习，1次50分，3次90分，求平均分，不要记入数据库",emptyList(),today)!!
        assertEquals("平均分 80 分（共 4 次）",plan.s("localAnswer"))
        assertFalse(plan.has("steps"))
    }

    @Test fun explicitCalendarDatesAndUnspecifiedClockStayDistinct() {
        val args=args(Offline.plan("11月2日学习了45分钟，没有具体开始时间",listOf(study),today)!!)
        assertEquals("2026-11-02",args.s("date"))
        assertTrue(args.isNull("occurredAt"))
        val overnight=args(Offline.plan("2026年10月20日23:00睡到10月21日00:40",listOf(sleep),today)!!)
        val duration=java.time.Duration.between(java.time.Instant.parse(overnight.s("startAt")),java.time.Instant.parse(overnight.s("endAt")))
        assertEquals(100L,duration.toMinutes())
    }
    @Test fun localCompoundCanReadItsOwnWritesWithoutAProvider() {
        val plan=Offline.plan("离线：新建“周记”目录，记今天阅读页数8页，再列出今天记录",listOf(pages),today)!!
        val steps=plan.getJSONArray("steps").objects()
        assertEquals(listOf("atlas.createObject","atlas.record","atlas.query"),steps.map{it.s("actionId")})
        val total=Offline.plan("今天一共支出多少？",listOf(meals),today)!!
        assertEquals("finance.cashflow",total.getJSONArray("steps").getJSONObject(0).s("actionId"))
        assertTrue(args(total).optBoolean("onlyExpense"))
    }
    @Test fun oldDashboardWordingCreatesAnAnalysisChartWithAConcreteSource() {
        val plan=Offline.plan("在分析页新增一个显示本月阅读页数的图表",listOf(pages),today)!!
        val steps=plan.getJSONArray("steps").objects()
        assertEquals(listOf("atlas.createView","atlas.layout"),steps.map{it.s("actionId")})
        val chart=steps[0].getJSONObject("args").getJSONArray("components").getJSONObject(0)
        assertEquals("pages",chart.s("metricId"))
        assertEquals("2026-11-01",chart.s("from"))
        assertEquals("2026-11-06",chart.s("to"))
    }
}
