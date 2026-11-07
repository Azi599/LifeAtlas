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
    var limits by remember{mutableStateOf(false)}
    var aliases by remember{mutableStateOf("")}
    var minimum by remember{mutableStateOf("")}
    var maximum by remember{mutableStateOf("")}
    var decimals by remember{mutableStateOf("")}
    var error by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("新建${objects.firstOrNull{it.s("id")==parent}?.s("title")?.let{" · $it"} ?: ""}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Pick("类型",listOf("WORKSPACE","FOLDER","PROJECT","METRIC","DASHBOARD").map{it to typeName(it)},type){type=it}
        OutlinedTextField(name,{name=it},label={Text("名称")},modifier=Modifier.fillMaxWidth())
        if(type=="METRIC") {
            Pick("记录方式",kinds.map{it to kindName(it)},kind){kind=it;if(it=="ALLOCATION" && !Units.isTime(unit))unit="分钟";aggregate=if(it=="SNAPSHOT") "LAST_SNAPSHOT" else "SUM"}
            OutlinedTextField(unit,{unit=it},label={Text("单位，如 kg、页、分钟")},modifier=Modifier.fillMaxWidth())
            Pick("统计方式",aggregateMethods.map{it to methodName(it)},aggregate){aggregate=it}
            Pick("专业规则",listOf("" to "通用指标","income" to "收入（正值）","expense" to "支出（负值）","balance" to "账户余额快照","accountChange" to "账户流水","sleep" to "睡眠时长"),role){role=it;when(it){"balance"->{kind="SNAPSHOT";aggregate="LAST_SNAPSHOT";unit="CNY"};"income","expense","accountChange"->{kind="CHANGE";aggregate="SUM";unit="CNY"};"sleep"->{kind="ALLOCATION";aggregate="SUM";unit="小时"}}}
            fields.forEach{Text("${it.s("title")} · ${it.s("type")}")};TextButton(onClick={addField=true}){Text("添加自定义字段")}
            OutlinedButton(onClick={limits=!limits}){Text(if(limits) "收起高级设置" else "高级设置")}
            if(limits) {
                OutlinedTextField(aliases,{aliases=it;error=""},label={Text("别名，每行一个")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(minimum,{minimum=it;error=""},label={Text("最小值")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(maximum,{maximum=it;error=""},label={Text("最大值")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(decimals,{decimals=it;error=""},label={Text("最多小数位")},modifier=Modifier.fillMaxWidth())
            }
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        }
    }},confirmButton={Button(enabled=name.isNotBlank(),onClick={
        val config=if(type=="METRIC") obj("kind" to kind,"unit" to unit,"aggregate" to aggregate,"role" to role,"fields" to JSONArray(fields))
            .apply {
                minimum.trim().takeIf{it.isNotEmpty()}?.let{put("minimum",it)}
                maximum.trim().takeIf{it.isNotEmpty()}?.let{put("maximum",it)}
                decimals.trim().takeIf{it.isNotEmpty()}?.let{put("decimalPlaces",it)}
                val names=aliases.lineSequence().map(String::trim).filter{it.isNotBlank()}.distinct().toList()
                if(names.isNotEmpty())put("aliases",JSONArray(names))
            } else JSONObject()
        val validation=runCatching{if(type=="METRIC") validateMetric(config)}
        if(validation.isSuccess) onSave(type,name,config)
        else error=validation.exceptionOrNull()?.message ?: "请核对数值限制"
    }){Text("创建")}},dismissButton={OutlinedButton(onClick=onDismiss){Text("取消")}})
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
    var title by remember{mutableStateOf(o.s("title"))};var parent by remember{mutableStateOf(o.s("parent_id"))};var field by remember{mutableStateOf(false)};var relate by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("管理${o.s("title")}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(title,{title=it},label={Text("名称")},modifier=Modifier.fillMaxWidth())
        Pick("移动到",listOf("" to "根空间")+objects.filter{it.s("id")!=o.s("id") && it.s("type") in setOf("FOLDER","PROJECT","WORKSPACE","DASHBOARD")}.map{it.s("id") to it.s("title")},parent){parent=it}
        if(o.s("type")=="METRIC")TextButton(onClick={field=true}){Text("增加字段")}
        Pick("关联对象",objects.filter{it.s("id")!=o.s("id")}.map{it.s("id") to it.s("title")},relate){relate=it}
        if(relate.isNotEmpty())TextButton(onClick={vm.action("atlas.relate",obj("fromId" to o.s("id"),"toId" to relate,"kind" to "display"),"关联对象");onDismiss()}){Text("添加关联")}
        TextButton(onClick={vm.action("atlas.updateObject",obj("id" to o.s("id"),"archived" to (o.optInt("archived")==0)),"更新归档状态");onDismiss()}){Text(if(o.optInt("archived")==0) "归档" else "恢复归档")}
    }},confirmButton={TextButton(onClick={vm.action("atlas.updateObject",obj("id" to o.s("id"),"title" to title,"parentId" to parent.ifEmpty{null}),"更新${o.s("title")}");onDismiss()}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("关闭")}})
    if(field)FieldDialog(onDismiss={field=false},onSave={f->val c=JSONObject(o.s("config"));val fields=c.optJSONArray("fields") ?: JSONArray();fields.put(f);c.put("fields",fields);vm.action("atlas.updateObject",obj("id" to o.s("id"),"config" to c),"增加字段");field=false;onDismiss()})
}
@Composable fun RecordDialog(metric:JSONObject,events:List<JSONObject> = emptyList(),onDismiss:()->Unit,onSave:(JSONObject)->Unit) {
    var value by remember{mutableStateOf("")};var date by remember{mutableStateOf(LocalDate.now().toString())};var values by remember{mutableStateOf<Map<String,String>>(emptyMap())};var error by remember{mutableStateOf("")};var eventId by remember{mutableStateOf("")}
    var timeMode by remember{mutableStateOf("date")};var start by remember{mutableStateOf("")};var end by remember{mutableStateOf("")};var zone by remember{mutableStateOf(java.time.ZoneId.systemDefault().id)}
    val context=androidx.compose.ui.platform.LocalContext.current;val config=remember(metric){JSONObject(metric.s("config"))};val fields=config.optJSONArray("fields") ?: JSONArray()
    fun save(incomplete:Boolean){runCatching{
        LocalDate.parse(date);java.time.ZoneId.of(zone);if(value.isNotBlank())decimal(value);val f=JSONObject(values);validateFields(config,f,incomplete)
        val args=obj("metricId" to metric.s("id"),"eventId" to eventId.ifBlank{null},"value" to value.ifBlank{null},"date" to date,"zone" to zone,"fields" to f,"allowIncomplete" to incomplete)
        if(timeMode=="instant"){require(start.isNotBlank()){"请选择发生时刻"};args.put("occurredAt",start)}
        if(timeMode=="interval"){require(start.isNotBlank() && end.isNotBlank()){"请选择起止时刻"};require(java.time.Instant.parse(end)>=java.time.Instant.parse(start)){"结束早于开始"};args.put("startAt",start).put("endAt",end);if(value.isNotBlank())require(decimal(value).compareTo(Units.convert(secondsBetween(java.time.Instant.parse(start),java.time.Instant.parse(end)),"s",config.s("unit")))==0){"时长与起止时刻不一致"}}
        onSave(args)
    }.onFailure{error=it.message ?: "输入有误"}}
    AlertDialog(onDismissRequest=onDismiss,title={Text("记录"+metric.s("title"))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(!config.has("expression"))OutlinedTextField(value,{value=it},label={Text("数值 · "+config.s("unit"))},modifier=Modifier.fillMaxWidth())
        DateInput("发生日期",date){date=it}
        Pick("时间",if(config.s("kind")=="ALLOCATION")listOf("date" to "仅时长","interval" to "起止时段")else listOf("date" to "仅日期","instant" to "具体时刻"),timeMode){timeMode=it}
        if(timeMode!="date"){
            OutlinedTextField(zone,{zone=it},label={Text("时区")},modifier=Modifier.fillMaxWidth())
            OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={runCatching{chooseInstant(context,date,zone){start=it;date=java.time.Instant.parse(it).atZone(java.time.ZoneId.of(zone)).toLocalDate().toString()}}.onFailure{error=it.message.orEmpty()}}){Text(if(start.isBlank())if(timeMode=="interval")"选择开始时刻"else "选择发生时刻"else formatInstant(start,zone))}
            if(timeMode=="interval")OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={runCatching{chooseInstant(context,date,zone){end=it}}.onFailure{error=it.message.orEmpty()}}){Text(if(end.isBlank())"选择结束时刻"else formatInstant(end,zone))}
        }
        if(events.isNotEmpty())Pick("关联事件",listOf("" to "新事件")+events.map{it.s("id") to it.s("raw_input").ifBlank{it.s("local_date","未指定日期")+" · "+it.s("id").take(8)}},eventId){eventId=it}
        fields.objects().forEach{f->val id=f.s("id");DynamicField(f,values[id].orEmpty()){values=values+(id to it)}}
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(onClick={save(false)}){Text("保存")}},dismissButton={Column{TextButton(onClick={save(true)}){Text("保存不完整")};TextButton(onClick=onDismiss){Text("取消")}}})
}
@Composable fun EditFactDialog(f:JSONObject,metric:JSONObject?=null,onDismiss:()->Unit,onSave:(JSONObject)->Unit,onDelete:()->Unit) {
    var value by remember{mutableStateOf(f.s("value"))};var date by remember{mutableStateOf(f.s("local_date"))};var error by remember{mutableStateOf("")};var confirmDelete by remember{mutableStateOf(false)};var clearTime by remember{mutableStateOf(false)};var occurred by remember{mutableStateOf(f.s("occurred_at"))}
    var start by remember{mutableStateOf(f.s("start_at"))};var end by remember{mutableStateOf(f.s("end_at"))}
    val context=androidx.compose.ui.platform.LocalContext.current;val config=metric?.let{JSONObject(it.s("config"))} ?: JSONObject();var fields by remember{mutableStateOf(JSONObject(f.s("fields_json")))}
    AlertDialog(onDismissRequest=onDismiss,title={Text("修改"+f.s("metric_title"))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(!config.has("expression"))OutlinedTextField(value,{value=it},label={Text("数值")},modifier=Modifier.fillMaxWidth());DateInput("发生日期",date){date=it}
        if(f.s("occurred_at").isNotEmpty()){
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(clearTime,{clearTime=it});Text("仅保留日期")}
            if(!clearTime)OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={chooseInstant(context,date,f.s("zone")){occurred=it;date=java.time.Instant.parse(it).atZone(java.time.ZoneId.of(f.s("zone"))).toLocalDate().toString()}}){Text(formatInstant(occurred,f.s("zone")))}
        }
        if(f.s("start_at").isNotEmpty()){
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(clearTime,{clearTime=it});Text("仅保留时长")}
            if(!clearTime){OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={chooseInstant(context,date,f.s("zone")){start=it;date=java.time.Instant.parse(it).atZone(java.time.ZoneId.of(f.s("zone"))).toLocalDate().toString()}}){Text(formatInstant(start,f.s("zone")))};OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={chooseInstant(context,date,f.s("zone")){end=it}}){Text(formatInstant(end,f.s("zone")))}}
        }
        (config.optJSONArray("fields") ?: JSONArray()).objects().forEach{field->val id=field.s("id");DynamicField(field,fields.s(id)){fields=fields.copyJson().put(id,it)}}
        Text("录入 "+formatInstant(f.s("created_at")),style=MaterialTheme.typography.bodySmall);Text("修改 "+formatInstant(f.s("updated_at")),style=MaterialTheme.typography.bodySmall)
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error);TextButton(onClick={confirmDelete=true}){Text("删除此记录")}
    }},confirmButton={TextButton(onClick={runCatching{LocalDate.parse(date);if(value.isNotBlank())decimal(value);validateFields(config,fields);val args=obj("id" to f.s("id"),"value" to value.ifBlank{null},"date" to date,"fields" to fields,"clearTime" to clearTime);if(!clearTime && occurred!=f.s("occurred_at"))args.put("occurredAt",occurred);if(!clearTime && (start!=f.s("start_at") || end!=f.s("end_at"))){require(java.time.Instant.parse(end)>=java.time.Instant.parse(start));args.put("startAt",start).put("endAt",end).remove("value")};onSave(args)}.onFailure{error=it.message ?: "输入有误"}}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("关闭")}})
    if(confirmDelete)AlertDialog(onDismissRequest={confirmDelete=false},title={Text("删除这条记录？")},confirmButton={TextButton(onClick=onDelete){Text("删除")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("取消")}})
}
@Composable fun DateInput(label:String,date:String,onChange:(String)->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={val d=runCatching{LocalDate.parse(date)}.getOrDefault(LocalDate.now());android.app.DatePickerDialog(context,{_,y,m,day->onChange(LocalDate.of(y,m+1,day).toString())},d.year,d.monthValue-1,d.dayOfMonth).show()}){Text(label+"："+date)}
}
fun chooseInstant(context:android.content.Context,date:String,zone:String,onSave:(String)->Unit) {
    val d=LocalDate.parse(date);val z=java.time.ZoneId.of(zone)
    android.app.DatePickerDialog(context,{_,year,month,day->
        android.app.TimePickerDialog(context,{_,hour,minute->val local=java.time.LocalDateTime.of(year,month+1,day,hour,minute);val offsets=z.rules.getValidOffsets(local)
            if(offsets.isEmpty())android.widget.Toast.makeText(context,"这个当地时刻不存在，请重新选择",android.widget.Toast.LENGTH_LONG).show()
            else if(offsets.size==1)onSave(java.time.ZonedDateTime.ofStrict(local,offsets[0],z).toInstant().toString())
            else android.app.AlertDialog.Builder(context).setTitle("选择时区偏移").setItems(offsets.map{it.toString()}.toTypedArray()){_,which->onSave(java.time.ZonedDateTime.ofStrict(local,offsets[which],z).toInstant().toString())}.show()
        },12,0,true).show()
    },d.year,d.monthValue-1,d.dayOfMonth).show()
}
fun methodName(s:String)=mapOf("SUM" to "累计","MEAN" to "均值","WEIGHTED_MEAN" to "加权平均","LAST_SNAPSHOT" to "最新快照","UNION_DURATION" to "时段并集","MEDIAN" to "中位数","MIN" to "最小值","MAX" to "最大值","COUNT" to "样本数")[s] ?: s

