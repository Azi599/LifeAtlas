package com.lifeatlas.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.*

val atlasColors=lightColorScheme(primary=Color(0xff68538a),secondary=Color(0xff52645b),background=Color(0xfffaf9fc),surface=Color(0xfffaf9fc),onSurface=Color(0xff25222b))
class MainActivity:ComponentActivity() {
    private lateinit var vm:AtlasModel
    private var csv=false
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){it?.let{vm.export(it,csv)}}
    private val restore=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{vm.restore(it)}}
    private val plugin=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{vm.importPlugin(it)}}
    private val image=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{vm.addImage(it)}}
    private val speech=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let{vm.saveDraft(it);vm.submit(it)}}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);vm=ViewModelProvider(this)[AtlasModel::class.java]
        setContent {MaterialTheme(colorScheme=atlasColors) {App(vm,onExport={isCsv->csv=isCsv;export.launch(if(isCsv) "LifeAtlas-records.csv" else "LifeAtlas-backup.jsonl")},onRestore={restore.launch(arrayOf("*/*"))},onPlugin={plugin.launch(arrayOf("application/json","text/plain","*/*"))},onImage={image.launch(arrayOf("image/*"))},onVoice={val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN");if(intent.resolveActivity(packageManager)!=null) speech.launch(intent) else vm.work("语音输入"){obj("message" to "请点击输入法的麦克风进行语音输入")}})}}
    }
}

@Composable fun App(vm:AtlasModel,onExport:(Boolean)->Unit,onRestore:()->Unit,onPlugin:()->Unit,onImage:()->Unit,onVoice:()->Unit) {
    val state by vm.ui.collectAsState();var page by rememberSaveable{mutableStateOf("workspace")};var selected by rememberSaveable{mutableStateOf<String?>(null)}
    var command by rememberSaveable{mutableStateOf(vm.draft)};var create by remember{mutableStateOf(false)};var edit by remember{mutableStateOf<JSONObject?>(null)};var record by remember{mutableStateOf<JSONObject?>(null)};var editingFact by remember{mutableStateOf<JSONObject?>(null)};var restoreConfirm by remember{mutableStateOf(false)}
    val chosen=state.objects.firstOrNull{it.s("id")==selected}
    fun open(id:String) {selected=id;page="spaces";vm.settings.prefs.edit().putString("selected",id).apply()}
    BackHandler(enabled=selected!=null || page!="workspace") {if(selected!=null) selected=chosen?.s("parent_id")?.ifEmpty{null} else page="workspace"}
    LaunchedEffect(state.result) {val result=state.result ?: return@LaunchedEffect;val steps=result.optJSONObject("steps");steps?.keys()?.forEach{key->val r=steps.optJSONObject(key);if(r!=null && r.has("page")){page=r.s("page");selected=r.s("objectId").ifEmpty{null}}}
    Scaffold(
        topBar={Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=18.dp,vertical=10.dp)) {
            Text("Life Atlas",style=MaterialTheme.typography.headlineSmall)
            if(page=="workspace") {
                OutlinedTextField(command,{command=it;vm.saveDraft(it)},modifier=Modifier.fillMaxWidth().padding(top=10.dp),label={Text("记录、查询，或创造一个新功能")},minLines=1,maxLines=4)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Button(enabled=!state.busy && command.isNotBlank(),onClick={vm.submit(command)}){Text(if(state.busy) "处理中" else "执行")};TextButton(onClick=onVoice){Text("语音")};TextButton(onClick=onImage){Text(if(state.image==null) "图片" else "已附图")}}
                if(state.image!=null) TextButton(onClick=vm::clearImage){Text("移除图片")}
            }
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }},
        bottomBar={NavigationBar {listOf("workspace" to "工作区","spaces" to "空间","history" to "记录","analytics" to "分析","settings" to "设置").forEachIndexed{i,(id,title)->NavigationBarItem(selected=page==id,onClick={page=id;selected=null},icon={Text(listOf("◎","▦","≡","↗","⚙")[i])},label={Text(title,maxLines=1)})}}
    ) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if(state.status.isNotBlank()) item {Text(state.status,style=MaterialTheme.typography.bodyMedium)}
            when(page) {
                "workspace" -> {
                    item {Row {TextButton(enabled=!state.busy,onClick={vm.undo()}){Text("撤销")};TextButton(enabled=!state.busy,onClick={vm.undo(true)}){Text("恢复")};TextButton(onClick={create=true}){Text("手动创建")}}}
                    state.pending?.let {pending->item {PlanPreview(pending,onConfirm={vm.execute(it,true)},onCancel=vm::clearPending)}}
                    item {Text("我的空间",style=MaterialTheme.typography.titleLarge)}
                    val layouts=state.layouts.associateBy{it.s("object_id")}
                    val cards=state.objects.filter{it.s("type") in setOf("PROJECT","DASHBOARD","VIEW") && layouts[it.s("id")]?.optInt("hidden")!=1}.sortedBy{layouts[it.s("id")]?.optInt("position") ?: 999}.take(8)
                    if(cards.isEmpty()) item {Text("从一个项目开始。你可以描述需求，也可以手动建立指标和页面。")}
                    items(cards,key={it.s("id")}) {o->ObjectCard(o,onOpen={open(o.s("id"))},onEdit={edit=o})}
                    if(state.timers.isNotEmpty()) item{Text("进行中的活动",style=MaterialTheme.typography.titleMedium)}
                    items(state.timers,key={it.s("id")}) {TimerCard(it,state.objects,vm)}
                    item{Text("操作历史",style=MaterialTheme.typography.titleMedium)}
                    items(state.operations.take(8),key={it.s("id")}) {op->Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp)){Text(op.s("title"));Text(op.s("created_at").take(19).replace('T',' '),style=MaterialTheme.typography.bodySmall);Text(if(op.optInt("undone")==1) "已撤销" else "已提交",style=MaterialTheme.typography.labelMedium);val result=JSONObject(op.s("result"));if(result.has("steps")) {val ids=linkedSetOf<String>();val steps=result.getJSONObject("steps");steps.keys().forEach{k->steps.optJSONObject(k)?.s("id")?.let(ids::add)};ids.filter{id->state.objects.any{it.s("id")==id}}.forEach{id->TextButton(onClick={open(id)}){Text("打开结果")}}}}}
                }
                "spaces" -> {
                    item{Text(chosen?.s("title") ?: "我的空间",style=MaterialTheme.typography.headlineSmall)}
                    item{Row{if(selected!=null) TextButton(onClick={selected=chosen?.s("parent_id")?.ifEmpty{null}}){Text("返回上级")};TextButton(onClick={create=true}){Text("新建")};if(chosen!=null) TextButton(onClick={edit=chosen}){Text("管理")}}}
                    if(chosen?.s("type")=="METRIC") {
                        item{MetricPanel(chosen,vm,state.revision,onRecord={record=chosen})}
                    } else if(chosen?.s("type")=="VIEW") {
                        val c=JSONObject(chosen.s("config"))
                        if(c.s("component")=="chart") {val metric=state.objects.firstOrNull{it.s("id")==c.s("metricId")};if(metric!=null)item{MetricPanel(metric,vm,state.revision,onRecord={record=metric},chartType=c.s("chart","line"))}}
                        else if(c.s("component")=="report") {val results=c.optJSONArray("results") ?: JSONArray();items(results.objects()){r->Card{Column(Modifier.padding(16.dp)){Text(state.objects.firstOrNull{it.s("id")==r.s("metricId")}?.s("title") ?: "指标");Text("${r.s("value","暂无数据")} ${r.s("unit")}",style=MaterialTheme.typography.headlineSmall);Text("样本 ${r.optLong("count")} · ${r.s("method")}");Text("${r.s("from")} 至 ${r.s("to")}")}}}}
                    }
                    val children=state.objects.filter{it.s("parent_id").ifEmpty{null}==selected}
                    items(children,key={it.s("id")}){o->ObjectCard(o,onOpen={open(o.s("id"))},onEdit={edit=o})}
                    val linked=storeRelations(vm,selected,state.revision);items(linked,key={"linked-${it.s("id")}"}){o->ObjectCard(o,onOpen={open(o.s("id"))},onEdit={edit=o})}
                    if(children.isEmpty() && chosen?.s("type") !in setOf("METRIC","VIEW")) item {Text("这里还没有内容，点“新建”添加目录、项目或指标。")}
                }
                "history" -> {
                    item{Text("记录",style=MaterialTheme.typography.headlineSmall)}
                    item{HistoryFilter(vm,state)}
                    items(state.facts,key={it.s("id")}){f->Card(Modifier.fillMaxWidth().clickable{editingFact=f}){Column(Modifier.padding(16.dp)){Text(f.s("metric_title"),style=MaterialTheme.typography.titleMedium);Text("${f.s("value","未填写")} · ${f.s("local_date")}");Text(if(f.s("precision")=="DATE") "仅日期" else f.s("occurred_at").ifEmpty{f.s("start_at")},style=MaterialTheme.typography.bodySmall);val fields=JSONObject(f.s("fields_json"));if(fields.length()>0) Text(fields.toString(),style=MaterialTheme.typography.bodySmall)}}}
                    if(state.facts.isEmpty())item{Text("所选范围没有记录")}
                }
                "analytics" -> {
                    item{Text("分析",style=MaterialTheme.typography.headlineSmall)}
                    item{Analytics(vm,state)}
                }
                "settings" -> {
                    item{Text("设置",style=MaterialTheme.typography.headlineSmall)}
                    item{SettingsPanel(vm,onExport,onRestore={restoreConfirm=true},onPlugin=onPlugin)}
                    item{Text("插件中心",style=MaterialTheme.typography.titleLarge)}
                    items(state.plugins.distinctBy{it.s("plugin_id")},key={it.s("id")}){p->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){val m=JSONObject(p.s("manifest"));Text(m.s("title"),style=MaterialTheme.typography.titleMedium);Text("版本 ${p.optInt("version")} · ${p.s("status")}");TextButton(onClick={vm.action("atlas.setPluginStatus",obj("pluginId" to p.s("plugin_id"),"enabled" to (p.s("status")!="ACTIVE")),"切换插件")}){Text(if(p.s("status")=="ACTIVE") "停用" else "启用")}}}}
                    item{Text("工作流",style=MaterialTheme.typography.titleLarge)}
                    items(state.workflows,key={it.s("id")}){Text(it.s("title"))}
                    item{Text("应用扩展与更新",style=MaterialTheme.typography.titleLarge)}
                    items(state.builds,key={it.s("id")}){b->BuildCard(b,vm)}
                }
            }
            item{Spacer(Modifier.height(12.dp))}
        }
    }
    if(create) ObjectDialog(parent=selected,objects=state.objects,onDismiss={create=false},onSave={type,title,config->vm.action("atlas.createObject",obj("type" to type,"title" to title,"parentId" to selected,"config" to config),"创建$title");create=false})
    edit?.let{o->ManageDialog(o,state.objects,vm,onDismiss={edit=null})}
    record?.let{m->RecordDialog(m,onDismiss={record=null},onSave={args->vm.action("atlas.record",args,"记录${m.s("title")}");record=null})}
    editingFact?.let{f->EditFactDialog(f,onDismiss={editingFact=null},onSave={a->vm.action("atlas.editRecord",a,"修改记录");editingFact=null},onDelete={vm.action("atlas.deleteRecord",obj("id" to f.s("id")),"删除记录");editingFact=null})}
    if(restoreConfirm) AlertDialog(onDismissRequest={restoreConfirm=false},title={Text("恢复备份")},text={Text("选择备份后将覆盖当前数据，并自动保存覆盖前的恢复点。")},confirmButton={TextButton(onClick={restoreConfirm=false;onRestore()}){Text("选择备份")}},dismissButton={TextButton(onClick={restoreConfirm=false}){Text("取消")}})
}

