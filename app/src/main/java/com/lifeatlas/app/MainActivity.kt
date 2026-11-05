package com.lifeatlas.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.launch

class MainActivity:ComponentActivity() {
    private lateinit var vm:AtlasModel
    private var csv=false
    private var errorExport=false
    private var csvUri by mutableStateOf<Uri?>(null)
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){it?.let{if(errorExport)vm.exportErrors(it) else vm.export(it,csv)}}
    private val csvPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){csvUri=it}
    private val restore=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{vm.restore(it)}}
    private val plugin=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{vm.importPlugin(it)}}
    private val image=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{vm.addImage(it)}}
    private val speech=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let{vm.saveDraft(it);vm.submit(it)}}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm=ViewModelProvider(this)[AtlasModel::class.java]
        setContent {
            val appState by vm.ui.collectAsState()
            val dark=when(appState.theme){"dark"->true;"light"->false;else->androidx.compose.foundation.isSystemInDarkTheme()}
            AtlasTheme(JSONObject(appState.themeConfig),dark,appState.compact) {
                val foreground=MaterialTheme.colorScheme.onBackground
                val navigationForeground=MaterialTheme.colorScheme.onSurface
                val barBackground=LocalAtlasTokens.current.gradient.firstOrNull()?.second ?: MaterialTheme.colorScheme.background
                val navigationBackground=MaterialTheme.colorScheme.surface
                SideEffect {
                    val transparent=android.graphics.Color.TRANSPARENT
                    enableEdgeToEdge(statusBarStyle=if(foreground.luminance()<.5f)androidx.activity.SystemBarStyle.light(transparent,transparent)else androidx.activity.SystemBarStyle.dark(transparent),navigationBarStyle=if(navigationForeground.luminance()<.5f)androidx.activity.SystemBarStyle.light(transparent,transparent)else androidx.activity.SystemBarStyle.dark(transparent))
                    val status=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    val navigation=android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    val flags=(if(foreground.luminance()<.5f)status else 0) or (if(navigationForeground.luminance()<.5f)navigation else 0)
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(barBackground.toArgb()))
                    window.statusBarColor=barBackground.toArgb();window.navigationBarColor=navigationBackground.toArgb();window.isStatusBarContrastEnforced=false
                    androidx.core.view.ViewCompat.requestApplyInsets(window.decorView)
                    window.insetsController?.setSystemBarsAppearance(flags,status or navigation)
                }
                App(vm,
                    onExport={isCsv->errorExport=false;csv=isCsv;export.launch(if(isCsv) "LifeAtlas-records.csv" else "LifeAtlas-backup.jsonl")},
                    onCsv={csvPicker.launch(arrayOf("text/*","application/csv","*/*"))},
                    onErrors={errorExport=true;export.launch("LifeAtlas-import-errors.csv")},
                    onRestore={restore.launch(arrayOf("*/*"))},
                    onPlugin={plugin.launch(arrayOf("application/json","text/plain","*/*"))},
                    onImage={image.launch(arrayOf("image/*"))},
                    onVoice={
                        val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN")
                        if(intent.resolveActivity(packageManager)!=null) speech.launch(intent)
                        else vm.work("语音输入"){obj("message" to "请点击输入法的麦克风进行语音输入")}
                    }
                )
                csvUri?.let{uri->val state by vm.ui.collectAsState();CsvImportDialog(state.objects,onDismiss={csvUri=null},onImport={metric,value,date->vm.importCsv(uri,metric,value,date);csvUri=null})}
            }
        }
    }
}

