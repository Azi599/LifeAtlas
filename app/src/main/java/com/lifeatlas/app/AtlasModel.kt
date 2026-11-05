package com.lifeatlas.app

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

data class AtlasState(
    val busy:Boolean=false,val status:String="",val objects:List<JSONObject> = emptyList(),val facts:List<JSONObject> = emptyList(),
    val operations:List<JSONObject> = emptyList(),val plugins:List<JSONObject> = emptyList(),val timers:List<JSONObject> = emptyList(),
    val builds:List<JSONObject> = emptyList(),val layouts:List<JSONObject> = emptyList(),val workflows:List<JSONObject> = emptyList(),
    val pending:JSONObject?=null,val result:JSONObject?=null,val revision:Int=0,val image:String?=null
)
class AtlasModel(app:Application):AndroidViewModel(app) {
    val store=AtlasStore(app);val settings=Settings(app);val actions=Actions(store);val planner=Planner(actions)
    private val state=MutableStateFlow(AtlasState());val ui=state.asStateFlow()
    var draft:String=settings.prefs.getString("draft","") ?: ""
    init {refresh()}
    private fun update(block:(AtlasState)->AtlasState) {state.value=block(state.value)}
    fun saveDraft(s:String) {draft=s;settings.prefs.edit().putString("draft",s).apply()}
    fun refresh()=viewModelScope.launch(Dispatchers.IO) {load()}
    private fun load() {update{it.copy(objects=store.objects(all=true),facts=store.facts(),operations=store.query("SELECT * FROM atlas_operations ORDER BY created_at DESC LIMIT 40"),plugins=store.query("SELECT * FROM atlas_plugins ORDER BY created_at DESC LIMIT 100"),timers=store.query("SELECT * FROM atlas_timers WHERE state!='STOPPED'"),builds=store.query("SELECT * FROM atlas_builds ORDER BY created_at DESC LIMIT 30"),layouts=store.query("SELECT * FROM atlas_layout ORDER BY position"),workflows=store.query("SELECT * FROM atlas_workflows"),revision=it.revision+1)} }
    fun work(title:String,block:()->JSONObject)=viewModelScope.launch(Dispatchers.IO) {
        if(state.value.busy) return@launch
        update{it.copy(busy=true,status=title)}
        try {val result=block();update{it.copy(result=result,pending=if(result.s("status")=="PREVIEW") result.getJSONObject("plan") else if(result.has("question")) result.getJSONObject("question") else null,status=result.s("message","已完成"))};load()} catch(e:Exception){update{it.copy(status=e.message ?: "操作失败")}} finally{update{it.copy(busy=false)}}
    }
    fun action(id:String,args:JSONObject,title:String=id,confirmed:Boolean=false)=work(title){actions.single(id,args,title,confirmed)}
    fun execute(plan:JSONObject,confirmed:Boolean=false)=work("正在执行"){actions.execute(plan,confirmed)}
    fun submit(input:String) {
        saveDraft(input)
        work("正在理解需求") {
            if(input.trim().startsWith("{")) return@work actions.execute(JSONObject(input))
            Offline.plan(input,store)?.let{return@work actions.execute(it)}
            val context=obj("objects" to JSONArray(store.objects(all=true).take(200)),"recentOperations" to JSONArray(store.query("SELECT title,result FROM atlas_operations ORDER BY created_at DESC LIMIT 5")),"selectedObjectId" to settings.prefs.getString("selected",""))
            val plan=planner.plan(settings.ai(),input,context,state.value.image)
            if(plan.has("question")){update{it.copy(pending=plan)};obj("message" to plan.getString("question"),"question" to plan)} else actions.execute(plan)
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
    fun importPlugin(uri:Uri)=work("正在读取功能包") {val text=getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()};require(text.length<1_000_000);val m=JSONObject(text);actions.manifestValid(m);actions.single(if(store.query("SELECT id FROM atlas_plugins WHERE plugin_id=?",arrayOf(m.s("id"))).isEmpty()) "atlas.installPlugin" else "atlas.updatePlugin",obj("manifest" to m),"安装功能包")}
    fun history(metric:String?,from:String,to:String,offset:Int)=work("正在查询") {val records=store.facts(metric,from,to,offset);update{it.copy(facts=if(offset==0) records else it.facts+records)};obj("message" to "已显示 ${offset+records.size} 条记录")}
    suspend fun summary(metric:String,from:String,to:String):JSONObject=withContext(Dispatchers.IO) {store.aggregate(metric,from,to)}
    suspend fun chart(metric:String,from:String,to:String):List<JSONObject> = withContext(Dispatchers.IO) {
        LocalDate.parse(from);LocalDate.parse(to);val c=store.config(metric);val days=linkedMapOf<String,java.math.BigDecimal>()
        store.readableDatabase.rawQuery("SELECT local_date,value,fields_json FROM atlas_facts WHERE metric_id=? AND deleted=0 AND value IS NOT NULL AND local_date BETWEEN ? AND ? ORDER BY local_date,occurred_at,created_at,id",arrayOf(metric,from,to)).use{cursor->while(cursor.moveToNext()) {
            val fields=JSONObject(cursor.getString(2));if(c.s("role") in setOf("income","expense") && fields.s("flow")=="transfer") continue
            val d=cursor.getString(0);val v=normalizedValue(decimal(cursor.getString(1)),fields,c)
            days[d]=if(c.s("kind")=="SNAPSHOT") v else (days[d] ?: java.math.BigDecimal.ZERO)+v
        }}
        days.map{(d,v)->obj("date" to d,"value" to v.toPlainString())}
    }
    fun build(id:String)=work("正在提交构建") {val request=store.one("atlas_builds",id) ?: error("构建任务不存在");val result=BuildClient(settings).submit(JSONObject(request.s("request")));store.transact("提交云端构建"){store.put("atlas_builds",request.put("status",result.s("status")).put("remote_id",result.s("id")).put("detail",result.s("detail")).put("updated_at",now()));obj("message" to "构建请求已提交")}}
    fun buildStatus(id:String)=work("正在查询构建") {val row=store.one("atlas_builds",id) ?: error("任务不存在");val result=BuildClient(settings).status(row.getString("remote_id"));store.transact("更新构建状态"){store.put("atlas_builds",row.put("status",result.s("status")).put("artifact_url",result.s("artifactUrl")).put("detail",result.s("detail")).put("updated_at",now()));obj("message" to result.s("status"))}}
    override fun onCleared(){store.close()}
}
