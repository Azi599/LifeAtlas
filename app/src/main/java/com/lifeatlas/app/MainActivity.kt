package com.lifeatlas.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
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
    private var sandboxImport=false
    private var pluginToExport:String?=null
    private var csvUri by mutableStateOf<Uri?>(null)
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){it?.let{if(errorExport)vm.exportErrors(it) else vm.export(it,csv)}}
    private val csvPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){csvUri=it}
    private val restore=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{vm.restore(it)}}
    private val plugin=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{vm.importPlugin(it,sandboxImport)}}
    private val pluginExport=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val id=pluginToExport
        pluginToExport=null
        if(uri!=null && id!=null) vm.exportPlugin(uri,id)
    }
    private val image=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let{vm.addImage(it)}}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        sandboxImport=savedInstanceState?.getBoolean("sandboxImport") ?: false
        pluginToExport=savedInstanceState?.getString("pluginToExport")
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
                    onPlugin={sandboxImport=false;plugin.launch(arrayOf("application/json","text/plain","*/*"))},
                    onSandboxPlugin={sandboxImport=true;plugin.launch(arrayOf("application/json","text/plain","*/*"))},
                    onPluginExport={id->pluginToExport=id;pluginExport.launch("$id.lifeatlas.json")},
                    onImage={image.launch(arrayOf("image/*"))}
                )
                csvUri?.let{uri->val state by vm.ui.collectAsState();CsvImportDialog(state.objects,onDismiss={csvUri=null},onImport={metric,value,date->vm.importCsv(uri,metric,value,date);csvUri=null})}
            }
        }
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putBoolean("sandboxImport",sandboxImport)
        outState.putString("pluginToExport",pluginToExport)
        super.onSaveInstanceState(outState)
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable fun App(vm:AtlasModel,onExport:(Boolean)->Unit,onCsv:()->Unit,onErrors:()->Unit,onRestore:()->Unit,
    onPlugin:()->Unit,onSandboxPlugin:()->Unit,onPluginExport:(String)->Unit,onImage:()->Unit) {
    val state by vm.ui.collectAsState();val keyboard=WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0;var settingsPanel by rememberSaveable{mutableStateOf("")};var page by rememberSaveable{mutableStateOf("workspace")};var selected by rememberSaveable{mutableStateOf<String?>(null)}
    val settingsSubpage=settingsPanel.isNotEmpty()
    var showImageText by remember{mutableStateOf(false)}
    var quickActions by remember{mutableStateOf(false)}
    var spaceReturn by rememberSaveable{mutableStateOf("settings")}
    val command=state.draft;var create by remember{mutableStateOf(false)};var edit by remember{mutableStateOf<JSONObject?>(null)};var record by remember{mutableStateOf<JSONObject?>(null)};var editingFact by remember{mutableStateOf<JSONObject?>(null)};var restoreConfirm by remember{mutableStateOf(false)};var pluginWizard by remember{mutableStateOf(false)};var workflowDialog by remember{mutableStateOf(false)};var buildDialog by remember{mutableStateOf(false)};var eventDialog by remember{mutableStateOf(false)};var financeDialog by remember{mutableStateOf<String?>(null)};var correlationDialog by remember{mutableStateOf(false)}
    val workspaceListState=rememberLazyListState()
    val historyListState=rememberLazyListState()
    val analyticsListState=rememberLazyListState()
    val settingsListState=rememberLazyListState()
    val spacesListState=rememberLazyListState()
    val listState=when(page){"workspace"->workspaceListState;"history"->historyListState;"analytics"->analyticsListState;"settings"->settingsListState;else->spacesListState}
    val pageAlpha=remember{Animatable(1f)}
    val notices=remember{SnackbarHostState()}
    val focusManager=LocalFocusManager.current
    val keyboardController=LocalSoftwareKeyboardController.current
    LaunchedEffect(state.status,state.busy,state.result) {
        if(!state.busy && state.status.isNotBlank() && (page!="workspace" || state.result?.optBoolean("notice")==true)) {
            notices.currentSnackbarData?.dismiss()
            notices.showSnackbar(state.status)
        }
    }
    LaunchedEffect(selected){spacesListState.scrollToItem(0)}
    LaunchedEffect(state.historyOffset){historyListState.scrollToItem(0)}
    LaunchedEffect(settingsPanel){settingsListState.scrollToItem(0)}
    LaunchedEffect(page) {
        notices.currentSnackbarData?.dismiss()
        quickActions=false
        pageAlpha.snapTo(0.88f)
        pageAlpha.animateTo(1f,animationSpec=tween(160))
    }
    val scope=rememberCoroutineScope()
    fun editById(id:String){scope.launch {val row=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){vm.store.query("SELECT f.*,o.title metric_title FROM atlas_facts f JOIN atlas_objects o ON o.id=f.metric_id WHERE f.id=?",arrayOf(id)).firstOrNull()?.also{f->f.put("value",resolvedRecordValue(f.s("value").takeIf{it.isNotEmpty()}?.let(::decimal),JSONObject(f.s("fields_json")),vm.store.config(f.s("metric_id")))?.toPlainString() ?: JSONObject.NULL)}};editingFact=row}}
    var pageBuilder by remember{mutableStateOf(false)};var editingPage by remember{mutableStateOf<JSONObject?>(null)}
    var manualRecord by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<JSONObject?>(null) }
    var objectCache by remember{mutableStateOf<Map<String,JSONObject>>(emptyMap())}
    var spaceRows by remember{mutableStateOf<List<JSONObject>>(emptyList())};var spaceSearch by rememberSaveable{mutableStateOf("")};var spaceOffset by rememberSaveable{mutableIntStateOf(0)};var archived by rememberSaveable{mutableStateOf(false)}
    val objects=(state.objects+objectCache.values).distinctBy{it.s("id")}
    val chosen=objects.firstOrNull{it.s("id")==selected}
    val analysisLayout=state.analysisLayout.associateBy{it.s("object_id")}
    fun setChartLayout(id:String,position:Int,hidden:Boolean) {
        vm.action("atlas.layout",obj("objectId" to id,"position" to position,"hidden" to hidden),
            if(hidden) "隐藏分析图表" else "更新分析图表位置")
    }
    LaunchedEffect(page,selected,spaceSearch,spaceOffset,archived,state.revision){
        if (page != "spaces") return@LaunchedEffect
        val rows=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
            val args=mutableListOf<String>();val where=if(spaceSearch.isNotBlank()){args.add("%$spaceSearch%");"title LIKE ?"}else if(selected==null)"parent_id IS NULL" else {args.add(selected!!);"parent_id=?"}
            args.add(if(archived) "1" else "0");args.add(spaceOffset.toString());val rows=vm.store.query("SELECT * FROM atlas_objects WHERE $where AND archived=? ORDER BY type,title,id LIMIT 100 OFFSET ?",args.toTypedArray())
            val current=selected?.let{vm.store.one("atlas_objects",it)};rows to current
        }
        spaceRows=rows.first;objectCache=objectCache+(rows.first+listOfNotNull(rows.second)).associateBy{it.s("id")}
    }
    val linked=storeRelations(vm,selected,state.revision)
    fun open(id:String) {if(page!="spaces") spaceReturn=page;selected=id;spaceOffset=0;spaceSearch="";page="spaces";vm.settings.prefs.edit().putString("selected",id).apply()}
    BackHandler(enabled=page!="workspace") {
        when {
            page=="spaces" && selected!=null -> selected=chosen?.s("parent_id")?.ifEmpty{null}
            page=="spaces" -> page=spaceReturn
            page=="settings" && settingsPanel.isNotEmpty() -> settingsPanel=""
            else -> {page="workspace";selected=null}
        }
    }
    LaunchedEffect(state.pending){if(state.pending!=null){page="workspace";selected=null}}
    LaunchedEffect(state.busy,state.result) {if(!state.busy && state.result!=null && page=="workspace")listState.animateScrollToItem(0)}
    LaunchedEffect(state.result) {val result=state.result ?: return@LaunchedEffect;val steps=result.optJSONObject("steps");steps?.keys()?.forEach{key->val r=steps.optJSONObject(key);if(r!=null && r.has("page")){page=r.s("page");selected=r.s("objectId").ifEmpty{null}}}}
    val tabs = listOf("workspace" to "输入", "history" to "时间轴", "analytics" to "分析", "settings" to "我的")
    val currentTab = if (page == "spaces") "settings" else page
    AtlasBackdrop { Scaffold(
        containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onBackground,
        snackbarHost={SnackbarHost(notices)},
        topBar={Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=18.dp,vertical=10.dp)) {
            val heading=when {
                page=="workspace" -> "Life Atlas"
                page=="spaces" -> chosen?.s("title") ?: "空间"
                page=="settings" && settingsSubpage -> mapOf("appearance" to "外观","ai" to "AI 与模型","data" to "数据管理","plugin" to "功能包","build" to "云端构建","updates" to "应用更新","help" to "使用说明")[settingsPanel] ?: "我的"
                else -> tabs.firstOrNull{it.first==page}?.second ?: "Life Atlas"
            }
            Text(heading,style=MaterialTheme.typography.headlineSmall)
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }},
        bottomBar={Column(Modifier.imePadding()) {
            if(page=="workspace") Surface(Modifier.fillMaxWidth().animateContentSize(),shape=RoundedCornerShape(topStart=20.dp,topEnd=20.dp)) {Column(Modifier.padding(horizontal=18.dp,vertical=10.dp)) {
                if(state.status.isNotBlank()) Text(state.status,style=MaterialTheme.typography.bodySmall,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=if(state.error.isNotBlank())MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.fillMaxWidth().padding(bottom=4.dp).testTag("command-status"))
                val canSend = !state.busy && (command.isNotBlank() || state.imageText.isNotBlank())
                fun sendCommand() {
                    if(!canSend) return
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    vm.submit(command)
                }
                OutlinedTextField(command,vm::saveDraft,modifier=Modifier.fillMaxWidth().testTag("command-input"),
                    placeholder={Text("写点什么")},minLines=1,maxLines=3,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),
                    keyboardActions=KeyboardActions(onSend={sendCommand()}))
                Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(enabled=!state.busy,onClick=onImage){Text(if(state.image==null) "图片" else "已附图")}
                    Box {
                        OutlinedButton(enabled=!state.busy,onClick={quickActions=true}){Text("操作")}
                        DropdownMenu(expanded=quickActions,onDismissRequest={quickActions=false},modifier=Modifier.widthIn(min=152.dp,max=220.dp)) {
                            val imageActions:List<Pair<String,()->Unit>> =
                                if(state.image!=null) listOf("移除图片" to {vm.clearImage()}) else emptyList()
                            val actions:List<Pair<String,()->Unit>> = listOf(
                                "手动录入" to {manualRecord=true},
                                "记文字" to {eventDialog=true},
                                "撤销" to {vm.undo();Unit},
                                "恢复" to {vm.undo(true);Unit}
                            ) + imageActions
                            actions.forEach { (title,action) ->
                                OutlinedButton(modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=3.dp),onClick={
                                    quickActions=false
                                    action()
                                }) { Text(title) }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(modifier=Modifier.testTag("command-send"),enabled=canSend,onClick={sendCommand()}){Text(if(state.busy) "处理中" else "发送")}
                }
            }}
            AnimatedVisibility(visible=!keyboard) {
                NavigationBar(modifier=Modifier.testTag("atlas-navigation"),containerColor=MaterialTheme.colorScheme.surface) {tabs.forEachIndexed{i,(id,title)->NavigationBarItem(modifier=Modifier.testTag("tab-$id"),colors=NavigationBarItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.primary,selectedTextColor=MaterialTheme.colorScheme.onSurface,unselectedIconColor=MaterialTheme.colorScheme.onSurfaceVariant,unselectedTextColor=MaterialTheme.colorScheme.onSurfaceVariant,indicatorColor=MaterialTheme.colorScheme.primaryContainer),selected=currentTab==id,onClick={page=id;selected=null;settingsPanel=""},icon={NavGlyph(if(i==0)0 else i+1,currentTab==id)},label={Text(title,maxLines=2,style=MaterialTheme.typography.labelMedium)})}}
            }
        }}
    ) {padding->
        key(page) {
        LazyColumn(Modifier.fillMaxSize().padding(padding).graphicsLayer{alpha=pageAlpha.value}.testTag("atlas-list"),state=listState,contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(if(state.compact)8.dp else 14.dp)) {
            if(state.error.isNotBlank() && page!="workspace") item {Text(state.error,color=MaterialTheme.colorScheme.error)}
            when(page) {
                "workspace" -> {
                    if(state.imageText.isNotBlank())item {Column {TextButton(onClick={showImageText=!showImageText}){Text(if(showImageText)"收起原文" else "识别原文")};if(showImageText)OutlinedTextField(state.imageText,vm::editImageText,label={Text("图片文字")},modifier=Modifier.fillMaxWidth(),minLines=3)}}
                    state.pending?.let {pending->item {PlanPreview(pending,objects=objects,busy=state.busy,error=state.error,onConfirm={vm.execute(it,true)},onCancel=vm::clearPending)}}
                    state.result?.let{result->item{ExecutionResults(result,objects,onOpen={open(it)},onFact={editingFact=it},onRetry={vm.execute(it,true)},onFactId={editById(it)})}}
                }
                "spaces" -> {
                    item{ActionRow{if(selected!=null) OutlinedButton(onClick={selected=chosen?.s("parent_id")?.ifEmpty{null}}){Text("返回上级")} else OutlinedButton(onClick={page=spaceReturn}){Text("返回")};OutlinedButton(onClick={create=true}){Text("新建")};if(chosen!=null) OutlinedButton(onClick={edit=chosen}){Text("管理")}}}
                    item{ActionRow{OutlinedButton(onClick={editingPage=null;pageBuilder=true}){Text("新建页面")};if(chosen?.s("type")=="VIEW" && JSONObject(chosen.s("config")).s("component")=="schema")OutlinedButton(onClick={editingPage=chosen;pageBuilder=true}){Text("编辑页面")}}}
                    if(chosen!=null && analysisComponents(chosen).isNotEmpty()) item {
                        val saved=analysisLayout[chosen.s("id")]
                        val pinned=saved!=null && saved.optInt("hidden")==0
                        OutlinedButton(onClick={
                            val next=(state.analysisLayout.maxOfOrNull{it.optInt("position")} ?: -1)+1
                            setChartLayout(chosen.s("id"),saved?.optInt("position") ?: next,pinned)
                        }){Text(if(pinned) "从分析隐藏" else "固定到分析")}
                    }
                    if(chosen?.s("type")=="METRIC") {
                        item{MetricPanel(chosen,vm,state.revision,onRecord={record=chosen})}
                    } else if(chosen?.s("type")=="VIEW") {
                        val c=JSONObject(chosen.s("config"))
                        if(c.s("component")=="chart" && c.s("chart")=="list") item{
                            val history=obj("component" to "history","title" to chosen.s("title"),"metricId" to c.s("metricId"))
                            DynamicScreen(obj("components" to JSONArray().put(history)),state,vm,onRecord={record=it},onOpen={open(it)},onFact={editingFact=it})
                        }
                        else if(c.s("component")=="chart") item{val metric=resolvedObject(c.s("metricId"),state,vm);if(metric!=null)MetricPanel(metric,vm,state.revision,onRecord={record=metric},chartType=c.s("chart","line"))}
                        else if(c.s("component")=="report") item{ReportPanel(c,state,vm)}
                        else if(c.s("component")=="schema") item{DynamicScreen(c,state,vm,onRecord={record=it},onOpen={open(it)},onFact={editingFact=it})}
                    }
                    item{OutlinedTextField(spaceSearch,{spaceSearch=it;spaceOffset=0},label={Text("搜索对象")},modifier=Modifier.fillMaxWidth());Row{Checkbox(archived,{archived=it;spaceOffset=0});Text("归档",Modifier.padding(top=12.dp))}}
                    val children=spaceRows
                    items(children,key={it.s("id")}){o->ObjectCard(o,onOpen={open(o.s("id"))},onEdit={edit=o})}
                    items(linked,key={"linked-${it.s("id")}"}){o->ObjectCard(o,onOpen={open(o.s("id"))},onEdit={edit=o})}
                    item{Row{if(spaceOffset>0)TextButton(onClick={spaceOffset=(spaceOffset-100).coerceAtLeast(0)}){Text("上一页")};if(spaceRows.size==100)TextButton(onClick={spaceOffset+=100}){Text("下一页")}}}
                    if(children.isEmpty() && chosen?.s("type") !in setOf("METRIC","VIEW")) item {Text("暂无内容")}
                }
                "history" -> timelineItems(vm,state,onFact={editingFact=it},onEvent={editingEvent=it})
                "analytics" -> {
                    items(state.timers,key={it.s("id")}){TimerCard(it,objects,vm)}
                    val pinned = state.analysisLayout.filter{it.optInt("hidden")==0}.mapNotNull { layout ->
                        objects.firstOrNull{it.s("id")==layout.s("object_id") && it.optInt("archived")==0 && analysisComponents(it).isNotEmpty()}
                    }
                    items(pinned,key={"analysis-"+it.s("id")}) { view ->
                        val id=view.s("id")
                        val first=pinned.first().s("id")
                        PinnedAnalysisCard(view,state,vm,onPinFirst=if(id==first) null else ({
                            val top=state.analysisLayout.minOfOrNull{it.optInt("position")} ?: 0
                            setChartLayout(id,top-1,false)
                        }),onHide={setChartLayout(id,analysisLayout[id]?.optInt("position") ?: 0,true)})
                    }
                    item{Analytics(vm,state)}
                    item{ActionRow {OutlinedButton(onClick={financeDialog="transfer"}){Text("转账")};OutlinedButton(onClick={financeDialog="balance"}){Text("余额")};OutlinedButton(onClick={correlationDialog=true}){Text("相关性分析")}}}
                    state.result?.let{r->item{ExecutionResults(r,objects,onOpen={open(it)},onFact={editingFact=it},onRetry={vm.execute(it,true)},onFactId={editById(it)})}}
                }
                "settings" -> {
                    if(!settingsSubpage) item("space-entry") {
                        OutlinedButton(onClick={selected=null;spaceSearch="";spaceOffset=0;archived=false;spaceReturn="settings";page="spaces"},modifier=Modifier.fillMaxWidth()){Text("空间管理",Modifier.weight(1f));Text("›")}
                    }
                    item("settings-panel"){SettingsPanel(vm=vm,panel=settingsPanel,onPanelChange={settingsPanel=it},
                        onExport=onExport,onCsv=onCsv,onErrors=onErrors,onRestore={restoreConfirm=true},onPlugin=onPlugin)}
                    if(settingsPanel=="plugin") {
                    item{
                        Text("插件中心",style=MaterialTheme.typography.titleLarge)
                        ActionRow {
                            OutlinedButton(onClick={pluginWizard=true}){Text("创建功能包")}
                            OutlinedButton(onClick=onSandboxPlugin){Text("导入到测试区")}
                        }
                    }
                    items(state.plugins.distinctBy{it.s("plugin_id")},key={it.s("id")}) { plugin ->
                        OutlinedCard(Modifier.fillMaxWidth()){
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                val manifest=JSONObject(plugin.s("manifest"))
                                val id=plugin.s("plugin_id")
                                val active=plugin.s("status")=="ACTIVE"
                                Text(manifest.s("title"),style=MaterialTheme.typography.titleMedium)
                                Text("版本 ${plugin.optInt("version")} · ${pluginState(plugin.s("status"))}")
                                ActionRow {
                                    OutlinedButton(onClick={onPluginExport(id)}){Text("导出模板")}
                                    if(plugin.optInt("version")>1) OutlinedButton(onClick={vm.action("atlas.rollbackPlugin",
                                        obj("pluginId" to id),"回退插件")}){Text("回退")}
                                    OutlinedButton(onClick={vm.action("atlas.setPluginStatus",
                                        obj("pluginId" to id,"enabled" to !active),"切换插件")}){Text(if(active) "停用" else "启用")}
                                }
                            }
                        }
                    }
                    item{Text("工作流",style=MaterialTheme.typography.titleLarge);TextButton(onClick={workflowDialog=true}){Text("新建工作流")}}
                    items(state.workflows,key={it.s("id")}){w->Column{Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(w.s("title"),Modifier.weight(1f));Switch(w.optInt("enabled")==1,{vm.action("atlas.setWorkflowStatus",obj("id" to w.s("id"),"enabled" to it),"更新工作流")})};TextButton(onClick={vm.action("atlas.deleteWorkflow",obj("id" to w.s("id")),"删除工作流")}){Text("删除")}}}
                    }
                    if(settingsPanel=="build") {
                    item{Text("应用扩展",style=MaterialTheme.typography.titleLarge);TextButton(onClick={buildDialog=true}){Text("新增原生能力")}}
                    items(state.builds,key={it.s("id")}){b->BuildCard(b,vm)}
                    }
                }
            }
            item{Spacer(Modifier.height(12.dp))}
        }
        }
    }
    }
    financeDialog?.let{mode->FinanceDialog(objects,balance=mode=="balance",onDismiss={financeDialog=null},onExecute={id,args->vm.action(id,args,if(mode=="balance") "计算余额" else "转账");financeDialog=null})}
    if(correlationDialog)CorrelationDialog(objects,onDismiss={correlationDialog=false},onExecute={vm.action("atlas.correlation",it,"相关性分析");correlationDialog=false})
    if (manualRecord) {
        val metrics = remember(objects) { objects.filter { it.s("type") == "METRIC" && it.optInt("archived")==0 } }
        AlertDialog(onDismissRequest={manualRecord=false},title={Text("手动录入")},text={
            if (metrics.isEmpty()) Text("暂无指标，可在空间管理中创建。")
            else Column(Modifier.verticalScroll(rememberScrollState())) {
                metrics.forEach { m -> OutlinedButton(onClick={record=m;manualRecord=false},modifier=Modifier.fillMaxWidth()){Text(m.s("title"))} }
            }
        },confirmButton={TextButton(onClick={manualRecord=false}){Text("关闭")}})
    }
    val eventToEdit = editingEvent
    if(eventDialog || eventToEdit!=null) TextEventDialog(initial=eventToEdit,onDismiss={eventDialog=false;editingEvent=null},onSave={text,date->
        val args=obj("text" to text,"date" to date)
        if (eventToEdit == null) vm.action("atlas.createEvent",args,"记录事件")
        else vm.action("atlas.editEvent",args.put("id",eventToEdit.s("id")),"修改事件")
        eventDialog=false;editingEvent=null
    })
    if(pluginWizard) PluginWizard(onDismiss={pluginWizard=false},onPreview={vm.action("atlas.installPlugin",obj("manifest" to it),"预览${it.s("title")}");pluginWizard=false})
    if(workflowDialog)WorkflowDialog(objects,onDismiss={workflowDialog=false},onSave={title,spec->vm.action("atlas.workflow",obj("title" to title,"spec" to spec),"创建工作流");workflowDialog=false})
    if(buildDialog)BuildRequestDialog(onDismiss={buildDialog=false},onSave={vm.action("atlas.requestBuild",it,"创建扩展任务");buildDialog=false})
    if(pageBuilder)PageBuilderDialog(vm,selected,editingPage,onDismiss={pageBuilder=false},onSave={title,components->val existing=editingPage;if(existing==null)vm.action("atlas.createView",obj("title" to title,"parentId" to selected,"components" to components),"创建页面")else vm.action("atlas.updateObject",obj("id" to existing.s("id"),"title" to title,"config" to obj("component" to "schema","components" to components)),"修改页面");pageBuilder=false})
    if(create) ObjectDialog(parent=selected,objects=objects,onDismiss={create=false},onSave={type,title,config->vm.action("atlas.createObject",obj("type" to type,"title" to title,"parentId" to selected,"config" to config),"创建$title");create=false})
    edit?.let{o->ManageDialog(o,objects,vm,onDismiss={edit=null})}
    record?.let { m ->
        val events by produceState<List<JSONObject>>(emptyList(),m.s("id")) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                vm.store.query("SELECT id, raw_input FROM atlas_events WHERE raw_input != '' ORDER BY local_date DESC,id DESC LIMIT 50")
            }
        }
        RecordDialog(m,events=events,onDismiss={record=null},onSave={args->vm.action("atlas.record",args,"记录${m.s("title")}");record=null})
    }
    editingFact?.let{f->EditFactDialog(f,metric=objects.firstOrNull{it.s("id")==f.s("metric_id")} ?: f.s("metric_config").takeIf{it.isNotBlank()}?.let{obj("config" to it)},onDismiss={editingFact=null},onSave={a->vm.action("atlas.editRecord",a,"修改记录");editingFact=null},onDelete={vm.action("atlas.deleteRecord",obj("id" to f.s("id")),"删除记录");editingFact=null})}
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
fun kindName(s:String)=mapOf("SNAPSHOT" to "逐次测量","CHANGE" to "逐笔累计","ALLOCATION" to "计时")[s] ?: s
@Composable fun TimerCard(timer:JSONObject,objects:List<JSONObject>,vm:AtlasModel) {
    var seconds by remember{mutableLongStateOf(0)}
    LaunchedEffect(timer.toString()){while(true){val periods=JSONArray(timer.s("periods")).objects();seconds=periods.sumOf{Duration.between(Instant.parse(it.s("start")),Instant.parse(it.s("end"))).seconds}+(if(timer.s("running_at").isNotEmpty()) if(!timer.isNull("elapsed_start") && timer.optInt("boot_count",-1)==android.provider.Settings.Global.getInt(vm.store.appContext.contentResolver,"boot_count",0)) ((android.os.SystemClock.elapsedRealtime()-timer.getLong("elapsed_start"))/1000).coerceAtLeast(0) else Duration.between(Instant.parse(timer.s("running_at")),Instant.now()).seconds.coerceAtLeast(0) else 0);kotlinx.coroutines.delay(1000)}}
    OutlinedCard{Column(Modifier.padding(16.dp)){Text(objects.firstOrNull{it.s("id")==timer.s("metric_id")}?.s("title") ?: "活动");Text("${seconds/3600}:${(seconds/60%60).toString().padStart(2,'0')}:${(seconds%60).toString().padStart(2,'0')}",style=MaterialTheme.typography.headlineMedium);Row{TextButton(onClick={vm.action(if(timer.s("state")=="RUNNING") "time.pause" else "time.resume",obj("timerId" to timer.s("id")),"更新计时")}){Text(if(timer.s("state")=="RUNNING") "暂停" else "恢复")};TextButton(onClick={vm.action("time.stop",obj("timerId" to timer.s("id")),"保存计时")}){Text("结束并保存")}}}}
}
@Composable fun BuildCard(b:JSONObject,vm:AtlasModel) {
    val context=LocalContext.current
    OutlinedCard{Column(Modifier.padding(16.dp)){Text(JSONObject(b.s("request")).s("title","原生能力请求"));Text(buildState(b.s("status")));if(b.s("detail").isNotBlank()) Text(b.s("detail"));if(b.s("remote_id").isEmpty())TextButton(onClick={vm.build(b.s("id"))}){Text("提交云端构建")} else TextButton(onClick={vm.buildStatus(b.s("id"))}){Text("更新构建状态")};if(b.s("status") in setOf("FAILED","INTERRUPTED"))TextButton(onClick={vm.action("atlas.retryBuild",obj("buildId" to b.s("id")),"重试构建")}){Text("重新构建")};if(b.s("status")=="READY_FOR_REVIEW" && JSONObject(b.s("request")).s("baselineCommit").isNotBlank())TextButton(onClick={vm.action("atlas.requestRollback",obj("buildId" to b.s("id")),"建立回退构建")}){Text("构建先前版本")};val preview=JSONObject(b.s("request")).s("previewUrl");if(preview.startsWith("https://"))TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(preview)))}){Text("查看变更")};if(b.s("status")=="READY_FOR_REVIEW" && b.s("remote_id").startsWith("gh:") && BuildConfig.CHANNEL=="standalone")Button(onClick={vm.installBuild(b.s("id"))}){Text("验证并安装")};if(b.s("artifact_url").startsWith("https://"))TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(b.s("artifact_url"))))}){Text("查看构建产物")}}}
}

