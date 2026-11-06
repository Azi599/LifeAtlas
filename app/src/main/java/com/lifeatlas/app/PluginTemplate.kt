package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject

private val manifestKeys=setOf("id","title","version","hostVersion","tier","requires","views","screens","actions","workflows")
private val metricKeys=setOf("key","title","kind","unit","role","aggregate","dailyAggregate","minimum","maximum",
    "decimalPlaces","fields","expression","weightField","migrationFactor")
private val fieldKeys=setOf("id","title","type","required","options","minimum","maximum")

internal fun portablePluginTemplate(manifest:JSONObject):JSONObject {
    fun JSONObject.only(keys:Set<String>):JSONObject = obj(*keys.filter{has(it)}.map{it to get(it)}.toTypedArray()).copyJson()
    return manifest.only(manifestKeys).put("metrics",JSONArray(manifest.getJSONArray("metrics").objects().map{metric->
        metric.only(metricKeys).apply {
            val fields=optJSONArray("fields")
            if(fields!=null) put("fields",JSONArray(fields.objects().map{it.only(fieldKeys)}))
        }
    }))
}

internal fun sandboxPluginTemplate(manifest:JSONObject):JSONObject {
    val copy=portablePluginTemplate(manifest)
    require((copy.optJSONArray("requires")?.length() ?: 0)==0){"测试区模板需要独立运行"}
    val keys=copy.getJSONArray("metrics").objects().map{it.s("key")}.toSet()
    (copy.optJSONArray("screens") ?: JSONArray()).objects().forEach{screen->
        screen.getJSONArray("components").objects().forEach{component->
            require(component.s("component")=="text" || (component.s("component") in setOf("form","history","chart","summary") &&
                component.s("metricKey") in keys)) {"测试区页面只能使用包内指标"}
        }
    }
    (copy.optJSONArray("workflows") ?: JSONArray()).objects().forEach{workflow->
        val spec=workflow.optJSONObject("spec") ?: workflow
        spec.getJSONArray("steps").objects().forEach{step->
            val args=step.getJSONObject("args")
            require(step.s("actionId")=="atlas.record" && args.optJSONObject("metricId")?.s("\$metric").orEmpty() in keys &&
                args.keys().asSequence().all{it in setOf("metricId","value","unit","date","startAt","endAt","zone","fields")}) {
                "测试区工作流只能写入包内指标"
            }
        }
    }
    val id=copy.getString("id")
    if(id.startsWith("sandbox.")) return copy
    val suffix=if(id.length<=72) id else {
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        id.take(59)+"."+hash.take(6).joinToString(""){"%02x".format(it)}
    }
    return copy.put("id","sandbox."+suffix).put("title","测试 · "+copy.getString("title").take(110))
}
