package com.lifeatlas.app

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable fun IconGallery() {
    val context=LocalContext.current
    val drawable=remember{context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("应用图标",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceAround) {
            listOf("圆形","圆角方形","方圆形").forEachIndexed{index,title->Column(horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally){
                Canvas(Modifier.size(64.dp)) {
                    val canvas=drawContext.canvas.nativeCanvas;val s=size.width;val path=android.graphics.Path()
                    if(index==0)path.addCircle(s/2,s/2,s/2,android.graphics.Path.Direction.CW)
                    else if(index==1)path.addRoundRect(0f,0f,s,s,s*.22f,s*.22f,android.graphics.Path.Direction.CW)
                    else {path.moveTo(s*.5f,0f);path.cubicTo(s*.94f,0f,s,0f,s,s*.5f);path.cubicTo(s,s*.94f,s,s,s*.5f,s);path.cubicTo(0f,s,0f,s*.94f,0f,s*.5f);path.cubicTo(0f,0f,s*.06f,0f,s*.5f,0f);path.close()}
                    canvas.save();canvas.clipPath(path)
                    val inset=(s*.25f).toInt();drawable.background.setBounds(-inset,-inset,s.toInt()+inset,s.toInt()+inset);drawable.foreground.setBounds(-inset,-inset,s.toInt()+inset,s.toInt()+inset);drawable.background.draw(canvas);drawable.foreground.draw(canvas);canvas.restore()
                };Text(title,style=MaterialTheme.typography.bodySmall)
            }}
        }
        val mono=remember{context.getDrawable(R.drawable.atlas_monochrome)!!};val tint=MaterialTheme.colorScheme.primary
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Canvas(Modifier.size(64.dp)){val canvas=drawContext.canvas.nativeCanvas;val s=size.width;val path=android.graphics.Path().apply{addCircle(s/2,s/2,s/2,android.graphics.Path.Direction.CW)};canvas.save();canvas.clipPath(path);canvas.drawColor(android.graphics.Color.rgb(237,228,250));mono.setTint(tint.toArgb());val inset=(s*.25f).toInt();mono.setBounds(-inset,-inset,s.toInt()+inset,s.toInt()+inset);mono.draw(canvas);canvas.restore()};Text("单色主题图标",Modifier.padding(start=16.dp))}
    }
}
