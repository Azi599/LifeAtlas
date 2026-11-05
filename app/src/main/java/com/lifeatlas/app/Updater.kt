package com.lifeatlas.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class Updater(private val context:Context,private val settings:Settings,private val store:AtlasStore) {
    private fun connection(url:String):HttpURLConnection {
        val parsed=URL(url);require(parsed.protocol=="https"){"更新地址必须使用 HTTPS"};val c=parsed.openConnection() as HttpURLConnection;c.connectTimeout=20000;c.readTimeout=120000;c.instanceFollowRedirects=false
        val build=settings.prefs.getString("build_url","")!!.trimEnd('/')
        if(build.isNotEmpty() && url.startsWith("$build/"))c.setRequestProperty("Authorization","Bearer ${settings.getSecret("build_token")}")
        return c
    }
    fun check(feed:String):JSONObject {
        require(BuildConfig.CHANNEL=="standalone"){"此渠道通过应用商店更新"};val c=connection(feed)
        val release=try{require(c.responseCode in 200..299){"更新服务 HTTP ${c.responseCode}"};val text=c.inputStream.bufferedReader().use{it.readText()};require(text.length<100000);JSONObject(text)}finally{c.disconnect()}
        val current=context.packageManager.getPackageInfo(context.packageName,0).longVersionCode
        require(release.getLong("versionCode")>current){"当前已是所选更新源的最新版本"};require(release.s("sha256").matches(Regex("[a-fA-F0-9]{64}"))){"更新包缺少校验信息"};return release
    }
    fun downloadAndInstall(feed:String):JSONObject {
        val release=check(feed);val folder=File(context.cacheDir,"updates").apply{mkdirs()};val apk=File(folder,"LifeAtlas-${release.getLong("versionCode")}.apk");val c=connection(release.getString("apkUrl"));val digest=MessageDigest.getInstance("SHA-256")
        try{require(c.responseCode in 200..299){"下载 HTTP ${c.responseCode}"};c.inputStream.use{input->apk.outputStream().use{out->val buffer=ByteArray(65536);var size=0L;while(true){val count=input.read(buffer);if(count<0)break;size+=count;require(size<=250_000_000){"更新包超过250MB"};digest.update(buffer,0,count);out.write(buffer,0,count)}}}}catch(e:Exception){apk.delete();throw e}finally{c.disconnect()}
        val sha=digest.digest().joinToString(""){"%02x".format(it)};require(sha.equals(release.getString("sha256"),true)){apk.delete();"更新包校验失败"}
        return installArtifact(apk,release.getLong("versionCode"))
    }
    fun installArtifact(apk:File,expectedVersion:Long?=null):JSONObject {
        require(BuildConfig.CHANNEL=="standalone"){"此渠道通过应用商店更新"}
        val pm=context.packageManager;val incoming=pm.getPackageArchiveInfo(apk.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES) ?: error("无法读取更新包")
        val current=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES);require(incoming.packageName==context.packageName && (expectedVersion==null || incoming.longVersionCode==expectedVersion) && incoming.longVersionCode>current.longVersionCode){"更新包身份或版本不符"}
        val existing=current.signingInfo?.apkContentsSigners?.map{it.toCharsString()}?.toSet() ?: emptySet();val next=incoming.signingInfo?.apkContentsSigners?.map{it.toCharsString()}?.toSet() ?: emptySet();require(existing.isNotEmpty() && existing==next){"更新包签名与当前应用不一致"}
        File(context.filesDir,"before-update-${System.currentTimeMillis()}.jsonl").bufferedWriter().use(store::export)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return obj("message" to "更新包已验证，请完成系统安装")
    }
}
