package com.lifeatlas.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.math.BigDecimal
import java.math.MathContext
import java.time.*

class AtlasStore(val appContext:Context, name:String="lifeatlas.db") : SQLiteOpenHelper(appContext,name,null,4) {
    val tables=listOf("atlas_objects","atlas_relations","atlas_events","atlas_facts","atlas_fields","atlas_plugins","atlas_layout","atlas_timers","atlas_workflows","atlas_builds","atlas_operations")
    private var journal:JSONArray?=null
    override fun onConfigure(db:SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true);db.enableWriteAheadLogging() }
    override fun onCreate(db:SQLiteDatabase) { schema(db) }
    private fun schema(db:SQLiteDatabase) {
        listOf(
            "CREATE TABLE atlas_objects(id TEXT PRIMARY KEY,parent_id TEXT REFERENCES atlas_objects(id) DEFERRABLE INITIALLY DEFERRED,type TEXT NOT NULL,title TEXT NOT NULL,config TEXT NOT NULL DEFAULT '{}',plugin_id TEXT,revision INTEGER NOT NULL DEFAULT 1,archived INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)",
            "CREATE INDEX object_parent ON atlas_objects(parent_id,type,archived)",
            "CREATE TABLE atlas_relations(id TEXT PRIMARY KEY,from_id TEXT NOT NULL,to_id TEXT NOT NULL,kind TEXT NOT NULL,UNIQUE(from_id,to_id,kind))",
            "CREATE TABLE atlas_events(id TEXT PRIMARY KEY,raw_input TEXT NOT NULL,source TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,zone TEXT NOT NULL)",
            "CREATE TABLE atlas_facts(id TEXT PRIMARY KEY,event_id TEXT NOT NULL REFERENCES atlas_events(id),metric_id TEXT NOT NULL REFERENCES atlas_objects(id),kind TEXT NOT NULL CHECK(kind IN ('SNAPSHOT','CHANGE','ALLOCATION')),value TEXT,local_date TEXT NOT NULL,occurred_at TEXT,start_at TEXT,end_at TEXT,precision TEXT NOT NULL,zone TEXT NOT NULL,fields_json TEXT NOT NULL DEFAULT '{}',deleted INTEGER NOT NULL DEFAULT 0,revision INTEGER NOT NULL DEFAULT 1,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)",
            "CREATE INDEX fact_metric_date ON atlas_facts(metric_id,deleted,local_date,occurred_at,id)",
            "CREATE INDEX fact_date ON atlas_facts(deleted,local_date,id)",
            "CREATE INDEX fact_event ON atlas_facts(event_id)",
            "CREATE TABLE atlas_fields(id TEXT PRIMARY KEY,fact_id TEXT NOT NULL REFERENCES atlas_facts(id),field_id TEXT NOT NULL,value TEXT,type TEXT NOT NULL)",
            "CREATE INDEX field_search ON atlas_fields(field_id,value,fact_id)",
            "CREATE TABLE atlas_plugins(id TEXT PRIMARY KEY,plugin_id TEXT NOT NULL,version INTEGER NOT NULL,manifest TEXT NOT NULL,status TEXT NOT NULL,created_at TEXT NOT NULL,UNIQUE(plugin_id,version))",
            "CREATE TABLE atlas_layout(id TEXT PRIMARY KEY,object_id TEXT NOT NULL,position INTEGER NOT NULL,hidden INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE atlas_timers(id TEXT PRIMARY KEY,metric_id TEXT NOT NULL,state TEXT NOT NULL,periods TEXT NOT NULL,running_at TEXT,zone TEXT NOT NULL,created_at TEXT NOT NULL,elapsed_start INTEGER,boot_count INTEGER)",
            "CREATE TABLE atlas_workflows(id TEXT PRIMARY KEY,title TEXT NOT NULL,spec TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 1)",
            "CREATE TABLE atlas_builds(id TEXT PRIMARY KEY,request TEXT NOT NULL,status TEXT NOT NULL,remote_id TEXT,artifact_url TEXT,detail TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)",
            "CREATE TABLE atlas_operations(id TEXT PRIMARY KEY,title TEXT NOT NULL,patches TEXT NOT NULL,result TEXT NOT NULL,undone INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL)"
        ).forEach(db::execSQL)
        revisionSchema(db)
    }
    private fun revisionSchema(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE atlas_revisions(id TEXT PRIMARY KEY,revision INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE atlas_aggregates(id TEXT PRIMARY KEY,metric_id TEXT NOT NULL,revision INTEGER NOT NULL,result TEXT NOT NULL)")
        listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD").forEach{(event,row)->db.execSQL("CREATE TRIGGER fact_revision_${event.lowercase()} AFTER $event ON atlas_facts BEGIN INSERT INTO atlas_revisions VALUES ($row.metric_id,1) ON CONFLICT(id) DO UPDATE SET revision=revision+1; END")}
        db.execSQL("CREATE TRIGGER metric_revision AFTER UPDATE OF config ON atlas_objects WHEN NEW.type='METRIC' BEGIN INSERT INTO atlas_revisions VALUES(NEW.id,1) ON CONFLICT(id) DO UPDATE SET revision=revision+1; END")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        if(oldVersion==1) {
            schema(db)
            db.execSQL("INSERT INTO atlas_objects(id,type,title,created_at,updated_at) SELECT id,'PROJECT',title,created_at,created_at FROM projects")
            db.rawQuery("SELECT id,project_id,title,kind,unit FROM metrics",null).use { c->while(c.moveToNext()) {
                val t=now();db.insertOrThrow("atlas_objects",null,cv(obj("id" to c.getString(0),"parent_id" to c.getString(1),"type" to "METRIC","title" to c.getString(2),"config" to obj("kind" to c.getString(3),"unit" to c.getString(4),"aggregate" to if(c.getString(3)=="SNAPSHOT") "LAST_SNAPSHOT" else "SUM").toString(),"created_at" to t,"updated_at" to t)))
            } }
            db.execSQL("INSERT INTO atlas_events SELECT id,'','legacy',recorded_at,recorded_at,zone FROM events")
            db.execSQL("INSERT INTO atlas_facts(id,event_id,metric_id,kind,value,local_date,precision,zone,created_at,updated_at) SELECT id,event_id,metric_id,kind,value,local_date,'DATE',zone,created_at,updated_at FROM records")
        } else if(oldVersion in 2..3) {if(oldVersion==2) revisionSchema(db);db.execSQL("ALTER TABLE atlas_timers ADD COLUMN elapsed_start INTEGER");db.execSQL("ALTER TABLE atlas_timers ADD COLUMN boot_count INTEGER")} else error("缺少数据库迁移：$oldVersion → $newVersion")
    }
    private fun cv(j:JSONObject)=ContentValues().apply { j.keys().forEach { k-> val v=j.get(k);when {v==JSONObject.NULL->putNull(k);v is Int->put(k,v);v is Long->put(k,v);else->put(k,v.toString())} } }
    private fun row(c:Cursor):JSONObject=JSONObject().apply {c.columnNames.forEachIndexed {i,k-> put(k,if(c.isNull(i)) JSONObject.NULL else when(c.getType(i)){Cursor.FIELD_TYPE_INTEGER->c.getLong(i);Cursor.FIELD_TYPE_FLOAT->c.getDouble(i);else->c.getString(i)})} }
    fun query(sql:String,args:Array<String>?=null):List<JSONObject> = readableDatabase.rawQuery(sql,args).use {c->buildList {while(c.moveToNext()) add(row(c))} }
    fun one(table:String,id:String):JSONObject? {require(table in tables);return query("SELECT * FROM $table WHERE id=?",arrayOf(id)).firstOrNull()}
    fun objects(parent:String?=null,all:Boolean=false):List<JSONObject> = if(all) query("SELECT * FROM atlas_objects WHERE archived=0 ORDER BY title LIMIT 500") else query("SELECT * FROM atlas_objects WHERE archived=0 AND ${if(parent==null) "parent_id IS NULL" else "parent_id=?"} ORDER BY type,title LIMIT 200",parent?.let{arrayOf(it)})
    fun metrics()=query("SELECT * FROM atlas_objects WHERE type='METRIC' AND archived=0 ORDER BY title LIMIT 500")
    fun config(id:String)=JSONObject(one("atlas_objects",id)?.s("config") ?: error("对象不存在"))
    fun put(table:String,row:JSONObject) {
        require(table in tables && table!="atlas_operations");val j=journal ?: error("写入必须经过操作事务");val id=row.getString("id");val before=one(table,id)
        j.put(obj("table" to table,"id" to id,"before" to before,"after" to row))
        if(before==null) writableDatabase.insertOrThrow(table,null,cv(row)) else writableDatabase.update(table,cv(row),"id=?",arrayOf(id))
    }
    fun remove(table:String,id:String) {
        require(table in tables && table!="atlas_operations");val j=journal ?: error("写入必须经过操作事务");val before=one(table,id) ?: return
        j.put(obj("table" to table,"id" to id,"before" to before,"after" to null));writableDatabase.delete(table,"id=?",arrayOf(id))
    }
    private fun restore(table:String,id:String,row:JSONObject?) { if(row==null) writableDatabase.delete(table,"id=?",arrayOf(id)) else {val existing=one(table,id);if(existing==null) writableDatabase.insertOrThrow(table,null,cv(row)) else writableDatabase.update(table,cv(row),"id=?",arrayOf(id))} }
    @Synchronized fun transact(title:String,requestId:String=uid(),block:()->JSONObject):JSONObject {
        one("atlas_operations",requestId)?.let {return JSONObject(it.s("result"))}
        require(journal==null);val db=writableDatabase;db.beginTransaction();journal=JSONArray()
        try {
            val result=block();val patches=journal!!
            // Preserve redo only until the next actual mutation.
            if(patches.length()>0) db.execSQL("UPDATE atlas_operations SET undone=2 WHERE undone=1")
            db.insertOrThrow("atlas_operations",null,cv(obj("id" to requestId,"title" to title,"patches" to patches.toString(),"result" to result.toString(),"undone" to 0,"created_at" to now())))
            db.setTransactionSuccessful();return result
        } finally {journal=null;db.endTransaction()}
    }
    @Synchronized fun undo(redo:Boolean=false):String {
        val op=query("SELECT * FROM atlas_operations WHERE undone=? AND patches!='[]' ORDER BY created_at ${if(redo) "ASC" else "DESC"} LIMIT 1",arrayOf(if(redo) "1" else "0")).firstOrNull() ?: error(if(redo) "没有可恢复操作" else "没有可撤销操作")
        val changes=JSONArray(op.s("patches")).objects().let{if(redo) it else it.reversed()};val db=writableDatabase;db.beginTransaction()
        try { changes.forEach {p->val table=p.getString("table");require(table in tables);val target=p.optJSONObject(if(redo) "after" else "before");restore(table,p.getString("id"),target)};db.execSQL("UPDATE atlas_operations SET undone=? WHERE id=?",arrayOf(if(redo) 0 else 1,op.getString("id")));db.setTransactionSuccessful() } finally {db.endTransaction()};return op.s("title")
    }
    fun facts(metric:String?=null,from:String="0001-01-01",to:String="9999-12-31",offset:Int=0,limit:Int=50,includeDeleted:Boolean=false):List<JSONObject> {
        val sql="SELECT f.*,o.title metric_title FROM atlas_facts f JOIN atlas_objects o ON o.id=f.metric_id WHERE ${if(includeDeleted) "1=1" else "f.deleted=0"} AND f.local_date>=? AND f.local_date<=? ${if(metric==null) "" else "AND f.metric_id=?"} ORDER BY f.local_date DESC,f.id DESC LIMIT ? OFFSET ?"
        return query(sql,(listOf(from,to)+listOfNotNull(metric)+listOf(limit.coerceIn(1,500).toString(),offset.coerceAtLeast(0).toString())).toTypedArray()).map{r->if(r.s("value").isNotEmpty()) {val raw=r.s("value");r.put("raw_value",raw);r.put("value",normalizedValue(decimal(raw),JSONObject(r.s("fields_json")),config(r.s("metric_id"))).toPlainString())};r}
    }
    fun aggregate(metric:String,from:String,to:String,method:String=config(metric).s("aggregate","SUM")):JSONObject {
        LocalDate.parse(from);LocalDate.parse(to);require(method in aggregateMethods)
        val metricConfig=config(metric)
        val revision=query("SELECT revision FROM atlas_revisions WHERE id=?",arrayOf(metric)).firstOrNull()?.optLong("revision") ?: 0L
        val cacheKey="$metric|$from|$to|$method|${ZoneId.systemDefault().id}"
        query("SELECT result FROM atlas_aggregates WHERE id=? AND revision=?",arrayOf(cacheKey,revision.toString())).firstOrNull()?.let{return JSONObject(it.s("result"))}
        var count=0L;var sum=BigDecimal.ZERO;var weighted=BigDecimal.ZERO;var weights=BigDecimal.ZERO;var last:String?=null;var min:BigDecimal?=null;var max:BigDecimal?=null
        val distribution=ArrayList<BigDecimal>();val intervals=ArrayList<Pair<Long,Long>>()
        val lower=LocalDate.parse(from).atStartOfDay(ZoneId.systemDefault()).toInstant().epochSecond
        val upper=LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().epochSecond
        val union=method=="UNION_DURATION"
        val where=if(union) "start_at IS NOT NULL AND end_at IS NOT NULL AND start_at<? AND end_at>?" else "local_date BETWEEN ? AND ?"
        val args=if(union) arrayOf(metric,Instant.ofEpochSecond(upper).toString(),Instant.ofEpochSecond(lower).toString()) else arrayOf(metric,from,to)
        readableDatabase.rawQuery("SELECT value,fields_json,start_at,end_at FROM atlas_facts WHERE metric_id=? AND deleted=0 AND $where ORDER BY local_date,occurred_at,created_at,id",args).use {c->while(c.moveToNext()) {
            if(!c.isNull(0)) {val fields=JSONObject(c.getString(1));if(metricConfig.s("role") in setOf("income","expense") && fields.s("flow")=="transfer") continue;val v=normalizedValue(decimal(c.getString(0)),fields,metricConfig);count++;sum+=v;last=v.toPlainString();if(min?.let{v<it} ?: true) min=v;if(max?.let{v>it} ?: true) max=v;if(method=="MEDIAN") distribution.add(v)
                if(method=="WEIGHTED_MEAN") {val w=JSONObject(c.getString(1)).s("weight").takeIf{it.isNotEmpty()}?.let(::decimal);if(w!=null){require(w>=BigDecimal.ZERO);weighted+=v*w;weights+=w}}
            }
            if(method=="UNION_DURATION" && !c.isNull(2) && !c.isNull(3)) intervals.add(maxOf(lower,Instant.parse(c.getString(2)).epochSecond) to minOf(upper,Instant.parse(c.getString(3)).epochSecond))
        } }
        val result=when(method){"SUM"->if(count==0L) null else sum;"COUNT"->count.toBigDecimal();"MEAN"->if(count==0L) null else sum.divide(count.toBigDecimal(),MathContext.DECIMAL128);"LAST_SNAPSHOT"->last?.toBigDecimal();"MIN"->min;"MAX"->max;"MEDIAN"->MathEngine.median(distribution);"WEIGHTED_MEAN"->if(weights.signum()==0) null else weighted.divide(weights,MathContext.DECIMAL128);"UNION_DURATION"->if(intervals.isEmpty()) null else MathEngine.union(intervals).toBigDecimal();else->null}
        val report=obj("metricId" to metric,"method" to method,"value" to result?.stripTrailingZeros()?.toPlainString(),"count" to count,"from" to from,"to" to to,"unit" to if(method=="UNION_DURATION") "秒" else metricConfig.s("unit"),"missing" to "缺失值不计入样本；时段并集只计算明确起止区间")
        writableDatabase.insertWithOnConflict("atlas_aggregates",null,cv(obj("id" to cacheKey,"metric_id" to metric,"revision" to revision,"result" to report.toString())),SQLiteDatabase.CONFLICT_REPLACE)
        return report
    }
    fun export(writer:Writer) {
        val db=readableDatabase;db.beginTransactionNonExclusive()
        try {writer.write(obj("format" to "lifeatlas-jsonl","version" to 2).toString()+"\n");tables.forEach {t->db.rawQuery("SELECT * FROM $t ORDER BY id",null).use {c->while(c.moveToNext()) writer.write(obj("table" to t,"row" to row(c)).toString()+"\n")}};writer.flush();db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    @Synchronized fun import(reader:BufferedReader) {
        val header=JSONObject(reader.readLine() ?: error("备份为空"));require(header.s("format")=="lifeatlas-jsonl" && header.optInt("version")==2){"备份格式不兼容"}
        val recovery=File(appContext.filesDir,"before-restore-${System.currentTimeMillis()}.jsonl");recovery.bufferedWriter().use(::export)
        val db=writableDatabase;db.beginTransaction()
        try {db.execSQL("PRAGMA defer_foreign_keys=ON");tables.reversed().forEach{db.delete(it,null,null)};var line=reader.readLine();while(line!=null){val entry=JSONObject(line);val table=entry.getString("table");require(table in tables);db.insertOrThrow(table,null,cv(entry.getJSONObject("row")));line=reader.readLine()};db.rawQuery("PRAGMA foreign_key_check",null).use{require(!it.moveToFirst()){ "备份关联校验失败" }};db.delete("atlas_aggregates",null,null);db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    fun exportCsv(writer:Writer) {
        fun q(x:String)="\""+x.replace("\"","\"\"")+"\""
        writer.write("record_id,event_id,metric_id,kind,value,local_date,occurred_at,zone,fields_json\n")
        readableDatabase.rawQuery("SELECT id,event_id,metric_id,kind,value,local_date,occurred_at,zone,fields_json FROM atlas_facts WHERE deleted=0 ORDER BY local_date,id",null).use{c->while(c.moveToNext()) writer.write((0..8).joinToString(","){q(c.getString(it) ?: "")}+"\n")};writer.flush()
    }
}
