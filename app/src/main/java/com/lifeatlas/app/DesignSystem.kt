package com.lifeatlas.app

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.*

data class AtlasTokens(val gap:androidx.compose.ui.unit.Dp=16.dp,val inset:androidx.compose.ui.unit.Dp=20.dp,val touch:androidx.compose.ui.unit.Dp=48.dp,val motionMillis:Int=180,val chartGrid:Color=Color.Gray,val gradient:List<Pair<Float,Color>> = emptyList(),val angle:Float=135f)
val LocalAtlasTokens=staticCompositionLocalOf{AtlasTokens()}
fun themeColor(hex:String)=Color(0xff000000 or hex.removePrefix("#").toLong(16))
fun colorHex(color:Color)="#%06X".format(color.toArgb() and 0xffffff)
fun contrastRatio(a:Color,b:Color):Double {val x=a.luminance().toDouble();val y=b.luminance().toDouble();return (max(x,y)+0.05)/(min(x,y)+0.05)}
fun onColor(background:Color)=if(contrastRatio(background,Color.Black)>=contrastRatio(background,Color.White))Color.Black else Color.White
fun readableColor(color:Color,background:Color,ratio:Double=4.5):Color {if(contrastRatio(color,background)>=ratio)return color;val target=onColor(background);for(i in 1..100){val candidate=lerp(color,target,i/100f);if(contrastRatio(candidate,background)>=ratio)return candidate};return target}
@Composable fun AtlasTheme(config:JSONObject,dark:Boolean,compact:Boolean=false,content:@Composable ()->Unit) {
    val c=config.getJSONObject(if(dark)"dark" else "light");val bg=themeColor(c.s("background"));val surface=themeColor(c.s("surface"));val seed=themeColor(c.s("primary"))
    val text=readableColor(themeColor(c.s("text")),surface);val secondary=readableColor(themeColor(c.s("secondaryText")),surface)
    val primary=readableColor(seed,surface);val container=lerp(surface,seed,if(dark)0.3f else 0.22f)
    val base=if(dark)darkColorScheme()else lightColorScheme()
    var gradient=if(c.optBoolean("gradient"))c.getJSONArray("stops").objects().map{it.getDouble("position").toFloat() to themeColor(it.s("color"))}else emptyList()
    var backgroundText=readableColor(text,bg)
    if(gradient.isNotEmpty()) {
        val original=gradient
        for(step in 0..20){val tinted=original.map{it.first to lerp(it.second,bg,step/20f)};val candidate=listOf(backgroundText,Color.Black,Color.White).firstOrNull{color->tinted.all{contrastRatio(color,it.second)>=4.5}};if(candidate!=null){gradient=tinted;backgroundText=candidate;break}}
    }
    val scheme=base.copy(primary=primary,onPrimary=onColor(primary),primaryContainer=container,onPrimaryContainer=readableColor(text,container),secondary=primary,onSecondary=onColor(primary),secondaryContainer=container,onSecondaryContainer=readableColor(text,container),background=bg,onBackground=backgroundText,surface=surface,onSurface=text,surfaceVariant=lerp(surface,seed,0.07f),onSurfaceVariant=secondary,surfaceTint=Color.Transparent,surfaceContainer=surface,surfaceContainerHigh=surface,surfaceContainerHighest=surface,surfaceContainerLow=surface,surfaceContainerLowest=surface,outline=lerp(surface,text,0.45f),outlineVariant=lerp(surface,text,0.15f))
    val defaults=Typography();val font=FontFamily.SansSerif
    val typography=defaults.copy(headlineSmall=TextStyle(fontFamily=font,fontSize=27.sp,lineHeight=36.sp,fontWeight=FontWeight.Medium),titleLarge=TextStyle(fontFamily=font,fontSize=22.sp,lineHeight=30.sp,fontWeight=FontWeight.Medium),titleMedium=TextStyle(fontFamily=font,fontSize=17.sp,lineHeight=25.sp,fontWeight=FontWeight.Medium),bodyLarge=TextStyle(fontFamily=font,fontSize=16.sp,lineHeight=25.sp),bodyMedium=TextStyle(fontFamily=font,fontSize=14.sp,lineHeight=22.sp),bodySmall=TextStyle(fontFamily=font,fontSize=13.sp,lineHeight=19.sp),labelLarge=TextStyle(fontFamily=font,fontSize=14.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium),labelSmall=TextStyle(fontFamily=font,fontSize=12.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium))
    CompositionLocalProvider(LocalAtlasBackground provides bg,LocalContentColor provides scheme.onBackground,LocalAtlasTokens provides AtlasTokens(gap=if(compact)8.dp else 16.dp,chartGrid=scheme.outlineVariant,gradient=gradient,angle=c.optDouble("angle",135.0).toFloat())) {
        MaterialTheme(colorScheme=scheme,typography=typography,shapes=Shapes(extraSmall=RoundedCornerShape(6.dp),small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(14.dp),large=RoundedCornerShape(20.dp),extraLarge=RoundedCornerShape(24.dp)),content=content)
    }
}
@Composable fun AtlasBackdrop(content:@Composable ()->Unit) {
    val t=LocalAtlasTokens.current;val background=MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(background)) {
        if(t.gradient.isNotEmpty())Canvas(Modifier.matchParentSize()) {val radians=t.angle*PI/180;val r=sqrt(size.width*size.width+size.height*size.height)/2;val dx=(cos(radians)*r).toFloat();val dy=(sin(radians)*r).toFloat();drawRect(Brush.linearGradient(*t.gradient.toTypedArray(),start=Offset(center.x-dx,center.y-dy),end=Offset(center.x+dx,center.y+dy)))}
        content()
    }
}
@Composable fun Button(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,contentPadding:PaddingValues=PaddingValues(horizontal=18.dp,vertical=12.dp),content:@Composable RowScope.()->Unit) {
    androidx.compose.material3.Button(onClick,modifier.heightIn(min=48.dp),enabled=enabled,shape=MaterialTheme.shapes.small,contentPadding=contentPadding,content=content)
}
@Composable fun OutlinedButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,contentPadding:PaddingValues=PaddingValues(horizontal=16.dp,vertical=12.dp),content:@Composable RowScope.()->Unit) {
    androidx.compose.material3.OutlinedButton(onClick,modifier.heightIn(min=48.dp),enabled=enabled,shape=MaterialTheme.shapes.small,colors=ButtonDefaults.outlinedButtonColors(contentColor=if(LocalAtlasBackground.current==MaterialTheme.colorScheme.background)MaterialTheme.colorScheme.onBackground else readableColor(MaterialTheme.colorScheme.primary,LocalAtlasBackground.current)),contentPadding=contentPadding,content=content)
}
@Composable fun TextButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,contentPadding:PaddingValues=PaddingValues(horizontal=12.dp,vertical=10.dp),content:@Composable RowScope.()->Unit) {
    androidx.compose.material3.OutlinedButton(onClick,modifier.heightIn(min=48.dp),enabled=enabled,shape=MaterialTheme.shapes.small,colors=ButtonDefaults.outlinedButtonColors(contentColor=if(LocalAtlasBackground.current==MaterialTheme.colorScheme.background)MaterialTheme.colorScheme.onBackground else readableColor(MaterialTheme.colorScheme.primary,LocalAtlasBackground.current)),contentPadding=contentPadding,content=content)
}
@Composable fun NavGlyph(index:Int,selected:Boolean) {
    val color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(23.dp)) {val stroke=androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round);val w=size.width;val h=size.height
        when(index) {
            0->{val path=Path().apply{moveTo(w*.12f,h*.43f);lineTo(w*.5f,h*.1f);lineTo(w*.88f,h*.43f);lineTo(w*.88f,h*.87f);lineTo(w*.59f,h*.87f);lineTo(w*.59f,h*.62f);lineTo(w*.41f,h*.62f);lineTo(w*.41f,h*.87f);lineTo(w*.12f,h*.87f);close()};drawPath(path,color,style=stroke)}
            1->{for(x in 0..1)for(y in 0..1)drawRoundRect(color,Offset(w*(.12f+x*.43f),h*(.12f+y*.43f)),androidx.compose.ui.geometry.Size(w*.32f,h*.32f),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),style=stroke)}
            2->{drawLine(color,Offset(w*.24f,h*.15f),Offset(w*.24f,h*.85f),1.8.dp.toPx());for(y in listOf(.22f,.5f,.78f)){drawCircle(color,2.5.dp.toPx(),Offset(w*.24f,h*y));drawLine(color,Offset(w*.48f,h*y),Offset(w*.88f,h*y),1.8.dp.toPx())}}
            3->{val path=Path().apply{moveTo(w*.1f,h*.78f);lineTo(w*.35f,h*.52f);lineTo(w*.55f,h*.63f);lineTo(w*.9f,h*.2f)};drawPath(path,color,style=stroke)}
            else->{drawCircle(color,w*.35f,style=stroke);drawCircle(color,w*.12f,style=stroke);for(i in 0..7){val a=i*PI/4;drawLine(color,Offset(center.x+(cos(a)*w*.35).toFloat(),center.y+(sin(a)*h*.35).toFloat()),Offset(center.x+(cos(a)*w*.46).toFloat(),center.y+(sin(a)*h*.46).toFloat()),1.8.dp.toPx())}}
        }
    }
}