@Composable fun storeRelations(vm:AtlasModel,id:String?,revision:Int):List<JSONObject> {
    var linked by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    LaunchedEffect(id,revision){linked=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){if(id==null) emptyList() else vm.store.query("SELECT o.* FROM atlas_objects o JOIN atlas_relations r ON r.to_id=o.id WHERE r.from_id=? AND o.archived=0 LIMIT 100",arrayOf(id))}}
    return linked
}
@Composable fun ObjectCard(o:JSONObject,onOpen:()->Unit,onEdit:()->Unit) {OutlinedCard(Modifier.fillMaxWidth().clickable(onClick=onOpen)){Column(Modifier.padding(16.dp)){Text(o.s("title"),style=MaterialTheme.typography.titleMedium);Row{Text(typeName(o.s("type")),style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f));TextButton(onClick=onEdit,contentPadding=PaddingValues(0.dp)){Text("管理")}}}}}
fun typeName(s:String)=mapOf("WORKSPACE" to "工作空间","FOLDER" to "目录","PROJECT" to "项目","METRIC" to "指标","VIEW" to "页面","DASHBOARD" to "仪表盘","WORKFLOW" to "工作流")[s] ?: s
fun kindName(s:String)=mapOf("SNAPSHOT" to "基础数据","CHANGE" to "真实变化","ALLOCATION" to "时间分配")[s] ?: s
@Composable fun PlanPreview(plan:JSONObject,onConfirm:(JSONObject)->Unit,onCancel:()->Unit) {
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(plan.s("question",plan.s("title","操作预览")),style=MaterialTheme.typography.titleMedium)
        if(plan.has("question")) (plan.optJSONArray("options") ?: JSONArray()).objects().forEach{choice->OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={onConfirm(choice.getJSONObject("plan"))}){Text(choice.s("title"))}}
        else {
            (plan.optJSONArray("steps") ?: JSONArray()).objects().forEach{step->val a=step.optJSONObject("args") ?: JSONObject();val m=a.optJSONObject("manifest");Text(if(m!=null) "创建功能：${m.s("title")}" else "${step.s("actionId")} · ${a.s("title")}");if(m!=null)(m.optJSONArray("metrics") ?: JSONArray()).objects().forEach{Text("${it.s("title")} · ${kindName(it.s("kind"))} · ${it.s("unit")}")}}
            Button(onClick={onConfirm(plan)}){Text("确认执行")}
        }
        TextButton(onClick=onCancel){Text("取消")}
    }}
}
@Composable fun TimerCard(timer:JSONObject,objects:List<JSONObject>,vm:AtlasModel) {
    var seconds by remember{mutableLongStateOf(0)}
    LaunchedEffect(timer.toString()){while(true){val periods=JSONArray(timer.s("periods")).objects();seconds=periods.sumOf{Duration.between(Instant.parse(it.s("start")),Instant.parse(it.s("end"))).seconds}+(if(timer.s("running_at").isNotEmpty()) Duration.between(Instant.parse(timer.s("running_at")),Instant.now()).seconds else 0);kotlinx.coroutines.delay(1000)}}
    OutlinedCard{Column(Modifier.padding(16.dp)){Text(objects.firstOrNull{it.s("id")==timer.s("metric_id")}?.s("title") ?: "活动");Text("${seconds/3600}:${(seconds/60%60).toString().padStart(2,'0')}:${(seconds%60).toString().padStart(2,'0')}",style=MaterialTheme.typography.headlineMedium);Row{TextButton(onClick={vm.action(if(timer.s("state")=="RUNNING") "time.pause" else "time.resume",obj("timerId" to timer.s("id")),"更新计时")}){Text(if(timer.s("state")=="RUNNING") "暂停" else "恢复")};TextButton(onClick={vm.action("time.stop",obj("timerId" to timer.s("id")),"保存计时")}){Text("结束并保存")}}}}
}
@Composable fun BuildCard(b:JSONObject,vm:AtlasModel) {
    val context=LocalContext.current
    OutlinedCard{Column(Modifier.padding(16.dp)){Text(JSONObject(b.s("request")).s("title","原生能力请求"));Text(b.s("status"));if(b.s("detail").isNotBlank()) Text(b.s("detail"));if(b.s("remote_id").isEmpty())TextButton(onClick={vm.build(b.s("id"))}){Text("提交云端构建")} else TextButton(onClick={vm.buildStatus(b.s("id"))}){Text("更新构建状态")};if(b.s("artifact_url").startsWith("https://"))TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(b.s("artifact_url"))))}){Text("打开更新包")}}}
}
