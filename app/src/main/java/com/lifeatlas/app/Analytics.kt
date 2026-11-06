package com.lifeatlas.app

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

@Composable fun MetricPanel(metric:JSONObject,vm:AtlasModel,revision:Int,onRecord:(()->Unit)?=null,chartType:String="line",chooseChart:Boolean=false) {
    var month by remember{mutableStateOf(YearMonth.now().toString())};var result by remember{mutableStateOf<JSONObject?>(null)};var points by remember{mutableStateOf<List<JSONObject>>(emptyList())};var error by remember{mutableStateOf("")};var request by remember{mutableIntStateOf(0)}
    var chosenChart by rememberSaveable { mutableStateOf(chartType) }
    val type = if (chooseChart) chosenChart else chartType
    val config=remember(metric){JSONObject(metric.s("config"))}
    val currentState by vm.ui.collectAsState()
    val live=currentState.timers.any{it.s("metric_id")==metric.s("id")}
    LaunchedEffect(metric.s("id"),revision,request,live){
        do {
            try{val ym=YearMonth.parse(month);result=vm.summary(metric.s("id"),ym.atDay(1).toString(),ym.atEndOfMonth().toString());points=vm.chart(metric.s("id"),ym.atDay(1).toString(),ym.atEndOfMonth().toString());error=""}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message ?: "日期无效";break}
            if(live)kotlinx.coroutines.delay(1000)
        }while(live)
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(metric.s("title"),style=MaterialTheme.typography.titleLarge);if(config.s("unit").isNotBlank())Text(config.s("unit"))
        if (onRecord != null) {
            Button(onClick=onRecord){Text("新增记录")}
            if(config.s("kind")=="ALLOCATION")OutlinedButton(onClick={vm.action("time.start",obj("metricId" to metric.s("id")),"开始${metric.s("title")}计时")}){Text("开始计时")}
        }
        OutlinedTextField(month,{month=it},label={Text("分析月份 YYYY-MM")},modifier=Modifier.fillMaxWidth());TextButton(onClick={request++}){Text("查看月份")}
        result?.let{r->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text(methodName(r.s("method")),style=MaterialTheme.typography.labelLarge);Text("${r.s("value","暂无数据")} ${r.s("unit")}",style=MaterialTheme.typography.headlineMedium);Text(if(r.s("method")=="UNION_DURATION") "${r.optInt("intervalCount")} 段区间" else "${r.optLong("usedCount",r.optLong("count"))} 条有效数值记录",style=MaterialTheme.typography.bodySmall);if(r.optBoolean("live"))Text("计时中",style=MaterialTheme.typography.labelMedium)}}}
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        if (chooseChart) Pick("图表",listOf("bar" to "柱状图","line" to "折线图","heatmap" to "热力图"),chosenChart){chosenChart=it}
        if(points.isEmpty())Text("该月暂无趋势数据") else Trend(points,config.s("unit"),type)
        TextButton(onClick={val ym=runCatching{YearMonth.parse(month)}.getOrNull();if(ym!=null)vm.action("atlas.report",obj("title" to "${metric.s("title")} $month 报告","metrics" to org.json.JSONArray().put(metric.s("id")),"from" to ym.atDay(1).toString(),"to" to ym.atEndOfMonth().toString()),"生成月度报告")}){Text("保存月度报告")}
    }
}
@Composable fun Trend(points:List<JSONObject>,unit:String,type:String="line") {
    val data=remember(points){points.mapNotNull{p->val v=p.s("value").toDoubleOrNull();val d=runCatching{LocalDate.parse(p.s("date")).toEpochDay()}.getOrNull();if(v!=null && v.isFinite() && d!=null) d to v else null}}
    if(data.isEmpty()) return
    val scale=remember(data,type){chartScale(data.map{it.second},type=="bar")}
    val density=LocalDensity.current;val textPx=with(density){11.sp.toPx()};val accent=MaterialTheme.colorScheme.primary;val labelColor=readableColor(MaterialTheme.colorScheme.onSurfaceVariant,LocalAtlasBackground.current).toArgb();val grid=LocalAtlasTokens.current.chartGrid
    Column {Text(listOf(when(type){"bar"->"每日柱状图";"heatmap"->"按日分布";else->"日趋势"},unit).filter{it.isNotBlank()}.joinToString(" · "),style=MaterialTheme.typography.titleMedium)
        if(scale.offset!=0.0)Text("纵轴基准 "+java.math.BigDecimal.valueOf(scale.offset).stripTrailingZeros().toPlainString(),style=MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(210.dp).testTag("trend-chart")) {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=labelColor;textSize=textPx}
            val left=maxOf(36.dp.toPx(),scale.ticks.maxOf{paint.measureText(it)}+10.dp.toPx()).coerceAtMost(size.width*.5f);val top=16.dp.toPx();val right=size.width-12.dp.toPx();val bottom=size.height-38.dp.toPx();val width=right-left;val height=bottom-top
            val minDay=data.minOf{it.first};val maxDay=data.maxOf{it.first};val dx=(maxDay-minDay).coerceAtLeast(1).toDouble()
            val min=scale.minimum;val max=scale.maximum;val span=max-min
            fun x(day:Long)=left+when {
                type=="bar" -> ((day-minDay+0.5)/(dx+1)*width).toFloat().let{if(maxDay==minDay) width/2 else it}
                maxDay==minDay -> width/2
                else -> ((day-minDay)/dx*width).toFloat()
            }
            fun y(value:Double)=bottom-((value-min)/span*height).toFloat()
            if(type!="heatmap")for(i in 0..2){val value=min+span*i/2;val yy=y(value);drawLine(grid,Offset(left,yy),Offset(right,yy),1f);drawContext.canvas.nativeCanvas.drawText(scale.ticks[i],0f,yy+textPx/3,paint)}
            val labels=if(type=="heatmap")emptyList() else listOf(minDay,(minDay+maxDay)/2,maxDay).distinct();labels.forEachIndexed{i,d->val label=LocalDate.ofEpochDay(d).toString().takeLast(5);val xx=when{labels.size==1->x(d)-paint.measureText(label)/2;i==0->left;i==labels.lastIndex->right-paint.measureText(label);else->x(d)-paint.measureText(label)/2};drawContext.canvas.nativeCanvas.drawText(label,xx,size.height-12.dp.toPx(),paint)}
            if(type=="heatmap") {
                val monday=LocalDate.ofEpochDay(minDay).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toEpochDay()
                val weeks=((maxDay-monday)/7+1).toInt();val cw=width/weeks.coerceAtLeast(1);val ch=height/7
                listOf("一","二","三","四","五","六","日").forEachIndexed{i,label->drawContext.canvas.nativeCanvas.drawText(label,0f,top+(i+0.7f)*ch,paint)}
                data.forEach{(d,v)->val day=(d-monday).toInt();drawRect(accent.copy(alpha=(0.15+0.85*(v-min)/span).toFloat()),Offset(left+day/7*cw,top+day%7*ch),Size((cw-3.dp.toPx()).coerceAtLeast(1f),(ch-3.dp.toPx()).coerceAtLeast(1f)))}
                val label=LocalDate.ofEpochDay(minDay).toString()+" — "+LocalDate.ofEpochDay(maxDay)
                drawContext.canvas.nativeCanvas.drawText(label,left,size.height-10.dp.toPx(),paint)
            }
            else if(type=="bar")data.filter{it.second!=0.0}.forEach{(d,v)->val zero=y(0.0);val yy=y(v);val bar=(width/((dx+1)*1.5)).toFloat().coerceAtMost(20.dp.toPx()).coerceAtLeast(0.5f);drawRect(accent,Offset(x(d)-bar/2,minOf(zero,yy)),Size(bar,kotlin.math.abs(yy-zero).coerceAtLeast(2f)))}
            else {data.zipWithNext().forEach{(a,b)->drawLine(accent,Offset(x(a.first),y(a.second)),Offset(x(b.first),y(b.second)),2.dp.toPx())};data.forEach{drawCircle(accent,3.dp.toPx(),Offset(x(it.first),y(it.second)))}}
        }
    }
}
private data class ChartSlice(val name:String,val value:BigDecimal)
private data class MonthlyOverview(val counts:List<JSONObject>,val cash:List<JSONObject>,val spending:List<ChartSlice>)