@Composable fun App(vm:AtlasModel,onExport:(Boolean)->Unit,onCsv:()->Unit,onErrors:()->Unit,onRestore:()->Unit,onPlugin:()->Unit,onImage:()->Unit,onVoice:()->Unit) {
    val state by vm.ui.collectAsState();val keyboard=WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0;var settingsSubpage by remember{mutableStateOf(false)};var page by rememberSaveable{mutableStateOf("workspace")};var selected by rememberSaveable{mutableStateOf<String?>(null)}
    var command by rememberSaveable{mutableStateOf(vm.draft)};var create by remember{mutableStateOf(false)};var edit by remember{mutableStateOf<JSONObject?>(null)};var record by remember{mutableStateOf<JSONObject?>(null)};var editingFact by remember{mutableStateOf<JSONObject?>(null)};var restoreConfirm by remember{mutableStateOf(false)};var pluginWizard by remember{mutableStateOf(false)};var workflowDialog by remember{mutableStateOf(false)};var buildDialog by remember{mutableStateOf(false)};var eventDialog by remember{mutableStateOf(false)};var financeDialog by remember{mutableStateOf<String?>(null)};var correlationDialog by remember{mutableStateOf(false)}
    val listState=rememberLazyListState()
    LaunchedEffect(page,selected){listState.scrollToItem(0)}
    val scope=rememberCoroutineScope()
    fun editById(id:String){scope.launch {val row=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){vm.store.query("SELECT f.*,o.title metric_title FROM atlas_facts f JOIN atlas_objects o ON o.id=f.metric_id WHERE f.id=?",arrayOf(id)).firstOrNull()?.also{f->if(f.s("value").isNotEmpty())f.put("value",normalizedValue(decimal(f.s("value")),JSONObject(f.s("fields_json")),vm.store.config(f.s("metric_id"))).toPlainString())}};editingFact=row}}
    var timeline by rememberSaveable{mutableStateOf(false)}
    var objectCache by remember{mutableStateOf<Map<String,JSONObject>>(emptyMap())}
    var spaceRows by remember{mutableStateOf<List<JSONObject>>(emptyList())};var spaceSearch by rememberSaveable{mutableStateOf("")};var spaceOffset by rememberSaveable{mutableIntStateOf(0)};var archived by rememberSaveable{mutableStateOf(false)}
    val objects=(state.objects+objectCache.values).distinctBy{it.s("id")}
    val chosen=objects.firstOrNull{it.s("id")==selected}
    LaunchedEffect(selected,spaceSearch,spaceOffset,archived,state.revision){
        val rows=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
            val args=mutableListOf<String>();val where=if(spaceSearch.isNotBlank()){args.add("%$spaceSearch%");"title LIKE ?"}else if(selected==null)"parent_id IS NULL" else {args.add(selected!!);"parent_id=?"}
            args.add(if(archived) "1" else "0");args.add(spaceOffset.toString());val rows=vm.store.query("SELECT * FROM atlas_objects WHERE $where AND archived=? ORDER BY type,title,id LIMIT 100 OFFSET ?",args.toTypedArray())
            val current=selected?.let{vm.store.one("atlas_objects",it)};rows to current
        }
        spaceRows=rows.first;objectCache=objectCache+(rows.first+listOfNotNull(rows.second)).associateBy{it.s("id")}
    }
    val linked=storeRelations(vm,selected,state.revision)
    fun open(id:String) {selected=id;spaceOffset=0;spaceSearch="";page="spaces";vm.settings.prefs.edit().putString("selected",id).apply()}
    BackHandler(enabled=selected!=null || page!="workspace") {if(selected!=null) selected=chosen?.s("parent_id")?.ifEmpty{null} else page="workspace"}
    LaunchedEffect(state.pending){if(state.pending!=null){page="workspace";selected=null}}
    LaunchedEffect(state.result) {val result=state.result ?: return@LaunchedEffect;val steps=result.optJSONObject("steps");steps?.keys()?.forEach{key->val r=steps.optJSONObject(key);if(r!=null && r.has("page")){page=r.s("page");selected=r.s("objectId").ifEmpty{null}}}}
    AtlasBackdrop { Scaffold(
        containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onBackground,
        topBar={Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=18.dp,vertical=10.dp)) {
            Text("Life Atlas",style=MaterialTheme.typography.headlineSmall)
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }},
        bottomBar={Column(Modifier.imePadding()) {
            if(page=="workspace") Surface(Modifier.fillMaxWidth()) {Column(Modifier.padding(horizontal=18.dp,vertical=8.dp)) {
                OutlinedTextField(command,{command=it;vm.saveDraft(it)},modifier=Modifier.fillMaxWidth().padding(top=10.dp),label={Text("输入")},minLines=1,maxLines=4)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Button(enabled=!state.busy && command.isNotBlank(),onClick={vm.submit(command)}){Text(if(state.busy) "处理中" else "执行")};TextButton(onClick=onVoice){Text("语音")};TextButton(onClick=onImage){Text(if(state.image==null) "图片" else "已附图")}}
                if(state.image!=null) TextButton(onClick=vm::clearImage){Text("移除图片")}
            }}
            if(!keyboard)NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {listOf("workspace" to "首页","spaces" to "空间","history" to "记录","analytics" to "分析","settings" to "我的").forEachIndexed{i,(id,title)->NavigationBarItem(colors=NavigationBarItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.primary,selectedTextColor=MaterialTheme.colorScheme.onSurface,unselectedIconColor=MaterialTheme.colorScheme.onSurfaceVariant,unselectedTextColor=MaterialTheme.colorScheme.onSurfaceVariant,indicatorColor=MaterialTheme.colorScheme.primaryContainer),selected=page==id,onClick={page=id;selected=null},icon={NavGlyph(i,page==id)},label={Text(title,maxLines=2,style=MaterialTheme.typography.labelSmall)})}}
        }}
    ) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("atlas-list"),state=listState,contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(if(state.compact)8.dp else 14.dp)) {
            if(state.status.isNotBlank()) item {Text(state.status,style=MaterialTheme.typography.bodyMedium)}
            when(page) {
                "workspace" -> {
                    item {Row {TextButton(enabled=!state.busy,onClick={vm.undo()}){Text("撤销")};TextButton(enabled=!state.busy,onClick={vm.undo(true)}){Text("恢复")};TextButton(onClick={create=true}){Text("手动创建")}}}
                    state.pending?.let {pending->item {PlanPreview(pending,onConfirm={vm.execute(it,true)},onCancel=vm::clearPending)}}
                    state.result?.let{result->item{ExecutionResults(result,objects,onOpen={open(it)},onFact={editingFact=it},onRetry={vm.execute(it,true)},onFactId={editById(it)})}}
                    item {Text("我的空间",style=MaterialTheme.typography.titleLarge)}
                    val layouts=state.layouts.associateBy{it.s("object_id")}
                    val cards=objects.filter{it.s("type") in setOf("PROJECT","DASHBOARD","VIEW") && layouts[it.s("id")]?.optInt("hidden")!=1}.sortedBy{layouts[it.s("id")]?.optInt("position") ?: 999}.take(8)
                    if(cards.isEmpty()) item {Text("暂无项目")}
                    items(cards,key={it.s("id")}) {o->ObjectCard(o,onOpen={open(o.s("id"))},onEdit={edit=o})}
                    if(state.timers.isNotEmpty()) item{Text("进行中的活动",style=MaterialTheme.typography.titleMedium)}
                    items(state.timers,key={it.s("id")}) {TimerCard(it,objects,vm)}
                    item{Text("操作历史",style=MaterialTheme.typography.titleMedium)}
                    items(state.operations.take(8),key={it.s("id")}) {op->Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(14.dp)){Text(op.s("title"));Text(formatInstant(op.s("created_at")),style=MaterialTheme.typography.bodySmall);Text(if(op.optInt("undone")>0) "已撤销" else "已提交",style=MaterialTheme.typography.labelMedium);val result=JSONObject(op.s("result"));if(result.has("steps")) {val ids=linkedSetOf<String>();val steps=result.getJSONObject("steps");steps.keys().forEach{k->steps.optJSONObject(k)?.s("id")?.let(ids::add)};ids.filter{id->objects.any{it.s("id")==id}}.forEach{id->TextButton(onClick={open(id)}){Text("打开结果")}}}}}}
                }
                "spaces" -> {
                    if(chosen?.s("type") !in setOf("METRIC","VIEW"))item{Text(chosen?.s("title") ?: "我的空间",style=MaterialTheme.typography.headlineSmall)}
                    item{Row{if(selected!=null) TextButton(onClick={selected=chosen?.s("parent_id")?.ifEmpty{null}}){Text("返回上级")};TextButton(onClick={create=true}){Text("新建")};if(chosen!=null) TextButton(onClick={edit=chosen}){Text("管理")}}}
                    if(chosen?.s("type")=="METRIC") {
                        item{MetricPanel(chosen,vm,state.revision,onRecord={record=chosen})}
                    } else if(chosen?.s("type")=="VIEW") {
                        val c=JSONObject(chosen.s("config"))
                        if(c.s("component")=="chart") item{val metric=resolvedObject(c.s("metricId"),state,vm);if(metric!=null)MetricPanel(metric,vm,state.revision,onRecord={record=metric},chartType=c.s("chart","line"))}
                        else if(c.s("component")=="report") item{ReportPanel(c,state,vm)}
                        else if(c.s("component")=="schema") item{DynamicScreen(c,state,vm,onRecord={record=it},onOpen={open(it)})}
                    }
                    item{OutlinedTextField(spaceSearch,{spaceSearch=it;spaceOffset=0},label={Text("搜索对象")},modifier=Modifier.fillMaxWidth());Row{Checkbox(archived,{archived=it;spaceOffset=0});Text("归档",Modifier.padding(top=12.dp))}}
                    val children=spaceRows
                    items(children,key={it.s("id")}){o->ObjectCard(o,onOpen={open(o.s("id"))},onEdit={edit=o})}
                    items(linked,key={"linked-${it.s("id")}"}){o->ObjectCard(o,onOpen={open(o.s("id"))},onEdit={edit=o})}
                    item{Row{if(spaceOffset>0)TextButton(onClick={spaceOffset=(spaceOffset-100).coerceAtLeast(0)}){Text("上一页")};if(spaceRows.size==100)TextButton(onClick={spaceOffset+=100}){Text("下一页")}}}
                    if(children.isEmpty() && chosen?.s("type") !in setOf("METRIC","VIEW")) item {Text("暂无内容")}
                }
                "history" -> {
                    item{Pick("显示",listOf("records" to "记录","timeline" to "时间轴"),if(timeline)"timeline" else "records"){timeline=it=="timeline"}}
                    item{TextButton(onClick={eventDialog=true}){Text("记录文字事件")};HistoryFilter(vm,state)}
                    if(timeline)item{TimelinePanel(vm,state,onFact={editingFact=it})}
                    if(!timeline)items(state.events.filter{it.optInt("fact_count")==0},key={"event-${it.s("id")}"}){event->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text(event.s("raw_input"));Text(event.s("local_date","日期未指定"),style=MaterialTheme.typography.bodySmall)}}}
                    if(!timeline)items(state.facts,key={it.s("id")}){f->Card(Modifier.fillMaxWidth().testTag("record-item").clickable{editingFact=f}){Column(Modifier.padding(16.dp)){Text(f.s("metric_title"),style=MaterialTheme.typography.titleMedium);Text("${f.s("value","未填写")} · ${f.s("local_date")}");Text(if(f.s("precision")=="DATE") "仅日期" else if(f.s("precision")=="INTERVAL") formatInstant(f.s("start_at"),f.s("zone"))+" — "+formatInstant(f.s("end_at"),f.s("zone")) else formatInstant(f.s("occurred_at"),f.s("zone")),style=MaterialTheme.typography.bodySmall);val fields=JSONObject(f.s("fields_json"));val defs=objects.firstOrNull{it.s("id")==f.s("metric_id")}?.let{JSONObject(it.s("config")).optJSONArray("fields")};defs?.objects()?.filter{fields.has(it.s("id"))}?.forEach{Text("${it.s("title")}: ${fields.s(it.s("id"))}",style=MaterialTheme.typography.bodySmall)}}}}
                    if(state.facts.isEmpty())item{Text("所选范围没有记录")}
                }
                "analytics" -> {
                    item{Text("分析",style=MaterialTheme.typography.headlineSmall)}
                    item{Column {OutlinedButton(onClick={financeDialog="transfer"}){Text("转账")};OutlinedButton(onClick={financeDialog="balance"}){Text("余额")};OutlinedButton(onClick={correlationDialog=true}){Text("相关性分析")}}}
                    item{Analytics(vm,state)}
                    state.result?.let{r->item{ExecutionResults(r,objects,onOpen={open(it)},onFact={editingFact=it},onRetry={vm.execute(it,true)},onFactId={editById(it)})}}
                }
                "settings" -> {
                    item{Text("我的",style=MaterialTheme.typography.headlineSmall)}
                    item{SettingsPanel(vm,onExport,onCsv,onErrors,onRestore={restoreConfirm=true},onPlugin=onPlugin,onSubpage={settingsSubpage=it})}
                    if(!settingsSubpage) {
                    item{Text("插件中心",style=MaterialTheme.typography.titleLarge);TextButton(onClick={pluginWizard=true}){Text("创建功能包")}}
                    items(state.plugins.distinctBy{it.s("plugin_id")},key={it.s("id")}){p->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){val m=JSONObject(p.s("manifest"));Text(m.s("title"),style=MaterialTheme.typography.titleMedium);Text("版本 ${p.optInt("version")} · ${pluginState(p.s("status"))}");TextButton(onClick={vm.action("atlas.rollbackPlugin",obj("pluginId" to p.s("plugin_id")),"回退插件")}){Text("回退")};TextButton(onClick={vm.action("atlas.setPluginStatus",obj("pluginId" to p.s("plugin_id"),"enabled" to (p.s("status")!="ACTIVE")),"切换插件")}){Text(if(p.s("status")=="ACTIVE") "停用" else "启用")}}}}
                    item{Text("工作流",style=MaterialTheme.typography.titleLarge);TextButton(onClick={workflowDialog=true}){Text("新建工作流")}}
                    items(state.workflows,key={it.s("id")}){w->Column{Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(w.s("title"),Modifier.weight(1f));Switch(w.optInt("enabled")==1,{vm.action("atlas.setWorkflowStatus",obj("id" to w.s("id"),"enabled" to it),"更新工作流")})};TextButton(onClick={vm.action("atlas.deleteWorkflow",obj("id" to w.s("id")),"删除工作流")}){Text("删除")}}}
                    item{Text("应用扩展与更新",style=MaterialTheme.typography.titleLarge);TextButton(onClick={buildDialog=true}){Text("新增原生能力")}}
                    items(state.builds,key={it.s("id")}){b->BuildCard(b,vm)}
                    }
                }
            }
            item{Spacer(Modifier.height(12.dp))}
        }
    }
    }
    financeDialog?.let{mode->FinanceDialog(objects,balance=mode=="balance",onDismiss={financeDialog=null},onExecute={id,args->vm.action(id,args,if(mode=="balance") "计算余额" else "转账");financeDialog=null})}
    if(correlationDialog)CorrelationDialog(objects,onDismiss={correlationDialog=false},onExecute={vm.action("atlas.correlation",it,"相关性分析");correlationDialog=false})
    if(eventDialog)TextEventDialog(onDismiss={eventDialog=false},onSave={text,date->vm.action("atlas.createEvent",obj("text" to text,"date" to date),"记录事件");eventDialog=false})
    if(pluginWizard) PluginWizard(onDismiss={pluginWizard=false},onPreview={vm.action("atlas.installPlugin",obj("manifest" to it),"预览${it.s("title")}");pluginWizard=false})
    if(workflowDialog)WorkflowDialog(objects,onDismiss={workflowDialog=false},onSave={title,spec->vm.action("atlas.workflow",obj("title" to title,"spec" to spec),"创建工作流");workflowDialog=false})
    if(buildDialog)BuildRequestDialog(onDismiss={buildDialog=false},onSave={vm.action("atlas.requestBuild",it,"创建扩展任务");buildDialog=false})
    if(create) ObjectDialog(parent=selected,objects=objects,onDismiss={create=false},onSave={type,title,config->vm.action("atlas.createObject",obj("type" to type,"title" to title,"parentId" to selected,"config" to config),"创建$title");create=false})
    edit?.let{o->ManageDialog(o,objects,vm,onDismiss={edit=null})}
    record?.let{m->RecordDialog(m,events=state.events,onDismiss={record=null},onSave={args->vm.action("atlas.record",args,"记录${m.s("title")}");record=null})}
    editingFact?.let{f->EditFactDialog(f,metric=objects.firstOrNull{it.s("id")==f.s("metric_id")},onDismiss={editingFact=null},onSave={a->vm.action("atlas.editRecord",a,"修改记录");editingFact=null},onDelete={vm.action("atlas.deleteRecord",obj("id" to f.s("id")),"删除记录");editingFact=null})}
    if(restoreConfirm) AlertDialog(onDismissRequest={restoreConfirm=false},title={Text("恢复备份")},text={Text("选择备份后将覆盖当前数据，并自动保存覆盖前的恢复点。")},confirmButton={TextButton(onClick={restoreConfirm=false;onRestore()}){Text("选择备份")}},dismissButton={TextButton(onClick={restoreConfirm=false}){Text("取消")}})
}

