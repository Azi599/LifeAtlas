package com.lifeatlas.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

@Composable fun ThemeEditor(vm:AtlasModel) {
    val state by vm.ui.collectAsState();val themes=remember(state.revision){ThemeRepository(vm.store).themes()}
    var selected by remember{mutableStateOf(state.themeId)};var draft by remember{mutableStateOf(JSONObject(state.themeConfig))}
    var name by remember{mutableStateOf(themes.firstOrNull{it.s("id")==selected}?.s("title") ?: "自定义主题")};var mode by remember{mutableStateOf("light")};var picker by remember{mutableStateOf<String?>(null)};var error by remember{mutableStateOf("")}
    fun change(key:String,value:Any){draft=draft.copyJson().apply{getJSONObject(mode).put(key,value)}}
    val c=draft.getJSONObject(mode);val stops=c.getJSONArray("stops").objects()
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Pick("已保存主题",themes.map{it.s("id") to it.s("title")},selected){id->selected=id;val t=themes.first{it.s("id")==id};draft=JSONObject(t.s("config"));name=t.s("title")}
        Button(onClick={vm.action("atlas.setAppearance",obj("themeId" to selected),"应用主题")}){Text("应用所选主题")}
        Pick("显示模式",listOf("system" to "跟随系统","light" to "浅色","dark" to "深色"),state.theme){vm.action("atlas.setAppearance",obj("mode" to it),"更改显示模式")}
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Switch(state.compact,{vm.action("atlas.setAppearance",obj("compact" to it),"调整间距")});Text("紧凑间距",Modifier.padding(start=12.dp))}
        HorizontalDivider()
        OutlinedTextField(name,{name=it},label={Text("主题名称")},modifier=Modifier.fillMaxWidth())
        Pick("编辑配色",listOf("light" to "浅色","dark" to "深色"),mode){mode=it}
        listOf("primary" to "主色","background" to "背景","surface" to "内容底色","text" to "主要文字","secondaryText" to "次要文字").forEach{(key,title)->
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Box(Modifier.size(28.dp).background(themeColor(c.s(key)),MaterialTheme.shapes.extraSmall));TextButton(onClick={picker=key}){Text(title)};Text(c.s(key),style=MaterialTheme.typography.bodySmall)}
        }
        Pick("背景",listOf("solid" to "纯色","gradient" to "渐变"),if(c.optBoolean("gradient"))"gradient" else "solid"){change("gradient",it=="gradient")}
        if(c.optBoolean("gradient")) {
            Text("角度 "+c.optInt("angle")+"°");Slider(c.optDouble("angle",135.0).toFloat(),{change("angle",it.toInt())},valueRange=0f..360f)
            stops.forEachIndexed{i,stop->
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Box(Modifier.size(26.dp).background(themeColor(stop.s("color")),MaterialTheme.shapes.extraSmall));TextButton(onClick={picker="stop:"+i}){Text("色标 "+(i+1))};if(stops.size>2)TextButton(onClick={val a=c.getJSONArray("stops").let{JSONArray(it.toString())};a.remove(i);change("stops",a)}){Text("移除")}}
                Text("位置 "+"%.0f".format(stop.getDouble("position")*100)+"%",style=MaterialTheme.typography.labelLarge)
                val lo=if(i==0)0f else (stops[i-1].getDouble("position").toFloat()+stop.getDouble("position").toFloat())/2;val hi=if(i==stops.lastIndex)1f else (stops[i+1].getDouble("position").toFloat()+stop.getDouble("position").toFloat())/2
                Slider(stop.getDouble("position").toFloat(),{v->val a=JSONArray(c.getJSONArray("stops").toString());a.getJSONObject(i).put("position",v.toDouble());change("stops",a)},valueRange=lo..hi)
            }
            if(stops.size<5)OutlinedButton(onClick={val sorted=stops.zipWithNext().maxBy{it.second.getDouble("position")-it.first.getDouble("position")};val pos=(sorted.first.getDouble("position")+sorted.second.getDouble("position"))/2;val next=stops.map{it.copyJson()}+obj("color" to c.s("primary"),"position" to pos);change("stops",JSONArray(next.sortedBy{it.getDouble("position")}))}){Text("添加色标")}
        }
        Text("实时预览",style=MaterialTheme.typography.titleMedium)
        AtlasTheme(draft,mode=="dark",state.compact) {
            Box(Modifier.fillMaxWidth().heightIn(min=180.dp)) {AtlasBackdrop {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("Life Atlas",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onBackground)
                Surface(shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(14.dp)){Text(state.objects.firstOrNull{it.s("type")=="PROJECT"}?.s("title") ?: "我的空间",style=MaterialTheme.typography.titleMedium);Text("记录",color=MaterialTheme.colorScheme.onSurfaceVariant);Button(onClick={}){Text("新增记录")}}}
                Row(horizontalArrangement=Arrangement.spacedBy(26.dp)){NavGlyph(0,true);NavGlyph(1,false);NavGlyph(3,false)}
            }}}
        }
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        Button(modifier=Modifier.fillMaxWidth(),onClick={runCatching{ThemeDefaults.validate(draft);vm.action("atlas.saveTheme",obj("id" to selected,"title" to name,"config" to draft),"保存主题")}.onFailure{error=it.message.orEmpty()}}){Text("保存并应用")}
        OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={runCatching{ThemeDefaults.validate(draft);val id=uid();selected=id;vm.action("atlas.saveTheme",obj("id" to id,"title" to name,"config" to draft),"保存新主题")}.onFailure{error=it.message.orEmpty()}}){Text("另存为新主题")}
        if(selected !in ThemeDefaults.presets().map{it.s("id")})TextButton(onClick={vm.action("atlas.deleteTheme",obj("id" to selected),"删除主题");selected="lavender";draft=ThemeDefaults.config();name="薰衣草"}){Text("删除此主题")}
        TextButton(onClick={vm.action("atlas.resetTheme",JSONObject(),"恢复默认主题");selected="lavender";draft=ThemeDefaults.config();name="薰衣草"}){Text("恢复默认主题")}
        IconGallery()
    }
    picker?.let{target->val initial=if(target.startsWith("stop:"))stops[target.substringAfter(':').toInt()].s("color")else c.s(target)
        ColorPicker(initial,onDismiss={picker=null},onSelect={hex->if(target.startsWith("stop:")){val a=JSONArray(c.getJSONArray("stops").toString());a.getJSONObject(target.substringAfter(':').toInt()).put("color",hex);change("stops",a)}else change(target,hex);picker=null})
    }
}
@Composable fun ColorPicker(initial:String,onDismiss:()->Unit,onSelect:(String)->Unit) {
    val initialHsv=remember(initial){FloatArray(3).also{android.graphics.Color.colorToHSV(themeColor(initial).toArgb(),it)}}
    var hue by remember{mutableFloatStateOf(initialHsv[0])};var saturation by remember{mutableFloatStateOf(initialHsv[1])};var value by remember{mutableFloatStateOf(initialHsv[2])};var hex by remember{mutableStateOf(initial)}
    fun sync(){hex=colorHex(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue,saturation,value))))}
    val pure=Color(android.graphics.Color.HSVToColor(floatArrayOf(hue,1f,1f)))
    AlertDialog(onDismissRequest=onDismiss,title={Text("调色盘")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(170.dp).pointerInput(hue){detectTapGestures{offset->saturation=(offset.x/size.width).coerceIn(0f,1f);value=(1-offset.y/size.height).coerceIn(0f,1f);sync()}}.pointerInput(hue){detectDragGestures{change,_->change.consume();saturation=(change.position.x/size.width).coerceIn(0f,1f);value=(1-change.position.y/size.height).coerceIn(0f,1f);sync()}}) {
            drawRect(Brush.horizontalGradient(listOf(Color.White,pure)));drawRect(Brush.verticalGradient(listOf(Color.Transparent,Color.Black)))
            val point=Offset(saturation*size.width,(1-value)*size.height);drawCircle(Color.Black,7.dp.toPx(),point,style=androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()));drawCircle(Color.White,7.dp.toPx(),point,style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
        Text("色相",style=MaterialTheme.typography.labelLarge);Slider(hue,{hue=it;sync()},valueRange=0f..360f)
        OutlinedTextField(hex,{typed->hex=typed.uppercase();if(hex.matches(Regex("#[A-F0-9]{6}"))){val hsv=FloatArray(3);android.graphics.Color.colorToHSV(themeColor(hex).toArgb(),hsv);hue=hsv[0];saturation=hsv[1];value=hsv[2]}},label={Text("HEX")},singleLine=true,modifier=Modifier.fillMaxWidth())
        if(hex.matches(Regex("#[A-F0-9]{6}")))Box(Modifier.fillMaxWidth().height(34.dp).background(themeColor(hex),MaterialTheme.shapes.small))
    }},confirmButton={TextButton(enabled=hex.matches(Regex("#[A-Fa-f0-9]{6}")),onClick={onSelect(hex)}){Text("选择")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
