package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Planner {
    fun create(endpoint:String, key:String, model:String, request:String):JSONObject {
        require(endpoint.startsWith("https://")) { "API 地址必须使用 HTTPS" }
        val instruction="""你是生活数据项目设计器。根据用户需求，输出 JSON 对象：{"title":"项目名称","metrics":[{"title":"指标名称","kind":"SNAPSHOT或CHANGE或ALLOCATION","unit":"单位"}]}。只输出 JSON。必须包括用户需要的全部数值指标。SNAPSHOT=状态测量；CHANGE=真实变化、次数或累计量；ALLOCATION=声明时间长度。除项目和指标以外的要求不要假装已经实现。"""
        val messages=JSONArray().put(JSONObject().put("role","system").put("content",instruction)).put(JSONObject().put("role","user").put("content",request))
        val body=JSONObject().put("model",model).put("messages",messages).put("temperature",0.1)
        val conn=URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod="POST";conn.connectTimeout=20000;conn.readTimeout=60000;conn.doOutput=true
            conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("Authorization","Bearer $key")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if(conn.responseCode !in 200..299) error("AI 服务返回 HTTP ${conn.responseCode}")
            val raw=conn.inputStream.bufferedReader().use { it.readText() }
            val text=JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            return JSONObject(text)
        } finally { conn.disconnect() }
    }
}