@Composable fun storeRelations(vm:AtlasModel,id:String?,revision:Int):List<JSONObject> {
    var linked by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    LaunchedEffect(id,revision){linked=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){if(id==null) emptyList() else vm.store.query("SELECT o.* FROM atlas_objects o JOIN atlas_relations r ON r.to_id=o.id WHERE r.from_id=? AND o.archived=0 LIMIT 100",arrayOf(id))}}
    return linked
}
@Composable fun ObjectCard(o:JSONObject,onOpen:()->Unit,onEdit:()->Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick=onOpen).padding(vertical=8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)){Text(o.s("title"),style=MaterialTheme.typography.titleMedium);Text(typeName(o.s("type")),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            TextButton(onClick=onEdit){Text("管理")}
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
    }
}
fun typeName(s:String)=mapOf("WORKSPACE" to "工作空间","FOLDER" to "目录","PROJECT" to "项目","METRIC" to "指标","VIEW" to "页面","DASHBOARD" to "仪表盘","WORKFLOW" to "工作流")[s] ?: s
fun kindName(s:String)=mapOf("SNAPSHOT" to "基础数据","CHANGE" to "真实变化","ALLOCATION" to "时间分配")[s] ?: s
@Composable fun PlanPreview(plan:JSONObject,onConfirm:(JSONObject)->Unit,onCancel:()->Unit) {
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(plan.s("question",plan.s("title","操作预览")),style=MaterialTheme.typography.titleMedium)
        if(plan.has("question")) (plan.optJSONArray("options") ?: JSONArray()).objects().forEach{choice->OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={onConfirm(choice.getJSONObject("plan"))}){Text(choice.s("title"))}}
        else {
            (plan.optJSONArray("steps") ?: JSONArray()).objects().forEach{step->val a=step.optJSONObject("args") ?: JSONObject();val m=a.optJSONObject("manifest");Text(if(m!=null) "创建功能：${m.s("title")}" else "${actionName(step.s("actionId"))} ${a.s("title")}");if(m!=null){(m.optJSONArray("metrics") ?: JSONArray()).objects().forEach{Text("${it.s("title")} · ${kindName(it.s("kind"))} · ${it.s("unit")}")};FeaturePreview(m)}}
            (plan.optJSONArray("impact") ?: JSONArray()).objects().forEach{impact->Text(impact.s("title")+" · "+impact.optLong("count")+" 条记录");if(impact.s("oldUnit")!=impact.s("newUnit"))Text(impact.s("oldUnit")+" → "+impact.s("newUnit")+" × "+impact.s("factor"))}
            Button(onClick={onConfirm(plan)}){Text("确认执行")}
        }
        TextButton(onClick=onCancel){Text("取消")}
    }}
}
@Composable fun TimerCard(timer:JSONObject,objects:List<JSONObject>,vm:AtlasModel) {
    var seconds by remember{mutableLongStateOf(0)}
    LaunchedEffect(timer.toString()){while(true){val periods=JSONArray(timer.s("periods")).objects();seconds=periods.sumOf{Duration.between(Instant.parse(it.s("start")),Instant.parse(it.s("end"))).seconds}+(if(timer.s("running_at").isNotEmpty()) if(!timer.isNull("elapsed_start") && timer.optInt("boot_count",-1)==android.provider.Settings.Global.getInt(vm.store.appContext.contentResolver,"boot_count",0)) ((android.os.SystemClock.elapsedRealtime()-timer.getLong("elapsed_start"))/1000).coerceAtLeast(0) else Duration.between(Instant.parse(timer.s("running_at")),Instant.now()).seconds.coerceAtLeast(0) else 0);kotlinx.coroutines.delay(1000)}}
    OutlinedCard{Column(Modifier.padding(16.dp)){Text(objects.firstOrNull{it.s("id")==timer.s("metric_id")}?.s("title") ?: "活动");Text("${seconds/3600}:${(seconds/60%60).toString().padStart(2,'0')}:${(seconds%60).toString().padStart(2,'0')}",style=MaterialTheme.typography.headlineMedium);Row{TextButton(onClick={vm.action(if(timer.s("state")=="RUNNING") "time.pause" else "time.resume",obj("timerId" to timer.s("id")),"更新计时")}){Text(if(timer.s("state")=="RUNNING") "暂停" else "恢复")};TextButton(onClick={vm.action("time.stop",obj("timerId" to timer.s("id")),"保存计时")}){Text("结束并保存")}}}}
}
@Composable fun BuildCard(b:JSONObject,vm:AtlasModel) {
    val context=LocalContext.current
    OutlinedCard{Column(Modifier.padding(16.dp)){Text(JSONObject(b.s("request")).s("title","原生能力请求"));Text(buildState(b.s("status")));if(b.s("detail").isNotBlank()) Text(b.s("detail"));if(b.s("remote_id").isEmpty())TextButton(onClick={vm.build(b.s("id"))}){Text("提交云端构建")} else TextButton(onClick={vm.buildStatus(b.s("id"))}){Text("更新构建状态")};if(b.s("status") in setOf("FAILED","INTERRUPTED"))TextButton(onClick={vm.action("atlas.retryBuild",obj("buildId" to b.s("id")),"重试构建")}){Text("重新构建")};if(b.s("status")=="READY_FOR_REVIEW" && JSONObject(b.s("request")).s("baselineCommit").isNotBlank())TextButton(onClick={vm.action("atlas.requestRollback",obj("buildId" to b.s("id")),"建立回退构建")}){Text("构建先前版本")};val preview=JSONObject(b.s("request")).s("previewUrl");if(preview.startsWith("https://"))TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(preview)))}){Text("查看变更")};if(b.s("status")=="READY_FOR_REVIEW" && b.s("remote_id").startsWith("gh:") && BuildConfig.CHANNEL=="standalone")Button(onClick={vm.installBuild(b.s("id"))}){Text("验证并安装")};if(b.s("artifact_url").startsWith("https://"))TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(b.s("artifact_url"))))}){Text("查看构建产物")}}}
}

