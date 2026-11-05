package com.lifeatlas.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

fun componentName(type:String)=mapOf("form" to "录入表单","summary" to "统计数值","chart" to "趋势图","history" to "记录列表","navigation" to "导航入口","text" to "文字")[type] ?: type
@Composable fun SearchObjectPicker(label:String,vm:AtlasModel,selected:String,type:String?=null,onSelect:(JSONObject)->Unit) {
    val state by vm.ui.collectAsState();val current=resolvedObject(selected,state,vm);var shown by remember{mutableStateOf(false)}
    OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={shown=true}){Text(label+"："+(current?.s("title") ?: "请选择"))}
    if(shown) {
        var query by remember{mutableStateOf("")};var offset by remember{mutableIntStateOf(0)};var rows by remember{mutableStateOf<List<JSONObject>>(emptyList())}
        LaunchedEffect(query,offset,type,state.revision){kotlinx.coroutines.delay(120);rows=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){val args=mutableListOf("%"+query+"%");if(type!=null)args.add(type);args.add(offset.toString());vm.store.query("SELECT * FROM atlas_objects WHERE archived=0 AND title LIKE ? "+(if(type!=null)"AND type=? " else "")+"ORDER BY title,id LIMIT 50 OFFSET ?",args.toTypedArray())}}
        AlertDialog(onDismissRequest={shown=false},title={Text(label)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(query,{query=it;offset=0},label={Text("搜索")},modifier=Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=300.dp)){items(rows,key={it.s("id")}){o->Column(Modifier.fillMaxWidth().clickable{onSelect(o);shown=false}.padding(vertical=12.dp)){Text(o.s("title"));Text(typeName(o.s("type")),style=MaterialTheme.typography.bodySmall)} }}
            if(rows.isEmpty())Text("暂无匹配对象")
            Row{TextButton(enabled=offset>0,onClick={offset=(offset-50).coerceAtLeast(0)}){Text("上一页")};TextButton(enabled=rows.size==50,onClick={offset+=50}){Text("下一页")}}
        }},confirmButton={TextButton(onClick={shown=false}){Text("关闭")}})
    }
}
@Composable fun PageBuilderDialog(vm:AtlasModel,parent:String?,existing:JSONObject?=null,onDismiss:()->Unit,onSave:(String,JSONArray)->Unit) {
    val state by vm.ui.collectAsState()
    var title by remember(existing?.s("id")){mutableStateOf(existing?.s("title").orEmpty())}
    var components by remember(existing?.s("id")){mutableStateOf(existing?.let{JSONObject(it.s("config")).optJSONArray("components")?.objects()?.map{it.copyJson()}} ?: emptyList())}
    var component by remember{mutableStateOf("form")};var metric by remember{mutableStateOf("")};var target by remember{mutableStateOf("")};var heading by remember{mutableStateOf("")};var text by remember{mutableStateOf("")};var chart by remember{mutableStateOf("line")};var method by remember{mutableStateOf("")};var range by remember{mutableStateOf("CURRENT_MONTH")};var from by remember{mutableStateOf(YearMonth.now().atDay(1).toString())};var to by remember{mutableStateOf(YearMonth.now().atEndOfMonth().toString())};var preview by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    val hasMetric=component in setOf("form","summary","chart","history")
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(existing==null)"新建页面"else "编辑页面")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        OutlinedTextField(title,{title=it},label={Text("页面名称")},modifier=Modifier.fillMaxWidth())
        Text("组件 "+components.size+"/50",style=MaterialTheme.typography.titleMedium)
        components.forEachIndexed{i,c->Column{Text((i+1).toString()+". "+c.s("title").ifBlank{componentName(c.s("component"))});Row{
            TextButton(enabled=i>0,onClick={val list=components.toMutableList();val moved=list.removeAt(i);list.add(i-1,moved);components=list}){Text("上移")}
            TextButton(enabled=i<components.lastIndex,onClick={val list=components.toMutableList();val moved=list.removeAt(i);list.add(i+1,moved);components=list}){Text("下移")}
            TextButton(onClick={components=components.filterIndexed{index,_->index!=i}}){Text("移除")}
        }}}
        HorizontalDivider()
        Pick("组件",listOf("form","summary","chart","history","navigation","text").map{it to componentName(it)},component){component=it}
        if(hasMetric)SearchObjectPicker("指标",vm,metric,"METRIC"){metric=it.s("id")}
        if(component=="navigation")SearchObjectPicker("目标",vm,target){target=it.s("id");if(heading.isBlank())heading=it.s("title")}
        if(component=="text")OutlinedTextField(text,{text=it},label={Text("内容")},modifier=Modifier.fillMaxWidth(),minLines=2)
        else OutlinedTextField(heading,{heading=it},label={Text("标题（可选）")},modifier=Modifier.fillMaxWidth())
        if(component=="chart")Pick("图表",listOf("line" to "折线","bar" to "柱形","heatmap" to "热力图"),chart){chart=it}
        if(component=="summary")Pick("统计",listOf("" to "指标默认规则")+aggregateMethods.map{it to methodName(it)},method){method=it}
        if(component in setOf("summary","chart","history")){
            Pick("时间范围",listOf("CURRENT_MONTH" to "本月","CUSTOM" to "自定义"),range){range=it}
            if(range=="CUSTOM"){DateInput("开始日期",from){from=it};DateInput("结束日期",to){to=it}}
        }
        OutlinedButton(enabled=components.size<50 && (!hasMetric || metric.isNotBlank()) && (component!="navigation" || target.isNotBlank()) && (component!="text" || text.isNotBlank()),modifier=Modifier.fillMaxWidth(),onClick={runCatching{
            val c=obj("component" to component,"title" to heading)
            if(hasMetric)c.put("metricId",metric)
            if(component=="navigation")c.put("objectId",target)
            if(component=="text")c.put("text",text)
            if(component=="chart")c.put("chart",chart)
            if(component=="summary" && method.isNotBlank())c.put("method",method)
            if(range=="CUSTOM" && component in setOf("summary","chart","history")){require(from<=to){"开始日期晚于结束日期"};c.put("from",from).put("to",to)}
            components=components+c;heading="";text="";error=""
        }.onFailure{error=it.message.orEmpty()}}){Text("添加组件")}
        if(components.isNotEmpty())TextButton(onClick={preview=!preview}){Text(if(preview)"收起预览"else "预览页面")}
        if(preview)DynamicScreen(obj("components" to JSONArray(components)),state,vm,onRecord={},onOpen={})
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(enabled=!state.busy && title.isNotBlank() && components.isNotEmpty(),onClick={onSave(title,JSONArray(components))}){Text("保存页面")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
