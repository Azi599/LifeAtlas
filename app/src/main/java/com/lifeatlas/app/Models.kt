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
fun decimal(value:String):BigDecimal { require(value.length<=100) { "数值过长" };return value.toBigDecimal().also { require(it.precision()<=80 && kotlin.math.abs(it.scale())<=80) { "数值超出精度范围" } } }
val kinds=setOf("SNAPSHOT","CHANGE","ALLOCATION")
val objectTypes=setOf("WORKSPACE","FOLDER","PROJECT","METRIC","DASHBOARD","VIEW","WORKFLOW")
val aggregateMethods=setOf("SUM","MEAN","WEIGHTED_MEAN","LAST_SNAPSHOT","UNION_DURATION","MEDIAN","MIN","MAX","COUNT")
fun validateMetric(config:JSONObject) {
    require(config.toString().length<=256000){"指标定义过大"}
    require(config.s("kind") in kinds) { "请选择记录方式" }
    if(config.s("kind")=="ALLOCATION")require(Units.isTime(config.s("unit"))){"时长单位请选择秒、分钟或小时"}
    when(config.s("role")){"balance"->require(config.s("kind")=="SNAPSHOT");"income","expense","accountChange"->require(config.s("kind")=="CHANGE");"sleep"->require(config.s("kind")=="ALLOCATION")}
    val method=config.s("aggregate",if(config.s("kind")=="SNAPSHOT") "LAST_SNAPSHOT" else "SUM")
    require(method in aggregateMethods) { "不支持的统计规则：$method" }
    require(config.s("unit").length<=30) { "单位过长" }
    val fields=config.optJSONArray("fields") ?: JSONArray()
    require(fields.length()<=40) { "字段超过40个" }
    val ids=HashSet<String>()
    fields.objects().forEach { f -> require(f.s("title",f.s("id")).length<=120){"字段名称过长"};require(f.s("id").matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,39}")) && ids.add(f.s("id"))) { "字段标识无效或重复" };require(f.s("type") in setOf("text","decimal","date","boolean","choice")) { "未知字段类型" };if(f.s("type")=="choice") require((f.optJSONArray("options")?.length() ?: 0) in 1..30) { "选项不能为空" };if(f.s("type")=="choice"){val options=f.getJSONArray("options");for(i in 0 until options.length())require(options.getString(i).length<=200){"选项文字过长"}} }
    if(config.has("expression"))Expressions.validate(config.getJSONObject("expression"),fields.objects().filter{it.s("type")=="decimal"}.map{it.s("id")}.toSet())
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
    fun validate(e:JSONObject,fields:Set<String>,depth:Int=0,budget:IntArray=intArrayOf(256)) {
        require(depth<24 && --budget[0]>=0){"表达式超过执行预算"}
        if(e.has("const")){decimal(e.get("const").toString());return}
        if(e.has("field")){require(e.getString("field") in fields){"表达式引用未知数字字段"};return}
        val args=(e.optJSONArray("args") ?: JSONArray()).objects();when(e.s("op")){"add","multiply","min","max"->require(args.isNotEmpty());"subtract","divide"->require(args.size==2);"ifPositive"->require(args.size==3);else->error("表达式运算无效")}
        args.forEach{validate(it,fields,depth+1,budget)}
    }

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

fun resolvedRecordValue(raw:BigDecimal?,fields:JSONObject,config:JSONObject):BigDecimal? {
    var value=raw;var unitVersion=fields.optInt("_unitVersion",0)
    val rule=(config.optJSONArray("historyRules") ?: JSONArray()).objects().lastOrNull{it.optLong("revision")>fields.optLong("_schemaRevision",1)}
    if(rule!=null) {
        if(rule.s("mode")=="raw")value=raw
        else {val vars=fields.keys().asSequence().mapNotNull{k->fields.s(k).toBigDecimalOrNull()?.let{k to it}}.toMap();value=runCatching{Expressions.eval(rule.getJSONObject("expression"),vars)}.getOrNull();unitVersion=rule.optInt("unitVersion",0)}
    }
    var result=value ?: return null
    (config.optJSONArray("conversions") ?: JSONArray()).objects().filter{it.optInt("version")>unitVersion}.forEach{result*=decimal(it.getString("factor"))}
    if(fields.s("flow")!="transfer"){if(config.s("role")=="expense")result=if(fields.s("flow")=="refund")result.abs()else result.abs().negate();if(config.s("role")=="income")result=result.abs()}
    if(config.s("role") in setOf("income","expense","balance","accountChange"))return runCatching{result.setScale(config.optInt("decimalPlaces",2),java.math.RoundingMode.UNNECESSARY)}.getOrNull()
    return result
}
fun normalizedValue(raw:BigDecimal,fields:JSONObject,config:JSONObject):BigDecimal=resolvedRecordValue(raw,fields,config) ?: error("计算所需字段缺失或无效")
fun retainCalculationHistory(before:JSONObject,next:JSONObject,revision:Long,mode:String,hasRecords:Boolean) {
    val rules=JSONArray((before.optJSONArray("historyRules") ?: JSONArray()).toString())
    if(before.optJSONObject("expression")?.toString()!=next.optJSONObject("expression")?.toString() && hasRecords) {
        require(mode in setOf("recalculate","preserve")){"计算规则已变化，请选择重算历史或保留原计算结果"}
        if(mode=="recalculate")rules.put(obj("revision" to revision,"mode" to if(next.has("expression"))"expression"else "raw","expression" to next.optJSONObject("expression"),"unitVersion" to next.optInt("unitVersion",0)))
    }
    next.put("historyRules",rules)
}

object Units {
    private val definitions=mapOf(
        "kg" to ("mass" to "1000"),"千克" to ("mass" to "1000"),"公斤" to ("mass" to "1000"),"斤" to ("mass" to "500"),"g" to ("mass" to "1"),"克" to ("mass" to "1"),"mg" to ("mass" to "0.001"),
        "L" to ("volume" to "1000"),"升" to ("volume" to "1000"),"ml" to ("volume" to "1"),"毫升" to ("volume" to "1"),
        "h" to ("time" to "3600"),"小时" to ("time" to "3600"),"min" to ("time" to "60"),"分钟" to ("time" to "60"),"d" to ("time" to "86400"),"天" to ("time" to "86400"),"s" to ("time" to "1"),"秒" to ("time" to "1"),
        "CNY" to ("CNY" to "1"),"元" to ("CNY" to "1"),"人民币" to ("CNY" to "1"),"块" to ("CNY" to "1"),"块钱" to ("CNY" to "1"),"分" to ("CNY" to "0.01")
    )
    fun convert(value:BigDecimal,from:String,to:String):BigDecimal {if(from==to) return value;val f=definitions[from] ?: error("未知单位 $from");val t=definitions[to] ?: error("未知单位 $to");require(f.first==t.first){"单位维度不匹配"};val numerator=value.multiply(f.second.toBigDecimal());return try{numerator.divide(t.second.toBigDecimal())}catch(_:ArithmeticException){numerator.divide(t.second.toBigDecimal(),MathContext(24))}}
    fun dimension(unit:String)=definitions[unit]?.first
    fun isTime(unit:String)=definitions[unit]?.first=="time"
    fun currency(unit:String)=definitions[unit]?.first?.takeIf{it=="CNY"} ?: unit
}

fun validateFields(config:JSONObject,fields:JSONObject,allowIncomplete:Boolean=false) {
    val definitions=config.optJSONArray("fields") ?: JSONArray()
    definitions.objects().forEach{f->val value=fields.s(f.s("id"));require(allowIncomplete || !f.optBoolean("required") || value.isNotBlank()){ "请填写 ${f.s("title")}" }
        if(value.isNotEmpty())when(f.s("type")) {
            "decimal"->{val number=decimal(value);if(f.has("minimum"))require(number>=decimal(f.get("minimum").toString())){"${f.s("title")}低于下限"};if(f.has("maximum"))require(number<=decimal(f.get("maximum").toString())){"${f.s("title")}超过上限"}}
            "date"->LocalDate.parse(value)
            "boolean"->require(value in setOf("true","false")){"是/否字段无效"}
            "choice"->require((f.optJSONArray("options") ?: JSONArray()).let{a->(0 until a.length()).any{a.getString(it)==value}}){"选项无效"}
            else->require(value.length<=4000){"文字过长"}
        }
    }
}

fun secondsBetween(start:Instant,end:Instant):BigDecimal {val duration=Duration.between(start,end);return duration.seconds.toBigDecimal()+BigDecimal(duration.nano).movePointLeft(9)}
fun timerIntervals(timer:JSONObject,context:android.content.Context):List<Pair<Instant,Instant>> {
    val periods=JSONArray(timer.s("periods")).objects().map{Instant.parse(it.s("start")) to Instant.parse(it.s("end"))}.toMutableList()
    if(timer.s("running_at").isNotEmpty()) {val start=Instant.parse(timer.s("running_at"));val sameBoot=timer.optInt("boot_count",-1)==android.provider.Settings.Global.getInt(context.contentResolver,"boot_count",0);val end=if(sameBoot && !timer.isNull("elapsed_start"))start.plusMillis((android.os.SystemClock.elapsedRealtime()-timer.getLong("elapsed_start")).coerceAtLeast(0))else maxOf(start,Instant.now());periods.add(start to end)}
    return periods
}
fun timerDaily(timer:JSONObject,config:JSONObject,context:android.content.Context):Map<String,BigDecimal> {
    val zone=ZoneId.of(timer.s("zone"));val result=linkedMapOf<String,BigDecimal>()
    timerIntervals(timer,context).forEach{(start,end)->
        val slices=if(config.s("role")=="sleep") mapOf(Instant.parse(timer.s("created_at")).atZone(zone).toLocalDate() to (start to end)) else MathEngine.splitByDate(start,end,zone).keys.associateWith{d->maxOf(start,d.atStartOfDay(zone).toInstant()) to minOf(end,d.plusDays(1).atStartOfDay(zone).toInstant())}
        slices.forEach{(date,span)->val value=Units.convert(secondsBetween(span.first,span.second),"s",config.s("unit"));result[date.toString()]=(result[date.toString()] ?: BigDecimal.ZERO)+value}
    };return result
}
