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
        val r=actions.single("atlas.createObject",obj("type" to "METRIC","title" to "测试指标","config" to obj("kind" to kind,"unit" to "次","aggregate" to aggregate)));return r.getJSONObject("steps").getJSONObject("result").getString("id")
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
    @Test fun backupRoundTripPreservesHistory(){val id=metric();actions.single("atlas.record",obj("metricId" to id,"value" to "65.5","date" to "2026-11-04"));val out=StringWriter();db.export(out);db.import(StringReader(out.toString()).buffered());assertEquals("65.5",db.facts(id).first().s("value"));assertTrue(db.query("SELECT * FROM atlas_operations").isNotEmpty())}
    @Test fun millionRecordsMonthUsesIndexAndExactSum(){
        val id=metric("CHANGE","SUM");val sql=db.writableDatabase;val t=now();sql.execSQL("INSERT INTO atlas_events VALUES ('fixture','benchmark','fixture',?,?,?)",arrayOf(t,t,"Asia/Shanghai"))
        val dates=(0 until 3650).map{LocalDate.of(2017,1,1).plusDays(it.toLong()).toString()};val statement=sql.compileStatement("INSERT INTO atlas_facts(id,event_id,metric_id,kind,value,local_date,precision,zone,created_at,updated_at) VALUES (?,'fixture',?,'CHANGE','0.01',?,'DATE','Asia/Shanghai',?,?)")
        val started=System.nanoTime();sql.beginTransaction();try{repeat(1_000_000){i->statement.clearBindings();statement.bindString(1,"fixture-${i.toString().padStart(7,'0')}");statement.bindString(2,id);statement.bindString(3,dates[i%dates.size]);statement.bindString(4,t);statement.bindString(5,t);statement.executeInsert()};sql.setTransactionSuccessful()}finally{sql.endTransaction();statement.close()}
        val plan=db.query("EXPLAIN QUERY PLAN SELECT * FROM atlas_facts WHERE metric_id=? AND deleted=0 AND local_date BETWEEN '2024-01-01' AND '2024-01-31' LIMIT 50",arrayOf(id)).toString();assertTrue(plan.contains("fact_metric_date"))
        val durations=mutableListOf<Long>();repeat(5){val begin=System.nanoTime();assertEquals(50,db.facts(id,"2024-01-01","2024-01-31").size);durations.add((System.nanoTime()-begin)/1_000_000)}
        val stats=db.aggregate(id,"2024-01-01","2024-01-31","SUM");assertEquals(0,stats.s("value").toBigDecimal().compareTo(stats.getLong("count").toBigDecimal()*java.math.BigDecimal("0.01")))
        println("PERF million_insert_ms=${(System.nanoTime()-started)/1_000_000} month_query_ms=$durations exact_count=${stats.getLong("count")}")
    }
}
