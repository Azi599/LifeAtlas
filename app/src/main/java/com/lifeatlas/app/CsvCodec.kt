package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import java.io.PushbackReader
import java.io.Writer

object CsvCodec {
    /** RFC 4180 quoting, including embedded commas, newlines and doubled quotes. */
    fun rows(reader:Reader):Sequence<List<String>> = sequence {
        val r=PushbackReader(reader,1);var cells=ArrayList<String>();val field=StringBuilder();var quoted=false;var touched=false
        while(true){val code=r.read();if(code==-1) break;val ch=code.toChar();touched=true
            if(quoted){if(ch=='"'){val next=r.read();if(next=='"'.code)field.append('"') else {quoted=false;if(next!=-1)r.unread(next)}}else field.append(ch)}
            else when(ch){'"'->{require(field.isEmpty()){ "CSV 引号位置无效" };quoted=true};','->{cells.add(field.toString());field.clear()};'\n'->{cells.add(field.toString());yield(cells);cells=ArrayList();field.clear();touched=false};'\r'->{val next=r.read();if(next!='\n'.code && next!=-1)r.unread(next);cells.add(field.toString());yield(cells);cells=ArrayList();field.clear();touched=false};else->field.append(ch)}
            require(field.length<=1_000_000 && cells.size<=200){ "CSV 单元格或列数过大" }
        }
        require(!quoted){ "CSV 引号未闭合" };if(touched || field.isNotEmpty() || cells.isNotEmpty()){cells.add(field.toString());yield(cells)}
    }
    fun import(reader:Reader,actions:Actions,hash:String,fallbackMetric:String?,valueColumn:String,dateColumn:String,errors:Writer):JSONObject {
        val it=rows(reader).iterator();require(it.hasNext()){ "CSV 为空" };val header=it.next().map{it.removePrefix("\uFEFF").trim()};require(header.distinct().size==header.size){ "CSV 表头重复" };require(valueColumn in header && dateColumn in header){ "找不到数值或日期列" }
        var index=1;var accepted=0;var rejected=0;var skipped=0;val examples=JSONArray();var batch=ArrayList<Pair<Int,List<String>>>()
        errors.write("row,error\n")
        fun flush() {
            if(batch.isEmpty()) return
            val batchId="csv:$hash:${batch.first().first}";if(actions.store.one("atlas_operations",batchId)!=null){skipped+=batch.size;batch=ArrayList();return};var ok=0;var bad=0;var skip=0
            val result=actions.store.transact("导入 CSV ${batch.first().first}—${batch.last().first}",batchId) {
                batch.forEach{(number,row)->try{actions.store.savepoint{
                    require(row.size==header.size){ "列数与表头不符" };val map=header.zip(row).toMap();val metric=fallbackMetric?.takeIf{m->m.isNotEmpty()} ?: map["metric_id"].orEmpty();require(metric.isNotEmpty()){ "缺少指标" }
                    val recordId=map["record_id"]?.takeIf{v->v.isNotEmpty()} ?: "csv:$hash:$number"
                    if(actions.store.one("atlas_facts",recordId)!=null){skip++}else{
                        val args=obj("recordId" to recordId,"metricId" to metric,"value" to map[valueColumn]?.ifEmpty{null},"date" to map[dateColumn],"fields" to JSONObject(map["fields_json"]?.ifEmpty{"{}"} ?: "{}"));map["occurred_at"]?.takeIf{v->v.isNotBlank()}?.let{v->args.put("occurredAt",v)};map["zone"]?.takeIf{v->v.isNotBlank()}?.let{v->args.put("zone",v)}
                        actions.dispatch("atlas.record",args,"CSV 导入");ok++
                    }
                }}catch(e:Exception){bad++;val message=e.message ?: "无效记录";errors.write("$number,\"${message.replace("\"","\"\"")}\"\n");if(examples.length()<30)examples.put(obj("row" to number,"error" to message))}}
                obj("accepted" to ok,"rejected" to bad,"skipped" to skip)
            }
            accepted+=result.optInt("accepted");rejected+=result.optInt("rejected");skipped+=result.optInt("skipped");batch=ArrayList()
        }
        while(it.hasNext()){val row=it.next();index++;if(row.all{v->v.isEmpty()})continue;batch.add(index to row);if(batch.size==60)flush()};flush();errors.flush()
        return obj("accepted" to accepted,"rejected" to rejected,"skipped" to skipped,"errors" to examples,"message" to "已导入 $accepted 条，跳过 $skipped 条，异常 $rejected 条")
    }
}