val LocalAtlasBackground=staticCompositionLocalOf{Color.White}
@Composable fun Card(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    val background=MaterialTheme.colorScheme.surface
    androidx.compose.material3.Card(modifier,colors=CardDefaults.cardColors(containerColor=background),elevation=CardDefaults.cardElevation(defaultElevation=0.dp)) {CompositionLocalProvider(LocalAtlasBackground provides background){content()}}
}
@Composable fun OutlinedCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    val background=MaterialTheme.colorScheme.surface
    androidx.compose.material3.OutlinedCard(modifier,colors=CardDefaults.outlinedCardColors(containerColor=background)) {CompositionLocalProvider(LocalAtlasBackground provides background){content()}}
}
@Composable fun Surface(modifier:Modifier=Modifier,shape:Shape=RectangleShape,content:@Composable ()->Unit) {
    val background=MaterialTheme.colorScheme.surface
    androidx.compose.material3.Surface(modifier,shape=shape,color=background){CompositionLocalProvider(LocalAtlasBackground provides background,LocalContentColor provides MaterialTheme.colorScheme.onSurface){content()}}
}
@Composable fun OutlinedTextField(value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,label:(@Composable ()->Unit)?=null,enabled:Boolean=true,singleLine:Boolean=false,minLines:Int=1,maxLines:Int=if(singleLine)1 else Int.MAX_VALUE,visualTransformation:androidx.compose.ui.text.input.VisualTransformation=androidx.compose.ui.text.input.VisualTransformation.None) {
    androidx.compose.material3.OutlinedTextField(value,onValueChange,modifier=modifier,label=label,enabled=enabled,singleLine=singleLine,minLines=minLines,maxLines=maxLines,visualTransformation=visualTransformation,colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surface,unfocusedContainerColor=MaterialTheme.colorScheme.surface,disabledContainerColor=MaterialTheme.colorScheme.surface))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable fun ActionRow(content:@Composable ()->Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){content()}
}
