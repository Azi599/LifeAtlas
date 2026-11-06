package com.lifeatlas.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.io.StringWriter
import java.time.LocalDate
import android.database.sqlite.SQLiteDatabase

@RunWith(AndroidJUnit4::class)
class StorageTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:AtlasStore;private lateinit var actions:Actions
    @Before fun setup(){context.deleteDatabase("tests.db");db=AtlasStore(context,"tests.db");actions=Actions(db)}
    @After fun cleanup(){db.close();context.deleteDatabase("tests.db")}
    private fun metric(kind:String="SNAPSHOT",aggregate:String="LAST_SNAPSHOT"):String {
        val r=actions.single("atlas.createObject",obj("type" to "METRIC","title" to "测试指标","config" to obj("kind" to kind,"unit" to if(kind=="ALLOCATION") "秒" else "次","aggregate" to aggregate)));return r.getJSONObject("steps").getJSONObject("result").getString("id")
    }
    @Test fun pluginPersistsAcrossDatabaseReopen(){
        val manifest=obj("id" to "unknown.test","title" to "咖啡与阅读","version" to 1,"metrics" to JSONArray().put(obj("key" to "coffee","title" to "咖啡次数","kind" to "CHANGE","unit" to "杯","aggregate" to "SUM")).put(obj("key" to "pages","title" to "阅读页数","kind" to "CHANGE","unit" to "页","aggregate" to "SUM")),"views" to JSONArray().put(obj("title" to "咖啡趋势","metricKey" to "coffee","chart" to "bar")))
        assertEquals("PREVIEW",actions.single("atlas.installPlugin",obj("manifest" to manifest)).s("status"))
        actions.single("atlas.installPlugin",obj("manifest" to manifest),confirmed=true);db.close();db=AtlasStore(context,"tests.db");actions=Actions(db)
        assertEquals(2,db.metrics().size);assertEquals(1,db.query("SELECT * FROM atlas_plugins WHERE status='ACTIVE'").size);assertEquals(1,db.query("SELECT * FROM atlas_objects WHERE type='VIEW'").size)
    }
    @Test fun multiStepFailureRollsBackEverything(){val count=db.objects(all=true).size;try{actions.execute(obj("steps" to JSONArray().put(obj("actionId" to "atlas.createObject","stepId" to "a","args" to obj("type" to "PROJECT","title" to "不应留下"))).put(obj("actionId" to "invalid","stepId" to "b","args" to JSONObject()))));fail()}catch(_:IllegalStateException){};assertEquals(count,db.objects(all=true).size)}
    @Test fun dateOnlyAndMissingValuePreserved(){val id=metric();actions.single("atlas.record",obj("metricId" to id,"date" to "2026-11-04","fields" to obj("note" to "补记")));val row=db.facts(id).first();assertEquals("2026-11-04",row.s("local_date"));assertEquals("DATE",row.s("precision"));assertTrue(row.isNull("occurred_at"));assertTrue(row.isNull("value"));assertTrue(db.aggregate(id,"2026-11-01","2026-11-30").isNull("value"))}
    @Test fun undoRedoPersistsAndIdempotency(){val id=metric("CHANGE","SUM");val plan=obj("requestId" to "same-request","steps" to JSONArray().put(obj("stepId" to "r","actionId" to "atlas.record","args" to obj("metricId" to id,"value" to "3","date" to "2026-11-04"))));actions.execute(plan);actions.execute(plan);assertEquals(1,db.facts(id).size);db.undo();assertEquals(0,db.facts(id).size);db.undo(true);assertEquals(1,db.facts(id).size)}
    @Test fun referencesResolveToCreatedObject(){val result=actions.execute(obj("steps" to JSONArray().put(obj("stepId" to "p","actionId" to "atlas.createObject","args" to obj("type" to "PROJECT","title" to "阅读"))).put(obj("stepId" to "m","actionId" to "atlas.createObject","args" to obj("type" to "METRIC","title" to "页数","parentId" to obj("\$ref" to "p.id"),"config" to obj("kind" to "CHANGE","unit" to "页","aggregate" to "SUM"))))));val ids=result.getJSONObject("steps");assertEquals(ids.getJSONObject("p").s("id"),db.one("atlas_objects",ids.getJSONObject("m").s("id"))!!.s("parent_id"))}
    @Test fun timelineFiltersByLiveRecordAndDate() {
        val selected = metric()
        val other = metric()
        fun save(id: String, date: String): JSONObject = actions.single(
            "atlas.record", obj("metricId" to id, "value" to "4", "date" to date)
        ).getJSONObject("steps").getJSONObject("result")
        val expected = save(selected, "2026-11-05")
        save(other, "2026-11-05")
        val corrected = save(selected, "2026-10-05")
        actions.single("atlas.editRecord", obj("id" to corrected.s("id"), "date" to "2026-11-04"))
        val deleted = save(selected, "2026-11-06")
        actions.single("atlas.deleteRecord", obj("id" to deleted.s("id")))
        val events = db.timeline(selected, "2026-11-01", "2026-11-30", 0)
        assertEquals(listOf(expected.s("id"), corrected.s("id")), events.map { it.s("id") })
        assertEquals("2026-11-04", events.last().s("local_date"))
    }
    @Test fun timelineMergesTextAndFactsByOccurrence() {
        val metric = metric()
        val date = "2026-11-05"
        val zone = "Asia/Shanghai"
        val late = actions.single("atlas.record",obj("metricId" to metric,"date" to date,
            "occurredAt" to "2026-11-05T08:00:00Z","zone" to zone,"value" to "9"))
            .getJSONObject("steps").getJSONObject("result").s("id")
        val note = actions.single("atlas.createEvent",obj("text" to "文字记录","date" to date,
            "occurredAt" to "2026-11-05T06:00:00Z","zone" to zone))
            .getJSONObject("steps").getJSONObject("result").s("id")
        val early = actions.single("atlas.record",obj("metricId" to metric,"date" to date,
            "occurredAt" to "2026-11-05T05:00:00Z","zone" to zone,"value" to "2"))
            .getJSONObject("steps").getJSONObject("result").s("id")
        assertEquals(listOf(late,note,early),db.timeline(null,"","",0).map { it.s("id") })
        actions.single("atlas.editEvent",obj("id" to note,"text" to "改过的记录","date" to "2026-11-04"))
        val after = db.timeline(null,"","",0)
        assertEquals(listOf(late,early,note),after.map { it.s("id") })
        assertEquals("改过的记录",after.last().s("raw_input"))
        assertEquals("2026-11-04T06:00:00Z",after.last().s("occurred_at"))
        db.undo()
        assertEquals(listOf(late,note,early),db.timeline(null,"","",0).map { it.s("id") })
    }

    @Test fun cashflowSeparatesMissingAmountsAndTransfers() {
        val date="2026-10-13"
        fun account(title:String,role:String):String = actions.single("atlas.createObject",obj(
            "type" to "METRIC","title" to title,"config" to obj("kind" to "CHANGE","unit" to "元","role" to role,"aggregate" to "SUM")
        )).getJSONObject("steps").getJSONObject("result").s("id")
        val expense=account("测试支出","expense")
        val income=account("测试收入","income")
        fun save(metric:String,value:String?,flow:String=""){
            actions.single("atlas.record",obj("metricId" to metric,"date" to date,"value" to value,"fields" to obj("flow" to flow)))
        }
        save(expense,"20.25")
        save(expense,"2.50","refund")
        save(income,"40")
        save(income,"100","transfer")
        val complete=db.cashflow(date,date).single()
        assertEquals("40",complete.s("income"))
        assertEquals("17.75",complete.s("expense"))
        assertEquals("22.25",complete.s("net"))
        save(expense,null)
        val partial=db.cashflow(date,date).single()
        assertEquals(1L,partial.optLong("missingCount"))
        assertEquals("17.75",partial.s("expense"))
        assertTrue(partial.isNull("net"))
        assertTrue(db.cashflow("2026-10-12","2026-10-12").isEmpty())
    }

    @Test fun dailySpendingKeepsUnknownDaysSeparateFromZero(){
        fun create(title:String):String = actions.single("atlas.createObject",obj(
            "type" to "METRIC","title" to title,"config" to obj("kind" to "CHANGE","unit" to "元","role" to "expense")
        )).getJSONObject("steps").getJSONObject("result").s("id")
        val meals=create("餐费")
        val travel=create("出行费")
        fun save(metric:String,date:String,value:String?,flow:String=""){
            actions.single("atlas.record",obj("metricId" to metric,"date" to date,"value" to value,"fields" to obj("flow" to flow)))
        }
        save(meals,"2026-08-01","22.50")
        save(travel,"2026-08-01","8.60")
        save(travel,"2026-08-01","3.10","refund")
        save(meals,"2026-08-03","2")
        save(travel,"2026-08-03",null)
        save(travel,"2026-08-04","5")
        save(travel,"2026-08-04","5","refund")
        val summary=db.dailySpending("2026-08-01","2026-08-04")
        val days=summary.getJSONArray("points").objects()
        assertEquals("28",days[0].s("value"))
        assertTrue(days[1].isNull("value"))
        assertTrue(days[2].isNull("value"))
        assertEquals("0",days[3].s("value"))
        assertEquals(1,summary.getInt("emptyDays"))
        assertEquals(1,summary.getInt("incompleteDays"))
    }
    @Test fun matchingExpensesDeleteAndUndoAsOneOperation() {
        val id=actions.single("atlas.createObject",obj("type" to "METRIC","title" to "日用支出",
            "config" to obj("kind" to "CHANGE","unit" to "元","role" to "expense")))
            .getJSONObject("steps").getJSONObject("result").s("id")
        val date=LocalDate.now().toString()
        fun save(value:String,flow:String=""){
            actions.single("atlas.record",obj("metricId" to id,"date" to date,"value" to value,"fields" to obj("flow" to flow)))
        }
        save("15.50")
        save("15.5")
        save("15.50","refund")
        val plan=Offline.plan("把今天的两笔金额相同的支出删除",db)!!
        actions.execute(plan)
        assertEquals("15.50",db.facts(id).single().s("value"))
        db.undo(expectedChange="delete")
        assertEquals(3,db.facts(id).size)
        save("8")
        save("8")
        actions.execute(Offline.plan("把今天的两笔金额相同的支出删除",db)!!)
        assertEquals(5,db.facts(id).size)
    }

    @Test fun refundAndPurchaseShareOneEventAndAffectNetSpending() {
        val id=actions.single("atlas.createObject",obj("type" to "METRIC","title" to "日用支出",
            "config" to obj("kind" to "CHANGE","unit" to "元","role" to "expense")))
            .getJSONObject("steps").getJSONObject("result").s("id")
        val date=LocalDate.now().toString()
        actions.execute(Offline.plan("今天购物40块，商家退款12块5",db)!!)
        val rows=db.facts(id)
        assertEquals(setOf("-40.00","12.50"),rows.map{it.s("value")}.toSet())
        assertEquals(1,rows.map{it.s("event_id")}.distinct().size)
        val share=db.spendingShare(date,date,setOf(id))
        assertEquals("27.5",share.s("total"))
        assertEquals(0,share.s("percentage").toBigDecimal().compareTo("100".toBigDecimal()))
    }
    @Test fun correlationUsesOnlyCompletePairedDays() {
        fun create(title:String):String = actions.single("atlas.createObject",obj(
            "type" to "METRIC","title" to title,"config" to obj("kind" to "CHANGE","unit" to "次","aggregate" to "SUM")
        )).getJSONObject("steps").getJSONObject("result").s("id")
        val a=create("观测甲")
        val b=create("观测乙")
        fun save(metric:String,date:String,value:String?){
            actions.single("atlas.record",obj("metricId" to metric,"date" to date,"value" to value))
        }
        val day=LocalDate.now()
        (0..3).forEach { offset ->
            val date=day.minusDays(offset.toLong()).toString()
            save(a,date,(offset+1).toString())
            save(b,date,(2*offset+2).toString())
            if(offset==3) save(a,date,null)
        }
        val plan=Offline.plan("分析最近10天观测甲和观测乙的相关性",db)!!
        val result=actions.execute(plan).getJSONObject("steps").getJSONObject("s1")
        assertEquals(3,result.getInt("samplePairs"))
        assertEquals(1.0,result.getDouble("pearson"),1e-12)
    }

    @Test fun proposedEditCannotReuseAStaleReview() {
        val metricId = metric()
        val result = actions.single("atlas.record", obj("metricId" to metricId, "value" to "65.2", "date" to "2026-11-05"))
        val id = result.getJSONObject("steps").getJSONObject("result").s("id")
        val plan = obj("steps" to JSONArray().put(obj(
            "stepId" to "edit", "actionId" to "atlas.editRecord", "args" to obj("id" to id, "value" to "64.9")
        )))
        actions.validateProposedPlan(plan)
        assertEquals("65.2", plan.getJSONArray("steps").getJSONObject(0).getJSONObject("review").s("value"))
        actions.single("atlas.editRecord", obj("id" to id, "value" to "66"))
        try {
            actions.validateProposedPlan(plan)
            fail("Stale review must require a new review")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("记录已变化"))
        }
        assertEquals("66", db.facts(metricId).single().s("value"))
    }
    @Test fun backupRoundTripPreservesHistory(){val id=metric();actions.single("atlas.record",obj("metricId" to id,"value" to "65.5","date" to "2026-11-04"));val out=StringWriter();db.export(out);db.import(StringReader(out.toString()).buffered());assertEquals("65.5",db.facts(id).first().s("value"));assertTrue(db.query("SELECT * FROM atlas_operations").isNotEmpty())}
    @Test fun cacheInvalidatesAfterHistoricalCorrection(){
        val id=metric("CHANGE","SUM");val r=actions.single("atlas.record",obj("metricId" to id,"value" to "10","date" to "2026-11-01"));assertEquals("10",db.aggregate(id,"2026-11-01","2026-11-30").s("value"));val fact=r.getJSONObject("steps").getJSONObject("result").s("id")
        actions.single("atlas.editRecord",obj("id" to fact,"value" to "30"));assertEquals("30",db.aggregate(id,"2026-11-01","2026-11-30").s("value"));db.undo();assertEquals("10",db.aggregate(id,"2026-11-01","2026-11-30").s("value"))
    }
    @Test fun pluginUnitMigrationRetainsOriginalAndRollsBack(){
        fun manifest(version:Int,unit:String,factor:String?=null)=obj("id" to "units.test","title" to "测量","version" to version,"metrics" to JSONArray().put(obj("key" to "weight","title" to "重量","kind" to "SNAPSHOT","unit" to unit,"aggregate" to "LAST_SNAPSHOT").apply{if(factor!=null)put("migrationFactor",factor)}))
        actions.single("atlas.installPlugin",obj("manifest" to manifest(1,"kg")),confirmed=true);val id=db.metrics().first().s("id")
        actions.single("atlas.record",obj("metricId" to id,"value" to "65.5","date" to "2026-11-01"));actions.single("atlas.updatePlugin",obj("manifest" to manifest(2,"g","1000")),confirmed=true)
        assertEquals("65.5",db.query("SELECT value FROM atlas_facts").first().s("value"));assertEquals(0,db.facts(id).first().s("value").toBigDecimal().compareTo(java.math.BigDecimal("65500")))
        actions.single("atlas.rollbackPlugin",obj("pluginId" to "units.test"),confirmed=true);assertEquals(0,db.facts(id).first().s("value").toBigDecimal().compareTo(java.math.BigDecimal("65.5")))
    }
    @Test fun additivePluginUpgradeKeepsHistoricalRecordAndPinnedPage(){
        fun manifest(version:Int,title:String):JSONObject {
            val metric=obj("key" to "count","title" to "观测数量","kind" to "SNAPSHOT","unit" to "个")
            if(version>1) metric.put("fields",JSONArray().put(obj("id" to "detail","title" to "样本说明","type" to "text")))
            return obj("id" to "observations.test","title" to "样本观察","version" to version,
                "metrics" to JSONArray().put(metric),"views" to JSONArray().put(obj(
                    "key" to "counts","title" to title,"metricKey" to "count","chart" to "line")))
        }
        actions.single("atlas.installPlugin",obj("manifest" to manifest(1,"观测趋势")),confirmed=true)
        val metric=db.metrics().single().s("id")
        val view=db.objects(all=true).single{it.s("type")=="VIEW"}.s("id")
        actions.single("atlas.layout",obj("objectId" to view,"position" to 3))
        actions.single("atlas.record",obj("metricId" to metric,"date" to "2026-09-12","value" to "8"))
        val original=db.facts(metric).single().s("id")
        val preview=actions.single("atlas.updatePlugin",obj("manifest" to manifest(2,"样本趋势")))
        assertEquals("PREVIEW",preview.s("status"))
        val impact=preview.getJSONObject("plan").getJSONArray("impact").getJSONObject(0)
        assertEquals(1,impact.getInt("count"))
        assertEquals("样本说明",impact.getJSONArray("addedFields").getString(0))
        actions.execute(preview.getJSONObject("plan"),confirmed=true)
        assertEquals("样本趋势",db.one("atlas_objects",view)!!.s("title"))
        assertEquals(3,db.one("atlas_layout",view)!!.getInt("position"))
        assertEquals("8",db.facts(metric).single().s("value"))
        assertEquals(original,db.facts(metric).single().s("id"))
        assertFalse(JSONObject(db.facts(metric).single().s("fields_json")).has("detail"))
        db.undo()
        assertEquals("观测趋势",db.one("atlas_objects",view)!!.s("title"))
        assertEquals(3,db.one("atlas_layout",view)!!.getInt("position"))
        assertEquals(original,db.facts(metric).single().s("id"))
    }
    @Test fun portableTemplateOmitsFactsAndInstallsUnderAnIsolatedWorkspace() {
        val manifest=obj("id" to "notebook.example","title" to "标本手账","version" to 1,
            "metrics" to JSONArray().put(obj("key" to "leaves","title" to "叶片数","kind" to "SNAPSHOT",
                "unit" to "片","privateData" to "private-metric-value")),
            "records" to JSONArray().put("private-history-value"))
        actions.single("atlas.installPlugin",obj("manifest" to manifest),confirmed=true)
        val existing=db.metrics().single().s("id")
        actions.single("atlas.record",obj("metricId" to existing,"date" to "2026-08-02","value" to "14"))
        val template=portablePluginTemplate(manifest)
        assertFalse(template.toString().contains("private-"))
        val sandbox=sandboxPluginTemplate(template)
        val preview=actions.single("atlas.installPlugin",obj("manifest" to sandbox))
        assertEquals("PREVIEW",preview.s("status"))
        assertEquals(1,db.metrics().size)
        val result=actions.execute(preview.getJSONObject("plan"),true).getJSONObject("steps").getJSONObject("result")
        val project=db.one("atlas_objects",result.s("id"))!!
        val workspace=db.one("atlas_objects",project.s("parent_id"))!!
        assertEquals("测试区",workspace.s("title"))
        val sandboxMetric=result.getJSONObject("metricIds").s("leaves")
        assertNotEquals(existing,sandboxMetric)
        assertTrue(db.facts(sandboxMetric).isEmpty())
        assertEquals("14",db.facts(existing).single().s("value"))
        val liveWrite=obj("actionId" to "atlas.record","args" to obj("metricId" to existing))
        val workflow=obj("triggerMetricKey" to "leaves","steps" to JSONArray().put(liveWrite))
        val unsafe=template.copyJson().put("workflows",JSONArray().put(workflow))
        assertThrows(IllegalArgumentException::class.java){sandboxPluginTemplate(unsafe)}
    }
    @Test fun financeTransferAndAnchorRules(){
        val balance=metric();val flows=metric("CHANGE","SUM");val other=metric("CHANGE","SUM")
        actions.single("atlas.record",obj("metricId" to balance,"value" to "1000","date" to "2026-11-02"));actions.single("atlas.record",obj("metricId" to flows,"value" to "999","date" to "2026-11-01"));actions.single("atlas.record",obj("metricId" to flows,"value" to "25","date" to "2026-11-03"))
        val computed=actions.dispatch("finance.balance",obj("snapshotMetricId" to balance,"changeMetricIds" to JSONArray().put(flows),"asOf" to "2026-11-05"));assertEquals("1025",computed.s("value"))
        actions.single("finance.transfer",obj("fromMetricId" to flows,"toMetricId" to other,"amount" to "500","date" to "2026-11-04"));val rows=db.facts(flows)+db.facts(other);val transfers=rows.filter{JSONObject(it.s("fields_json")).s("flow")=="transfer"};assertEquals(2,transfers.size);assertEquals(transfers[0].s("event_id"),transfers[1].s("event_id"));assertEquals(0,transfers.map{it.s("value").toBigDecimal()}.reduce{a,b->a+b}.signum())
    }
    @Test fun midnightUnionClipsToRequestedDay(){
        val id=metric("ALLOCATION","UNION_DURATION");actions.single("atlas.record",obj("metricId" to id,"date" to "2026-11-04","value" to "7200","startAt" to "2026-11-04T23:00:00Z","endAt" to "2026-11-05T01:00:00Z","zone" to "UTC"))
        val previous=java.util.TimeZone.getDefault();try{java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));assertEquals("3600",db.aggregate(id,"2026-11-05","2026-11-05").s("value"))}finally{java.util.TimeZone.setDefault(previous)}
    }
    @Test fun naturalLanguageSavesIntervalsAndMeasuresTheirUnion(){
        fun activity(title:String):String = actions.single("atlas.createObject",obj(
            "type" to "METRIC","title" to title,
            "config" to obj("kind" to "ALLOCATION","unit" to "秒","aggregate" to "SUM")
        )).getJSONObject("steps").getJSONObject("result").s("id")
        val study=activity("学习时间")
        val reading=activity("阅读时长")
        val day=LocalDate.now().minusDays(1).toString()
        val plan=Offline.plan("昨天08:00到09:30学习，08:45到09:15阅读；分别记下并告诉我总占用时间",db)!!
        actions.validateProposedPlan(plan)
        val result=actions.execute(plan).getJSONObject("steps").getJSONObject("total")
        assertEquals("90",result.s("minutes"))
        assertEquals(2,result.getInt("knownIntervals"))
        assertEquals(day,db.facts(study).single().s("local_date"))
        assertEquals(day,db.facts(reading).single().s("local_date"))
        actions.single("atlas.record",obj("metricId" to study,"date" to day,"value" to "1200"))
        val query=actions.dispatch("time.occupied",obj("metricIds" to JSONArray(listOf(study,reading)),"from" to day,"to" to day))
        assertEquals("90",query.s("minutes"))
        assertEquals(1,query.getInt("untimed"))
    }
    @Test fun freshDatabaseCreatesOnlyTheMetricNeededByAnOfflineRecord(){
        assertTrue(db.metrics().isEmpty())
        val today=LocalDate.now().toString()
        val plan=Offline.plan("离线：新建「随手记」目录，记今天喝水450毫升，再列出今天记录",db)!!
        val result=actions.execute(plan)
        assertEquals("DONE",result.s("status"))
        assertEquals(1,db.objects(all=true).count{it.s("type")=="FOLDER"})
        val metric=db.metrics().single()
        assertEquals("饮水量",metric.s("title"))
        assertEquals("450",db.timeline(metric.s("id"),today,today,0).single().s("value"))
        val query=result.getJSONObject("steps").getJSONObject("part3_s1").getJSONArray("records")
        assertEquals(1,query.length())
        assertEquals("450",query.getJSONObject(0).s("value"))
        actions.execute(Offline.plan("今天饮水200毫升",db)!!)
        assertEquals(1,db.metrics().size)
        assertEquals(2,db.facts(metric.s("id")).size)
    }

    @Test fun freshCompoundSpendingCanQueryTheJustCreatedCategory(){
        val plan=Offline.plan("记一笔今天午饭17元，再告诉我今天餐饮一共花了多少",db)!!
        val result=actions.execute(plan)
        val expense=result.getJSONObject("steps").getJSONObject("part2_s1").getJSONArray("cashflow").getJSONObject(0)
        assertEquals("17",expense.s("expense"))
        assertEquals(1,db.metrics().size)
        assertEquals(1,db.facts().size)
    }
    @Test fun customMetricConstraintsApplyToRecordsAndEdits(){
        val folder=actions.single("atlas.createObject",obj("type" to "PROJECT","title" to "实验日志"))
            .getJSONObject("steps").getJSONObject("result").s("id")
        val plan=Offline.plan("给「实验日志」增加「试管数量」指标，单位支，只能填非负整数",db)!!
        val metric=actions.execute(plan).getJSONObject("steps").getJSONObject("s1").s("id")
        assertEquals(folder,db.one("atlas_objects",metric)!!.s("parent_id"))
        fun record(value:String):JSONObject = actions.single("atlas.record",
            obj("metricId" to metric,"date" to "2026-09-15","value" to value))
        assertThrows(IllegalArgumentException::class.java){record("-1")}
        assertThrows(IllegalArgumentException::class.java){record("0.5")}
        record("7")
        val fact=db.facts(metric).single().s("id")
        assertThrows(IllegalArgumentException::class.java){
            actions.single("atlas.editRecord",obj("id" to fact,"value" to "7.1"))
        }
        assertEquals("7",db.facts(metric).single().s("value"))
    }
    @Test fun scopedAliasWritesToOnlyOneProjectAndSurvivesReopen() {
        fun project(title:String):String = actions.single("atlas.createObject",obj("type" to "PROJECT","title" to title))
            .getJSONObject("steps").getJSONObject("result").s("id")
        fun metric(parent:String):String = actions.single("atlas.createObject",obj("type" to "METRIC",
            "title" to "样带长度","parentId" to parent,
            "config" to obj("kind" to "SNAPSHOT","unit" to "厘米","aliases" to JSONArray().put("实测跨度"))))
            .getJSONObject("steps").getJSONObject("result").s("id")
        val a=metric(project("西岸采样"))
        val b=metric(project("东岸采样"))
        val text="请在「东岸采样」里记录昨日实测跨度0.25米"
        actions.execute(Offline.plan(text,db)!!)
        assertTrue(db.facts(a).isEmpty())
        assertEquals(0,db.facts(b).single().s("value").toBigDecimal().compareTo("25".toBigDecimal()))
        db.close()
        db=AtlasStore(context,"tests.db")
        actions=Actions(db)
        val second=Offline.plan("在「西岸采样」里记今天实测跨度180毫米",db)!!
        actions.execute(second)
        assertEquals("18",db.facts(a).single().s("value"))
        assertEquals(0,db.facts(b).single().s("value").toBigDecimal().compareTo("25".toBigDecimal()))
    }
    @Test fun naturalLanguageAccountTransferAndProjectionUseMeasuredAnchor(){
        fun create(title:String,role:String,unit:String):String = actions.single("atlas.createObject",obj(
            "type" to "METRIC","title" to title,
            "config" to obj("kind" to if(role=="balance") "SNAPSHOT" else "CHANGE","unit" to unit,"role" to role)
        )).getJSONObject("steps").getJSONObject("result").s("id")
        val ledger=create("旅行卡流水","accountChange","CNY")
        val savings=create("储蓄账户流水","accountChange","元")
        val balance=create("旅行卡余额","balance","元")
        val today=LocalDate.now()
        actions.single("atlas.record",obj("metricId" to balance,"date" to today.minusDays(1).toString(),"value" to "200.50"))
        actions.single("atlas.record",obj("metricId" to ledger,"date" to today.minusDays(2).toString(),"value" to "999"))
        val plan=Offline.plan("从旅行卡转35.20元到储蓄账户",db)!!
        assertTrue(plan.optBoolean("review"))
        actions.validateProposedPlan(plan)
        val receipt=actions.execute(plan,true).getJSONObject("steps").getJSONObject("s1")
        assertEquals("35.20",receipt.s("transferAmount"))
        val debit=db.facts(ledger).first{it.s("local_date")==today.toString()}
        val credit=db.facts(savings).single()
        assertEquals(debit.s("event_id"),credit.s("event_id"))
        assertEquals("-35.20",debit.s("value"))
        assertEquals("35.20",credit.s("value"))
        assertTrue(db.cashflow(today.toString(),today.toString()).isEmpty())
        assertTrue(db.recentTransferAudit().s("message").contains("排除"))
        val query=Offline.plan("从上一次旅行卡余额实测以来，净变动多少，现在推算余额多少？",db)!!
        val projected=actions.execute(query).getJSONObject("steps").getJSONObject("s1")
        assertEquals("-35.20",projected.s("netDelta"))
        assertEquals("165.30",projected.s("value"))
        db.undo()
        assertEquals(1,db.facts(ledger).size)
        assertTrue(db.facts(savings).isEmpty())
    }

    @Test fun reviewedDateCorrectionRecalculatesBothAffectedMonths() {
        val id=actions.single("atlas.createObject",obj("type" to "METRIC","title" to "交通支出",
            "config" to obj("kind" to "CHANGE","unit" to "元","role" to "expense")
        )).getJSONObject("steps").getJSONObject("result").s("id")
        val today=LocalDate.now()
        val nextMonth=today.withDayOfMonth(1)
        val oldDay=nextMonth.minusDays(1).toString()
        actions.single("atlas.record",obj("metricId" to id,"date" to oldDay,"value" to "31.20"))
        assertEquals("31.2",db.cashflow(oldDay,oldDay).single().s("expense"))
        val plan=Offline.plan("我有一笔交通费日期写错了，帮我改成$nextMonth",db)!!
        actions.validateProposedPlan(plan)
        assertTrue(plan.optBoolean("review"))
        assertEquals(oldDay,plan.getJSONArray("steps").getJSONObject(0).getJSONObject("review").s("date"))
        actions.execute(plan,true)
        assertTrue(db.cashflow(oldDay,oldDay).isEmpty())
        assertEquals("31.2",db.cashflow(nextMonth.toString(),nextMonth.toString()).single().s("expense"))
        db.undo()
        assertEquals("31.2",db.cashflow(oldDay,oldDay).single().s("expense"))
        assertTrue(db.cashflow(nextMonth.toString(),nextMonth.toString()).isEmpty())
    }
    @Test fun csvImportRejectsBadRowsAndDeduplicates(){
        val id=metric("CHANGE","SUM");val source="value,local_date\n3,2026-11-01\nbad,2026-11-01\n";val errors=StringWriter();val result=CsvCodec.import(StringReader(source),actions,"test-csv",id,"value","local_date",errors)
        assertEquals(1,result.getInt("accepted"));assertEquals(1,result.getInt("rejected"));assertEquals(1,db.facts(id).size);assertTrue(errors.toString().contains("3,"));CsvCodec.import(StringReader(source),actions,"test-csv",id,"value","local_date",StringWriter());assertEquals(1,db.facts(id).size)
    }
    @Test fun timerPauseRestartResumePersists(){
        val id=metric("ALLOCATION","SUM");val started=actions.single("time.start",obj("metricId" to id));val timer=started.getJSONObject("steps").getJSONObject("result").s("id");Thread.sleep(1100);actions.single("time.pause",obj("timerId" to timer));db.close();db=AtlasStore(context,"tests.db");actions=Actions(db);assertEquals("PAUSED",db.one("atlas_timers",timer)!!.s("state"));actions.single("time.resume",obj("timerId" to timer));Thread.sleep(1100);actions.single("time.stop",obj("timerId" to timer));assertTrue(db.facts(id).map{it.s("value").toBigDecimal()}.reduce{a,b->a+b}>=java.math.BigDecimal("2"))
    }
    @LargeTest @Test fun millionRecordsMonthUsesIndexAndExactSum(){
        val id=metric("CHANGE","SUM");val sql=db.writableDatabase;val t=now();sql.execSQL("INSERT INTO atlas_events(id,raw_input,source,created_at,updated_at,zone) VALUES ('fixture','benchmark','fixture',?,?,?)",arrayOf(t,t,"Asia/Shanghai"))
        val dates=(0 until 3650).map{LocalDate.of(2017,1,1).plusDays(it.toLong()).toString()};val statement=sql.compileStatement("INSERT INTO atlas_facts(id,event_id,metric_id,kind,value,local_date,precision,zone,created_at,updated_at) VALUES (?,'fixture',?,'CHANGE','0.01',?,'DATE','Asia/Shanghai',?,?)")
        val started=System.nanoTime();sql.beginTransaction();try{repeat(1_000_000){i->statement.clearBindings();statement.bindString(1,"fixture-${i.toString().padStart(7,'0')}");statement.bindString(2,id);statement.bindString(3,dates[i%dates.size]);statement.bindString(4,t);statement.bindString(5,t);statement.executeInsert()};sql.setTransactionSuccessful()}finally{sql.endTransaction();statement.close()}
        val plan=db.query("EXPLAIN QUERY PLAN SELECT * FROM atlas_facts WHERE metric_id=? AND deleted=0 AND local_date BETWEEN '2024-01-01' AND '2024-01-31' LIMIT 50",arrayOf(id)).toString();assertTrue(plan.contains("fact_metric_date"))
        val durations=mutableListOf<Long>();repeat(5){val begin=System.nanoTime();assertEquals(50,db.facts(id,"2024-01-01","2024-01-31").size);durations.add((System.nanoTime()-begin)/1_000_000)}
        val stats=db.aggregate(id,"2024-01-01","2024-01-31","SUM");assertEquals(0,stats.s("value").toBigDecimal().compareTo(stats.getLong("count").toBigDecimal()*java.math.BigDecimal("0.01")))
        println("PERF million_insert_ms=${(System.nanoTime()-started)/1_000_000} month_query_ms=$durations exact_count=${stats.getLong("count")}")
        val file=java.io.File(context.cacheDir,"million-backup.jsonl");file.bufferedWriter().use(db::export)
        context.deleteDatabase("restored.db");val restored=AtlasStore(context,"restored.db")
        try{file.bufferedReader().use(restored::import);assertEquals(1_000_000L,restored.query("SELECT COUNT(*) n FROM atlas_facts").first().getLong("n"));assertEquals(stats.s("value"),restored.aggregate(id,"2024-01-01","2024-01-31","SUM").s("value"))}finally{restored.close();context.deleteDatabase("restored.db");file.delete()}
    }
    @Test fun themeSaveReopenUndoAndBackup() {
        val config=ThemeDefaults.config();config.getJSONObject("light").put("primary","#B894CB").put("gradient",true).put("angle",42)
        actions.single("atlas.saveTheme",obj("id" to "custom","title" to "我的渐变","config" to config));actions.single("atlas.setAppearance",obj("compact" to true,"mode" to "dark"))
        db.close();db=AtlasStore(context,"tests.db");actions=Actions(db)
        assertEquals("custom",ThemeRepository(db).current().s("id"));assertEquals("dark",ThemeRepository(db).appearance().s("mode"))
        val out=StringWriter();db.export(out);actions.single("atlas.resetTheme",JSONObject());assertEquals("lavender",ThemeRepository(db).current().s("id"));db.undo();assertEquals("custom",ThemeRepository(db).current().s("id"))
        db.import(StringReader(out.toString()).buffered());assertEquals("custom",ThemeRepository(db).current().s("id"));assertEquals(42,JSONObject(ThemeRepository(db).current().s("config")).getJSONObject("light").getInt("angle"))
    }
    @Test fun nonAtomicRetrySkipsCommittedSteps() {
        val request="partial";val bad=obj("requestId" to request,"atomic" to false,"steps" to JSONArray().put(obj("stepId" to "first","actionId" to "atlas.createObject","args" to obj("type" to "PROJECT","title" to "一次"))).put(obj("stepId" to "second","actionId" to "missing.operation","args" to JSONObject())))
        assertEquals("PARTIAL",actions.execute(bad).s("status"));bad.getJSONArray("steps").getJSONObject(1).put("actionId","atlas.createObject").put("args",obj("type" to "PROJECT","title" to "重试"));assertEquals("DONE",actions.execute(bad).s("status"));assertEquals(2,db.objects(all=true).size)
    }
    @Test fun crossMidnightSumAndSeriesAgree() {
        val previous=java.util.TimeZone.getDefault();try{java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));val id=metric("ALLOCATION","SUM");actions.single("atlas.record",obj("metricId" to id,"date" to "2026-11-04","startAt" to "2026-11-04T23:00:00Z","endAt" to "2026-11-05T01:00:00Z","zone" to "UTC"));assertEquals("3600",db.aggregate(id,"2026-11-05","2026-11-05").s("value"));val days=db.dailySeries(id,"2026-11-04","2026-11-05");assertEquals(2,days.size);assertTrue(days.all{it.s("value")=="3600"})}finally{java.util.TimeZone.setDefault(previous)}
    }
    @Test fun latestSnapshotAndChangeSumArePairedByDay() {
        val snapshot=metric();val change=metric("CHANGE","SUM")
        actions.single("atlas.record",obj("metricId" to snapshot,"date" to "2026-11-01","occurredAt" to "2026-11-01T00:00:00Z","zone" to "UTC","value" to "60"));actions.single("atlas.record",obj("metricId" to snapshot,"date" to "2026-11-01","occurredAt" to "2026-11-01T01:00:00Z","zone" to "UTC","value" to "62"))
        listOf("3","4").forEach{actions.single("atlas.record",obj("metricId" to change,"date" to "2026-11-01","value" to it))}
        assertEquals("62",db.dailySeries(snapshot,"2026-11-01","2026-11-01").first().s("value"));assertEquals("7",db.dailySeries(change,"2026-11-01","2026-11-01").first().s("value"))
    }
    @Test fun schemaFourMigratesWithoutLosingRecords() {
        val id=metric()
        actions.single("atlas.record",obj("metricId" to id,"value" to "65.5","date" to "2026-11-04"))
        val sql=db.writableDatabase
        sql.setForeignKeyConstraintsEnabled(false)
        sql.beginTransaction()
        try {
            sql.execSQL("DROP TABLE atlas_versions")
            sql.execSQL("DROP TABLE atlas_themes")
            sql.execSQL("DROP TABLE atlas_appearance")
            sql.execSQL("""CREATE TABLE atlas_events_v4(
                id TEXT PRIMARY KEY,raw_input TEXT NOT NULL,source TEXT NOT NULL,
                created_at TEXT NOT NULL,updated_at TEXT NOT NULL,zone TEXT NOT NULL)""")
            sql.execSQL("INSERT INTO atlas_events_v4 SELECT id,raw_input,source,created_at,updated_at,zone FROM atlas_events")
            sql.execSQL("DROP TABLE atlas_events")
            sql.execSQL("ALTER TABLE atlas_events_v4 RENAME TO atlas_events")
            sql.version=4
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
            db.close()
        }
        db=AtlasStore(context,"tests.db")
        actions=Actions(db)
        assertEquals("65.5",db.facts(id).single().s("value"))
        assertEquals(7,db.readableDatabase.version)
        assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        assertTrue(db.query("SELECT * FROM atlas_versions").isNotEmpty())
        assertEquals("lavender",ThemeRepository(db).current().s("id"))
    }

    @Test fun pluginInstallsVersionedWorkflowAndUpgradePreviewCountsHistory() {
        val manifest=obj("id" to "workflow.test","title" to "花园观测","version" to 1,"metrics" to JSONArray().put(obj("key" to "source","title" to "观测量","kind" to "CHANGE","unit" to "次","aggregate" to "SUM")).put(obj("key" to "target","title" to "合计量","kind" to "CHANGE","unit" to "次","aggregate" to "SUM")),"workflows" to JSONArray().put(obj("key" to "copy","title" to "关联同步","triggerMetricKey" to "source","spec" to obj("steps" to JSONArray().put(obj("actionId" to "atlas.record","args" to obj("metricId" to obj("\$metric" to "target"),"value" to obj("\$ref" to "record.value"),"date" to obj("\$ref" to "record.date"),"eventId" to obj("\$ref" to "record.eventId"))))))))
        val result=actions.single("atlas.installPlugin",obj("manifest" to manifest),confirmed=true).getJSONObject("steps").getJSONObject("result");val ids=result.getJSONObject("metricIds")
        actions.single("atlas.record",obj("metricId" to ids.s("source"),"value" to "3","date" to "2026-11-05"))
        assertEquals("3",db.facts(ids.s("target")).first().s("value"));assertEquals(db.facts(ids.s("source")).first().s("event_id"),db.facts(ids.s("target")).first().s("event_id"))
        manifest.put("version",2);val preview=actions.single("atlas.updatePlugin",obj("manifest" to manifest));assertEquals("PREVIEW",preview.s("status"));assertEquals(1L,preview.getJSONObject("plan").getJSONArray("impact").getJSONObject(0).getLong("count"))
        actions.single("atlas.updatePlugin",obj("manifest" to manifest),confirmed=true);assertEquals(2,JSONObject(db.query("SELECT spec FROM atlas_workflows").first().s("spec")).getInt("version"))
    }

    @Test fun editingAndDeletingTransferMaintainsBothLegs() {
        val from=metric("CHANGE","SUM");val to=metric("CHANGE","SUM");actions.single("finance.transfer",obj("fromMetricId" to from,"toMetricId" to to,"amount" to "500","date" to "2026-11-04"))
        val id=db.facts(from).first().s("id");actions.single("atlas.editRecord",obj("id" to id,"value" to "-300","date" to "2026-11-05"));assertEquals("300",db.facts(to).first().s("value"));assertEquals("2026-11-05",db.facts(to).first().s("local_date"));db.undo();assertEquals("500",db.facts(to).first().s("value"))
        actions.single("atlas.deleteRecord",obj("id" to id));assertTrue(db.facts(from).isEmpty() && db.facts(to).isEmpty());db.undo();assertEquals(1,db.facts(from).size);assertEquals(1,db.facts(to).size)
    }

    @Test fun expressionUpgradeRetainsRawHistoryAndExplicitPolicy() {
        fun manifest(version:Int,factor:String,mode:String="")=obj("id" to "formula.test","title" to "自定义计算","version" to version,"historyMode" to mode,"metrics" to JSONArray().put(obj("key" to "computed","title" to "结果","kind" to "CHANGE","unit" to "分值","aggregate" to "SUM","fields" to JSONArray().put(obj("id" to "x","title" to "原始输入","type" to "decimal","required" to true)),"expression" to obj("op" to "multiply","args" to JSONArray().put(obj("field" to "x")).put(obj("const" to factor))))))
        actions.single("atlas.installPlugin",obj("manifest" to manifest(1,"2")),confirmed=true);val metric=db.metrics().first().s("id")
        val original=actions.single("atlas.record",obj("metricId" to metric,"date" to "2026-11-05","fields" to obj("x" to "3"))).getJSONObject("steps").getJSONObject("result").s("id")
        val preview=actions.single("atlas.updatePlugin",obj("manifest" to manifest(2,"3")));assertTrue(preview.getJSONObject("plan").getJSONArray("impact").getJSONObject(0).getBoolean("expressionChanged"))
        try{actions.single("atlas.updatePlugin",obj("manifest" to manifest(2,"3")),confirmed=true);fail()}catch(_:IllegalArgumentException){}
        assertEquals("6",db.facts(metric).first().s("value"))
        actions.single("atlas.updatePlugin",obj("manifest" to manifest(2,"3","recalculate")),confirmed=true);assertEquals("9",db.aggregate(metric,"2026-11-01","2026-11-30").s("value"));assertEquals("6",db.one("atlas_facts",original)!!.s("value"))
        db.close();db=AtlasStore(context,"tests.db");actions=Actions(db);assertEquals("9",db.facts(metric).first().s("value"))
        actions.single("atlas.rollbackPlugin",obj("pluginId" to "formula.test"),confirmed=true);assertEquals("6",db.facts(metric).first().s("value"))
        actions.single("atlas.updatePlugin",obj("manifest" to manifest(4,"4","preserve")),confirmed=true);actions.single("atlas.record",obj("metricId" to metric,"date" to "2026-11-05","fields" to obj("x" to "3")))
        assertEquals("18",db.aggregate(metric,"2026-11-01","2026-11-30").s("value"));assertEquals("6",db.facts(metric).first{it.s("id")==original}.s("value"))
    }

}
