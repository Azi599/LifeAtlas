package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject

object ThemeDefaults {
    fun config(primary:String="#A78BDA",background:String="#FAF8FD",deep:String="#7053B6")=obj(
        "light" to obj("primary" to primary,"background" to background,"surface" to "#FFFFFF","text" to "#282431","secondaryText" to "#706B79","gradient" to false,"angle" to 135,"stops" to JSONArray().put(obj("color" to "#EDE4FA","position" to 0)).put(obj("color" to "#FBE8F1","position" to 1))),
        "dark" to obj("primary" to primary,"background" to "#19161F","surface" to "#24202D","text" to "#F4EFFA","secondaryText" to "#C5BCCD","gradient" to false,"angle" to 135,"stops" to JSONArray().put(obj("color" to "#2C223F","position" to 0)).put(obj("color" to "#352333","position" to 1))),"deep" to deep)
    fun presets()=listOf(obj("id" to "lavender","title" to "薰衣草","config" to config().toString()),obj("id" to "rose","title" to "蔷薇","config" to config("#D49BB5","#FFF8FB","#8F4669").toString()),obj("id" to "sage","title" to "鼠尾草","config" to config("#94BAA5","#F7FBF8","#456C57").toString()),obj("id" to "ink","title" to "墨色","config" to config("#A3A1B1","#FAF9FC","#575363").toString()))
    fun validate(config:JSONObject) {
        require(config.toString().length<=12000){"主题定义过大"}
        listOf("light","dark").forEach{mode->val c=config.getJSONObject(mode)
            listOf("primary","background","surface","text","secondaryText").forEach{key->require(c.s(key).matches(Regex("#[A-Fa-f0-9]{6}"))){"请输入六位 HEX 颜色"}}
            require(c.optDouble("angle",135.0).isFinite() && c.optDouble("angle",135.0) in 0.0..360.0){"角度范围 0–360"}
            val stops=c.getJSONArray("stops").objects();require(stops.size in 2..5){"渐变需包含2至5个色标"}
            stops.forEach{require(it.s("color").matches(Regex("#[A-Fa-f0-9]{6}")));require(it.getDouble("position").isFinite() && it.getDouble("position") in 0.0..1.0)}
            require(stops.zipWithNext().all{it.first.getDouble("position")<it.second.getDouble("position")}){"色标位置需递增"}
        }
    }
}
class ThemeRepository(private val store:AtlasStore) {
    fun themes():List<JSONObject> {val saved=store.query("SELECT * FROM atlas_themes ORDER BY updated_at DESC");return saved+ThemeDefaults.presets().filter{p->saved.none{it.s("id")==p.s("id")}}}
    fun appearance()=store.one("atlas_appearance","global") ?: obj("id" to "global","theme_id" to "lavender","mode" to "system","compact" to 0)
    fun current():JSONObject {val id=appearance().s("theme_id","lavender");return themes().firstOrNull{it.s("id")==id} ?: ThemeDefaults.presets().first()}
    fun save(a:JSONObject):JSONObject {
        val id=a.s("id").ifBlank{uid()};require(id.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        val old=themes().firstOrNull{it.s("id")==id};val config=JSONObject(old?.s("config") ?: ThemeDefaults.config().toString());val patch=a.getJSONObject("config")
        patch.keys().forEach{key->if(key in setOf("light","dark")){val target=config.getJSONObject(key);val values=patch.getJSONObject(key);values.keys().forEach{target.put(it,values.get(it))}}else if(key=="deep") config.put(key,patch.get(key))}
        ThemeDefaults.validate(config);val title=a.s("title",old?.s("title") ?: "自定义主题").trim();require(title.length in 1..80)
        store.put("atlas_themes",obj("id" to id,"title" to title,"config" to config.toString(),"created_at" to (old?.s("created_at")?.ifBlank{now()} ?: now()),"updated_at" to now()))
        if(a.optBoolean("select",true))select(obj("themeId" to id))
        return obj("id" to id,"title" to title,"message" to "主题已保存")
    }
    fun select(a:JSONObject):JSONObject {
        val row=appearance();if(a.has("themeId")){require(themes().any{it.s("id")==a.s("themeId")}){"主题不存在"};row.put("theme_id",a.s("themeId"))}
        if(a.has("mode")){require(a.s("mode") in setOf("system","light","dark"));row.put("mode",a.s("mode"))}
        if(a.has("compact"))row.put("compact",if(a.getBoolean("compact"))1 else 0)
        store.put("atlas_appearance",row);return obj("message" to "外观已更新")
    }
    fun reset():JSONObject {store.one("atlas_themes","lavender")?.let{store.remove("atlas_themes","lavender")};return select(obj("themeId" to "lavender","mode" to "system","compact" to false))}
    fun delete(id:String):JSONObject {require(id !in ThemeDefaults.presets().map{it.s("id")}){"预设主题请使用恢复默认"};if(appearance().s("theme_id")==id)select(obj("themeId" to "lavender"));store.remove("atlas_themes",id);return obj("message" to "主题已删除")}
}
