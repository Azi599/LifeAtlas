package com.lifeatlas.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
class BuildClient(private val settings:Settings) {
    private fun call(path:String,payload:JSONObject?=null):JSONObject {
        val base=settings.prefs.getString("build_url","")!!.trimEnd('/');require(base.startsWith("https://")){"请先配置 HTTPS 构建服务地址"}
        val c=URL(base+path).openConnection() as HttpURLConnection
        try {c.connectTimeout=20000;c.readTimeout=90000;c.instanceFollowRedirects=false;c.setRequestProperty("Authorization","Bearer ${settings.getSecret("build_token")}");if(payload!=null){c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(payload.toString().toByteArray())}};require(c.responseCode in 200..299){"构建服务 HTTP ${c.responseCode}"};return JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}
    }
    fun submit(spec:JSONObject)=call("/builds",spec)
    fun status(id:String)=call("/builds/${java.net.URLEncoder.encode(id,"UTF-8")}")
}