@Composable fun CsvImportDialog(objects:List<JSONObject>,onDismiss:()->Unit,onImport:(String?,String,String)->Unit) {
    var metric by remember{mutableStateOf("")};var value by remember{mutableStateOf("value")};var date by remember{mutableStateOf("local_date")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("导入 CSV")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Pick("目标指标",listOf("" to "使用文件中的 metric_id")+objects.filter{it.s("type")=="METRIC"}.map{it.s("id") to it.s("title")},metric){metric=it};OutlinedTextField(value,{value=it},label={Text("数值列名")},modifier=Modifier.fillMaxWidth());OutlinedTextField(date,{date=it},label={Text("日期列名")},modifier=Modifier.fillMaxWidth())
    }},confirmButton={TextButton(enabled=value.isNotBlank() && date.isNotBlank(),onClick={onImport(metric.ifEmpty{null},value,date)}){Text("导入")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable fun TextEventDialog(initial:JSONObject?=null,onDismiss:()->Unit,onSave:(String,String?)->Unit) {
    var text by remember(initial) { mutableStateOf(initial?.s("raw_input").orEmpty()) }
    var date by remember(initial) { mutableStateOf(if(initial==null) LocalDate.now().toString() else initial.s("local_date")) }
    val validDate = date.isBlank() || runCatching { LocalDate.parse(date) }.isSuccess
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(initial==null) "记录文字" else "修改文字记录")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text,{text=it},label={Text("内容")},modifier=Modifier.fillMaxWidth(),minLines=3)
            OutlinedTextField(date,{date=it},label={Text("发生日期（可选）")},modifier=Modifier.fillMaxWidth())
            if (!validDate) Text("请使用 YYYY-MM-DD",color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={Button(enabled=text.isNotBlank() && validDate,onClick={onSave(text.trim(),date.ifBlank{null})}){Text("保存")}},
        dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable fun FinanceDialog(objects:List<JSONObject>,balance:Boolean,onDismiss:()->Unit,onExecute:(String,JSONObject)->Unit) {
    var from by remember{mutableStateOf("")}
    var to by remember{mutableStateOf("")}
    var selected by remember{mutableStateOf<Set<String>>(emptySet())}
    var amount by remember{mutableStateOf("")}
    var date by remember{mutableStateOf(LocalDate.now().toString())}
    val money=objects.filter { item ->
        item.s("type")=="METRIC" && item.optInt("archived")==0 &&
            Units.currency(JSONObject(item.s("config")).s("unit"))=="CNY"
    }
    val snapshots=money.filter{JSONObject(it.s("config")).s("kind")=="SNAPSHOT"}
    val changes=money.filter{JSONObject(it.s("config")).s("kind")=="CHANGE"}
    val validDate=runCatching{LocalDate.parse(date)}.isSuccess
    val validAmount=amount.toBigDecimalOrNull()?.signum()==1
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(balance) "余额" else "转账")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Pick(if(balance) "实测余额" else "转出账户",(if(balance)snapshots else changes).map{it.s("id") to it.s("title")},from){from=it}
            if(balance) {
                Text("关联流水",style=MaterialTheme.typography.titleSmall)
                changes.forEach { item ->
                    val id=item.s("id")
                    Row(Modifier.fillMaxWidth().clickable {
                        selected=if(id in selected) selected-id else selected+id
                    },verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(id in selected,onCheckedChange={checked->selected=if(checked) selected+id else selected-id})
                        Text(item.s("title"),Modifier.weight(1f))
                    }
                }
            } else {
                Pick("转入账户",changes.filter{it.s("id")!=from}.map{it.s("id") to it.s("title")},to){to=it}
                OutlinedTextField(amount,{amount=it},label={Text("金额")},modifier=Modifier.fillMaxWidth())
            }
            OutlinedTextField(date,{date=it},label={Text(if(balance) "截止日期" else "发生日期")},modifier=Modifier.fillMaxWidth())
        }
    },confirmButton={
        val ready=from.isNotEmpty() && validDate && if(balance) selected.isNotEmpty()
            else to.isNotEmpty() && from!=to && validAmount
        Button(enabled=ready,onClick={
            if(balance) onExecute("finance.balance",obj("snapshotMetricId" to from,"changeMetricIds" to JSONArray(selected.toList()),"asOf" to date))
            else onExecute("finance.transfer",obj("fromMetricId" to from,"toMetricId" to to,"amount" to amount,"date" to date))
        }){Text(if(balance) "计算" else "转账")}
    },dismissButton={OutlinedButton(onClick=onDismiss){Text("取消")}})
}
@Composable fun CorrelationDialog(objects:List<JSONObject>,onDismiss:()->Unit,onExecute:(JSONObject)->Unit) {
    var x by remember{mutableStateOf("")};var y by remember{mutableStateOf("")};var from by remember{mutableStateOf(LocalDate.now().minusMonths(3).toString())};var to by remember{mutableStateOf(LocalDate.now().toString())};val metrics=objects.filter{it.s("type")=="METRIC"}
    AlertDialog(onDismissRequest=onDismiss,title={Text("相关性分析")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Pick("指标一",metrics.map{it.s("id") to it.s("title")},x){x=it};Pick("指标二",metrics.map{it.s("id") to it.s("title")},y){y=it};OutlinedTextField(from,{from=it},label={Text("开始日期")},modifier=Modifier.fillMaxWidth());OutlinedTextField(to,{to=it},label={Text("结束日期")},modifier=Modifier.fillMaxWidth())
    }},confirmButton={TextButton(enabled=x.isNotEmpty() && y.isNotEmpty(),onClick={onExecute(obj("xMetricId" to x,"yMetricId" to y,"from" to from,"to" to to))}){Text("分析")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable fun DynamicField(field:JSONObject,value:String,onChange:(String)->Unit) {
    val label=field.s("title",field.s("id"))+if(field.optBoolean("required")) " *" else ""
    when(field.s("type")) {
        "choice"->Pick(label,(field.optJSONArray("options") ?: JSONArray()).let{a->(0 until a.length()).map{a.getString(it) to a.getString(it)}},value,onChange)
        "boolean"->Pick(label,listOf("true" to "是","false" to "否"),value,onChange)
        else->OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth())
    }
}
