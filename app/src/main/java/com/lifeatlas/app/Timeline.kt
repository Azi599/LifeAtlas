package com.lifeatlas.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable fun TimelinePanel(vm:AtlasModel,state:AtlasState,onFact:(JSONObject)->Unit) {
    var linked by remember{mutableStateOf<Map<String,List<JSONObject>>>(emptyMap())}
    LaunchedEffect(state.revision){linked=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){state.events.associate{e->e.s("id") to vm.store.query("SELECT f.*,o.title metric_title FROM atlas_facts f JOIN atlas_objects o ON o.id=f.metric_id WHERE f.event_id=? AND f.deleted=0 ORDER BY f.created_at LIMIT 100",arrayOf(e.s("id")))}}}
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        state.events.groupBy{it.s("local_date","日期未指定")}.forEach{(date,events)->
            Text(date,style=MaterialTheme.typography.titleMedium)
            events.forEach{event->Column(Modifier.fillMaxWidth().padding(start=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(event.s("raw_input").isNotBlank())Text(event.s("raw_input"),style=MaterialTheme.typography.bodyLarge)
                linked[event.s("id")].orEmpty().forEach{fact->val c=vm.store.config(fact.s("metric_id"));val v=resolvedRecordValue(fact.s("value").takeIf{it.isNotEmpty()}?.let(::decimal),org.json.JSONObject(fact.s("fields_json")),c)?.toPlainString() ?: "未填写"
                    Row(Modifier.fillMaxWidth().testTag("timeline-item").clickable{onFact(fact)}.padding(vertical=8.dp)){Text(fact.s("metric_title"),Modifier.weight(1f));Text(v+" "+c.s("unit"))}
                }
                if(event.optLong("fact_count")>100)Text("共 "+event.optLong("fact_count")+" 条记录",style=MaterialTheme.typography.bodySmall)
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            }}
        }
        if(state.events.isEmpty())Text("所选范围没有事件")
    }
}
