package com.lifeatlas.app

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Bundled Chinese/Latin OCR. No network or Google Play model download required. Call on IO. */
object ImageText {
    fun read(context:Context,uri:Uri):String {
        val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val result=Tasks.await(recognizer.process(InputImage.fromFilePath(context,uri)),45,TimeUnit.SECONDS)
            // Preserve the recognizer's block/line structure, not an amount/date keyword parser.
            result.textBlocks.joinToString("\n\n"){block->block.lines.joinToString("\n"){it.text}}.take(60000)
        } finally {recognizer.close()}
    }
}