private fun monthlyOverview(store: AtlasStore, month: YearMonth): MonthlyOverview {
    val from = month.atDay(1).toString()
    val to = month.atEndOfMonth().toString()
    val counts = store.query("""
        SELECT f.metric_id id, o.title, COUNT(*) count
        FROM atlas_facts f JOIN atlas_objects o ON o.id = f.metric_id
        WHERE f.deleted = 0 AND f.local_date BETWEEN ? AND ? AND o.archived = 0
        GROUP BY f.metric_id
        ORDER BY count DESC, o.title, f.metric_id
    """.trimIndent(),arrayOf(from,to))
    val finance = store.metrics().map { it to JSONObject(it.s("config")) }
        .filter { (_, config) -> config.s("role") in setOf("income","expense") }
        .groupBy { (_, config) -> Units.currency(config.s("unit")) }
    val spending = mutableListOf<ChartSlice>()
    val cash = finance.mapNotNull { (currency,group) ->
        var income = BigDecimal.ZERO
        var expense = BigDecimal.ZERO
        var missing = 0L
        var hasRecords = false
        group.forEach { (metric,config) ->
            val stats = store.aggregate(metric.s("id"),from,to,"SUM")
            missing += stats.optLong("missingCount")
            hasRecords = hasRecords || stats.optLong("recordCount") > 0
            val raw = stats.s("value").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val amount = if (currency=="CNY") Units.convert(raw,config.s("unit"),"CNY") else raw
            if (config.s("role")=="income") income += amount else {
                expense -= amount
                if (currency=="CNY" && amount.signum()<0) spending.add(ChartSlice(metric.s("title"),amount.negate()))
            }
        }
        if (!hasRecords) null else obj("unit" to if(currency=="CNY") "元" else currency,
            "income" to income.toPlainString(),"expense" to expense.toPlainString(),
            "net" to if(missing>0) null else (income-expense).toPlainString())
    }
    return MonthlyOverview(counts,cash,spending.sortedByDescending { it.value })
}

