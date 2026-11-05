package com.lifeatlas.app

import org.json.JSONObject

class ExternalActions(private val store:AtlasStore) {
    private val settings=Settings(store.appContext)
    companion object {val ids=setOf("atlas.submitBuild","atlas.getBuildStatus","atlas.installBuild","atlas.checkForUpdate","atlas.installUpdate","system.undo","system.redo")}
    fun dispatch(action:String,args:JSONObject):JSONObject=when(action) {
        "system.undo"->obj("message" to ("已撤销："+store.undo()))
        "system.redo"->obj("message" to ("已恢复："+store.undo(true)))
        "atlas.checkForUpdate"->{val feed=args.s("feed",settings.prefs.getString("update_url","") ?: "");obj("update" to Updater(store.appContext,settings,store).check(feed),"feed" to feed)}
        "atlas.installUpdate"->Updater(store.appContext,settings,store).downloadAndInstall(args.s("feed",settings.prefs.getString("update_url","") ?: ""))
        "atlas.submitBuild"->{
            val row=store.one("atlas_builds",args.getString("id")) ?: error("构建任务不存在")
            if(row.s("remote_id").isNotEmpty())obj("id" to row.s("id"),"status" to row.s("status")) else {
                val spec=JSONObject(row.s("request"));val result=if(settings.getSecret("github_build_token").isNotBlank())GitHubBuildClient(settings).submit(spec,row.s("id")) else BuildClient(settings).submit(spec,row.s("id"))
                store.transact("提交云端构建"){store.put("atlas_builds",row.put("request",spec.put("baselineCommit",result.s("baselineCommit")).toString()).put("status",result.s("status")).put("remote_id",result.s("id")).put("detail",result.s("detail")).put("updated_at",now()));obj("id" to row.s("id"),"status" to result.s("status"))}
            }
        }
        "atlas.getBuildStatus"->{
            val row=store.one("atlas_builds",args.getString("id")) ?: error("任务不存在");val remote=row.getString("remote_id");require(remote.isNotBlank()){ "请先提交构建" }
            val result=if(remote.startsWith("gh:"))GitHubBuildClient(settings).status(remote) else BuildClient(settings).status(remote)
            store.transact("更新构建状态"){val spec=JSONObject(row.s("request")).put("previewUrl",result.s("previewUrl")).put("builtCommit",result.s("commit"));store.put("atlas_builds",row.put("request",spec.toString()).put("status",result.s("status")).put("artifact_url",result.s("artifactUrl")).put("detail",result.s("detail")).put("updated_at",now()));result}
        }
        "atlas.installBuild"->{val row=store.one("atlas_builds",args.getString("id")) ?: error("任务不存在");val remote=row.getString("remote_id");require(remote.startsWith("gh:")){ "请使用构建服务更新入口" };val apk=GitHubBuildClient(settings).downloadSigned(store.appContext,remote);Updater(store.appContext,settings,store).installArtifact(apk)}
        else->error("未注册的外部操作")
    }
}