fun actionName(id:String)=mapOf("atlas.createObject" to "创建对象","atlas.updateObject" to "修改对象","atlas.record" to "记录数据","atlas.editRecord" to "修改记录","atlas.deleteRecord" to "删除记录","atlas.query" to "查询记录","atlas.aggregate" to "统计数据","atlas.report" to "生成报告","atlas.createView" to "创建页面","atlas.layout" to "调整首页","atlas.rollbackPlugin" to "回退插件","atlas.requestBuild" to "建立扩展任务","finance.transfer" to "转账","time.start" to "开始计时","time.stop" to "结束计时")[id] ?: "执行操作"
fun pluginState(state:String)=mapOf("ACTIVE" to "已启用","DISABLED" to "已停用","SUPERSEDED" to "历史版本")[state] ?: state

fun buildState(s:String)=mapOf("AWAITING_SERVICE" to "待提交构建","SUBMITTED" to "已提交","QUEUED" to "排队中","GENERATING" to "生成源码中","COMMITTING" to "提交源码中","BUILDING" to "编译与测试中","WAITING_APPROVAL" to "等待发行确认","READY_FOR_REVIEW" to "更新包已就绪","FAILED" to "构建失败","INTERRUPTED" to "任务已中断")[s] ?: s

fun formatInstant(value:String,zone:String=java.time.ZoneId.systemDefault().id):String=runCatching{java.time.Instant.parse(value).atZone(java.time.ZoneId.of(zone)).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))}.getOrDefault(value)
