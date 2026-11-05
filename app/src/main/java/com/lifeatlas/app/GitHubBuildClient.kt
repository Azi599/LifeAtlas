package com.lifeatlas.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class GitHubBuildClient(private val settings:Settings) {
    private val repo=settings.prefs.getString("build_repo","Azi599/LifeAtlas")!!.also{require(it.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))){"构建仓库格式应为 owner/name"}}
    private val token=settings.getSecret("github_build_token").also{require(it.isNotBlank()){ "请配置 GitHub 构建令牌" }}
    private fun api(path:String,body:JSONObject?=null):JSONObject {
        val c=URL("https://api.github.com/repos/$repo/$path").openConnection() as HttpURLConnection
        try{c.instanceFollowRedirects=false;c.connectTimeout=20000;c.readTimeout=90000;c.setRequestProperty("Authorization","Bearer $token");c.setRequestProperty("Accept","application/vnd.github+json");c.setRequestProperty("X-GitHub-Api-Version","2022-11-28")
            if(body!=null){c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray())}}
            require(c.responseCode in 200..299){"GitHub HTTP ${c.responseCode}"};val text=c.inputStream.bufferedReader().use{it.readText()};return if(text.isBlank())JSONObject() else JSONObject(text)
        }finally{c.disconnect()}
    }
    fun submit(spec:JSONObject,requestId:String=uid()):JSONObject {
        val id=requestId.replace("-","");require(id.matches(Regex("[a-f0-9]{32}")))
        val base=settings.prefs.getString("build_branch","main")!!
        val baseline=api("git/ref/heads/"+java.net.URLEncoder.encode(base,"UTF-8")).getJSONObject("object").getString("sha")
        if(spec.s("kind")=="rollback") {
            val commit=spec.getString("sourceCommit");require(commit.matches(Regex("[a-f0-9]{40}")))
            val branch="native/"+id;api("git/refs",obj("ref" to "refs/heads/"+branch,"sha" to commit))
            api("actions/workflows/native-build.yml/dispatches",obj("ref" to branch,"inputs" to obj("request_id" to id)))
        }else api("actions/workflows/forge.yml/dispatches",obj("ref" to base,"inputs" to obj("request_id" to id,"request_json" to spec.toString())))
        return obj("id" to "gh:"+id,"status" to "SUBMITTED","baselineCommit" to baseline,"detail" to "GitHub 已接收扩展任务")
    }

    fun status(remoteId:String):JSONObject {
        val id=remoteId.removePrefix("gh:");require(id.matches(Regex("[a-f0-9]{32}")))
        val branch="native/$id";val runs=api("actions/runs?branch=native%2F$id&event=workflow_dispatch&per_page=10").getJSONArray("workflow_runs").objects()
        val native=runs.firstOrNull{it.s("path")==".github/workflows/native-build.yml"}
        val preview="https://github.com/$repo/compare/${settings.prefs.getString("build_branch","main")}...$branch"
        if(native!=null){val state=when(native.s("status")){"completed"->if(native.s("conclusion")=="success") "READY_FOR_REVIEW" else "FAILED";"waiting","pending"->"WAITING_APPROVAL";else->"BUILDING"};return obj("id" to remoteId,"status" to state,"detail" to (native.s("conclusion").ifEmpty{native.s("status")}),"artifactUrl" to native.s("html_url"),"previewUrl" to preview,"runId" to native.getLong("id"),"commit" to native.s("head_sha"))}
        val forge=api("actions/workflows/forge.yml/runs?event=workflow_dispatch&per_page=100").getJSONArray("workflow_runs").objects().firstOrNull{it.s("display_title")=="Forge $id"}
        val status=if(forge==null) "SUBMITTED" else if(forge.s("status")=="completed" && forge.s("conclusion")!="success") "FAILED" else "GENERATING"
        return obj("id" to remoteId,"status" to status,"detail" to (forge?.s("conclusion")?.ifEmpty{forge?.s("status").orEmpty()} ?: "已提交"),"previewUrl" to preview,"artifactUrl" to forge?.s("html_url"))
    }
    fun downloadSigned(context:Context,remoteId:String):File {
        val status=status(remoteId);require(status.s("status")=="READY_FOR_REVIEW"){ "构建与签名尚未完成" }
        val artifact=api("actions/runs/${status.getLong("runId")}/artifacts").getJSONArray("artifacts").objects().firstOrNull{it.s("name")=="signed-native-update" && !it.optBoolean("expired")} ?: error("签名产物尚未就绪")
        var url="https://api.github.com/repos/$repo/actions/artifacts/${artifact.getLong("id")}/zip"
        var connection:HttpURLConnection?=null
        for(i in 0..4){val c=URL(url).openConnection() as HttpURLConnection;c.instanceFollowRedirects=false;c.connectTimeout=20000;c.readTimeout=120000;if(URL(url).host=="api.github.com")c.setRequestProperty("Authorization","Bearer $token");val code=c.responseCode;if(code in 300..399){val next=c.getHeaderField("Location") ?: error("下载重定向缺失");c.disconnect();require(URL(next).protocol=="https");url=next}else{require(code in 200..299){"产物下载 HTTP $code"};connection=c;break}}
        val c=connection ?: error("下载重定向过多");val folder=File(context.cacheDir,"updates").apply{mkdirs()};val apk=File(folder,"native-update.apk")
        try{ZipInputStream(c.inputStream).use{zip->var found=false;while(true){val entry=zip.nextEntry ?: break;if(entry.name.endsWith(".apk")){require(!found){"产物含多个安装包"};found=true;apk.outputStream().use{out->val buffer=ByteArray(65536);var total=0L;while(true){val n=zip.read(buffer);if(n<0)break;total+=n;require(total<=250_000_000){"安装包超过250MB"};out.write(buffer,0,n)}}}};require(found){"未找到安装包"}}}catch(e:Exception){apk.delete();throw e}finally{c.disconnect()};return apk
    }
}
