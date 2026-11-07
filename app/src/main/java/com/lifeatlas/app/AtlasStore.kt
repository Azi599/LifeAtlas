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

class AtlasStore(val appContext:Context, name:String="lifeatlas.db") : SQLiteOpenHelper(appContext,name,null,7) {
    val tables=listOf("atlas_objects","atlas_themes","atlas_appearance","atlas_versions","atlas_relations","atlas_events","atlas_facts","atlas_fields","atlas_plugins","atlas_layout","atlas_timers","atlas_workflows","atlas_builds","atlas_operations")
    private var journal:JSONArray?=null
    override fun onConfigure(db:SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true);db.enableWriteAheadLogging() }
    override fun onCreate(db:SQLiteDatabase) { schema(db) }
    private fun schema(db:SQLiteDatabase) {
        listOf(
            "CREATE TABLE atlas_objects(id TEXT PRIMARY KEY,parent_id TEXT REFERENCES atlas_objects(id) DEFERRABLE INITIALLY DEFERRED,type TEXT NOT NULL,title TEXT NOT NULL,config TEXT NOT NULL DEFAULT '{}',plugin_id TEXT,revision INTEGER NOT NULL DEFAULT 1,archived INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)",
            "CREATE INDEX object_parent ON atlas_objects(parent_id,type,archived)",
            "CREATE TABLE atlas_relations(id TEXT PRIMARY KEY,from_id TEXT NOT NULL,to_id TEXT NOT NULL,kind TEXT NOT NULL,UNIQUE(from_id,to_id,kind))",
            "CREATE TABLE atlas_events(id TEXT PRIMARY KEY,raw_input TEXT NOT NULL,source TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,zone TEXT NOT NULL,local_date TEXT,occurred_at TEXT,precision TEXT NOT NULL DEFAULT 'UNKNOWN')",
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
        versionSchema(db)
        themeSchema(db)
    }
    private fun themeSchema(db:SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS fact_interval ON atlas_facts(metric_id,deleted,start_at,end_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS event_date ON atlas_events(local_date,id)")
        db.execSQL("CREATE TABLE atlas_themes(id TEXT PRIMARY KEY,title TEXT NOT NULL,config TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE atlas_appearance(id TEXT PRIMARY KEY,theme_id TEXT NOT NULL,mode TEXT NOT NULL,compact INTEGER NOT NULL DEFAULT 0)")
    }
    private fun versionSchema(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE atlas_versions(id TEXT PRIMARY KEY,object_id TEXT NOT NULL REFERENCES atlas_objects(id) DEFERRABLE INITIALLY DEFERRED,revision INTEGER NOT NULL,snapshot TEXT NOT NULL,created_at TEXT NOT NULL,UNIQUE(object_id,revision))")
    }
    private fun seedVersions(db:SQLiteDatabase) {
        db.rawQuery("SELECT * FROM atlas_objects",null).use{c->while(c.moveToNext()){val r=row(c);db.insertWithOnConflict("atlas_versions",null,cv(obj("id" to "${r.s("id")}@${r.optLong("revision",1)}","object_id" to r.s("id"),"revision" to r.optLong("revision",1),"snapshot" to r.toString(),"created_at" to r.s("updated_at"))),SQLiteDatabase.CONFLICT_IGNORE)}}
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
            db.execSQL("INSERT INTO atlas_events(id,raw_input,source,created_at,updated_at,zone) SELECT id,'','legacy',recorded_at,recorded_at,zone FROM events")
            db.execSQL("INSERT INTO atlas_facts(id,event_id,metric_id,kind,value,local_date,precision,zone,created_at,updated_at) SELECT id,event_id,metric_id,kind,value,local_date,'DATE',zone,created_at,updated_at FROM records")
        } else if(oldVersion in 2..6) {if(oldVersion==2)revisionSchema(db);if(oldVersion<=3){db.execSQL("ALTER TABLE atlas_timers ADD COLUMN elapsed_start INTEGER");db.execSQL("ALTER TABLE atlas_timers ADD COLUMN boot_count INTEGER")};if(oldVersion<=4){db.execSQL("ALTER TABLE atlas_events ADD COLUMN local_date TEXT");db.execSQL("ALTER TABLE atlas_events ADD COLUMN occurred_at TEXT");db.execSQL("ALTER TABLE atlas_events ADD COLUMN precision TEXT NOT NULL DEFAULT 'UNKNOWN'")};if(oldVersion<=5)versionSchema(db);themeSchema(db)} else error("缺少数据库迁移：$oldVersion → $newVersion")
        seedVersions(db)
    }
    private fun cv(j:JSONObject)=ContentValues().apply { j.keys().forEach { k-> val v=j.get(k);when {v==JSONObject.NULL->putNull(k);v is Int->put(k,v);v is Long->put(k,v);else->put(k,v.toString())} } }
    private fun row(c:Cursor):JSONObject=JSONObject().apply {c.columnNames.forEachIndexed {i,k-> put(k,if(c.isNull(i)) JSONObject.NULL else when(c.getType(i)){Cursor.FIELD_TYPE_INTEGER->c.getLong(i);Cursor.FIELD_TYPE_FLOAT->c.getDouble(i);else->c.getString(i)})} }
    fun query(sql:String,args:Array<String>?=null):List<JSONObject> = readableDatabase.rawQuery(sql,args).use {c->buildList {while(c.moveToNext()) add(row(c))} }
    fun one(table:String,id:String):JSONObject? {require(table in tables);return query("SELECT * FROM $table WHERE id=?",arrayOf(id)).firstOrNull()}
    fun objects(parent:String?=null,all:Boolean=false):List<JSONObject> = if(all) query("SELECT * FROM atlas_objects WHERE archived=0 ORDER BY title LIMIT 500") else query("SELECT * FROM atlas_objects WHERE archived=0 AND ${if(parent==null) "parent_id IS NULL" else "parent_id=?"} ORDER BY type,title LIMIT 200",parent?.let{arrayOf(it)})
    fun metrics()=query("SELECT * FROM atlas_objects WHERE type='METRIC' AND archived=0 ORDER BY title,id")
    fun config(id:String)=JSONObject(one("atlas_objects",id)?.s("config") ?: error("对象不存在"))
    fun put(table:String,row:JSONObject) {
        require(table in tables && table!="atlas_operations");val j=journal ?: error("写入必须经过操作事务");val id=row.getString("id");val before=one(table,id)
        if(table=="atlas_objects" && before!=null && row.optLong("revision",1)<=before.optLong("revision",1))row.put("revision",before.optLong("revision",1)+1)
        j.put(obj("table" to table,"id" to id,"before" to before,"after" to row))
        if(before==null) writableDatabase.insertOrThrow(table,null,cv(row)) else writableDatabase.update(table,cv(row),"id=?",arrayOf(id))
        if(table=="atlas_objects")put("atlas_versions",obj("id" to "$id@${row.optLong("revision",1)}","object_id" to id,"revision" to row.optLong("revision",1),"snapshot" to row.toString(),"created_at" to row.s("updated_at",now())))
    }
    fun remove(table:String,id:String) {
        require(table in tables && table!="atlas_operations");val j=journal ?: error("写入必须经过操作事务");val before=one(table,id) ?: return
        j.put(obj("table" to table,"id" to id,"before" to before,"after" to null));writableDatabase.delete(table,"id=?",arrayOf(id))
    }
    fun <T> savepoint(block:()->T):T {
        val j=journal ?: error("需要操作事务");val length=j.length();writableDatabase.execSQL("SAVEPOINT import_row")
        try{val result=block();writableDatabase.execSQL("RELEASE SAVEPOINT import_row");return result}catch(e:Exception){writableDatabase.execSQL("ROLLBACK TO SAVEPOINT import_row");writableDatabase.execSQL("RELEASE SAVEPOINT import_row");while(j.length()>length)j.remove(j.length()-1);throw e}
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
    @Synchronized fun undo(redo:Boolean=false,expectedChange:String="",targetHint:String=""):String {
        val op=query("SELECT * FROM atlas_operations WHERE undone=? AND patches!='[]' ORDER BY created_at ${if(redo) "ASC" else "DESC"} LIMIT 1",arrayOf(if(redo) "1" else "0")).firstOrNull() ?: error(if(redo) "没有可恢复操作" else "没有可撤销操作")
        val patches=JSONArray(op.s("patches")).objects()
        if(expectedChange.isNotBlank()) {
            val records = patches.filter { it.s("table")=="atlas_facts" }
            require(records.isNotEmpty() && (expectedChange!="edit" || records.size==1) &&
                (targetHint.isBlank() || op.s("title").contains(targetHint)) &&
                patches.all{it.s("table") in setOf("atlas_facts","atlas_fields")} &&
                records.all { patch ->
                    val before=patch.optJSONObject("before") ?: return@all false
                    val after=patch.optJSONObject("after") ?: return@all false
                    when(expectedChange) {
                        "delete" -> before.optInt("deleted")==0 && after.optInt("deleted")==1
                        "edit" -> before.optInt("deleted")==0 && after.optInt("deleted")==0 &&
                            before.optLong("revision")!=after.optLong("revision")
                        else -> false
                    }
                }) {"最近的操作与这条撤销请求不一致"}
        }
        val changes=if(redo) patches else patches.reversed()
        val db=writableDatabase;db.beginTransaction()
        try { changes.forEach {p->val table=p.getString("table");require(table in tables);val target=p.optJSONObject(if(redo) "after" else "before");restore(table,p.getString("id"),target)};db.execSQL("UPDATE atlas_operations SET undone=? WHERE id=?",arrayOf(if(redo) 0 else 1,op.getString("id")));db.setTransactionSuccessful() } finally {db.endTransaction()};return op.s("title")
    }
    fun facts(metric:String?=null,from:String="0001-01-01",to:String="9999-12-31",offset:Int=0,limit:Int=50,includeDeleted:Boolean=false):List<JSONObject> {
        val sql="SELECT f.*,o.title metric_title FROM atlas_facts f JOIN atlas_objects o ON o.id=f.metric_id WHERE ${if(includeDeleted) "1=1" else "f.deleted=0"} AND f.local_date>=? AND f.local_date<=? ${if(metric==null) "" else "AND f.metric_id=?"} ORDER BY f.local_date DESC,f.id DESC LIMIT ? OFFSET ?"
        return query(sql,(listOf(from,to)+listOfNotNull(metric)+listOf(limit.coerceIn(1,500).toString(),offset.coerceAtLeast(0).toString())).toTypedArray()).map{r->val raw=r.s("value").takeIf{it.isNotEmpty()}?.let(::decimal);val value=resolvedRecordValue(raw,JSONObject(r.s("fields_json")),config(r.s("metric_id")));r.put("raw_value",raw?.toPlainString() ?: JSONObject.NULL);r.put("value",value?.toPlainString() ?: JSONObject.NULL);if(raw!=null && value==null)r.put("calculationError",true);r}

    }
    fun timeline(metricId: String?, from: String, to: String, offset: Int): List<JSONObject> {
        val bounds = listOf(from.ifBlank { "0001-01-01" }, to.ifBlank { "9999-12-31" })
        val metricFilter = if (metricId == null) "" else "AND f.metric_id = ?"
        val withoutDate = if (from.isBlank() && to.isBlank()) "OR e.local_date IS NULL" else ""
        val notes = if (metricId != null) "" else """
            UNION ALL
            SELECT e.id, 1, e.local_date, julianday(e.occurred_at), e.created_at
            FROM atlas_events e
            WHERE (e.local_date BETWEEN ? AND ? $withoutDate)
                AND e.source = 'text'
        """.trimIndent()
        val args = bounds + if (metricId == null) bounds else listOf(metricId)
        val rows = query("""
            WITH feed AS (
                SELECT f.id, 0 is_note, f.local_date day,
                    julianday(COALESCE(f.start_at, f.occurred_at)) clock, f.created_at added
                FROM atlas_facts f
                WHERE f.deleted = 0 AND f.local_date BETWEEN ? AND ? $metricFilter
                $notes
                ORDER BY day DESC, clock DESC, added DESC, id DESC, is_note
                LIMIT 51 OFFSET ?
            )
            SELECT f.*, o.title metric_title, o.config metric_config, feed.day, feed.is_note,
                feed.id item_id, e.raw_input note, e.occurred_at note_time, e.zone note_zone
            FROM feed
            LEFT JOIN atlas_facts f ON feed.is_note = 0 AND f.id = feed.id
            LEFT JOIN atlas_objects o ON o.id = f.metric_id
            LEFT JOIN atlas_events e ON feed.is_note = 1 AND e.id = feed.id
            ORDER BY feed.day DESC, feed.clock DESC, feed.added DESC, feed.id DESC, feed.is_note
        """.trimIndent(), (args + offset.coerceAtLeast(0).toString()).toTypedArray())
        return rows.map { row ->
            if (row.optInt("is_note") == 1) {
                obj("id" to row.s("item_id"), "is_note" to 1, "raw_input" to row.s("note"),
                    "local_date" to row.opt("day"), "occurred_at" to row.opt("note_time"), "zone" to row.s("note_zone"))
            } else {
                val config = JSONObject(row.s("metric_config"))
                val raw = row.s("value").takeIf { it.isNotEmpty() }?.let(::decimal)
                val value = resolvedRecordValue(raw, JSONObject(row.s("fields_json")), config)
                row.put("value", value?.toPlainString() ?: JSONObject.NULL)
            }
        }
    }

    @Synchronized fun aggregate(metric:String,from:String,to:String,method:String=config(metric).s("aggregate","SUM")):JSONObject {
        LocalDate.parse(from);LocalDate.parse(to);require(method in aggregateMethods)
        val metricConfig=config(metric)
        val activeTimers=if(metricConfig.s("kind")=="ALLOCATION" && method in setOf("SUM","UNION_DURATION"))query("SELECT * FROM atlas_timers WHERE metric_id=? AND state!='STOPPED'",arrayOf(metric))else emptyList()
        val revision=query("SELECT revision FROM atlas_revisions WHERE id=?",arrayOf(metric)).firstOrNull()?.optLong("revision") ?: 0L
        val cacheKey="$metric|$from|$to|$method|${ZoneId.systemDefault().id}"
        if(activeTimers.isEmpty())query("SELECT result FROM atlas_aggregates WHERE id=? AND revision=?",arrayOf(cacheKey,revision.toString())).firstOrNull()?.let{return JSONObject(it.s("result"))}
        val definitionVersions=linkedMapOf<String,Long>()
        var hasLiveValue=false;var count=0L;var recordCount=0L;var weightedCount=0L;var sum=BigDecimal.ZERO;var weighted=BigDecimal.ZERO;var weights=BigDecimal.ZERO;var last:String?=null;var min:BigDecimal?=null;var max:BigDecimal?=null
        val distribution=ArrayList<BigDecimal>();val intervals=ArrayList<Pair<Long,Long>>()
        val lower=LocalDate.parse(from).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val upper=LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val union=method=="UNION_DURATION"
        val splitTime=metricConfig.s("kind")=="ALLOCATION" && metricConfig.s("role")!="sleep"
        val where=if(union) "start_at IS NOT NULL AND end_at IS NOT NULL AND start_at<? AND end_at>?" else if(splitTime) "(local_date BETWEEN ? AND ? OR (start_at IS NOT NULL AND start_at<? AND end_at>?))" else "local_date BETWEEN ? AND ?"
        val args=if(union) arrayOf(metric,Instant.ofEpochMilli(upper).toString(),Instant.ofEpochMilli(lower).toString()) else if(splitTime)arrayOf(metric,from,to,Instant.ofEpochMilli(upper).toString(),Instant.ofEpochMilli(lower).toString()) else arrayOf(metric,from,to)
        readableDatabase.rawQuery("SELECT value,fields_json,start_at,end_at FROM atlas_facts WHERE metric_id=? AND deleted=0 AND $where ORDER BY local_date,occurred_at,created_at,id",args).use {c->while(c.moveToNext()) {
            val fields=JSONObject(c.getString(1));if(metricConfig.s("role") in setOf("income","expense") && fields.s("flow")=="transfer") continue
            val definitionVersion=fields.optLong("_schemaRevision",1).toString();definitionVersions[definitionVersion]=(definitionVersions[definitionVersion] ?: 0)+1
            recordCount++
            val resolved=resolvedRecordValue(if(c.isNull(0))null else decimal(c.getString(0)),fields,metricConfig)
            if(resolved!=null) {var v=resolved;if(splitTime && !c.isNull(2) && !c.isNull(3)){val a=maxOf(Instant.parse(c.getString(2)),Instant.ofEpochMilli(lower));val b=minOf(Instant.parse(c.getString(3)),Instant.ofEpochMilli(upper));if(b<=a)continue;v=Units.convert(secondsBetween(a,b),"s",metricConfig.s("unit"))};count++;sum+=v;last=v.toPlainString();if(min?.let{v<it} ?: true) min=v;if(max?.let{v>it} ?: true) max=v;if(method=="MEDIAN") distribution.add(v)
                if(method=="WEIGHTED_MEAN") {val w=JSONObject(c.getString(1)).s("weight").takeIf{it.isNotEmpty()}?.let(::decimal);if(w!=null){require(w>=BigDecimal.ZERO);weighted+=v*w;weights+=w;weightedCount++}}
            }
            if(method=="UNION_DURATION" && !c.isNull(2) && !c.isNull(3)) intervals.add(maxOf(lower,Instant.parse(c.getString(2)).toEpochMilli()) to minOf(upper,Instant.parse(c.getString(3)).toEpochMilli()))
        } }
        activeTimers.forEach{timer->if(method=="SUM"){val values=timerDaily(timer,metricConfig,appContext).filterKeys{it>=from && it<=to}.values;sum+=MathEngine.sum(values.toList());if(values.isNotEmpty())hasLiveValue=true}else{timerIntervals(timer,appContext).forEach{(start,end)->val a=maxOf(lower,start.toEpochMilli());val b=minOf(upper,end.toEpochMilli());if(b>a)intervals.add(a to b)}}}
        val result=when(method){"SUM"->if(count==0L && !hasLiveValue) null else sum;"COUNT"->recordCount.toBigDecimal();"MEAN"->if(count==0L) null else sum.divide(count.toBigDecimal(),MathContext.DECIMAL128);"LAST_SNAPSHOT"->last?.toBigDecimal();"MIN"->min;"MAX"->max;"MEDIAN"->MathEngine.median(distribution);"WEIGHTED_MEAN"->if(weights.signum()==0) null else weighted.divide(weights,MathContext.DECIMAL128);"UNION_DURATION"->if(intervals.isEmpty()) null else MathEngine.union(intervals).toBigDecimal().movePointLeft(3);else->null}
        val report=obj("metricId" to metric,"method" to method,"value" to result?.stripTrailingZeros()?.toPlainString(),"count" to count,"live" to activeTimers.isNotEmpty(),"intervalCount" to intervals.size,"definitionVersions" to JSONObject(definitionVersions),"recordCount" to recordCount,"missingCount" to recordCount-count,"usedCount" to if(method=="WEIGHTED_MEAN") weightedCount else count,"sum" to sum.toPlainString(),"weightSum" to weights.toPlainString(),"weightedSum" to weighted.toPlainString(),"from" to from,"to" to to,"unit" to if(method=="UNION_DURATION") "秒" else metricConfig.s("unit"),"missing" to "缺失值不计入样本；时段并集只计算明确起止区间")
        if(activeTimers.isEmpty())writableDatabase.insertWithOnConflict("atlas_aggregates",null,cv(obj("id" to cacheKey,"metric_id" to metric,"revision" to revision,"result" to report.toString())),SQLiteDatabase.CONFLICT_REPLACE)
        return report
    }
    fun export(writer:Writer) {
        val db=readableDatabase;db.beginTransactionNonExclusive()
        try {writer.write(obj("format" to "lifeatlas-jsonl","version" to 2,"schemaVersion" to readableDatabase.version).toString()+"\n");tables.forEach {t->db.rawQuery("SELECT * FROM $t ORDER BY id",null).use {c->while(c.moveToNext()) writer.write(obj("table" to t,"row" to row(c)).toString()+"\n")}};writer.flush();db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    @Synchronized fun import(reader:BufferedReader) {
        val header=JSONObject(reader.readLine() ?: error("备份为空"));require(header.s("format")=="lifeatlas-jsonl" && header.optInt("version")==2){"备份格式不兼容"}
        require(header.optInt("schemaVersion",2)<=readableDatabase.version){"备份来自更高版本，请先更新应用"}
        val recovery=File(appContext.filesDir,"before-restore-${System.currentTimeMillis()}.jsonl");recovery.bufferedWriter().use(::export)
        val db=writableDatabase;db.beginTransaction()
        try {db.execSQL("PRAGMA defer_foreign_keys=ON");tables.reversed().forEach{db.delete(it,null,null)};var line=reader.readLine();while(line!=null){val entry=JSONObject(line);val table=entry.getString("table");require(table in tables);db.insertOrThrow(table,null,cv(entry.getJSONObject("row")));line=reader.readLine()};db.rawQuery("PRAGMA foreign_key_check",null).use{require(!it.moveToFirst()){ "备份关联校验失败" }};db.delete("atlas_aggregates",null,null);seedVersions(db);db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    fun exportCsv(writer:Writer) {
        fun q(x:String)="\""+x.replace("\"","\"\"")+"\""
        writer.write("record_id,event_id,metric_id,kind,value,unit,local_date,occurred_at,start_at,end_at,zone,fields_json\n")
        val definitions=hashMapOf<String,JSONObject>()
        readableDatabase.rawQuery("SELECT id,event_id,metric_id,kind,value,local_date,occurred_at,start_at,end_at,zone,fields_json FROM atlas_facts WHERE deleted=0 ORDER BY local_date,id",null).use{c->while(c.moveToNext()) {
            val definition=definitions.getOrPut(c.getString(2)){config(c.getString(2))};val fields=JSONObject(c.getString(10));val value=resolvedRecordValue(if(c.isNull(4))null else decimal(c.getString(4)),fields,definition)?.toPlainString().orEmpty();fields.put("_unitVersion",definition.optInt("unitVersion",0))
            val row=listOf(c.getString(0),c.getString(1),c.getString(2),c.getString(3),value,definition.s("unit"),c.getString(5),c.getString(6),c.getString(7),c.getString(8),c.getString(9),fields.toString())
            writer.write(row.joinToString(","){q(it ?: "")}+"\n")
        }};writer.flush()
    }
}
