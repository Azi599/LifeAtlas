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
    val busy:Boolean=false,val status:String="",val theme:String="system",val compact:Boolean=false,val themeConfig:String=ThemeDefaults.config().toString(),val themeId:String="lavender",val objects:List<JSONObject> = emptyList(),val timeline:List<JSONObject> = emptyList(),
    val plugins:List<JSONObject> = emptyList(),val timers:List<JSONObject> = emptyList(),
    val historyMetric:String="",val historyFrom:String="",val historyTo:String="",val historyOffset:Int=0,val analysisLayout:List<JSONObject> = emptyList(),val builds:List<JSONObject> = emptyList(),val workflows:List<JSONObject> = emptyList(),
    val pending:JSONObject?=null,val result:JSONObject?=null,val revision:Int=0,val image:String?=null,val imageText:String="",val imageLayout:String="[]",val error:String="",val draft:String=""
)
class AtlasModel(app:Application):AndroidViewModel(app) {
    val store=AtlasStore(app);val settings=Settings(app);val actions=Actions(store);val planner=Planner(actions)
    private val commandMutex=Mutex()
    private val submitting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val state=MutableStateFlow(AtlasState(draft=settings.prefs.getString("draft", "").orEmpty()));val ui=state.asStateFlow()
    private var historyMetric:String?=null
    private var historyFrom=""
    private var historyTo=""
    private var historyOffset=0
    val draft: String get() = state.value.draft
    init {refresh()}
    private fun update(block:(AtlasState)->AtlasState) {state.update(block)}
    @Synchronized
    fun saveDraft(text: String) {
        update { it.copy(draft = text) }
        settings.prefs.edit().putString("draft", text).apply()
    }

    @Synchronized
    private fun clearSubmittedDraft(input: String) {
        if (draft == input) saveDraft("")
    }
    fun refresh()=viewModelScope.launch(Dispatchers.IO) {load()}
    private fun load() {update{it.copy(theme=ThemeRepository(store).appearance().s("mode","system"),compact=ThemeRepository(store).appearance().optInt("compact")==1,themeConfig=ThemeRepository(store).current().s("config"),themeId=ThemeRepository(store).current().s("id"),objects=store.objects(all=true),historyMetric=historyMetric.orEmpty(),historyFrom=historyFrom,historyTo=historyTo,historyOffset=historyOffset,timeline=store.timeline(historyMetric,historyFrom,historyTo,historyOffset),analysisLayout=store.query("SELECT * FROM atlas_layout ORDER BY position,id"),plugins=store.query("SELECT p.* FROM atlas_plugins p JOIN (SELECT plugin_id,MAX(version) version FROM atlas_plugins GROUP BY plugin_id) latest ON p.plugin_id=latest.plugin_id AND p.version=latest.version ORDER BY p.created_at DESC LIMIT 100"),timers=store.query("SELECT * FROM atlas_timers WHERE state!='STOPPED'"),builds=store.query("SELECT * FROM atlas_builds ORDER BY created_at DESC LIMIT 30"),workflows=store.query("SELECT * FROM atlas_workflows"),revision=it.revision+1)} }
    fun work(title:String,block:()->JSONObject)=viewModelScope.launch(Dispatchers.IO) {
        commandMutex.withLock {
        update{it.copy(busy=true,status=title,error="")}
        try {val result=block();update{it.copy(result=result,pending=if(result.s("status")=="PREVIEW") result.getJSONObject("plan") else if(result.has("question")) result.getJSONObject("question") else null,status=result.s("message",if(result.s("status")=="PREVIEW") "请确认操作" else "已完成"))};load()} catch(e:Exception){update{it.copy(status="操作失败："+(e.message?.take(160) ?: "请重试"),error=e.message?.take(300) ?: "操作失败")}} finally{update{it.copy(busy=false)}}
        }
    }
    fun action(id:String,args:JSONObject,title:String=id,confirmed:Boolean=false)=work(title){actions.single(id,args,title,confirmed)}
    fun execute(plan:JSONObject,confirmed:Boolean=false)=work("正在保存"){
        val command=plan.copyJson().apply{if(s("rawInput").isEmpty())put("rawInput",draft)}
        if(command.optBoolean("ai") || command.optBoolean("review")) actions.validateProposedPlan(command)
        val fromChoice = command.optBoolean("fromChoice")
        command.remove("fromChoice")
        if (fromChoice && requiresReview(command)) {
            return@work obj("status" to "PREVIEW", "plan" to command, "message" to "请核对后保存")
        }
        require(command.optJSONArray("steps")?.objects().orEmpty().none(PlanReview::metricSelectionPending)) {
            "请选择每笔记录的归属"
        }
        val result = actions.execute(command, confirmed && !fromChoice)
        if (result.s("status") == "DONE" && (command.optBoolean("ai") || command.optBoolean("local"))) {
            if (command.optBoolean("imageImport")) update { it.copy(image = null, imageText = "", imageLayout = "[]") }
            clearSubmittedDraft(command.s("rawInput"))
        }
        result
    }
    private fun planInput(input:String,source:String="",importOnly:Boolean=false):JSONObject {
        val available=store.objects(all=true)
        val context=obj("appearance" to ThemeRepository(store).appearance(),"objects" to JSONArray(available.take(200)),
            "objectsTruncated" to (available.size>200),"selectedObjectId" to settings.prefs.getString("selected",""),"currentTask" to state.value.pending)
        if(source.isNotBlank())context.put("imageText",source).put("imageLayout",JSONArray(state.value.imageLayout)).put("imageImport",importOnly)
        var attempt=0
        while(true) {
            val plan=planner.plan(settings.ai(),input,context)
            if(plan.has("question"))return obj("message" to plan.getString("question"),"question" to plan)
            if(source.isNotBlank() || requiresReview(plan)) {
                return obj("status" to "PREVIEW", "plan" to plan,
                    "message" to if(source.isNotBlank()) "识别完成，请核对后保存" else "请核对后保存")
            }
            try{update{it.copy(status="正在执行")};return actions.execute(plan)}catch(missing:CapabilityMissing){require(++attempt<=2){"能力生成仍需补充信息，输入已保存"};context.put("missingAction",missing.action).put("instruction","通过现有通用操作、声明式插件或原生构建实现缺失能力")}
        }
    }
    private fun requiresReview(plan: JSONObject): Boolean =
        plan.optBoolean("imageImport") || plan.optBoolean("review") || (plan.optJSONArray("steps") ?: JSONArray()).objects().any { step ->
            val action = step.s("actionId")
            PlanReview.metricChoices(step).isNotEmpty() || action in setOf("atlas.editRecord", "atlas.deleteRecord", "atlas.restoreRecord", "atlas.deleteObject") ||
                (action == "atlas.createObject" && step.optJSONObject("args")?.s("type") == "METRIC")
        }

