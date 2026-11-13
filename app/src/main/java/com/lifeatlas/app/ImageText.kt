package com.lifeatlas.app

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Bundled Chinese/Latin OCR. No network or Google Play model download required. Call on IO. */
data class ImageReading(val text:String,val layout:String)
object ImageText {
    fun read(context:Context,uri:Uri):String=readDetailed(context,uri).text
    fun readDetailed(context:Context,uri:Uri):ImageReading {
        val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val result=Tasks.await(recognizer.process(InputImage.fromFilePath(context,uri)),45,TimeUnit.SECONDS)
            // Preserve the recognizer's block/line structure, not an amount/date keyword parser.
            val text=result.textBlocks.joinToString("\n\n"){block->block.lines.joinToString("\n"){it.text}}.take(60000)
            val lines=org.json.JSONArray()
            result.textBlocks.forEachIndexed{index,block->block.lines.forEach{line->if(lines.length()<1500){val box=line.boundingBox;lines.put(obj("block" to index,"text" to line.text,"left" to box?.left,"top" to box?.top,"right" to box?.right,"bottom" to box?.bottom))}}}
            ImageReading(text,lines.toString())
        } finally {recognizer.close()}
    }
}
