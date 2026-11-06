package com.lifeatlas.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.LocalDate

fun LazyListScope.timelineItems(
    vm: AtlasModel,
    state: AtlasState,
    onFact: (JSONObject) -> Unit,
    onEvent: (JSONObject) -> Unit
) {
    item("timeline-filter") { TimelineFilter(vm,state) }
    val rows = state.timeline.take(50)
    rows.forEachIndexed { index, row ->
        val date = row.s("local_date")
        val isNote = row.optInt("is_note") == 1
        val key = (if (isNote) "note-" else "fact-") + row.s("id")
        if (index == 0 || rows[index - 1].s("local_date") != date) {
            item("day-$date") {
                Text(date.ifBlank { "日期未指定" },style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
            }
        }
        item(key) { TimelineRow(row) { if (isNote) onEvent(row) else onFact(row) } }
    }
    if (rows.isEmpty()) item("timeline-empty") { Text("所选范围没有记录") }
    if (state.historyOffset > 0 || state.timeline.size > 50) item("timeline-pages") {
        ActionRow {
            if (state.historyOffset > 0) OutlinedButton(enabled=!state.busy,onClick={
                vm.history(state.historyMetric.ifBlank{null},state.historyFrom,state.historyTo,state.historyOffset-50)
            }) { Text("较新记录") }
            if (state.timeline.size > 50) OutlinedButton(enabled=!state.busy,onClick={
                vm.history(state.historyMetric.ifBlank{null},state.historyFrom,state.historyTo,state.historyOffset+50)
            }) { Text("较早记录") }
        }
    }
}

@Composable
private fun TimelineFilter(vm: AtlasModel,state: AtlasState) {
    var open by remember { mutableStateOf(false) }
    val filtered = listOf(state.historyMetric,state.historyFrom,state.historyTo).any { it.isNotBlank() }
    ActionRow {
        OutlinedButton(enabled=!state.busy,onClick={open=true}) { Text("筛选") }
        if (filtered) OutlinedButton(enabled=!state.busy,onClick={vm.history(null,"","",0)}) { Text("清除筛选") }
    }
    if (open) {
        var metric by remember { mutableStateOf(state.historyMetric) }
        var from by remember { mutableStateOf(state.historyFrom) }
        var to by remember { mutableStateOf(state.historyTo) }
        val valid = runCatching {
            val start = from.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            val end = to.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            start == null || end == null || start <= end
        }.getOrDefault(false)
        AlertDialog(onDismissRequest={open=false},title={Text("筛选记录")},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Pick("指标",listOf("" to "全部指标")+state.objects.filter{it.s("type")=="METRIC"}.map{it.s("id") to it.s("title")},metric){metric=it}
                OutlinedTextField(from,{from=it},label={Text("开始日期")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(to,{to=it},label={Text("结束日期")},modifier=Modifier.fillMaxWidth())
                if (!valid) Text("请核对日期范围，格式为 YYYY-MM-DD",color=MaterialTheme.colorScheme.error)
            }
        },confirmButton={Button(enabled=valid,onClick={vm.history(metric.ifBlank{null},from,to,0);open=false}){Text("应用")}},
            dismissButton={TextButton(onClick={open=false}){Text("取消")}})
    }
}

@Composable
private fun TimelineRow(row: JSONObject, onEdit: () -> Unit) {
    val zone = row.s("zone")
    val time = when {
        row.s("precision")=="INTERVAL" -> formatInstant(row.s("start_at"),zone) + " — " + formatInstant(row.s("end_at"),zone)
        row.s("occurred_at").isNotBlank() -> formatInstant(row.s("occurred_at"),zone)
        else -> ""
    }
    val note = row.optInt("is_note")==1
    Column(Modifier.fillMaxWidth().testTag("timeline-item")) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(if(note) row.s("raw_input") else row.s("metric_title"),style=MaterialTheme.typography.titleSmall)
                if (!note) {
                    val config = JSONObject(row.s("metric_config","{}"))
                    val unit = config.s("unit").let{if(it=="CNY") "元" else it}
                    Text(listOf(row.s("value","未填写"),unit.takeIf{!row.isNull("value")}.orEmpty()).filter{it.isNotBlank()}.joinToString(" "))
                    val fields = JSONObject(row.s("fields_json","{}"))
                    config.optJSONArray("fields")?.objects()?.forEach { field ->
                        val raw = fields.s(field.s("id"))
                        if (raw.isNotBlank()) {
                            val display = if (field.s("type")=="boolean") if(raw=="true") "是" else "否" else raw
                            Text(field.s("title")+"："+display,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (time.isNotBlank()) Text(time,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick=onEdit){Text("修改")}
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
    }
}