    fun submit(input: String) {
        if (!submitting.compareAndSet(false, true)) return
        saveDraft(input)
        val job = work("正在理解需求") {
            val result = if (input.trim().startsWith("{")) {
                actions.execute(JSONObject(input))
            } else {
                val text = state.value.imageText
                val attached = state.value.image != null
                require(!attached || text.isNotBlank()) { "图片未识别出文字，请换一张清晰图片" }
                val offline = if (attached) OfflineReceipt.plan(text,input,store.metrics())
                    else Offline.followUp(input,state.value.pending) ?: Offline.plan(input, store)
                if (offline!=null) localResult(offline)
                else if(Offline.explicitlyOffline(input)) obj("message" to "有部分指令需要补充。原文已保留。")
                else planInput(input.ifBlank { "整理图片中的记录供我确认" },text,attached)
            }
            if (result.s("status") == "DONE") clearSubmittedDraft(input)
            result
        }
        job.invokeOnCompletion { submitting.set(false) }
    }
    private fun localResult(plan:JSONObject):JSONObject = when {
        plan.has("question") -> obj("message" to plan.s("question"),"question" to plan)
        plan.has("localAnswer") -> obj("status" to "DONE","message" to plan.s("localAnswer"))
        plan.has("localHistory") -> {
            val redo=plan.s("localHistory")=="redo"
            val title=store.undo(redo,plan.s("expectedChange"),plan.s("targetHint"))
            obj("status" to "DONE","message" to (if(redo) "已重做：" else "已撤销：")+title)
        }
        plan.optBoolean("review") -> {
            actions.validateProposedPlan(plan)
            obj("status" to "PREVIEW","plan" to plan,"message" to "请核对后保存")
        }
        else -> actions.execute(plan)
    }
    fun clearPending() {update{if(it.pending==null)it else it.copy(pending=null,error="",status="已取消")}}
    fun undo(redo:Boolean=false)=work(if(redo) "正在恢复" else "正在撤销") {obj("message" to ((if(redo) "已恢复：" else "已撤销：")+store.undo(redo)))}
    fun addImage(uri:Uri)=work("正在识别图片") {
        val resolver=getApplication<Application>().contentResolver;val mime=resolver.getType(uri) ?: "image/jpeg";require(mime.startsWith("image/")){"请选择图片"}
        update{it.copy(image=uri.toString(),imageText="",imageLayout="[]",pending=null,result=null)}
        val reading=ImageText.readDetailed(getApplication(),uri);val text=reading.text
        require(text.isNotBlank()){"未识别到清晰文字，请更换图片"}
        update{it.copy(imageText=text,imageLayout=reading.layout,status="正在整理图片记录")}
        OfflineReceipt.plan(text,draft,store.metrics())?.let { return@work localResult(it) }
        if(!settings.ai().configured)return@work obj("message" to "文字已识别，可展开原文核对；其他图片格式可用自选模型整理")
        planInput(draft.ifBlank{"识别图片中的实际记录，列出数值与发生日期，供我核对保存"},text,true)
    }
    fun editImageText(text:String){update{it.copy(imageText=text,imageLayout="[]")}}
    fun clearImage(){update{it.copy(image=null,imageText="",imageLayout="[]",pending=null,error="",status="图片已移除")}}
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
    fun importPlugin(uri:Uri,sandbox:Boolean=false)=work("正在读取功能包") {
        val content=getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()
            ?: error("无法读取功能包文件")
        val text=content.use { reader ->
            val buffer=CharArray(4096)
            buildString {
                var size=reader.read(buffer)
                while(size>0) {
                    require(length+size<=1_000_000) {"功能包文件过大"}
                    append(buffer,0,size)
                    size=reader.read(buffer)
                }
            }
        }
        val original=JSONObject(text)
        actions.manifestValid(original)
        val manifest=if(sandbox) sandboxPluginTemplate(original) else original
        val existing=store.query("SELECT version FROM atlas_plugins WHERE plugin_id=? ORDER BY version DESC LIMIT 1",
            arrayOf(manifest.s("id"))).firstOrNull()
        require(existing==null || manifest.optInt("version",1)>existing.optInt("version")){"已安装相同或更高版本"}
        actions.single(if(existing==null) "atlas.installPlugin" else "atlas.updatePlugin",obj("manifest" to manifest),
            if(sandbox) "核对测试功能包" else "核对功能包")
    }
    fun exportPlugin(uri:Uri,pluginId:String)=work("正在导出模板") {
        val row=store.query("SELECT manifest FROM atlas_plugins WHERE plugin_id=? ORDER BY version DESC LIMIT 1",arrayOf(pluginId))
            .firstOrNull() ?: error("功能包不存在")
        val template=portablePluginTemplate(JSONObject(row.s("manifest")))
        val resolver=getApplication<Application>().contentResolver
        val output=resolver.openOutputStream(uri)?.bufferedWriter() ?: error("无法保存模板")
        output.use{it.write(template.toString(2))}
        obj("message" to "模板已导出","notice" to true)
    }
    fun history(metric:String?,from:String,to:String,offset:Int)=work("正在查询") {from.takeIf{it.isNotBlank()}?.let(LocalDate::parse);to.takeIf{it.isNotBlank()}?.let(LocalDate::parse);require(from.isBlank() || to.isBlank() || from<=to){"开始日期晚于结束日期"};historyMetric=metric;historyFrom=from;historyTo=to;historyOffset=offset;obj("message" to "查询完成")}
    suspend fun summary(metric:String,from:String,to:String):JSONObject=withContext(Dispatchers.IO) {store.aggregate(metric,from,to)}
    suspend fun chart(metric:String,from:String,to:String):List<JSONObject> = withContext(Dispatchers.IO) {store.dailySeries(metric,from,to)}
    fun installUpdate(feed:String)=action("atlas.installUpdate",obj("feed" to feed),"正在验证与下载更新")
    fun build(id:String)=action("atlas.submitBuild",obj("id" to id),"正在提交构建")
    fun buildStatus(id:String)=action("atlas.getBuildStatus",obj("id" to id),"正在查询构建")
    fun installBuild(id:String)=action("atlas.installBuild",obj("id" to id),"正在验证签名安装包")
    override fun onCleared(){store.close()}
}
