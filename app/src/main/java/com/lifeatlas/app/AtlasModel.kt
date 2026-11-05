package com.lifeatlas.app

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

data class AtlasState(
    val busy:Boolean=false,val status:String="",val theme:String="system",val compact:Boolean=false,val themeConfig:String=ThemeDefaults.config().toString(),val themeId:String="lavender",val objects:List<JSONObject> = emptyList(),val facts:List<JSONObject> = emptyList(),
    val operations:List<JSONObject> = emptyList(),val plugins:List<JSONObject> = emptyList(),val timers:List<JSONObject> = emptyList(),
    val historyMetric:String="",val historyFrom:String=java.time.YearMonth.now().atDay(1).toString(),val historyTo:String=LocalDate.now().toString(),val historyOffset:Int=0,val events:List<JSONObject> = emptyList(),val builds:List<JSONObject> = emptyList(),val layouts:List<JSONObject> = emptyList(),val workflows:List<JSONObject> = emptyList(),
    val pending:JSONObject?=null,val result:JSONObject?=null,val revision:Int=0,val image:String?=null
)
class AtlasModel(app:Application):AndroidViewModel(app) {
    val store=AtlasStore(app);val settings=Settings(app);val actions=Actions(store);val planner=Planner(actions)
    private val commandMutex=Mutex()
    private val state=MutableStateFlow(AtlasState());val ui=state.asStateFlow()
    private var historyMetric:String?=null
    private var historyFrom=java.time.YearMonth.now().atDay(1).toString()
    private var historyTo=LocalDate.now().toString()
    private var historyOffset=0
    var draft:String=settings.prefs.getString("draft","") ?: ""
    init {refresh()}
    private fun update(block:(AtlasState)->AtlasState) {state.update(block)}
    fun saveDraft(s:String) {draft=s;settings.prefs.edit().putString("draft",s).apply()}
    fun refresh()=viewModelScope.launch(Dispatchers.IO) {load()}
    private fun load() {update{it.copy(theme=ThemeRepository(store).appearance().s("mode","system"),compact=ThemeRepository(store).appearance().optInt("compact")==1,themeConfig=ThemeRepository(store).current().s("config"),themeId=ThemeRepository(store).current().s("id"),objects=store.objects(all=true),historyMetric=historyMetric.orEmpty(),historyFrom=historyFrom,historyTo=historyTo,historyOffset=historyOffset,facts=store.facts(historyMetric,historyFrom,historyTo,historyOffset),events=store.query("SELECT e.*,(SELECT COUNT(*) FROM atlas_facts f WHERE f.event_id=e.id AND f.deleted=0) fact_count FROM atlas_events e WHERE (e.local_date BETWEEN ? AND ? OR e.local_date IS NULL) ORDER BY e.local_date DESC,e.created_at DESC LIMIT 50 OFFSET ?",arrayOf(historyFrom,historyTo,historyOffset.toString())),operations=store.query("SELECT * FROM atlas_operations ORDER BY created_at DESC LIMIT 40"),plugins=store.query("SELECT p.* FROM atlas_plugins p JOIN (SELECT plugin_id,MAX(version) version FROM atlas_plugins GROUP BY plugin_id) latest ON p.plugin_id=latest.plugin_id AND p.version=latest.version ORDER BY p.created_at DESC LIMIT 100"),timers=store.query("SELECT * FROM atlas_timers WHERE state!='STOPPED'"),builds=store.query("SELECT * FROM atlas_builds ORDER BY created_at DESC LIMIT 30"),layouts=store.query("SELECT * FROM atlas_layout ORDER BY position"),workflows=store.query("SELECT * FROM atlas_workflows"),revision=it.revision+1)} }
    fun work(title:String,block:()->JSONObject)=viewModelScope.launch(Dispatchers.IO) {
        commandMutex.withLock {
        update{it.copy(busy=true,status=title)}
        try {val result=block();update{it.copy(result=result,pending=if(result.s("status")=="PREVIEW") result.getJSONObject("plan") else if(result.has("question")) result.getJSONObject("question") else null,status=result.s("message",if(result.s("status")=="PREVIEW") "请确认操作" else "已完成"))};load()} catch(e:Exception){update{it.copy(status=e.message ?: "操作失败")}} finally{update{it.copy(busy=false)}}
        }
    }
    fun action(id:String,args:JSONObject,title:String=id,confirmed:Boolean=false)=work(title){actions.single(id,args,title,confirmed)}
    fun execute(plan:JSONObject,confirmed:Boolean=false)=work("正在执行"){actions.execute(plan.copyJson().apply{if(s("rawInput").isEmpty())put("rawInput",draft)},confirmed)}
    fun submit(input:String) {
        saveDraft(input)
        work("正在理解需求") {
            if(input.trim().startsWith("{")) return@work actions.execute(JSONObject(input))
            Offline.plan(input,store)?.let{return@work actions.execute(it)}
            val context=obj("appearance" to ThemeRepository(store).appearance(),"currentTheme" to ThemeRepository(store).current(),"objects" to JSONArray(store.objects(all=true).take(200)),"recentOperations" to JSONArray(store.query("SELECT title,result FROM atlas_operations ORDER BY created_at DESC LIMIT 5")),"selectedObjectId" to settings.prefs.getString("selected",""))
            var plan=planner.plan(settings.ai(),input,context,state.value.image)
            var attempt=0
            while(true) {
                if(plan.has("question"))return@work obj("message" to plan.getString("question"),"question" to plan)
                try{update{it.copy(status="正在执行")};return@work actions.execute(plan)}catch(missing:CapabilityMissing){require(++attempt<=2){"能力生成仍需补充信息，输入已保存"};context.put("missingAction",missing.action);context.put("instruction","通过现有通用操作、声明式插件或原生构建实现缺失能力");plan=planner.plan(settings.ai(),input,context,state.value.image)}
            }
            @Suppress("UNREACHABLE_CODE") obj("message" to "已处理")
        }
    }
    fun clearPending() {update{it.copy(pending=null)}}
    fun undo(redo:Boolean=false)=work(if(redo) "正在恢复" else "正在撤销") {obj("message" to ((if(redo) "已恢复：" else "已撤销：")+store.undo(redo)))}
    fun addImage(uri:Uri)=work("正在读取图片") {
        val resolver=getApplication<Application>().contentResolver;val mime=resolver.getType(uri) ?: "image/jpeg";require(mime.startsWith("image/")){"请选择图片"}
        val bytes=resolver.openInputStream(uri)!!.use{it.readNBytes(4_000_001)};require(bytes.size<=4_000_000){"图片需小于4MB"};update{it.copy(image="data:$mime;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP))};obj("message" to "图片已附加，描述需要识别或执行的内容")
    }
    fun clearImage(){update{it.copy(image=null)}}
    fun export(uri:Uri,csv:Boolean=false)=work("正在导出") {getApplication<Application>().contentResolver.openOutputStream(uri)!!.bufferedWriter().use{if(csv) store.exportCsv(it) else store.export(it)};obj("message" to "导出完成")}
    fun restore(uri:Uri)=work("正在恢复备份") {getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use(store::import);obj("message" to "恢复完成，覆盖前的数据已保存为恢复点")}
    fun importCsv(uri:Uri,metric:String?,valueColumn:String,dateColumn:String)=work("正在导入 CSV") {
        val resolver=getApplication<Application>().contentResolver
        val digest=java.security.MessageDigest.getInstance("SHA-256");resolver.openInputStream(uri)!!.use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        digest.update((metric.orEmpty()+"|"+valueColumn+"|"+dateColumn).toByteArray());val hash=digest.digest().joinToString(""){"%02x".format(it)}
        val errors=java.io.File(getApplication<Application>().filesDir,"import-errors.csv")
        val result=errors.bufferedWriter().use{out->resolver.openInputStream(uri)!!.bufferedReader().use{reader->CsvCodec.import(reader,actions,hash,metric,valueColumn,dateColumn,out)}}
        result
    }
    fun restoreRecovery(name:String)=work("正在恢复") {val file=java.io.File(getApplication<Application>().filesDir,name);require(file.parentFile!!.canonicalFile==getApplication<Application>().filesDir.canonicalFile && file.name.startsWith("before-"));file.bufferedReader().use(store::import);obj("message" to "恢复完成")}
    fun exportErrors(uri:Uri)=work("正在导出异常记录") {val f=java.io.File(getApplication<Application>().filesDir,"import-errors.csv");require(f.exists()){ "暂无导入异常报告" };getApplication<Application>().contentResolver.openOutputStream(uri)!!.use{out->f.inputStream().use{it.copyTo(out)}};obj("message" to "异常报告已导出")}
    fun importPlugin(uri:Uri)=work("正在读取功能包") {val text=getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()};require(text.length<1_000_000);val m=JSONObject(text);actions.manifestValid(m);actions.single(if(store.query("SELECT id FROM atlas_plugins WHERE plugin_id=?",arrayOf(m.s("id"))).isEmpty()) "atlas.installPlugin" else "atlas.updatePlugin",obj("manifest" to m),"安装功能包")}
    fun history(metric:String?,from:String,to:String,offset:Int)=work("正在查询") {LocalDate.parse(from);LocalDate.parse(to);require(from<=to){"开始日期晚于结束日期"};historyMetric=metric;historyFrom=from;historyTo=to;historyOffset=offset;obj("message" to "查询完成")}
    suspend fun summary(metric:String,from:String,to:String):JSONObject=withContext(Dispatchers.IO) {store.aggregate(metric,from,to)}
    suspend fun chart(metric:String,from:String,to:String):List<JSONObject> = withContext(Dispatchers.IO) {store.dailySeries(metric,from,to)}
    fun installUpdate(feed:String)=action("atlas.installUpdate",obj("feed" to feed),"正在验证与下载更新")
    fun build(id:String)=action("atlas.submitBuild",obj("id" to id),"正在提交构建")
    fun buildStatus(id:String)=action("atlas.getBuildStatus",obj("id" to id),"正在查询构建")
    fun installBuild(id:String)=action("atlas.installBuild",obj("id" to id),"正在验证签名安装包")
    override fun onCleared(){store.close()}
}
