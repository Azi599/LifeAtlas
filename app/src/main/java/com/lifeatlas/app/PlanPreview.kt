package com.lifeatlas.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

@Composable fun PlanPreview(plan:JSONObject,objects:List<JSONObject> = emptyList(),busy:Boolean=false,error:String="",onConfirm:(JSONObject)->Unit,onCancel:()->Unit) {
    var edited by remember(plan.toString()){mutableStateOf(plan.copyJson())}
    var editing by remember(plan.toString()){mutableStateOf("")}
    var historyMode by rememberSaveable(plan.toString()){mutableStateOf("")}
    var validation by remember{mutableStateOf("")}
    val steps=(edited.optJSONArray("steps") ?: JSONArray()).objects()
    val needsMetricSelection = steps.any(PlanReview::metricSelectionPending)
    val hasRecords=steps.any{it.s("actionId")=="atlas.record"}
    val needsHistory=(plan.optJSONArray("impact") ?: JSONArray()).objects().any{it.optBoolean("expressionChanged") && it.optLong("count")>0}
    fun change(index:Int,key:String,value:String,field:Boolean=false){val copy=edited.copyJson();val args=copy.getJSONArray("steps").getJSONObject(index).getJSONObject("args");if(field){val fields=args.optJSONObject("fields") ?: JSONObject();fields.put(key,value);args.put("fields",fields)}else args.put(key,value);edited=copy;validation=""}
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(plan.s("question",plan.s("title","操作预览")),style=MaterialTheme.typography.titleMedium)
        if(plan.has("question")) {
            (plan.optJSONArray("options") ?: JSONArray()).objects().forEach{choice->
                OutlinedButton(enabled=!busy,modifier=Modifier.fillMaxWidth(),onClick={onConfirm(choice.getJSONObject("plan").copyJson().put("fromChoice",true))}){Text(choice.s("title"))}
            }
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
            OutlinedButton(enabled=!busy,onClick=onCancel){Text("取消")}
        }
        else {
            steps.forEachIndexed{index,step->
                val a=step.optJSONObject("args") ?: JSONObject();val manifest=a.optJSONObject("manifest")
                if(step.s("actionId")=="atlas.record") {
                    val metric=PlanReview.metric(edited,a,objects);val config=JSONObject(metric.s("config","{}"));val fields=a.optJSONObject("fields") ?: JSONObject()
                    val choices = PlanReview.metricChoices(step).mapNotNull { id ->
                        objects.firstOrNull { it.s("id") == id }?.let { candidate ->
                            val parent = objects.firstOrNull { it.s("id") == candidate.s("parent_id") }
                            id to listOfNotNull(candidate.s("title"), parent?.s("title")).joinToString(" · ")
                        }
                    }
                    val awaitingMetric = PlanReview.metricSelectionPending(step)
                    Column(Modifier.fillMaxWidth().testTag("plan-record-$index"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(awaitingMetric) "建议：${metric.s("title")}" else metric.s("title"),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);Text(PlanReview.value(metric,a)+" "+PlanReview.unit(metric),style=MaterialTheme.typography.titleMedium)}
                        Text(a.s("date").ifBlank{"待补日期"}+(a.s("occurredAt").takeIf{it.isNotBlank()}?.let{" · "+formatInstant(it,a.s("zone",java.time.ZoneId.systemDefault().id))} ?: ""),style=MaterialTheme.typography.bodyMedium)
                        if(a.s("startAt").isNotBlank() && a.s("endAt").isNotBlank()) {
                            val zone=a.s("zone",java.time.ZoneId.systemDefault().id)
                            Text(formatInstant(a.s("startAt"),zone)+" → "+formatInstant(a.s("endAt"),zone),style=MaterialTheme.typography.bodyMedium)
                        }
                        if (choices.isNotEmpty() && !busy) {
                            Pick("记到",choices,if(awaitingMetric) "" else a.s("metricId")) { id ->
                                val copy = edited.copyJson()
                                val updated = copy.getJSONArray("steps").getJSONObject(index)
                                updated.put("metricConfirmed", true).getJSONObject("args").put("metricId",id)
                                edited = copy
                                validation = ""
                            }
                        }
                        fields.keys().forEach{k->val label=(config.optJSONArray("fields") ?: JSONArray()).objects().firstOrNull{it.s("id")==k}?.s("title") ?: k;Text("$label：${fields.s(k)}",style=MaterialTheme.typography.bodyMedium)}
                        if(a.s("sourceExcerpt").isNotBlank())Text(a.s("sourceExcerpt"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(enabled=!busy,onClick={editing=if(editing==index.toString())"" else index.toString()}){Text(if(editing==index.toString())"收起" else "修改")}
                        if(editing==index.toString()) {
                            OutlinedTextField(a.s("value"),{change(index,"value",it)},label={Text(listOf("数值",a.s("unit",PlanReview.unit(metric))).filter{it.isNotBlank()}.joinToString(" · "))},modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(a.s("date"),{change(index,"date",it)},label={Text("发生日期")},modifier=Modifier.fillMaxWidth())
                            fields.keys().forEach{k->OutlinedTextField(fields.s(k),{change(index,k,it,true)},label={Text((config.optJSONArray("fields") ?: JSONArray()).objects().firstOrNull{it.s("id")==k}?.s("title") ?: k)},modifier=Modifier.fillMaxWidth())}
                        }
                        HorizontalDivider()
                    }
                } else if(step.has("review")) {
                    val review=step.getJSONObject("review")
                    Text(actionName(step.s("actionId"))+" · "+review.s("title"),style=MaterialTheme.typography.titleSmall)
                    val unit=review.s("unit").let{if(it=="CNY")"元" else it}
                    val value=review.s("value","待补数值")
                    Text(if(a.has("value")) "$value → ${review.s("proposedValue","待补数值")} $unit" else "$value $unit")
                    Text(if(a.s("date").isNotBlank() && a.s("date")!=review.s("date")) "${review.s("date")} → ${a.s("date")}" else review.s("date"))
                    val zone=a.s("zone",review.s("zone"))
                    if(a.optBoolean("clearTime"))Text("移除具体时刻")
                    for((key,label) in listOf("occurredAt" to "发生时刻","startAt" to "开始","endAt" to "结束")) {
                        if(a.s(key).isNotBlank())Text("$label ${formatInstant(a.s(key),zone)}")
                    }
                    a.optJSONObject("fields")?.let{changed->
                        changed.keys().forEach{field->Text("$field：${review.optJSONObject("fields")?.s(field,"空") ?: "空"} → ${changed.s(field,"空")}")}
                    }
                    HorizontalDivider()
                } else {
                    Text(if(manifest!=null)manifest.s("title") else "${actionName(step.s("actionId"))} ${a.s("title")}")
                    if(manifest!=null && !hasRecords)FeaturePreview(manifest)
                }
            }
            if(!needsMetricSelection) PlanReview.totals(edited,objects).forEach{Text(it,style=MaterialTheme.typography.titleMedium)}
            (plan.optJSONArray("impact") ?: JSONArray()).objects().forEach{impact->Text(impact.s("title")+" · "+impact.optLong("count")+" 条记录");if(impact.s("oldUnit")!=impact.s("newUnit"))Text(impact.s("oldUnit")+" → "+impact.s("newUnit")+" × "+impact.s("factor"))}
            if(needsHistory)Pick("历史记录",listOf("recalculate" to "按新规则重算","preserve" to "保留原计算结果"),historyMode){historyMode=it}
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("plan-error"))
            if(validation.isNotBlank())Text(validation,color=MaterialTheme.colorScheme.error)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                OutlinedButton(enabled=!busy,onClick=onCancel){Text("取消")}
                Spacer(Modifier.weight(1f))
                Button(enabled=!busy && !needsMetricSelection && (!needsHistory || historyMode.isNotBlank()),onClick={
                    val invalid=steps.filter{it.s("actionId")=="atlas.record"}.firstOrNull{val a=it.optJSONObject("args") ?: JSONObject();runCatching{LocalDate.parse(a.s("date"))}.isFailure || (a.s("value").isNotBlank() && a.s("value").toBigDecimalOrNull()==null)}
                    if(invalid!=null)validation="请检查记录的数值和发生日期" else {val confirmed=edited.copyJson();if(historyMode.isNotBlank())confirmed.getJSONArray("steps").objects().forEach{it.optJSONObject("args")?.optJSONObject("manifest")?.put("historyMode",historyMode)};onConfirm(confirmed)}
                }){Text(if(busy)"正在保存…" else if(error.isNotBlank())"重试保存" else "确认执行")}
            }
        }
    }}
}
