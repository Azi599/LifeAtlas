package com.lifeatlas.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var store: AtlasStore
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); store=AtlasStore(this);setContent { MaterialTheme { Workspace() } } }
    override fun onDestroy() { store.close(); super.onDestroy() }

    @Composable fun Workspace() {
        val prefs=remember { getSharedPreferences("ai_settings", MODE_PRIVATE) }
        var page by remember { mutableStateOf("home") }; var tick by remember { mutableIntStateOf(0) }
        var project by remember { mutableStateOf<Project?>(null) }; var metric by remember { mutableStateOf<Metric?>(null) }
        var request by remember { mutableStateOf("") }; var status by remember { mutableStateOf("") }; var pending by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
        var endpoint by remember { mutableStateOf(prefs.getString("endpoint","https://api.deepseek.com/chat/completions") ?: "") }
        var model by remember { mutableStateOf(prefs.getString("model","deepseek-chat") ?: "") }
        var key by remember { mutableStateOf(prefs.getString("key","") ?: "") }
        var name by remember { mutableStateOf("") }; var metricName by remember { mutableStateOf("") }; var unit by remember { mutableStateOf("") }; var kind by remember { mutableStateOf("SNAPSHOT") }
        var value by remember { mutableStateOf("") }; var date by remember { mutableStateOf(LocalDate.now().toString()) }
        Column(Modifier.fillMaxSize().padding(horizontal=20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(12.dp)); Text("Life Atlas",style=MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={page="home"}) { Text("工作区") }; OutlinedButton(onClick={page="settings"}) { Text("AI 设置") }
            }
            when(page) {
                "settings" -> {
                    Text("AI 服务",style=MaterialTheme.typography.titleLarge)
                    OutlinedTextField(endpoint,{endpoint=it},label={Text("Chat Completions 完整地址")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(model,{model=it},label={Text("模型")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(key,{key=it},label={Text("API Key")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                    Text("密钥保存在本机应用数据中；生成配置时会将你的输入发送到上方服务。")
                    Button(onClick={prefs.edit().putString("endpoint",endpoint.trim()).putString("model",model.trim()).putString("key",key.trim()).apply();status="设置已保存";page="home"}) {Text("保存")}
                }
                "home" -> {
                    Text("工作区",style=MaterialTheme.typography.titleLarge)
                    OutlinedTextField(request,{request=it},label={Text("描述你想创建的项目")},modifier=Modifier.fillMaxWidth(),minLines=2)
                    Button(enabled=!busy && request.isNotBlank(),onClick={
                        busy=true;status="正在生成项目配置";val input=request;val e=endpoint.trim();val k=key.trim();val m=model.trim()
                        Thread { try { val result=Planner.create(e,k,m,input);runOnUiThread { pending=result.toString();status="请检查项目预览";busy=false } } catch(ex:Exception) {runOnUiThread {status="生成失败：${ex.message}";busy=false} } }.start()
                    }) {Text(if(busy) "正在生成…" else "生成项目")}
                    if(status.isNotBlank()) Text(status)
                    val preview=pending
                    if(preview!=null) {
                        val j=remember(preview) { runCatching { JSONObject(preview) }.getOrNull() }
                        if(j!=null) Card { Column(Modifier.padding(16.dp)) {
                            Text("预览：${j.optString("title")}",style=MaterialTheme.typography.titleMedium)
                            val ms=j.optJSONArray("metrics") ?: JSONArray()
                            for(i in 0 until ms.length()) { val x=ms.optJSONObject(i); if(x!=null) Text("${x.optString("title")} · ${x.optString("kind")} · ${x.optString("unit")}") }
                            Row { Button(onClick={try { val id=store.install(j);project=store.projects().first{it.id==id};tick++;pending=null;page="project";status="项目已创建" } catch(e:Exception){status="创建失败：${e.message}"}}) {Text("创建")}; TextButton(onClick={pending=null}) {Text("取消")} }
                        } }
                    }
                    OutlinedButton(onClick={page="create"}) {Text("手动创建项目")}
                    Text("我的项目",style=MaterialTheme.typography.titleMedium)
                    val projects=remember(tick) {store.projects()}
                    if(projects.isEmpty()) Text("还没有项目")
                    projects.forEach { p -> OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={project=p;page="project"}) {Text(p.title)} }
                }
                "create" -> {
                    Text("新建项目",style=MaterialTheme.typography.titleLarge)
                    OutlinedTextField(name,{name=it},label={Text("项目名称")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(metricName,{metricName=it},label={Text("第一个指标")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(unit,{unit=it},label={Text("单位")},modifier=Modifier.fillMaxWidth())
                    listOf("SNAPSHOT" to "状态", "CHANGE" to "变化", "ALLOCATION" to "时长").forEach { (code,title)-> Row { RadioButton(selected=kind==code,onClick={kind=code});Text(title,Modifier.padding(top=12.dp)) } }
                    Button(onClick={try {val m=JSONObject().put("title",metricName).put("kind",kind).put("unit",unit);val id=store.install(JSONObject().put("title",name).put("metrics",JSONArray().put(m)));project=store.projects().first{it.id==id};tick++;page="project";name="";metricName="";unit=""}catch(e:Exception){status="创建失败：${e.message}"}}) {Text("创建")}
                    Text(status)
                }
                "project" -> {
                    Text(project?.title ?: "项目",style=MaterialTheme.typography.titleLarge)
                    val metrics=remember(project,tick) {project?.let {store.metrics(it.id)} ?: emptyList()}
                    metrics.forEach { m -> OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={metric=m;page="metric"}) {Text("${m.title}  ${m.unit}")} }
                    TextButton(onClick={page="home"}) {Text("返回")}
                }
                "metric" -> {
                    val m=metric
                    if(m!=null) {
                        Text(m.title,style=MaterialTheme.typography.titleLarge)
                        OutlinedTextField(value,{value=it},label={Text("数值 ${m.unit}")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(date,{date=it},label={Text("日期 YYYY-MM-DD")},modifier=Modifier.fillMaxWidth())
                        Button(onClick={try {store.record(m,value.trim(),date.trim());value="";tick++;status="记录已保存"}catch(e:Exception){status="请检查数值与日期"}}) {Text("保存记录")}
                        Text(status)
                        val points=remember(m,tick) {store.points(m.id)}
                        Text("最近记录",style=MaterialTheme.typography.titleMedium)
                        if(points.isEmpty()) Text("还没有记录") else {
                            Trend(points.reversed());points.take(30).forEach {Text("${it.date}    ${it.value} ${m.unit}")}
                        }
                        TextButton(onClick={page="project"}) {Text("返回项目")}
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
    @Composable fun Trend(points:List<Point>) {
        val values=points.mapNotNull {it.value.toFloatOrNull()?.takeIf {v->v.isFinite()} }
        if(values.isEmpty()) return
        val min=values.min();val max=values.max();val span=(max-min).takeIf {it>0f} ?: 1f
        Column { Text("趋势（最近 ${values.size} 条）")
            Canvas(Modifier.fillMaxWidth().height(150.dp).padding(8.dp)) {
                val coords=values.mapIndexed { i,v->Offset(if(values.size==1) size.width/2 else i*size.width/(values.size-1),size.height-(v-min)/span*size.height) }
                coords.zipWithNext().forEach { (a,b)->drawLine(Color(0xff7655aa),a,b,3.dp.toPx()) };coords.forEach {drawCircle(Color(0xff7655aa),4.dp.toPx(),it)}
            }
        }
    }
}
