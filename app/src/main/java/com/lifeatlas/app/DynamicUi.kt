package com.lifeatlas.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

fun componentRange(component:JSONObject):Pair<String,String> {val month=YearMonth.now();return component.s("from").ifBlank{month.atDay(1).toString()} to component.s("to").ifBlank{month.atEndOfMonth().toString()}}
@Composable fun DynamicScreen(config:JSONObject,state:AtlasState,vm:AtlasModel,onRecord:(JSONObject)->Unit,onOpen:(String)->Unit,onFact:(JSONObject)->Unit={}) {
    Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {
        (config.optJSONArray("components") ?: JSONArray()).objects().forEach{component->
            val metric=resolvedObject(component.s("metricId"),state,vm)
            when(component.s("component")) {
                "text"->Text(component.s("text"))
                "navigation"->OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={onOpen(component.s("objectId"))}){Text(component.s("title").ifBlank{"打开"})}
                "form"->if(metric!=null)Button(onClick={onRecord(metric)}){Text(component.s("title").ifBlank{"记录"+metric.s("title")})}
                "summary"->if(metric!=null)SummaryComponent(component,metric,state,vm)
                "chart"->if(metric!=null)ChartComponent(component,metric,state,vm)
                "history"->if(metric!=null)HistoryComponent(component,metric,state,vm,onFact)
            }
        }
    }
}
@Composable private fun SummaryComponent(component:JSONObject,metric:JSONObject,state:AtlasState,vm:AtlasModel) {
    var result by remember{mutableStateOf<JSONObject?>(null)};var error by remember{mutableStateOf("")};val running=state.timers.any{it.s("metric_id")==metric.s("id") && it.s("state")=="RUNNING"}
    LaunchedEffect(component.toString(),metric.s("id"),state.revision,running){do {try{val range=componentRange(component);result=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){vm.store.aggregate(metric.s("id"),range.first,range.second,component.s("method",vm.store.config(metric.s("id")).s("aggregate","SUM")))};error=""}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message.orEmpty();break};if(running)kotlinx.coroutines.delay(1000)}while(running)}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(component.s("title").ifBlank{metric.s("title")},style=MaterialTheme.typography.titleMedium);result?.let{r->Text(r.s("value","暂无数据")+" "+r.s("unit"),style=MaterialTheme.typography.headlineMedium);Text(methodName(r.s("method"))+" · "+r.optLong("usedCount",r.optLong("count"))+" 条",style=MaterialTheme.typography.bodySmall)};if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)}
}
@Composable private fun ChartComponent(component:JSONObject,metric:JSONObject,state:AtlasState,vm:AtlasModel) {
    var points by remember{mutableStateOf<List<JSONObject>>(emptyList())};var loading by remember{mutableStateOf(true)};var error by remember{mutableStateOf("")};val running=state.timers.any{it.s("metric_id")==metric.s("id") && it.s("state")=="RUNNING"}
    LaunchedEffect(component.toString(),metric.s("id"),state.revision,running){do {try{val range=componentRange(component);points=vm.chart(metric.s("id"),range.first,range.second);error=""}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message.orEmpty();break}finally{loading=false};if(running)kotlinx.coroutines.delay(1000)}while(running)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(component.s("title").ifBlank{metric.s("title")},style=MaterialTheme.typography.titleMedium);if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())else if(points.isEmpty())Text("该范围暂无数据")else Trend(points,JSONObject(metric.s("config")).s("unit"),component.s("chart","line"));if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)}
}
@Composable private fun HistoryComponent(component:JSONObject,metric:JSONObject,state:AtlasState,vm:AtlasModel,onFact:(JSONObject)->Unit) {
    var facts by remember{mutableStateOf<List<JSONObject>>(emptyList())};var offset by remember(component.toString()){mutableIntStateOf(0)};var error by remember{mutableStateOf("")}
    LaunchedEffect(component.toString(),state.revision,metric.s("id"),offset){try{val range=componentRange(component);facts=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){vm.store.facts(metric.s("id"),range.first,range.second,offset,30)};error=""}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message.orEmpty()}}
    val metricConfig=JSONObject(metric.s("config"))
    val definitions=metricConfig.optJSONArray("fields")?.objects().orEmpty()
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(component.s("title").ifBlank{metric.s("title")},style=MaterialTheme.typography.titleMedium)
        facts.forEach { fact ->
            Column(Modifier.fillMaxWidth().clickable { onFact(fact) }.padding(vertical=10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(fact.s("local_date"),Modifier.weight(1f))
                    Text(fact.s("value","未填写")+" "+metricConfig.s("unit"))
                }
                val fields=JSONObject(fact.s("fields_json"))
                definitions.forEach { field ->
                    val value=fields.s(field.s("id"))
                    if(value.isNotBlank()) {
                        val display=if(field.s("type")=="boolean") if(value=="true") "是" else "否" else value
                        Text(field.s("title")+"："+display,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
        }
        if(facts.isEmpty())Text("暂无记录")
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        ActionRow {
            TextButton(enabled=offset>0,onClick={offset=(offset-30).coerceAtLeast(0)}){Text("上一页")}
            TextButton(enabled=facts.size==30,onClick={offset+=30}){Text("下一页")}
        }
    }
}
@Composable fun ReportPanel(config:JSONObject,state:AtlasState,vm:AtlasModel) {
    var reports by remember{mutableStateOf<List<JSONObject>>(emptyList())};var error by remember{mutableStateOf("")}
    LaunchedEffect(state.revision,config.toString()) {runCatching{val ids=config.optJSONArray("metrics");reports=if(ids!=null)(0 until ids.length()).map{vm.summary(ids.getString(it),config.s("from"),config.s("to"))} else (config.optJSONArray("results") ?: JSONArray()).objects()}.onFailure{error=it.message ?: "读取失败"}}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {if(error.isNotEmpty())Text(error);reports.forEach{r->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text(state.objects.firstOrNull{it.s("id")==r.s("metricId")}?.s("title") ?: "指标");Text("${r.s("value","暂无数据")} ${r.s("unit")}",style=MaterialTheme.typography.headlineSmall);Text("${methodName(r.s("method"))} · ${r.optLong("count")} 条");Text("${r.s("from")} — ${r.s("to")}",style=MaterialTheme.typography.bodySmall)}}}}
}

@Composable fun FeaturePreview(manifest:JSONObject) {
    val metrics=(manifest.optJSONArray("metrics") ?: JSONArray()).objects();var key by remember(manifest.toString()){mutableStateOf(metrics.firstOrNull()?.s("key") ?: "")};var value by remember{mutableStateOf("")};var fields by remember{mutableStateOf<Map<String,String>>(emptyMap())}
    val metric=metrics.firstOrNull{it.s("key")==key}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Pick("表单预览",metrics.map{it.s("key") to it.s("title")},key){key=it;fields=emptyMap();value=""}
        if(metric!=null){OutlinedTextField(value,{value=it},label={Text("数值 · ${metric.s("unit")}")},modifier=Modifier.fillMaxWidth());(metric.optJSONArray("fields") ?: JSONArray()).objects().forEach{f->DynamicField(f,fields[f.s("id")] ?: ""){fields=fields+(f.s("id") to it)}}}
    }
}

@Composable fun resolvedObject(id:String,state:AtlasState,vm:AtlasModel):JSONObject? {
    val known=state.objects.firstOrNull{it.s("id")==id}
    var loaded by remember(id,state.revision){mutableStateOf<JSONObject?>(known)}
    LaunchedEffect(id,state.revision){if(known==null && id.isNotEmpty())loaded=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){vm.store.one("atlas_objects",id)}}
    return known ?: loaded
}
