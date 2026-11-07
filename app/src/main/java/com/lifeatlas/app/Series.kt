package com.lifeatlas.app

import org.json.JSONObject
import org.json.JSONArray
import java.math.BigDecimal
import java.math.MathContext
import java.time.*

private class DailyBucket {
    var count=0L;var missing=0L;var sum=BigDecimal.ZERO;var last:BigDecimal?=null;var weighted=BigDecimal.ZERO;var weights=BigDecimal.ZERO
    var min:BigDecimal?=null;var max:BigDecimal?=null;val values=mutableListOf<BigDecimal>();val intervals=mutableListOf<Pair<Long,Long>>()
    fun add(value:BigDecimal?,weight:BigDecimal?,method:String) {if(value==null){missing++;return};count++;sum+=value;last=value;min=min?.min(value) ?: value;max=max?.max(value) ?: value;if(method=="MEDIAN")values.add(value);if(weight!=null){require(weight>=BigDecimal.ZERO);weighted+=value*weight;weights+=weight}}
    fun result(method:String):BigDecimal?=when(method){"COUNT"->(count+missing).toBigDecimal();"UNION_DURATION"->if(intervals.isEmpty())null else MathEngine.union(intervals).toBigDecimal().movePointLeft(3);"WEIGHTED_MEAN"->if(weights.signum()==0)null else weighted.divide(weights,MathContext.DECIMAL128);"LAST_SNAPSHOT"->last;"MIN"->min;"MAX"->max;"MEDIAN"->MathEngine.median(values);"MEAN"->if(count==0L)null else sum.divide(count.toBigDecimal(),MathContext.DECIMAL128);else->if(count==0L)null else sum}
}
fun AtlasStore.dailySeries(metric:String,from:String,to:String,methodOverride:String?=null):List<JSONObject> = synchronized(this) {
    val begin=LocalDate.parse(from);val finish=LocalDate.parse(to);require(begin<=finish && java.time.temporal.ChronoUnit.DAYS.between(begin,finish)<=36600){"日期范围无效"}
    val c=config(metric);val method=(methodOverride ?: c.s("dailyAggregate",if(c.s("kind")=="SNAPSHOT")"LAST_SNAPSHOT" else c.s("aggregate","SUM"))).let{if(it in aggregateMethods)it else "SUM"}
    val days=sortedMapOf<String,DailyBucket>();val lower=begin.atStartOfDay(ZoneId.systemDefault()).toInstant();val upper=finish.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
    val allocation=c.s("kind")=="ALLOCATION";val split=allocation && c.s("role")!="sleep"
    val where=if(split)"(local_date BETWEEN ? AND ? OR (start_at IS NOT NULL AND start_at<? AND end_at>?))"else "local_date BETWEEN ? AND ?"
    val args=if(split)arrayOf(metric,from,to,upper.toString(),lower.toString())else arrayOf(metric,from,to)
    fun add(date:String,value:BigDecimal?,weight:BigDecimal?=null,start:Instant?=null,end:Instant?=null){if(date<from || date>to)return;val b=days.getOrPut(date){DailyBucket()};b.add(value,weight,method);if(start!=null && end!=null)b.intervals.add(start.toEpochMilli() to end.toEpochMilli())}
    readableDatabase.rawQuery("SELECT local_date,value,fields_json,start_at,end_at,zone FROM atlas_facts WHERE metric_id=? AND deleted=0 AND $where ORDER BY local_date,occurred_at,created_at,id",args).use{cursor->while(cursor.moveToNext()) {
        val fields=JSONObject(cursor.getString(2));if(c.s("role") in setOf("income","expense") && fields.s("flow")=="transfer")continue
        val weight=fields.s("weight").toBigDecimalOrNull();val value=resolvedRecordValue(if(cursor.isNull(1))null else decimal(cursor.getString(1)),fields,c)
        if(allocation && !cursor.isNull(3) && !cursor.isNull(4)) {
            val start=Instant.parse(cursor.getString(3));val end=Instant.parse(cursor.getString(4));val zone=ZoneId.of(cursor.getString(5))
            if(split) {var t=start;while(t<end){val day=t.atZone(zone).toLocalDate();val next=minOf(end,day.plusDays(1).atStartOfDay(zone).toInstant());val duration=Units.convert(secondsBetween(t,next),"s",c.s("unit"));add(day.toString(),duration,weight,t,next);t=next}}
            else add(cursor.getString(0),value,weight,start,end)
        } else add(cursor.getString(0),value,weight)
    }}
    if(allocation)query("SELECT * FROM atlas_timers WHERE metric_id=? AND state!='STOPPED'",arrayOf(metric)).forEach{timer->if(method=="UNION_DURATION") {timerIntervals(timer,appContext).forEach{(start,end)->var t=start;val zone=ZoneId.of(timer.s("zone"));while(t<end){val day=t.atZone(zone).toLocalDate();val next=minOf(end,day.plusDays(1).atStartOfDay(zone).toInstant());add(day.toString(),Units.convert(secondsBetween(t,next),"s",c.s("unit")),start=t,end=next);t=next}}}else timerDaily(timer,c,appContext).forEach{(day,value)->add(day,value)}}
    days.map{(day,b)->obj("date" to day,"value" to b.result(method)?.let{if(method=="UNION_DURATION")Units.convert(it,"s",c.s("unit"))else it}?.stripTrailingZeros()?.toPlainString(),"count" to b.count,"missingCount" to b.missing,"method" to method)}
}
