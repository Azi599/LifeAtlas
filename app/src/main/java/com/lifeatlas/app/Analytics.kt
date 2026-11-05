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
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

@Composable fun MetricPanel(metric:JSONObject,vm:AtlasModel,revision:Int,onRecord:()->Unit,chartType:String="line") {
    var month by remember{mutableStateOf(YearMonth.now().toString())};var result by remember{mutableStateOf<JSONObject?>(null)};var points by remember{mutableStateOf<List<JSONObject>>(emptyList())};var error by remember{mutableStateOf("")};var request by remember{mutableIntStateOf(0)}
    val config=remember(metric){JSONObject(metric.s("config"))}
    LaunchedEffect(metric.s("id"),revision,request){runCatching{val ym=YearMonth.parse(month);result=vm.summary(metric.s("id"),ym.atDay(1).toString(),ym.atEndOfMonth().toString());points=vm.chart(metric.s("id"),ym.atDay(1).toString(),ym.atEndOfMonth().toString());error=""}.onFailure{error=it.message ?: "日期无效"}}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(metric.s("title"),style=MaterialTheme.typography.titleLarge);Text("${kindName(config.s("kind"))} · ${config.s("unit")}")
        Button(onClick=onRecord){Text("新增记录")}
        if(config.s("kind")=="ALLOCATION")OutlinedButton(onClick={vm.action("time.start",obj("metricId" to metric.s("id")),"开始${metric.s("title")}计时")}){Text("开始计时")}
        OutlinedTextField(month,{month=it},label={Text("分析月份 YYYY-MM")},modifier=Modifier.fillMaxWidth());TextButton(onClick={request++}){Text("查看月份")}
        result?.let{r->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text(methodName(r.s("method")),style=MaterialTheme.typography.labelLarge);Text("${r.s("value","暂无数据")} ${r.s("unit")}",style=MaterialTheme.typography.headlineMedium);Text("${r.optLong("count")} 条有效数值记录",style=MaterialTheme.typography.bodySmall)}}}
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        if(points.isEmpty())Text("该月暂无趋势数据") else Trend(points,config.s("unit"),chartType)
        TextButton(onClick={val ym=runCatching{YearMonth.parse(month)}.getOrNull();if(ym!=null)vm.action("atlas.report",obj("title" to "${metric.s("title")} $month 报告","metrics" to org.json.JSONArray().put(metric.s("id")),"from" to ym.atDay(1).toString(),"to" to ym.atEndOfMonth().toString()),"生成月度报告")}){Text("保存月度报告")}
    }
}
@Composable fun Trend(points:List<JSONObject>,unit:String,type:String="line") {
    val data=remember(points){points.mapNotNull{p->val v=p.s("value").toDoubleOrNull();val d=runCatching{LocalDate.parse(p.s("date")).toEpochDay()}.getOrNull();if(v!=null && v.isFinite() && d!=null) d to v else null}}
    if(data.isEmpty()) return
    val density=LocalDensity.current;val textPx=with(density){11.dp.toPx()};val accent=MaterialTheme.colorScheme.primary
    Column {Text(if(type=="heatmap") "按日分布" else "日趋势 · $unit",style=MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().height(210.dp)) {
            val left=56.dp.toPx();val top=16.dp.toPx();val right=size.width-8.dp.toPx();val bottom=size.height-38.dp.toPx();val width=right-left;val height=bottom-top
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.DKGRAY;textSize=textPx}
            val minDay=data.minOf{it.first};val maxDay=data.maxOf{it.first};val dx=(maxDay-minDay).coerceAtLeast(1).toDouble()
            val min=if(type=="bar") minOf(0.0,data.minOf{it.second}) else data.minOf{it.second};val max=if(type=="bar") maxOf(0.0,data.maxOf{it.second}) else data.maxOf{it.second};val span=(max-min).takeIf{it>0} ?: 1.0
            fun x(day:Long)=left+if(maxDay==minDay) width/2 else ((day-minDay)/dx*width).toFloat()
            fun y(value:Double)=bottom-((value-min)/span*height).toFloat()
            for(i in 0..2){val value=min+span*i/2;val yy=y(value);drawLine(Color(0xffdedbe2),Offset(left,yy),Offset(right,yy),1f);drawContext.canvas.nativeCanvas.drawText("%.3g".format(java.util.Locale.ROOT,value),0f,yy+textPx/3,paint)}
            val labels=listOf(minDay,(minDay+maxDay)/2,maxDay).distinct();labels.forEachIndexed{i,d->val label=LocalDate.ofEpochDay(d).toString().takeLast(5);val xx=when(i){0->left;labels.lastIndex->right-paint.measureText(label);else->x(d)-paint.measureText(label)/2};drawContext.canvas.nativeCanvas.drawText(label,xx,size.height-12.dp.toPx(),paint)}
            if(type=="heatmap") {data.forEachIndexed{i,(d,v)->val col=i%7;val row=i/7;val cw=width/7;val ch=(height/5).coerceAtMost(30.dp.toPx());drawRect(accent.copy(alpha=(0.15+0.85*(v-min)/span).toFloat()),Offset(left+col*cw,top+row*ch),Size(cw-4.dp.toPx(),ch-4.dp.toPx()))}}
            else if(type=="bar")data.forEach{(d,v)->val zero=y(0.0);val yy=y(v);val bar=(width/(data.size*1.8)).toFloat().coerceAtMost(20.dp.toPx());drawRect(accent,Offset(x(d)-bar/2,minOf(zero,yy)),Size(bar,kotlin.math.abs(yy-zero).coerceAtLeast(2f)))}
            else {data.zipWithNext().forEach{(a,b)->drawLine(accent,Offset(x(a.first),y(a.second)),Offset(x(b.first),y(b.second)),2.dp.toPx())};data.forEach{drawCircle(accent,3.dp.toPx(),Offset(x(it.first),y(it.second)))}}
        }
    }
}
@Composable fun HistoryFilter(vm:AtlasModel,state:AtlasState) {
    var metric by remember{mutableStateOf("")};var from by remember{mutableStateOf(YearMonth.now().atDay(1).toString())};var to by remember{mutableStateOf(LocalDate.now().toString())};var offset by remember{mutableIntStateOf(0)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {Pick("指标",listOf("" to "全部指标")+state.objects.filter{it.s("type")=="METRIC"}.map{it.s("id") to it.s("title")},metric){metric=it};OutlinedTextField(from,{from=it},label={Text("开始日期")},modifier=Modifier.fillMaxWidth());OutlinedTextField(to,{to=it},label={Text("结束日期")},modifier=Modifier.fillMaxWidth());Row{Button(onClick={offset=0;vm.history(metric.ifEmpty{null},from,to,offset)}){Text("查询")};TextButton(enabled=state.facts.isNotEmpty(),onClick={offset+=50;vm.history(metric.ifEmpty{null},from,to,offset)}){Text("加载更多")}}}
}
@Composable fun Analytics(vm:AtlasModel,state:AtlasState) {
    var metricId by remember{mutableStateOf("")};val metrics=state.objects.filter{it.s("type")=="METRIC"}
    var cash by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    LaunchedEffect(state.revision){cash=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
        val ym=YearMonth.now();val groups=metrics.filter{JSONObject(it.s("config")).s("role") in setOf("income","expense")}.groupBy{Units.currency(JSONObject(it.s("config")).s("unit"))}
        groups.map{(currency,group)->var income=BigDecimal.ZERO;var expense=BigDecimal.ZERO;group.forEach{m->val c=JSONObject(m.s("config"));val v=vm.store.aggregate(m.s("id"),ym.atDay(1).toString(),ym.atEndOfMonth().toString(),"SUM").s("value").toBigDecimalOrNull() ?: BigDecimal.ZERO;val amount=if(currency=="CNY") Units.convert(v,c.s("unit"),"CNY") else v;if(c.s("role")=="income")income+=amount else expense-=amount};obj("unit" to currency,"income" to income.toPlainString(),"expense" to expense.toPlainString(),"net" to (income-expense).toPlainString())}
    }}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        cash.forEach{c->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("本月已记录现金流 · ${c.s("unit")}",style=MaterialTheme.typography.titleMedium);Text("收入 ${c.s("income")}　支出净额 ${c.s("expense")}");Text("净额 ${c.s("net")}",style=MaterialTheme.typography.headlineSmall)}}}
        Pick("选择指标",metrics.map{it.s("id") to it.s("title")},metricId){metricId=it}
        metrics.firstOrNull{it.s("id")==metricId}?.let{m->MetricPanel(m,vm,state.revision,onRecord={vm.work("录入提示"){obj("message" to "请在空间中打开指标并点击新增记录")}})}
        if(metrics.isEmpty())Text("暂无指标")
    }
}
