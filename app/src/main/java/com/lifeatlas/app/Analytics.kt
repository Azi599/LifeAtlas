package com.lifeatlas.app

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

@Composable fun MetricPanel(metric:JSONObject,vm:AtlasModel,revision:Int,onRecord:()->Unit,chartType:String="line") {
    var month by remember{mutableStateOf(YearMonth.now().toString())};var result by remember{mutableStateOf<JSONObject?>(null)};var points by remember{mutableStateOf<List<JSONObject>>(emptyList())};var error by remember{mutableStateOf("")};var request by remember{mutableIntStateOf(0)}
    val config=remember(metric){JSONObject(metric.s("config"))}
    val live=vm.ui.value.timers.any{it.s("metric_id")==metric.s("id")}
    LaunchedEffect(metric.s("id"),revision,request,live){
        do {
            try{val ym=YearMonth.parse(month);result=vm.summary(metric.s("id"),ym.atDay(1).toString(),ym.atEndOfMonth().toString());points=vm.chart(metric.s("id"),ym.atDay(1).toString(),ym.atEndOfMonth().toString());error=""}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message ?: "日期无效";break}
            if(live)kotlinx.coroutines.delay(1000)
        }while(live)
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(metric.s("title"),style=MaterialTheme.typography.titleLarge);Text("${kindName(config.s("kind"))} · ${config.s("unit")}")
        Button(onClick=onRecord){Text("新增记录")}
        if(config.s("kind")=="ALLOCATION")OutlinedButton(onClick={vm.action("time.start",obj("metricId" to metric.s("id")),"开始${metric.s("title")}计时")}){Text("开始计时")}
        OutlinedTextField(month,{month=it},label={Text("分析月份 YYYY-MM")},modifier=Modifier.fillMaxWidth());TextButton(onClick={request++}){Text("查看月份")}
        result?.let{r->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text(methodName(r.s("method")),style=MaterialTheme.typography.labelLarge);Text("${r.s("value","暂无数据")} ${r.s("unit")}",style=MaterialTheme.typography.headlineMedium);Text(if(r.s("method")=="UNION_DURATION") "${r.optInt("intervalCount")} 段区间" else "${r.optLong("usedCount",r.optLong("count"))} 条有效数值记录",style=MaterialTheme.typography.bodySmall);if(r.optBoolean("live"))Text("计时中",style=MaterialTheme.typography.labelMedium)}}}
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        if(points.isEmpty())Text("该月暂无趋势数据") else Trend(points,config.s("unit"),chartType)
        TextButton(onClick={val ym=runCatching{YearMonth.parse(month)}.getOrNull();if(ym!=null)vm.action("atlas.report",obj("title" to "${metric.s("title")} $month 报告","metrics" to org.json.JSONArray().put(metric.s("id")),"from" to ym.atDay(1).toString(),"to" to ym.atEndOfMonth().toString()),"生成月度报告")}){Text("保存月度报告")}
    }
}
@Composable fun Trend(points:List<JSONObject>,unit:String,type:String="line") {
    val data=remember(points){points.mapNotNull{p->val v=p.s("value").toDoubleOrNull();val d=runCatching{LocalDate.parse(p.s("date")).toEpochDay()}.getOrNull();if(v!=null && v.isFinite() && d!=null) d to v else null}}
    if(data.isEmpty()) return
    val density=LocalDensity.current;val textPx=with(density){11.sp.toPx()};val accent=MaterialTheme.colorScheme.primary;val labelColor=readableColor(MaterialTheme.colorScheme.onSurfaceVariant,LocalAtlasBackground.current).toArgb();val grid=LocalAtlasTokens.current.chartGrid
    Column {Text(if(type=="heatmap") "按日分布" else "日趋势 · $unit",style=MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().height(210.dp)) {
            val left=56.dp.toPx();val top=16.dp.toPx();val right=size.width-8.dp.toPx();val bottom=size.height-38.dp.toPx();val width=right-left;val height=bottom-top
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=labelColor;textSize=textPx}
            val minDay=data.minOf{it.first};val maxDay=data.maxOf{it.first};val dx=(maxDay-minDay).coerceAtLeast(1).toDouble()
            val min=if(type=="bar") minOf(0.0,data.minOf{it.second}) else data.minOf{it.second};val max=if(type=="bar") maxOf(0.0,data.maxOf{it.second}) else data.maxOf{it.second};val span=(max-min).takeIf{it>0} ?: 1.0
            fun x(day:Long)=left+if(maxDay==minDay) width/2 else ((day-minDay)/dx*width).toFloat()
            fun y(value:Double)=bottom-((value-min)/span*height).toFloat()
            if(type!="heatmap")for(i in 0..2){val value=min+span*i/2;val yy=y(value);drawLine(grid,Offset(left,yy),Offset(right,yy),1f);drawContext.canvas.nativeCanvas.drawText("%.3g".format(java.util.Locale.ROOT,value),0f,yy+textPx/3,paint)}
            val labels=if(type=="heatmap")emptyList() else listOf(minDay,(minDay+maxDay)/2,maxDay).distinct();labels.forEachIndexed{i,d->val label=LocalDate.ofEpochDay(d).toString().takeLast(5);val xx=when(i){0->left;labels.lastIndex->right-paint.measureText(label);else->x(d)-paint.measureText(label)/2};drawContext.canvas.nativeCanvas.drawText(label,xx,size.height-12.dp.toPx(),paint)}
            if(type=="heatmap") {
                val monday=LocalDate.ofEpochDay(minDay).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toEpochDay()
                val weeks=((maxDay-monday)/7+1).toInt();val cw=width/weeks.coerceAtLeast(1);val ch=height/7
                listOf("一","二","三","四","五","六","日").forEachIndexed{i,label->drawContext.canvas.nativeCanvas.drawText(label,0f,top+(i+0.7f)*ch,paint)}
                data.forEach{(d,v)->val day=(d-monday).toInt();drawRect(accent.copy(alpha=(0.15+0.85*(v-min)/span).toFloat()),Offset(left+day/7*cw,top+day%7*ch),Size((cw-3.dp.toPx()).coerceAtLeast(1f),(ch-3.dp.toPx()).coerceAtLeast(1f)))}
                val label=LocalDate.ofEpochDay(minDay).toString()+" — "+LocalDate.ofEpochDay(maxDay)
                drawContext.canvas.nativeCanvas.drawText(label,left,size.height-10.dp.toPx(),paint)
            }
            else if(type=="bar")data.forEach{(d,v)->val zero=y(0.0);val yy=y(v);val bar=(width/(data.size*1.8)).toFloat().coerceAtMost(20.dp.toPx());drawRect(accent,Offset(x(d)-bar/2,minOf(zero,yy)),Size(bar,kotlin.math.abs(yy-zero).coerceAtLeast(2f)))}
            else {data.zipWithNext().forEach{(a,b)->drawLine(accent,Offset(x(a.first),y(a.second)),Offset(x(b.first),y(b.second)),2.dp.toPx())};data.forEach{drawCircle(accent,3.dp.toPx(),Offset(x(it.first),y(it.second)))}}
        }
    }
}
@Composable fun HistoryFilter(vm:AtlasModel,state:AtlasState) {
    var metric by remember{mutableStateOf(state.historyMetric)};var from by remember{mutableStateOf(state.historyFrom)};var to by remember{mutableStateOf(state.historyTo)};var offset by remember{mutableIntStateOf(state.historyOffset)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {Pick("指标",listOf("" to "全部指标")+state.objects.filter{it.s("type")=="METRIC"}.map{it.s("id") to it.s("title")},metric){metric=it};OutlinedTextField(from,{from=it},label={Text("开始日期")},modifier=Modifier.fillMaxWidth());OutlinedTextField(to,{to=it},label={Text("结束日期")},modifier=Modifier.fillMaxWidth());Row{Button(onClick={offset=0;vm.history(metric.ifEmpty{null},from,to,offset)}){Text("查询")};TextButton(enabled=offset>0,onClick={offset=(offset-50).coerceAtLeast(0);vm.history(metric.ifEmpty{null},from,to,offset)}){Text("上一页")};TextButton(enabled=state.facts.size==50,onClick={offset+=50;vm.history(metric.ifEmpty{null},from,to,offset)}){Text("下一页")}}}
}
@Composable fun Analytics(vm:AtlasModel,state:AtlasState) {
    var metricId by remember{mutableStateOf("")};val metrics=state.objects.filter{it.s("type")=="METRIC"}
    var cash by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    LaunchedEffect(state.revision){cash=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
        val ym=YearMonth.now();val groups=metrics.filter{JSONObject(it.s("config")).s("role") in setOf("income","expense")}.groupBy{Units.currency(JSONObject(it.s("config")).s("unit"))}
        groups.map{(currency,group)->var income=BigDecimal.ZERO;var expense=BigDecimal.ZERO;var missing=0L;group.forEach{m->val c=JSONObject(m.s("config"));val stats=vm.store.aggregate(m.s("id"),ym.atDay(1).toString(),ym.atEndOfMonth().toString(),"SUM");missing+=stats.optLong("missingCount");val v=stats.s("value").toBigDecimalOrNull() ?: BigDecimal.ZERO;val amount=if(currency=="CNY") Units.convert(v,c.s("unit"),"CNY") else v;if(c.s("role")=="income")income+=amount else expense-=amount};obj("unit" to currency,"income" to income.toPlainString(),"expense" to expense.toPlainString(),"net" to if(missing>0)null else (income-expense).toPlainString(),"missingCount" to missing)}
    }}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        cash.forEach{c->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("本月已记录现金流 · ${c.s("unit")}",style=MaterialTheme.typography.titleMedium);Text("收入 ${c.s("income")}　支出净额 ${c.s("expense")}");Text("净额 ${c.s("net","待补充")}",style=MaterialTheme.typography.headlineSmall)}}}
        Pick("选择指标",metrics.map{it.s("id") to it.s("title")},metricId){metricId=it}
        metrics.firstOrNull{it.s("id")==metricId}?.let{m->MetricPanel(m,vm,state.revision,onRecord={vm.work("录入提示"){obj("message" to "请在空间中打开指标并点击新增记录")}})}
        if(metrics.isEmpty())Text("暂无指标")
    }
}
