package com.lifeatlas.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

@Composable fun DynamicScreen(config:JSONObject,state:AtlasState,vm:AtlasModel,onRecord:(JSONObject)->Unit,onOpen:(String)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
        (config.optJSONArray("components") ?: JSONArray()).objects().forEach{component->
            val metric=state.objects.firstOrNull{it.s("id")==component.s("metricId")}
            when(component.s("component")) {
                "text"->Text(component.s("text"))
                "navigation"->OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={onOpen(component.s("objectId"))}){Text(component.s("title","打开"))}
                "form"->if(metric!=null)Button(onClick={onRecord(metric)}){Text(component.s("title","记录${metric.s("title")}"))}
                "chart","summary"->if(metric!=null)MetricPanel(metric,vm,state.revision,onRecord={onRecord(metric)},chartType=component.s("chart","line"))
                "history"->if(metric!=null){var facts by remember{mutableStateOf<List<JSONObject>>(emptyList())};LaunchedEffect(state.revision,metric.s("id")){facts=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){vm.store.facts(metric.s("id"),limit=30)}};Text(metric.s("title"),style=MaterialTheme.typography.titleMedium);facts.forEach{Text("${it.s("local_date")}  ${it.s("value","未填写")}")}}
            }
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
        if(metric!=null){OutlinedTextField(value,{value=it},label={Text("数值 · ${metric.s("unit")}")},modifier=Modifier.fillMaxWidth());(metric.optJSONArray("fields") ?: JSONArray()).objects().forEach{f->DynamicField(f,fields[f.s("id")] ?: ""){fields=fields+(f.s("id") to it)}}
    }
}