@Composable
private fun CompositionPie(title: String, entries: List<ChartSlice>, unit: String) {
    if (entries.isEmpty()) return
    val slices = remember(entries) {
        if (entries.size <= 6) entries
        else entries.take(5) + ChartSlice("其他",MathEngine.sum(entries.drop(5).map{it.value}))
    }
    val total = MathEngine.sum(slices.map{it.value})
    val colors = MaterialTheme.colorScheme.run {
        listOf(primary,tertiary,secondary,lerp(primary,tertiary,.55f),outline,error)
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium)
        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
            Canvas(Modifier.size(184.dp).testTag("composition-pie")) {
                var start = -90f
                slices.forEachIndexed { index,slice ->
                    val sweep = if(index==slices.lastIndex) 270f-start else
                        slice.value.divide(total,MathContext.DECIMAL64).multiply(BigDecimal(360)).toFloat()
                    drawArc(colors[index],start,sweep.coerceAtLeast(0f),useCenter=true)
                    start += sweep
                }
            }
        }
        slices.forEachIndexed { index,slice ->
            val percent = slice.value.multiply(BigDecimal(100)).divide(total,1,RoundingMode.HALF_UP).stripTrailingZeros()
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(colors[index]))
                Text(slice.name,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Text("${slice.value.stripTrailingZeros().toPlainString()} $unit · ${percent.toPlainString()}%",style=MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable fun Analytics(vm:AtlasModel,state:AtlasState) {
    var metricId by rememberSaveable { mutableStateOf("") }
    var distribution by rememberSaveable { mutableStateOf("records") }
    val metrics = state.objects.filter { it.s("type")=="METRIC" }
    val month = YearMonth.now()
    var overview by remember { mutableStateOf<MonthlyOverview?>(null) }
    var failure by remember { mutableStateOf("") }
    LaunchedEffect(state.revision,month) {
        try {
            overview = withContext(Dispatchers.IO) { monthlyOverview(vm.store,month) }
            failure = ""
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "无法读取统计" }
    }
    val snapshot = overview
    val active = metrics.firstOrNull { it.s("id")==metricId }
        ?: snapshot?.counts?.firstNotNullOfOrNull { row -> metrics.firstOrNull { it.s("id")==row.s("id") } }
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        if (failure.isNotBlank()) Text(failure,color=MaterialTheme.colorScheme.error)
        snapshot?.cash?.forEach { c ->
            Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text("$month 现金流 · ${c.s("unit")}",style=MaterialTheme.typography.titleMedium)
                Text("收入 ${c.s("income")}　支出净额 ${c.s("expense")}")
                Text("净额 ${c.s("net","待补充")}",style=MaterialTheme.typography.headlineSmall)
            }}
        }
        if (snapshot != null && snapshot.counts.isNotEmpty()) {
            val showSpending = distribution=="spending" && snapshot.spending.isNotEmpty()
            if (snapshot.spending.isNotEmpty()) {
                Pick("构成",listOf("records" to "记录条数","spending" to "支出净额"),if(showSpending) "spending" else "records"){distribution=it}
            }
            if (showSpending) CompositionPie("$month 正净支出构成",snapshot.spending,"元")
            else CompositionPie("$month 记录构成",snapshot.counts.map{ChartSlice(it.s("title"),it.optLong("count").toBigDecimal())},"条")
        }
        if (metrics.isNotEmpty()) {
            Pick("选择指标",metrics.map{it.s("id") to it.s("title")},active?.s("id").orEmpty()){metricId=it}
            active?.let { metric ->
                key(metric.s("id")) { MetricPanel(metric,vm,state.revision,chartType="bar",chooseChart=true) }
            }
        } else Text("暂无指标")
    }
}
