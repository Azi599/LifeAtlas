package com.lifeatlas.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable fun ExecutionResults(result:JSONObject,objects:List<JSONObject>,onOpen:(String)->Unit,onFact:(JSONObject)->Unit,onRetry:(JSONObject)->Unit={},onFactId:(String)->Unit={}) {
    val steps=result.optJSONObject("steps") ?: return
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(result.has("retryPlan"))OutlinedButton(onClick={onRetry(result.getJSONObject("retryPlan"))}){Text("重试剩余操作")}

        steps.keys().forEach{key->val value=steps.optJSONObject(key) ?: return@forEach
            if(value.has("status"))Text(buildState(value.s("status")))
            if(value.has("update")){val release=value.getJSONObject("update");Text("可更新至 ${release.s("versionName",release.optLong("versionCode").toString())}")}
            if(value.has("points"))Trend(value.getJSONArray("points").objects(),value.s("unit"),value.s("chart","line"))
            if(value.has("eventId") && value.has("metricId") && value.s("id").isNotEmpty())OutlinedButton(onClick={onFactId(value.s("id"))}){Text("修改此记录")}
            if(value.has("records")) {
                val records=value.getJSONArray("records").objects()
                Text("查询结果 · ${records.size} 条",style=MaterialTheme.typography.titleMedium)
                records.forEach{r->OutlinedCard(Modifier.fillMaxWidth().clickable{onFact(r)}){Column(Modifier.padding(12.dp)){Text(r.s("metric_title"));Text("${r.s("local_date")}  ${r.s("value","未填写")}")}}}
            }
            if(value.has("method") && value.has("metricId")) Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text(objects.firstOrNull{it.s("id")==value.s("metricId")}?.s("title") ?: "统计结果");Text("${value.s("value","暂无数据")} ${value.s("unit")}",style=MaterialTheme.typography.headlineSmall);Text("${methodName(value.s("method"))} · ${value.optLong("usedCount",value.optLong("count"))} 条")}}
            if(value.has("pearson"))OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("相关系数 ${value.s("pearson","无法计算")}");Text("配对样本 ${value.optInt("samplePairs")}")}}
            if(value.has("anchorDate"))OutlinedCard{Column(Modifier.padding(16.dp)){Text("余额 ${value.s("value","待补充")} ${value.s("unit")}");if(value.optInt("uncertainSameDayCount")>0)Text("同日先后未确定：${value.optInt("uncertainSameDayCount")} 条")}}
            val id=value.s("id");if(id.isNotEmpty() && (objects.any{it.s("id")==id} || value.has("title") || value.has("pluginId")))OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={onOpen(id)}){Text("打开${objects.firstOrNull{it.s("id")==id}?.s("title") ?: value.s("title","结果")}")}
        }
    }
}
