package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal object OfflineReceipt {
    private val headers = setOf("支付明细","支出明细","消费记录","支出记录")
    private val headerDate = Regex("^(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})(?:日)?$")
    private val receiptRow = Regex("^(?:(\\d{1,2}:\\d{2})\\s+)?(.+?)\\s+(?:[¥￥]\\s*)?(-?\\d+(?:\\.\\d{1,2})?)\\s*(元)?$")
    private val total = Regex("^(?:合计|小计|总计)\\s*[:：]?\\s*[¥￥]?-?\\d+(?:\\.\\d{1,2})?\\s*元?$")

    fun plan(source: String,input: String,metrics: List<JSONObject>,zone: ZoneId = ZoneId.systemDefault()):JSONObject? {
        if (input.isNotBlank() && listOf("记录","记到","记入","核对","识别","整理").none(input::contains)) return null
        val lines = source.lineSequence().filter { it.isNotBlank() }.map { original ->
            original to Normalizer.normalize(original.trim(),Normalizer.Form.NFKC)
        }.toList()
        if (lines.firstOrNull()?.second !in headers) return null
        val possible = metrics.filter { metric ->
            val config = JSONObject(metric.s("config"))
            config.s("role")=="expense" && Units.currency(config.s("unit"))=="CNY" &&
                !config.has("expression") && config.optJSONArray("fields")?.objects()?.none { it.optBoolean("required") }!=false
        }
        if (possible.isEmpty()) return null
        val named = possible.filter { it.s("title").isNotBlank() && input.contains(it.s("title")) }
        val targets = named.ifEmpty { possible }
        var date: LocalDate? = null
        val records = mutableListOf<JSONObject>()
        for ((sourceLine,line) in lines.drop(1)) {
            val day = headerDate.matchEntire(line.replace(" ",""))
            if (day!=null) {
                val (year,month,number) = day.destructured
                date = runCatching { LocalDate.of(year.toInt(),month.toInt(),number.toInt()) }.getOrNull() ?: return null
                continue
            }
            if (total.matches(line)) continue
            val row = receiptRow.matchEntire(line) ?: return null
            val dayOfRecord = date ?: return null
            val (time,label,amount,unit) = row.destructured
            if (listOf("退款","收入","转账","余额","待支付").any { it in label }) return null
            if (unit.isEmpty() && '.' !in amount && '¥' !in line) return null
            val value = amount.toBigDecimalOrNull()?.abs() ?: return null
            val args = obj("date" to dayOfRecord.toString(),"zone" to zone.id,"value" to value.toPlainString(),
                "unit" to "元","sourceExcerpt" to sourceLine.trim())
            if (time.isNotBlank()) {
                val clock = runCatching {
                    val parts = time.split(':')
                    LocalTime.of(parts[0].toInt(),parts[1].toInt())
                }.getOrNull() ?: return null
                val local = dayOfRecord.atTime(clock)
                val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: return null
                args.put("occurredAt",local.toInstant(offset).toString())
            }
            records.add(args)
            if (records.size>60) return null
        }
        if (records.isEmpty()) return null
        val options = targets.take(8).map { target ->
            val steps = records.mapIndexed { index,args -> obj("stepId" to "r${index+1}","actionId" to "atlas.record",
                "args" to args.copyJson().put("metricId",target.s("id"))) }
            val plan = obj("title" to "核对 ${records.size} 笔支出","requestId" to uid(),"rawInput" to input,
                "steps" to JSONArray(steps),"local" to true,"imageImport" to true,"review" to true)
            PlanReview.validateImageImport(plan,source)
            obj("title" to target.s("title"),"plan" to plan)
        }
        return if (options.size==1) options.single().getJSONObject("plan")
            else obj("question" to "把这张账单记到哪里？","rawInput" to input,"options" to JSONArray(options))
    }
}
