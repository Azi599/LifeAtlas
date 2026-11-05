package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

/** Resolve display metadata without executing any step or altering source values. */
object PlanReview {
    fun metric(plan:JSONObject,args:JSONObject,objects:List<JSONObject>):JSONObject {
        val ref=args.optJSONObject("metricId")?.s("\$ref")
        if(ref!=null) {
            val path=ref.split('.');val step=(plan.optJSONArray("steps") ?: JSONArray()).objects().firstOrNull{it.s("stepId")==path.first()}
            val a=step?.optJSONObject("args") ?: JSONObject()
            if(path.getOrNull(1)=="metricIds") {
                val m=(a.optJSONObject("manifest")?.optJSONArray("metrics") ?: JSONArray()).objects().firstOrNull{it.s("key")==path.getOrNull(2)}
                if(m!=null)return obj("title" to m.s("title"),"config" to m.toString())
            }
            if(a.s("type")=="METRIC")return obj("title" to a.s("title"),"config" to (a.optJSONObject("config") ?: JSONObject()).toString())
        }
        return objects.firstOrNull{it.s("id")==args.s("metricId")} ?: obj("title" to args.s("title","记录"),"config" to "{}")
    }
    fun unit(metric:JSONObject):String=JSONObject(metric.s("config","{}")).s("unit").let{if(it=="CNY")"元" else it}
    fun value(metric:JSONObject,args:JSONObject):String {
        val raw=args.s("value");val number=raw.toBigDecimalOrNull() ?: return "待补数值"
        val role=JSONObject(metric.s("config","{}")).s("role")
        return (when(role){"expense"->number.abs().negate();"income"->number.abs();else->number}).stripTrailingZeros().toPlainString()
    }
    fun totals(plan:JSONObject,objects:List<JSONObject>):List<String> {
        val groups=linkedMapOf<String,Pair<BigDecimal,Int>>()
        (plan.optJSONArray("steps") ?: JSONArray()).objects().filter{it.s("actionId")=="atlas.record"}.forEach{step->
            val a=step.optJSONObject("args") ?: JSONObject();val m=metric(plan,a,objects);val number=value(m,a).toBigDecimalOrNull() ?: return@forEach
            val c=JSONObject(m.s("config","{}"));if(c.s("kind")!="CHANGE" || c.s("aggregate","SUM")!="SUM")return@forEach
            val key=m.s("title")+" · "+unit(m);val old=groups[key] ?: (BigDecimal.ZERO to 0);groups[key]=(old.first+number) to (old.second+1)
        }
        return groups.filterValues{it.second>1}.map{(key,v)->"$key：${v.first.stripTrailingZeros().toPlainString()}（${v.second}笔）"}
    }
    fun validateImageImport(plan:JSONObject,source:String) {
        val steps=plan.optJSONArray("steps") ?: error("图片识别未产生记录")
        require(steps.length()>0){"图片中暂无可确认的记录"}
        val allowed=setOf("atlas.record","atlas.createObject","atlas.createEvent")
        steps.objects().forEach{step->
            val id=step.s("actionId");require(id in allowed){"识别图片只需整理记录，请去掉额外功能后重试"}
            val a=step.optJSONObject("args") ?: JSONObject()
            if(id=="atlas.createObject")require(a.s("type") in setOf("PROJECT","METRIC")){"图片导入不创建额外页面或目录"}
            if(id=="atlas.record") {
                require(a.s("value").isBlank() || a.s("value").toBigDecimalOrNull()!=null){"有记录的数值格式无效"}
                require(runCatching{LocalDate.parse(a.s("date"))}.isSuccess){"有记录尚未确定发生日期，请补充日期"}
                val evidence=a.s("sourceExcerpt").filterNot{it.isWhitespace()}
                require(evidence.isNotBlank() && source.filterNot{it.isWhitespace()}.contains(evidence)){"有记录缺少图片原文依据，请重新识别"}
            }
        }
    }
}
