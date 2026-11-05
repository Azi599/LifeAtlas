package com.lifeatlas.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun TimelinePanel(vm: AtlasModel, state: AtlasState, onFact: (JSONObject) -> Unit) {
    var linked by remember(state.revision) { mutableStateOf<Map<String, List<JSONObject>>>(emptyMap()) }
    LaunchedEffect(state.revision) {
        linked = withContext(Dispatchers.IO) {
            state.events.associate { event ->
                val metricFilter = if (state.historyMetric.isBlank()) "" else "AND f.metric_id = ?"
                val args = mutableListOf(event.s("id"))
                if (state.historyMetric.isNotBlank()) args.add(state.historyMetric)
                args.addAll(listOf(state.historyFrom, state.historyTo))
                val records = vm.store.query("""
                    SELECT f.*, o.title metric_title, o.config metric_config
                    FROM atlas_facts f JOIN atlas_objects o ON o.id = f.metric_id
                    WHERE f.event_id = ? AND f.deleted = 0 $metricFilter AND f.local_date BETWEEN ? AND ?
                    ORDER BY f.created_at LIMIT 100
                """.trimIndent(), args.toTypedArray())
                event.s("id") to records.map { fact ->
                    val config = JSONObject(fact.s("metric_config"))
                    val raw = fact.s("value").takeIf { it.isNotEmpty() }?.let(::decimal)
                    val value = resolvedRecordValue(raw, JSONObject(fact.s("fields_json")), config)
                    fact.remove("metric_config")
                    fact.put("value", value?.toPlainString() ?: JSONObject.NULL).put("unit", config.s("unit"))
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        state.events.groupBy { it.s("timeline_date", "日期未指定") }.forEach { (date, events) ->
            Text(date, style = MaterialTheme.typography.titleMedium)
            events.forEach { event ->
                Column(Modifier.fillMaxWidth().padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (event.s("raw_input").isNotBlank()) {
                        Text(event.s("raw_input"), style = MaterialTheme.typography.bodyLarge)
                    }
                    linked[event.s("id")].orEmpty().forEach { fact -> TimelineRecord(fact) { onFact(fact) } }
                    if (event.optLong("fact_count") > 100) {
                        Text("共 ${event.optLong("fact_count")} 条记录", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        if (state.events.isEmpty()) Text("所选范围没有事件")
    }
}

@Composable
private fun TimelineRecord(fact: JSONObject, onClick: () -> Unit) {
    val zone = fact.s("zone")
    val time = when (fact.s("precision")) {
        "INTERVAL" -> formatInstant(fact.s("start_at"), zone) + " — " + formatInstant(fact.s("end_at"), zone)
        "INSTANT" -> formatInstant(fact.s("occurred_at"), zone)
        else -> ""
    }
    Column(Modifier.fillMaxWidth().testTag("timeline-item").clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(fact.s("metric_title"), Modifier.weight(1f))
            Text(fact.s("value", "未填写") + " " + fact.s("unit"))
        }
        if (time.isNotBlank()) Text(time, style = MaterialTheme.typography.bodySmall)
    }
}
