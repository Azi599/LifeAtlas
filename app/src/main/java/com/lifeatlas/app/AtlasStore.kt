package com.lifeatlas.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class Metric(val id: String, val title: String, val kind: String, val unit: String)
data class Project(val id: String, val title: String)
data class Point(val date: String, val value: String)

class AtlasStore(context: Context) : SQLiteOpenHelper(context, "lifeatlas.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE projects(id TEXT PRIMARY KEY,title TEXT NOT NULL,created_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE metrics(id TEXT PRIMARY KEY,project_id TEXT NOT NULL REFERENCES projects(id),title TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('SNAPSHOT','CHANGE','ALLOCATION')),unit TEXT NOT NULL)")
        db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY,recorded_at TEXT NOT NULL,zone TEXT NOT NULL)")
        db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY,event_id TEXT NOT NULL REFERENCES events(id),metric_id TEXT NOT NULL REFERENCES metrics(id),kind TEXT NOT NULL CHECK(kind IN ('SNAPSHOT','CHANGE','ALLOCATION')),value TEXT NOT NULL,local_date TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,zone TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_record_metric_day ON records(metric_id,local_date,created_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Explicit migration required") }
    fun install(json: JSONObject): String {
        val title=json.optString("title").trim(); require(title.isNotEmpty() && title.length<=80) { "项目名称无效" }
        val metrics=json.getJSONArray("metrics"); require(metrics.length() in 1..20) { "指标数量必须为 1 到 20" }
        val id=UUID.randomUUID().toString(); val db=writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("projects",null,ContentValues().apply { put("id",id);put("title",title);put("created_at",Instant.now().toString()) })
            for(i in 0 until metrics.length()) {
                val m=metrics.getJSONObject(i);val name=m.optString("title").trim();val kind=m.optString("kind");val unit=m.optString("unit").trim()
                require(name.isNotEmpty() && name.length<=80 && unit.length<=30 && kind in setOf("SNAPSHOT","CHANGE","ALLOCATION")) { "指标定义无效" }
                db.insertOrThrow("metrics",null,ContentValues().apply { put("id",UUID.randomUUID().toString());put("project_id",id);put("title",name);put("kind",kind);put("unit",unit) })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }
    fun projects():List<Project> = readableDatabase.rawQuery("SELECT id,title FROM projects ORDER BY created_at DESC",null).use { c -> buildList { while(c.moveToNext()) add(Project(c.getString(0),c.getString(1))) } }
    fun metrics(project:String):List<Metric> = readableDatabase.rawQuery("SELECT id,title,kind,unit FROM metrics WHERE project_id=? ORDER BY rowid", arrayOf(project)).use { c -> buildList { while(c.moveToNext()) add(Metric(c.getString(0),c.getString(1),c.getString(2),c.getString(3))) } }
    fun record(metric:Metric, value:String, date:String) {
        BigDecimal(value);LocalDate.parse(date);val now=Instant.now().toString();val zone=ZoneId.systemDefault().id;val event=UUID.randomUUID().toString();val db=writableDatabase
        db.beginTransaction();try {
            db.insertOrThrow("events",null,ContentValues().apply { put("id",event);put("recorded_at",now);put("zone",zone) })
            db.insertOrThrow("records",null,ContentValues().apply { put("id",UUID.randomUUID().toString());put("event_id",event);put("metric_id",metric.id);put("kind",metric.kind);put("value",value);put("local_date",date);put("created_at",now);put("updated_at",now);put("zone",zone) })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun points(metric:String):List<Point> = readableDatabase.rawQuery("SELECT local_date,value FROM records WHERE metric_id=? ORDER BY local_date DESC,created_at DESC LIMIT 120",arrayOf(metric)).use { c -> buildList { while(c.moveToNext()) add(Point(c.getString(0),c.getString(1))) } }
}
