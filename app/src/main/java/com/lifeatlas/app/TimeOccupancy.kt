package com.lifeatlas.app

import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun AtlasStore.occupancy(args:JSONObject):JSONObject {
    fun ids(key:String):List<String> = args.optJSONArray(key)?.let { array ->
        require(array.length() in 1..60) {"请选择 1 至 60 项"}
        (0 until array.length()).map { array.getString(it) }.distinct()
    }.orEmpty()
    val recordIds=ids("recordIds")
    val metricIds=ids("metricIds")
    val from=args.s("from")
    val to=args.s("to")
    val zone=ZoneId.systemDefault()
    val lower=if(from.isBlank()) null else LocalDate.parse(from).atStartOfDay(zone).toInstant()
    val upper=if(to.isBlank()) null else LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant()
    val rows=if(recordIds.isNotEmpty()) {
        val marks=recordIds.joinToString(","){"?"}
        query("SELECT local_date,start_at,end_at FROM atlas_facts WHERE deleted=0 AND kind='ALLOCATION' AND id IN ($marks)",recordIds.toTypedArray())
    } else {
        require(lower!=null && upper!=null && lower<upper && metricIds.isNotEmpty()) {"请选择有效日期与时长指标"}
        val marks=metricIds.joinToString(","){"?"}
        query("""
            SELECT local_date,start_at,end_at FROM atlas_facts WHERE deleted=0 AND kind='ALLOCATION' AND metric_id IN ($marks)
              AND (local_date BETWEEN ? AND ? OR (start_at IS NOT NULL AND start_at<? AND end_at>?))
        """.trimIndent(),(metricIds+listOf(from,to,upper.toString(),lower.toString().removeSuffix("Z"))).toTypedArray())
    }
    val intervals=mutableListOf<Pair<Long,Long>>()
    var untimed=0
    rows.forEach { row ->
        val start=row.s("start_at")
        val end=row.s("end_at")
        if(start.isEmpty() || end.isEmpty()) {
            val date=row.s("local_date")
            if ((from.isEmpty() || date>=from) && (to.isEmpty() || date<=to)) untimed++
        }
        else {
            val a=Instant.parse(start).let{if(lower==null)it else maxOf(it,lower)}
            val b=Instant.parse(end).let{if(upper==null)it else minOf(it,upper)}
            if(a<b) intervals.add(a.toEpochMilli() to b.toEpochMilli())
        }
    }
    val known=if(intervals.isEmpty()) null else BigDecimal(MathEngine.union(intervals)).movePointLeft(3)
    return obj("occupancy" to true,
        "minutes" to known?.let{Units.convert(it,"s","分钟").stripTrailingZeros().toPlainString()},
        "knownIntervals" to intervals.size,"untimed" to untimed,
        "from" to from,"to" to to)
}
