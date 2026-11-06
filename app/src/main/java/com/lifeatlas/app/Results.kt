package com.lifeatlas.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable fun ExecutionResults(result:JSONObject,objects:List<JSONObject>,onOpen:(String)->Unit,onFact:(JSONObject)->Unit,onRetry:(JSONObject)->Unit={},onFactId:(String)->Unit={}) {
    val steps=result.optJSONObject("steps") ?: return
    val receipts=steps.keys().asSequence().mapNotNull{steps.optJSONObject(it)}.filter{it.has("eventId") && it.has("metricId") && it.s("id").isNotEmpty()}.toList()
    var expanded by remember(result.toString()){mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(receipts.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            (if(expanded)receipts else receipts.take(3)).forEach{r->OutlinedCard(Modifier.fillMaxWidth().clickable{onFactId(r.s("id"))}){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Row(Modifier.fillMaxWidth()){Text(r.s("metricTitle").ifBlank{objects.firstOrNull{it.s("id")==r.s("metricId")}?.s("title") ?: "记录"},Modifier.weight(1f));Text(r.s("value","待补数值")+" "+r.s("unit").let{if(it=="CNY")"元" else it})}
                Text(r.s("date"),style=MaterialTheme.typography.bodySmall)
            }}}
            if(receipts.size>3)TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起" else "展开其余 ${receipts.size-3} 条")}
        }
        if(result.has("retryPlan"))OutlinedButton(onClick={onRetry(result.getJSONObject("retryPlan"))}){Text("重试剩余操作")}

        steps.keys().forEach{key->val value=steps.optJSONObject(key) ?: return@forEach
            if(value.has("status"))Text(buildState(value.s("status")))
            if(value.has("duration"))Text("有效时长 ${value.s("duration")} ${value.s("unit")}")
            if(value.has("update")){val release=value.getJSONObject("update");Text("可更新至 ${release.s("versionName",release.optLong("versionCode").toString())}")}
            if(value.has("points"))Trend(value.getJSONArray("points").objects(),value.s("unit"),value.s("chart","line"))

            if(value.has("records")) {
                val records=value.getJSONArray("records").objects()
                Text("查询结果 · ${records.size} 条",style=MaterialTheme.typography.titleMedium)
                records.forEach{r->OutlinedCard(Modifier.fillMaxWidth().clickable{onFact(r)}){Column(Modifier.padding(12.dp)){Text(r.s("metric_title"));Text("${r.s("local_date")}  ${r.s("value","未填写")}")}}}
            }
            if(value.has("method") && value.has("metricId")) Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text(objects.firstOrNull{it.s("id")==value.s("metricId")}?.s("title") ?: "统计结果");Text("${value.s("value","暂无数据")} ${value.s("unit")}",style=MaterialTheme.typography.headlineSmall);Text("${methodName(value.s("method"))} · ${value.optLong("usedCount",value.optLong("count"))} 条")}}
            if(value.has("pearson"))OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("相关系数 ${value.s("pearson","无法计算")}");Text("配对样本 ${value.optInt("samplePairs")}")}}
            if(value.optBoolean("occupancy")) {
                OutlinedCard(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text("已知时段占用",style=MaterialTheme.typography.titleMedium)
                        Text(value.s("minutes").takeIf{it.isNotBlank()}?.let{"$it 分钟"} ?: "暂无可计算时段",
                            style=MaterialTheme.typography.headlineSmall)
                        if(value.optInt("untimed")>0) Text("${value.optInt("untimed")} 条缺少起止时间，无法判断是否重叠")
                    }
                }
            }
            if(value.has("cashflow")) {
                val flows=value.getJSONArray("cashflow").objects()
                if(flows.isEmpty()) Text("所选日期没有收支记录")
                flows.forEach { flow ->
                    val unit=flow.s("unit")
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            if(!value.optBoolean("onlyExpense")) Text("收入 "+flow.s("income","暂无数值")+
                                if(flow.has("income") && !flow.isNull("income")) " $unit" else "")
                            Text("支出净额 "+flow.s("expense","暂无数值")+
                                if(flow.has("expense") && !flow.isNull("expense")) " $unit" else "")
                            if(!value.optBoolean("onlyExpense")) Text("已记录净额 "+flow.s("net","待补充")+
                                if(flow.has("net") && !flow.isNull("net")) " $unit" else "")
                            if(flow.optLong("missingCount")>0) Text("${flow.optLong("missingCount")} 笔金额待补充")
                            Text("${value.s("from")} — ${value.s("to")}",style=MaterialTheme.typography.bodySmall)
                            if(value.optBoolean("includeBasis")) Text("支出扣除退款，转账不计入",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if(value.has("transferAmount")) OutlinedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    val from=objects.firstOrNull{it.s("id")==value.s("fromMetricId")}?.s("title") ?: "转出账户"
                    val to=objects.firstOrNull{it.s("id")==value.s("toMetricId")}?.s("title") ?: "转入账户"
                    val unit=value.s("unit").let{if(it=="CNY") "元" else it}
                    Text("已记录转账 ${value.s("transferAmount")} $unit")
                    Text("$from → $to")
                    Text(value.s("date"),style=MaterialTheme.typography.bodySmall)
                }
            }
            if(value.optBoolean("balanceProjection")) OutlinedCard(Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    val unit=value.s("unit").let{if(it=="CNY") "元" else it}
                    if(value.has("reason")) Text(value.s("reason"))
                    else {
                        Text(value.s("value").ifBlank{"推算余额待核对"}.let{if(value.isNull("value")) it else "推算余额 $it $unit"})
                        Text(if(value.isNull("netDelta")) "已确认净变动 ${value.s("confirmedDelta")} $unit"
                            else "净变动 ${value.s("netDelta")} $unit")
                        Text("实测日期 ${value.s("anchorDate")}",style=MaterialTheme.typography.bodySmall)
                        if(value.optInt("uncertainSameDayCount")>0) Text("${value.optInt("uncertainSameDayCount")} 条同日流水先后待核对")
                        if(value.optInt("missingCount")>0) Text("${value.optInt("missingCount")} 条流水金额待补充")
                    }
                }
            }
            val id=value.s("id");if(id.isNotEmpty() && (objects.any{it.s("id")==id} || value.has("title") || value.has("pluginId")))OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={onOpen(id)}){Text("打开${objects.firstOrNull{it.s("id")==id}?.s("title") ?: value.s("title","结果")}")}
        }
    }
}
