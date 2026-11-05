package com.lifeatlas.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable fun SettingsPanel(vm:AtlasModel,onExport:(Boolean)->Unit,onRestore:()->Unit,onPlugin:()->Unit) {
    var panel by remember{mutableStateOf("")};var endpoint by remember{mutableStateOf(vm.settings.ai().endpoint)};var model by remember{mutableStateOf(vm.settings.ai().model)};var key by remember{mutableStateOf(vm.settings.ai().key)}
    var buildUrl by remember{mutableStateOf(vm.settings.prefs.getString("build_url","") ?: "")};var buildToken by remember{mutableStateOf(vm.settings.getSecret("build_token"))};var json by remember{mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        listOf("ai" to "AI 与模型","data" to "数据管理","plugin" to "创建与导入功能包","build" to "云端构建服务","help" to "使用说明").forEach{(id,title)->OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={panel=if(panel==id) "" else id}){Text(title)}}
        when(panel) {
            "ai"->{OutlinedTextField(endpoint,{endpoint=it},label={Text("Chat Completions 完整 HTTPS 地址")},modifier=Modifier.fillMaxWidth());OutlinedTextField(model,{model=it},label={Text("模型名称")},modifier=Modifier.fillMaxWidth());OutlinedTextField(key,{key=it},label={Text("API 密钥")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Button(onClick={vm.work("保存 AI 设置"){vm.settings.saveAi(endpoint,model,key);obj("message" to "AI 设置已保存")}}){Text("保存")}}
            "data"->{Button(onClick={onExport(false)}){Text("完整备份 JSONL")};OutlinedButton(onClick={onExport(true)}){Text("导出记录 CSV")};OutlinedButton(onClick=onRestore){Text("恢复备份")};}
            "plugin"->{OutlinedButton(onClick=onPlugin){Text("从文件导入功能包")};OutlinedTextField(json,{json=it},label={Text("Manifest JSON")},modifier=Modifier.fillMaxWidth(),minLines=4,maxLines=10);Button(enabled=json.isNotBlank(),onClick={vm.work("验证功能包"){val m=JSONObject(json);vm.actions.manifestValid(m);vm.actions.single("atlas.installPlugin",obj("manifest" to m),"安装${m.s("title")}")}}){Text("预览")}}
            "build"->{OutlinedTextField(buildUrl,{buildUrl=it},label={Text("构建服务 HTTPS 地址")},modifier=Modifier.fillMaxWidth());OutlinedTextField(buildToken,{buildToken=it},label={Text("服务访问令牌")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Button(onClick={vm.work("保存构建设置"){require(buildUrl.startsWith("https://"));vm.settings.prefs.edit().putString("build_url",buildUrl.trim()).apply();vm.settings.setSecret("build_token",buildToken);obj("message" to "构建服务已配置")}}){Text("保存")};}
            "help"->{Text("首页输入可以规划多个操作，结果提交后显示在操作历史。遇到选项卡时直接选择。空间页面提供离线目录、项目和指标管理。每个指标都可包含自定义字段，并拥有录入、查询、统计和趋势图。",style=MaterialTheme.typography.bodyLarge);Text("基础数据保存状态，真实变化保存发生的增减，时间分配保存时长或区间。同一事件可以关联多条记录。未知的金额和时间保持缺失。",style=MaterialTheme.typography.bodyMedium);Text("图表：基础数据采用日内最新快照，变化与声明时长按日累计；时段并集另行计算。修改历史记录后查询和图表读取当前有效数据。",style=MaterialTheme.typography.bodyMedium)}
        }
    }
}
