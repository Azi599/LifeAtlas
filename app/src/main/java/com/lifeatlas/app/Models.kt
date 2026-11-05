package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.time.*
import java.util.UUID

fun uid() = UUID.randomUUID().toString()
fun now() = Instant.now().toString()
fun obj(vararg pairs:Pair<String,Any?>) = JSONObject().apply { pairs.forEach { put(it.first,it.second ?: JSONObject.NULL) } }
fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
fun JSONObject.s(key:String, fallback:String="") = if(isNull(key)) fallback else optString(key,fallback)
fun JSONObject.copyJson() = JSONObject(toString())
fun decimal(value:String):BigDecimal { require(value.length<=100) { "数值过长" };return value.toBigDecimal().also { require(it.precision()<=30 && kotlin.math.abs(it.scale())<=12) { "数值超出精度范围" } } }
val kinds=setOf("SNAPSHOT","CHANGE","ALLOCATION")
val objectTypes=setOf("WORKSPACE","FOLDER","PROJECT","METRIC","DASHBOARD","VIEW","WORKFLOW")
val aggregateMethods=setOf("SUM","MEAN","WEIGHTED_MEAN","LAST_SNAPSHOT","UNION_DURATION","MEDIAN","MIN","MAX","COUNT")
fun validateMetric(config:JSONObject) {
    require(config.s("kind") in kinds) { "请选择数据语义" }
    val method=config.s("aggregate",if(config.s("kind")=="SNAPSHOT") "LAST_SNAPSHOT" else "SUM")
    require(method in aggregateMethods) { "不支持的统计规则：$method" }
    require(config.s("unit").length<=30) { "单位过长" }
    val fields=config.optJSONArray("fields") ?: JSONArray()
    require(fields.length()<=40) { "字段超过40个" }
    val ids=HashSet<String>()
    fields.objects().forEach { f -> require(f.s("id").matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,39}")) && ids.add(f.s("id"))) { "字段标识无效或重复" };require(f.s("type") in setOf("text","decimal","date","boolean","choice")) { "未知字段类型" };if(f.s("type")=="choice") require((f.optJSONArray("options")?.length() ?: 0) in 1..30) { "选项不能为空" } }
}

object MathEngine {
    private val mc=MathContext.DECIMAL128
    fun sum(values:List<BigDecimal>)=values.fold(BigDecimal.ZERO,BigDecimal::add)
    fun mean(values:List<BigDecimal>):BigDecimal?=if(values.isEmpty()) null else sum(values).divide(values.size.toBigDecimal(),mc)
    fun weighted(values:List<Pair<BigDecimal,BigDecimal>>):BigDecimal? {
        require(values.all { it.second>=BigDecimal.ZERO }) { "权重不能为负" }
        val w=sum(values.map {it.second});return if(w.signum()==0) null else sum(values.map {it.first*it.second}).divide(w,mc)
    }
    fun median(values:List<BigDecimal>):BigDecimal? { if(values.isEmpty()) return null;val sorted=values.sorted();val n=sorted.size;return if(n%2==1) sorted[n/2] else (sorted[n/2-1]+sorted[n/2]).divide(BigDecimal(2),mc) }
    fun union(intervals:List<Pair<Long,Long>>):Long {
        require(intervals.all {it.second>=it.first}) { "结束时间早于开始时间" }
        var total=0L;var start=0L;var end=0L;var seen=false
        intervals.sortedBy{it.first}.forEach { (a,b)-> if(!seen){start=a;end=b;seen=true} else if(a<=end) end=maxOf(end,b) else {total+=end-start;start=a;end=b} };return total+if(seen) end-start else 0L
    }
    fun pearson(pairs:List<Pair<Double,Double>>):Double? { if(pairs.size<3) return null;val x=pairs.map{it.first};val y=pairs.map{it.second};val mx=x.average();val my=y.average();val xx=x.sumOf{(it-mx)*(it-mx)};val yy=y.sumOf{(it-my)*(it-my)};if(xx==0.0 || yy==0.0) return null;return pairs.sumOf{(it.first-mx)*(it.second-my)}/kotlin.math.sqrt(xx*yy) }
    fun cashflow(income:BigDecimal,expenseMagnitude:BigDecimal)=income-expenseMagnitude.abs()
    fun snapshotBalance(anchor:BigDecimal,changesAfterAnchor:List<BigDecimal>)=anchor+sum(changesAfterAnchor)
    fun splitByDate(start:Instant,end:Instant,zone:ZoneId):Map<LocalDate,Long> {
        require(end>=start);val result=linkedMapOf<LocalDate,Long>();var t=start
        while(t<end) { val d=t.atZone(zone).toLocalDate();val boundary=d.plusDays(1).atStartOfDay(zone).toInstant();val next=minOf(boundary,end);result[d]=(result[d] ?: 0)+Duration.between(t,next).seconds;t=next };return result
    }
}

/** Bounded expression AST: no source evaluation or filesystem/network access. */
object Expressions {
    fun eval(e:JSONObject,vars:Map<String,BigDecimal>,depth:Int=0,budget:IntArray=intArrayOf(256)):BigDecimal {
        require(depth<24 && --budget[0]>=0) { "表达式超过执行预算" }
        if(e.has("const")) return decimal(e.get("const").toString())
        if(e.has("field")) return vars[e.getString("field")] ?: error("缺少字段 ${e.getString("field")}")
        val args=(e.optJSONArray("args") ?: JSONArray()).objects().map {eval(it,vars,depth+1,budget)}
        fun two() {require(args.size==2) { "该运算需要两个参数" }}
        return when(e.s("op")) {
            "add"->MathEngine.sum(args);"multiply"->args.fold(BigDecimal.ONE,BigDecimal::multiply)
            "subtract"->{two();args[0]-args[1]};"divide"->{two();args[0].divide(args[1],MathContext.DECIMAL128)}
            "min"->args.minOrNull() ?: error("缺少参数");"max"->args.maxOrNull() ?: error("缺少参数")
            "ifPositive"->{require(args.size==3);if(args[0]>BigDecimal.ZERO) args[1] else args[2]}
            else->error("表达式运算无效")
        }
    }
}
