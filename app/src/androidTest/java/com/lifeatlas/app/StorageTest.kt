package com.lifeatlas.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val events = db.historyEvents(selected, "2026-11-01", "2026-11-30", 0)
        assertEquals(listOf(expected.s("eventId"), corrected.s("eventId")), events.map { it.s("id") })
        assertEquals("2026-11-04", events.last().s("timeline_date"))
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
    @Test fun csvImportRejectsBadRowsAndDeduplicates(){
        val id=metric("CHANGE","SUM");val source="value,local_date\n3,2026-11-01\nbad,2026-11-01\n";val errors=StringWriter();val result=CsvCodec.import(StringReader(source),actions,"test-csv",id,"value","local_date",errors)
        assertEquals(1,result.getInt("accepted"));assertEquals(1,result.getInt("rejected"));assertEquals(1,db.facts(id).size);assertTrue(errors.toString().contains("3,"));CsvCodec.import(StringReader(source),actions,"test-csv",id,"value","local_date",StringWriter());assertEquals(1,db.facts(id).size)
    }
    @Test fun timerPauseRestartResumePersists(){
        val id=metric("ALLOCATION","SUM");val started=actions.single("time.start",obj("metricId" to id));val timer=started.getJSONObject("steps").getJSONObject("result").s("id");Thread.sleep(1100);actions.single("time.pause",obj("timerId" to timer));db.close();db=AtlasStore(context,"tests.db");actions=Actions(db);assertEquals("PAUSED",db.one("atlas_timers",timer)!!.s("state"));actions.single("time.resume",obj("timerId" to timer));Thread.sleep(1100);actions.single("time.stop",obj("timerId" to timer));assertTrue(db.facts(id).map{it.s("value").toBigDecimal()}.reduce{a,b->a+b}>=java.math.BigDecimal("2"))
    }
    @Test fun millionRecordsMonthUsesIndexAndExactSum(){
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
        val id=metric();actions.single("atlas.record",obj("metricId" to id,"value" to "65.5","date" to "2026-11-04"));val sql=db.writableDatabase
        sql.execSQL("DROP TABLE atlas_versions");sql.execSQL("DROP TABLE atlas_themes");sql.execSQL("DROP TABLE atlas_appearance");sql.execSQL("DROP INDEX event_date");listOf("local_date","occurred_at","precision").forEach{sql.execSQL("ALTER TABLE atlas_events DROP COLUMN "+it)};sql.version=4;db.close();db=AtlasStore(context,"tests.db");actions=Actions(db)
        assertEquals("65.5",db.facts(id).first().s("value"));assertEquals(7,db.readableDatabase.version);assertTrue(db.query("SELECT * FROM atlas_versions").isNotEmpty());assertEquals("lavender",ThemeRepository(db).current().s("id"))
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
