package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object Offline {
    fun plan(input:String,store:AtlasStore):JSONObject? {
        val tokens=input.trim().split(Regex("\\s+"));if(tokens.isEmpty()) return null
        val head=tokens[0]
        fun single(id:String,args:JSONObject)=obj("title" to input,"steps" to JSONArray().put(obj("stepId" to "s1","actionId" to id,"args" to args)))
        return when(head) {
            "/folder","/project" -> {require(tokens.size>=2);single("atlas.createObject",obj("type" to if(head=="/folder") "FOLDER" else "PROJECT","title" to tokens.drop(1).joinToString(" ")))}
            "/record" -> {require(tokens.size>=3){"格式：/record 指标名 数值 日期（可选）"};val found=store.metrics().filter{it.s("title")==tokens[1]};require(found.size==1){"指标名称有歧义，请从空间中选择"};single("atlas.record",obj("metricId" to found[0].s("id"),"value" to tokens[2],"date" to tokens.getOrElse(3){LocalDate.now().toString()}))}
            "/query" -> {val found=store.metrics().filter{it.s("title")==tokens.getOrElse(1){""}};require(found.size==1);single("atlas.query",obj("metricId" to found[0].s("id")))}
            "/event" -> single("atlas.createEvent",obj("text" to tokens.drop(1).joinToString(" ")))
            else -> null
        }
    }
}
