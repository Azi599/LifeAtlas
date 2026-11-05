package com.lifeatlas.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

@Composable fun Pick(label:String,choices:List<Pair<String,String>>,selected:String,onSelect:(String)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={expanded=true}){Text("$label：${choices.firstOrNull{it.first==selected}?.second ?: "请选择"}")}
    if(expanded) AlertDialog(onDismissRequest={expanded=false},title={Text(label)},text={Column(Modifier.verticalScroll(rememberScrollState())){choices.forEach{(id,title)->Row(Modifier.fillMaxWidth().clickable{onSelect(id);expanded=false}.padding(vertical=8.dp)){RadioButton(selected==id,onClick={onSelect(id);expanded=false});Text(title,Modifier.padding(top=12.dp))}}}},confirmButton={TextButton(onClick={expanded=false}){Text("关闭")}})
}
@Composable fun ObjectDialog(parent:String?,objects:List<JSONObject>,onDismiss:()->Unit,onSave:(String,String,JSONObject)->Unit) {
    var type by remember{mutableStateOf("PROJECT")};var name by remember{mutableStateOf("")};var kind by remember{mutableStateOf("SNAPSHOT")};var unit by remember{mutableStateOf("")};var aggregate by remember{mutableStateOf("LAST_SNAPSHOT")};var role by remember{mutableStateOf("")};var fields by remember{mutableStateOf<List<JSONObject>>(emptyList())};var addField by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("新建${objects.firstOrNull{it.s("id")==parent}?.s("title")?.let{" · $it"} ?: ""}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Pick("类型",listOf("WORKSPACE","FOLDER","PROJECT","METRIC","DASHBOARD").map{it to typeName(it)},type){type=it}
        OutlinedTextField(name,{name=it},label={Text("名称")},modifier=Modifier.fillMaxWidth())
        if(type=="METRIC") {
            Pick("数据语义",kinds.map{it to kindName(it)},kind){kind=it;aggregate=if(it=="SNAPSHOT") "LAST_SNAPSHOT" else "SUM"}
            OutlinedTextField(unit,{unit=it},label={Text("单位，如 kg、页、分钟")},modifier=Modifier.fillMaxWidth())
            Pick("统计方式",aggregateMethods.map{it to methodName(it)},aggregate){aggregate=it}
            Pick("专业规则",listOf("" to "通用指标","income" to "收入（正值）","expense" to "支出（负值）","balance" to "账户余额快照","sleep" to "睡眠时长"),role){role=it}
            fields.forEach{Text("${it.s("title")} · ${it.s("type")}")};TextButton(onClick={addField=true}){Text("添加自定义字段")}
        }
    }},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={val c=if(type=="METRIC") obj("kind" to kind,"unit" to unit,"aggregate" to aggregate,"role" to role,"fields" to JSONArray(fields)) else JSONObject();onSave(type,name,c)}){Text("创建")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
    if(addField) FieldDialog(onDismiss={addField=false},onSave={fields=fields+it;addField=false})
}
@Composable fun FieldDialog(onDismiss:()->Unit,onSave:(JSONObject)->Unit) {
    var title by remember{mutableStateOf("")};var id by remember{mutableStateOf("")};var type by remember{mutableStateOf("text")};var required by remember{mutableStateOf(false)};var options by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("自定义字段")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(title,{title=it},label={Text("显示名称")},modifier=Modifier.fillMaxWidth());OutlinedTextField(id,{id=it},label={Text("字段标识，英文字母开头")},modifier=Modifier.fillMaxWidth())
        Pick("字段类型",listOf("text" to "文字","decimal" to "数字","date" to "日期","boolean" to "是/否","choice" to "选项"),type){type=it}
        if(type=="choice") OutlinedTextField(options,{options=it},label={Text("选项，每行一个")},modifier=Modifier.fillMaxWidth(),minLines=2)
        Row{Checkbox(required,{required=it});Text("必填",Modifier.padding(top=12.dp))}
    }},confirmButton={TextButton(enabled=title.isNotBlank() && id.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,39}")),onClick={onSave(obj("id" to id,"title" to title,"type" to type,"required" to required,"options" to JSONArray(options.lines().filter{it.isNotBlank()})))}){Text("添加")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
@Composable fun ManageDialog(o:JSONObject,objects:List<JSONObject>,vm:AtlasModel,onDismiss:()->Unit) {
    var title by remember{mutableStateOf(o.s("title"))};var parent by remember{mutableStateOf(o.s("parent_id"))};var field by remember{mutableStateOf(false)};var position by remember{mutableStateOf("0")};var hidden by remember{mutableStateOf(false)};var relate by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("管理${o.s("title")}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(title,{title=it},label={Text("名称")},modifier=Modifier.fillMaxWidth())
        Pick("移动到",listOf("" to "根空间")+objects.filter{it.s("id")!=o.s("id") && it.s("type") in setOf("FOLDER","PROJECT","WORKSPACE","DASHBOARD")}.map{it.s("id") to it.s("title")},parent){parent=it}
        if(o.s("type")=="METRIC")TextButton(onClick={field=true}){Text("增加字段")}
        OutlinedTextField(position,{position=it},label={Text("首页顺序（数字越小越靠前）")},modifier=Modifier.fillMaxWidth())
        Row{Checkbox(hidden,{hidden=it});Text("隐藏首页卡片",Modifier.padding(top=12.dp))}
        TextButton(onClick={vm.action("atlas.layout",obj("objectId" to o.s("id"),"position" to (position.toIntOrNull() ?: 0),"hidden" to hidden),"调整首页");onDismiss()}){Text("应用首页设置")}
        Pick("关联对象",objects.filter{it.s("id")!=o.s("id")}.map{it.s("id") to it.s("title")},relate){relate=it}
        if(relate.isNotEmpty())TextButton(onClick={vm.action("atlas.relate",obj("fromId" to o.s("id"),"toId" to relate,"kind" to "display"),"关联对象");onDismiss()}){Text("添加关联")}
        TextButton(onClick={vm.action("atlas.updateObject",obj("id" to o.s("id"),"archived" to true),"归档${o.s("title")}");onDismiss()}){Text("归档（可撤销）")}
    }},confirmButton={TextButton(onClick={vm.action("atlas.updateObject",obj("id" to o.s("id"),"title" to title,"parentId" to parent.ifEmpty{null}),"更新${o.s("title")}");onDismiss()}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("关闭")}})
    if(field)FieldDialog(onDismiss={field=false},onSave={f->val c=JSONObject(o.s("config"));val fields=c.optJSONArray("fields") ?: JSONArray();fields.put(f);c.put("fields",fields);vm.action("atlas.updateObject",obj("id" to o.s("id"),"config" to c),"增加字段");field=false;onDismiss()})
}
@Composable fun RecordDialog(metric:JSONObject,onDismiss:()->Unit,onSave:(JSONObject)->Unit) {
    var value by remember{mutableStateOf("")};var date by remember{mutableStateOf(LocalDate.now().toString())};var fieldValues by remember{mutableStateOf<Map<String,String>>(emptyMap())};var error by remember{mutableStateOf("")}
    val config=remember(metric){JSONObject(metric.s("config"))};val fields=config.optJSONArray("fields") ?: JSONArray()
    AlertDialog(onDismissRequest=onDismiss,title={Text("记录${metric.s("title")}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        if(!config.has("expression"))OutlinedTextField(value,{value=it},label={Text("数值 · ${config.s("unit")}")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(date,{date=it},label={Text("发生日期 YYYY-MM-DD")},modifier=Modifier.fillMaxWidth())
        fields.objects().forEach{f->val id=f.s("id");val v=fieldValues[id] ?: "";val label=f.s("title",id)+if(f.optBoolean("required")) " *" else ""
            when(f.s("type")){"choice"->Pick(label,(f.optJSONArray("options") ?: JSONArray()).let{a->(0 until a.length()).map{a.getString(it) to a.getString(it)}},v){fieldValues=fieldValues+(id to it)}
                "boolean"->Row{Checkbox(v=="true",{fieldValues=fieldValues+(id to it.toString())});Text(label,Modifier.padding(top=12.dp))}
                else->OutlinedTextField(v,{fieldValues=fieldValues+(id to it)},label={Text(label)},modifier=Modifier.fillMaxWidth())}
        }
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(onClick={runCatching{LocalDate.parse(date);if(value.isNotBlank())decimal(value);fields.objects().forEach{require(!it.optBoolean("required") || !fieldValues[it.s("id")].isNullOrBlank()){ "请填写${it.s("title")}" }};onSave(obj("metricId" to metric.s("id"),"value" to value.ifBlank{null},"date" to date,"fields" to JSONObject(fieldValues)))}.onFailure{error=it.message ?: "输入有误"}}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
@Composable fun EditFactDialog(f:JSONObject,onDismiss:()->Unit,onSave:(JSONObject)->Unit,onDelete:()->Unit) {
    var value by remember{mutableStateOf(f.s("value"))};var date by remember{mutableStateOf(f.s("local_date"))};var error by remember{mutableStateOf("")};var confirmDelete by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("修改${f.s("metric_title")}")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(value,{value=it},label={Text("数值")},modifier=Modifier.fillMaxWidth());OutlinedTextField(date,{date=it},label={Text("发生日期")},modifier=Modifier.fillMaxWidth());Text(error);TextButton(onClick={confirmDelete=true}){Text("删除此记录")}}},confirmButton={TextButton(onClick={runCatching{LocalDate.parse(date);if(value.isNotBlank())decimal(value);onSave(obj("id" to f.s("id"),"value" to value.ifBlank{null},"date" to date))}.onFailure{error="请检查日期与数值"}}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("关闭")}})
    if(confirmDelete)AlertDialog(onDismissRequest={confirmDelete=false},title={Text("删除这条记录？")},text={Text("可从操作历史撤销。")},confirmButton={TextButton(onClick=onDelete){Text("删除")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("取消")}})
}
fun methodName(s:String)=mapOf("SUM" to "累计","MEAN" to "均值","WEIGHTED_MEAN" to "加权平均","LAST_SNAPSHOT" to "最新快照","UNION_DURATION" to "时段并集","MEDIAN" to "中位数","MIN" to "最小值","MAX" to "最大值","COUNT" to "样本数")[s] ?: s