fun actionName(id:String)=mapOf("atlas.createObject" to "创建对象","atlas.updateObject" to "修改对象","atlas.record" to "记录数据","atlas.editRecord" to "修改记录","atlas.deleteRecord" to "删除记录","atlas.restoreRecord" to "恢复记录","atlas.deleteObject" to "归档","atlas.query" to "查询记录","atlas.aggregate" to "统计数据","atlas.report" to "生成报告","atlas.createView" to "创建页面","atlas.layout" to "调整分析图表","atlas.rollbackPlugin" to "回退插件","atlas.requestBuild" to "建立扩展任务","finance.transfer" to "转账","time.start" to "开始计时","time.stop" to "结束计时")[id] ?: "执行操作"
fun pluginState(state:String)=mapOf("ACTIVE" to "已启用","DISABLED" to "已停用","SUPERSEDED" to "历史版本")[state] ?: state

fun buildState(s:String)=mapOf("AWAITING_SERVICE" to "待提交构建","SUBMITTED" to "已提交","QUEUED" to "排队中","GENERATING" to "生成源码中","COMMITTING" to "提交源码中","BUILDING" to "编译与测试中","WAITING_APPROVAL" to "等待发行确认","READY_FOR_REVIEW" to "更新包已就绪","FAILED" to "构建失败","INTERRUPTED" to "任务已中断")[s] ?: s

fun formatInstant(value:String,zone:String=java.time.ZoneId.systemDefault().id):String=runCatching{java.time.Instant.parse(value).atZone(java.time.ZoneId.of(zone)).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))}.getOrDefault(value)
