package com.lifeatlas.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable fun PluginWizard(onDismiss:()->Unit,onPreview:(JSONObject)->Unit) {
    var title by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var unit by remember{mutableStateOf("")};var kind by remember{mutableStateOf("SNAPSHOT")};var method by remember{mutableStateOf("LAST_SNAPSHOT")};var chart by remember{mutableStateOf("line")};var metrics by remember{mutableStateOf<List<JSONObject>>(emptyList())};var fields by remember{mutableStateOf<List<JSONObject>>(emptyList())};var fieldDialog by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("创建功能包")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(title,{title=it},label={Text("功能名称")},modifier=Modifier.fillMaxWidth())
        metrics.forEachIndexed{i,m->OutlinedCard{Column(Modifier.padding(12.dp)){Text("${m.s("title")} · ${m.s("unit")}");TextButton(onClick={metrics=metrics.filterIndexed{index,_->index!=i}}){Text("移除")}}}}
        HorizontalDivider();OutlinedTextField(name,{name=it},label={Text("指标名称")},modifier=Modifier.fillMaxWidth());OutlinedTextField(unit,{unit=it},label={Text("单位")},modifier=Modifier.fillMaxWidth())
        Pick("记录方式",kinds.map{it to kindName(it)},kind){kind=it;if(it=="ALLOCATION" && !Units.isTime(unit))unit="分钟";method=if(it=="SNAPSHOT") "LAST_SNAPSHOT" else "SUM"}
        Pick("统计方式",aggregateMethods.map{it to methodName(it)},method){method=it;if(it=="WEIGHTED_MEAN" && fields.none{f->f.s("id")=="weight"})fields=fields+obj("id" to "weight","title" to "权重","type" to "decimal","required" to true)}
        Pick("图表",listOf("line" to "折线","bar" to "柱形","heatmap" to "热力图"),chart){chart=it}
        fields.forEach{Text(it.s("title"))};TextButton(onClick={fieldDialog=true}){Text("增加字段")}
        OutlinedButton(enabled=name.isNotBlank(),onClick={metrics=metrics+obj("key" to "m${uid().replace("-","")}","title" to name,"kind" to kind,"unit" to unit,"aggregate" to method,"fields" to JSONArray(fields),"chart" to chart);name="";unit="";fields=emptyList()}){Text("加入指标")}
    }},confirmButton={TextButton(enabled=title.isNotBlank() && metrics.isNotEmpty(),onClick={val id="user.${uid().replace("-","")}";val views=JSONArray();val actions=JSONArray();metrics.forEach{m->views.put(obj("title" to m.s("title")+"趋势","metricKey" to m.s("key"),"chart" to m.s("chart")));actions.put(obj("id" to "record_${m.s("key")}","metricKey" to m.s("key"),"template" to "record"))};onPreview(obj("id" to id,"title" to title,"version" to 1,"metrics" to JSONArray(metrics),"views" to views,"actions" to actions))}){Text("预览")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
    if(fieldDialog)FieldDialog(onDismiss={fieldDialog=false},onSave={fields=fields+it;fieldDialog=false})
}
@Composable fun WorkflowDialog(objects:List<JSONObject>,onDismiss:()->Unit,onSave:(String,JSONObject)->Unit) {
    var title by remember{mutableStateOf("")};var trigger by remember{mutableStateOf("")};var threshold by remember{mutableStateOf("")};var target by remember{mutableStateOf("")}
    val metrics=objects.filter{it.s("type")=="METRIC"}
    AlertDialog(onDismissRequest=onDismiss,title={Text("新建工作流")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(title,{title=it},label={Text("名称")},modifier=Modifier.fillMaxWidth());Pick("记录触发指标",metrics.map{it.s("id") to it.s("title")},trigger){trigger=it};OutlinedTextField(threshold,{threshold=it},label={Text("数值大于（留空为每次记录）")},modifier=Modifier.fillMaxWidth());Pick("置于首页最前",objects.filter{it.s("type") in setOf("PROJECT","VIEW","DASHBOARD")}.map{it.s("id") to it.s("title")},target){target=it}
    }},confirmButton={TextButton(enabled=title.isNotBlank() && trigger.isNotEmpty() && target.isNotEmpty() && (threshold.isBlank() || threshold.toBigDecimalOrNull()!=null),onClick={val spec=obj("triggerMetricId" to trigger,"steps" to JSONArray().put(obj("actionId" to "atlas.layout","args" to obj("objectId" to target,"position" to 0,"hidden" to false))));if(threshold.isNotBlank())spec.put("condition",obj("op" to "subtract","args" to JSONArray().put(obj("field" to "value")).put(obj("const" to threshold))));onSave(title,spec)}){Text("创建")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
@Composable fun BuildRequestDialog(onDismiss:()->Unit,onSave:(JSONObject)->Unit) {
    var title by remember{mutableStateOf("")};var requirements by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("新增原生能力")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(title,{title=it},label={Text("功能名称")},modifier=Modifier.fillMaxWidth());OutlinedTextField(requirements,{requirements=it},label={Text("具体需求")},modifier=Modifier.fillMaxWidth(),minLines=3)}},confirmButton={TextButton(enabled=title.isNotBlank() && requirements.isNotBlank(),onClick={onSave(obj("title" to title,"requirements" to requirements))}){Text("建立任务")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
