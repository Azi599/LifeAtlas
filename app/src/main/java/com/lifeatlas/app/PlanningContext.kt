package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Short labels let the model address existing objects without inventing database identifiers. */
internal class PlanningContext(private val original: JSONObject) {
    private val aliases = linkedMapOf<String, String>()
    private val referenceKeys = setOf(
        "metricId", "xMetricId", "yMetricId", "snapshotMetricId", "fromMetricId", "toMetricId",
        "triggerMetricId", "objectId", "parentId", "eventId", "fromId", "toId", "timerId", "buildId"
    )

    fun forModel(): JSONObject {
        val objects = (original.optJSONArray("objects") ?: JSONArray()).objects()
        objects.forEach { alias(it.s("id"), if (it.s("type") == "METRIC") "m" else "o") }
        val task = original.optJSONObject("currentTask")
        return obj(
            "objects" to JSONArray(objects.map(::objectInfo)),
            "selectedObjectId" to alias(original.s("selectedObjectId"), "o"),
            "appearance" to original.optJSONObject("appearance")?.let {
                obj("mode" to it.s("mode"), "compact" to (it.optInt("compact") == 1), "themeId" to it.s("theme_id"))
            },
            "currentTask" to task?.let { obj("input" to it.s("rawInput").take(500), "question" to it.s("question").take(500)) },
            "imageText" to original.opt("imageText"),
            "imageLayout" to original.opt("imageLayout"),
            "imageImport" to original.optBoolean("imageImport"),
            "missingAction" to original.opt("missingAction")
        )
    }

    fun bindPlan(plan: JSONObject): JSONObject = plan.copyJson().apply {
        optJSONArray("steps")?.objects()?.forEach { step ->
            step.optJSONObject("args")?.let { args -> step.put("args", bindArgs(step.s("actionId"), args)) }
        }
        optJSONArray("options")?.objects()?.forEach { option ->
            option.optJSONObject("plan")?.let { option.put("plan", bindPlan(it)) }
        }
    }

    fun bindArgs(action: String, args: JSONObject): JSONObject {
        val result = bindFields(args)
        if (action == "atlas.createObject") result.remove("id")
        else if (result.has("id")) result.put("id", bindReference(result.get("id")))
        if (result.opt("zone") is String && result.s("zone").isBlank()) result.remove("zone")
        if (action in setOf("atlas.query", "atlas.aggregate", "atlas.report")) {
            for (key in listOf("from", "to")) {
                val value = result.s(key)
                val day = runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
                    ?: runCatching { LocalDateTime.parse(value).toLocalDate() }.getOrNull()
                if (day != null) result.put(key, day.toString())
                else if (value.isBlank()) result.remove(key)
            }
        }
        return result
    }

    fun toolResult(result: JSONObject): JSONObject = when {
        result.has("records") -> obj(
            "records" to JSONArray(result.getJSONArray("records").objects().map(::record)),
            "offset" to result.opt("offset"), "limit" to result.opt("limit")
        )
        result.has("objects") -> obj("objects" to JSONArray(result.getJSONArray("objects").objects().map(::objectInfo)))
        else -> result
    }

    private fun alias(id: String, prefix: String): String {
        if (id.isBlank()) return ""
        return aliases.getOrPut(id) { "$prefix${aliases.size + 1}" }
    }

    private fun objectInfo(row: JSONObject): JSONObject {
        val config = row.optJSONObject("config") ?: runCatching { JSONObject(row.s("config")) }.getOrDefault(JSONObject())
        return obj(
            "id" to alias(row.s("id"), if (row.s("type") == "METRIC") "m" else "o"),
            "title" to row.s("title"), "type" to row.s("type"),
            "parentId" to alias(row.s("parent_id"), "o")
        ).apply {
            listOf("kind", "unit", "aggregate", "role", "fields").forEach { key ->
                config.opt(key)?.let { put(key, it) }
            }
        }
    }

    private fun record(row: JSONObject): JSONObject {
        val metric = row.s("metricId", row.s("metric_id"))
        return obj(
            "id" to alias(row.s("id"), "r"), "metricId" to alias(metric, "m"),
            "title" to row.s("metricTitle", row.s("metric_title")),
            "value" to row.opt("value"), "date" to row.s("date", row.s("local_date")),
            "occurredAt" to row.opt("occurred_at"), "startAt" to row.opt("start_at"), "endAt" to row.opt("end_at"),
            "fields" to (row.optJSONObject("fields") ?: runCatching { JSONObject(row.s("fields_json")) }.getOrNull())
        )
    }

    private fun bindReference(value: Any): Any {
        if (value !is String) return value
        val label = value.removePrefix("\$ref:").removeSuffix(".id")
        return aliases.entries.firstOrNull { it.value == label }?.key ?: value
    }

    private fun bindFields(args: JSONObject): JSONObject = JSONObject().apply {
        args.keys().forEach { key ->
            val value = args.get(key)
            val bound = when {
                key == "fields" -> value
                key == "parentId" && value == "null" -> JSONObject.NULL
                key in referenceKeys -> bindReference(value)
                key in setOf("metrics", "changeMetricIds") && value is JSONArray -> JSONArray().apply {
                    for (index in 0 until value.length()) {
                        val item = value.get(index)
                        put(if (item is JSONObject) bindFields(item) else bindReference(item))
                    }
                }
                value is JSONObject -> bindFields(value)
                value is JSONArray -> JSONArray().apply {
                    for (index in 0 until value.length()) {
                        val item = value.get(index)
                        put(if (item is JSONObject) bindFields(item) else item)
                    }
                }
                else -> value
            }
            put(key, bound)
        }
    }
}
