package com.lifeatlas.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.*

class CapabilityMissing(val action:String):IllegalStateException("需要扩展操作能力")
class Actions(val store:AtlasStore) {
    val catalog=linkedMapOf(
        "atlas.saveTheme" to "创建或调整主题 args id?,title?,config:{light?:{primary?,background?,surface?,text?,secondaryText?,gradient?,angle?,stops?:[{color:#HEX,position:0..1}]},dark?:同上},select?；2至5个色标，支持更新当前主题，使用 atlas.getTheme 查询",
        "atlas.getTheme" to "返回当前外观、主题配置和已保存主题",
        "atlas.setAppearance" to "应用外观 args themeId?,mode?(system/light/dark),compact?(boolean)",
        "atlas.resetTheme" to "恢复默认淡紫色外观，可撤销",
        "atlas.deleteTheme" to "删除自定义主题 args id",
        "system.undo" to "撤销最近操作；单独执行",
        "system.redo" to "恢复最近撤销；单独执行",
        "atlas.submitBuild" to "提交真实原生构建 args id（来自 requestBuild）",
        "atlas.getBuildStatus" to "查询云端构建 args id",
        "atlas.installBuild" to "验证并安装构建结果 args id；由 Android 系统确认安装",
        "atlas.checkForUpdate" to "查询更新源 args feed?",
        "atlas.installUpdate" to "验证并安装更新 args feed?",
        "atlas.createObject" to "创建工作空间、目录、项目、指标、仪表盘、页面；args type,title,parentId?,config?。指标 config={kind,unit,aggregate,role?(income/expense/balance/accountChange/sleep),decimalPlaces?,fields:[{id,title,type,required,options?}],expression?}",
        "atlas.updateObject" to "修改或移动对象 args id,title?,parentId?,config?,historyMode?(recalculate/preserve),archived?；指标语义不得改变",
        "atlas.relate" to "关联对象 args fromId,toId,kind",
        "atlas.createEvent" to "创建纯文字事件 args text；结果 id 可供多条 record 的 eventId 引用",
        "atlas.copyObject" to "复制对象定义 args id,title?；不复制数据事实",
        "atlas.deleteObject" to "归档对象 args id，可撤销",
        "atlas.record" to "新增记录 args metricId,value?(十进制字符串),unit?(原始数值的单位),date(YYYY-MM-DD),occurredAt?,fields?,eventId?,startAt?,endAt?,zone?(IANA 时区)。时刻需带 Z 或 UTC 偏移；未知数值留空",
        "atlas.editRecord" to "修改记录 args id,value?,date?,fields?,startAt?,endAt?,zone?,clearTime?；时长与区间必须一致",
        "atlas.deleteRecord" to "软删除记录 args id；可撤销",
        "atlas.restoreRecord" to "恢复记录 args id",
        "atlas.searchObjects" to "按名称发现对象和指标 args keyword,type?,offset?，返回至多100个真实对象",
        "atlas.query" to "查询记录 args metricId?,from?,to?,offset?,limit? 最多100条",
        "atlas.chart" to "生成图表 args metricId,from,to,chart?(line/bar/heatmap)；返回实际日统计点",
        "atlas.aggregate" to "精确统计 args metricId,from,to,method?；SUM MEAN WEIGHTED_MEAN LAST_SNAPSHOT UNION_DURATION MEDIAN MIN MAX COUNT",
        "atlas.createView" to "创建声明式页面 args title,parentId?,components:[{component:form/history/chart/summary/text/navigation,metricId?,chart?,text?,objectId?}]",
        "atlas.report" to "创建报告页面 args title,metrics:[metricId],from,to",
        "atlas.installPlugin" to "安装功能包 args manifest={id,title,version,metrics:[{key,title,kind,unit,aggregate,fields?,expression?}],views:[{title,metricKey,chart}],actions?:[{id,metricKey,template}],workflows?:[]}；需用户预览确认",
        "atlas.updatePlugin" to "升级功能包 args manifest，同 key 保留数据；单位转换需 migrationFactor 显式确认，计算规则变化需 historyMode(recalculate/preserve)",
        "atlas.setPluginStatus" to "启停已安装插件 args pluginId,enabled",
        "atlas.rollbackPlugin" to "回退插件上一版本 args pluginId；通过操作历史同步恢复配置和记录",
        "atlas.layout" to "设置首页对象卡 args objectId,position,hidden",
        "atlas.setWorkflowStatus" to "启停工作流 args id,enabled",
        "atlas.deleteWorkflow" to "删除工作流 args id，可撤销",
        "atlas.workflow" to "创建或升级版本化工作流 args id?,title,spec={triggerMetricId,condition?:表达式,steps:[{actionId,args}]}，保存记录时执行一次",
        "time.start" to "开始计时 args metricId（ALLOCATION）",
        "time.pause" to "暂停 args timerId",
        "time.resume" to "恢复 args timerId",
        "time.stop" to "结束计时并保存有起止时间的分段记录 args timerId",
        "finance.balance" to "推算账户余额 args snapshotMetricId,changeMetricIds:[id],asOf?；只累计锚点后明确发生的变化，同日未知先后列为未确定",
        "finance.transfer" to "转账 args fromMetricId,toMetricId,amount,date；同币种两条关联变化，净额0",
        "atlas.capabilities" to "列出宿主及动态能力",
        "atlas.navigate" to "导航 args page(workspace/spaces/history/analytics/settings),objectId?",
        "atlas.requestRollback" to "创建回退构建 args buildId；重新编译先前基线，保留数据并提高版本号",
        "atlas.retryBuild" to "重新创建失败构建任务 args buildId",
        "atlas.requestBuild" to "保存原生能力构建请求 args title,requirements；云端构建服务连接后提交",
        "atlas.correlation" to "按日对齐两个指标 args xMetricId,yMetricId,from,to，缺失不补0，至少3对样本"
    )
    private fun validateUi(components:JSONArray) {
        require(components.length() in 1..50)
        components.objects().forEach{c->require(c.s("component") in setOf("form","history","chart","summary","text","navigation")){"界面组件未注册"};if(c.s("component") in setOf("form","history","chart","summary")) require(c.s("metricId").isNotEmpty() || c.s("metricKey").isNotEmpty()){ "组件缺少指标引用" };if(c.s("component")=="chart") require(c.s("chart","line") in setOf("line","bar","heatmap"))}
    }
    fun manifestValid(m:JSONObject) {
        require(m.toString().length<=1000000){"功能包定义过大"}
        require(m.optInt("hostVersion",1)==1){"功能包宿主合约不兼容，请先更新应用"};require(m.s("tier","L1") in setOf("L1","L2")){"原生能力请通过构建任务安装"}
        require(m.s("id").matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,79}"))) { "插件 ID 无效" };require(m.optInt("version",1)>0)
        require(m.s("title").isNotBlank());val metrics=m.getJSONArray("metrics");require(metrics.length() in 1..50)
        val keys=hashSetOf<String>();metrics.objects().forEach{require(it.s("key").isNotBlank() && keys.add(it.s("key"))) {"指标 key 重复"};validateMetric(it)}
        (m.optJSONArray("views") ?: JSONArray()).objects().forEach{require(it.s("metricKey") in keys && it.s("chart","line") in setOf("line","bar","heatmap","list")) {"图表定义不合法"}}
        (m.optJSONArray("screens") ?: JSONArray()).objects().forEach{screen->validateUi(screen.getJSONArray("components"));screen.getJSONArray("components").objects().forEach{component->if(component.has("metricKey"))require(component.s("metricKey") in keys)}}
        (m.optJSONArray("workflows") ?: JSONArray()).objects().forEach{w->val spec=w.optJSONObject("spec") ?: w;require(w.s("triggerMetricKey",spec.s("triggerMetricKey")) in keys){"工作流触发指标不存在"};require((spec.optJSONArray("steps")?.length() ?: 0) in 1..10){"工作流需包含1至10步"}}
        (m.optJSONArray("actions") ?: JSONArray()).objects().forEach{require(it.s("metricKey") in keys && it.s("template") in setOf("record","query","aggregate")) {"动作模板不合法"}}
    }
    internal fun validateProposedPlan(plan: JSONObject, evidence: InputEvidence? = null) {
        if (plan.s("requestId").isNotBlank() && store.one("atlas_operations", plan.s("requestId")) != null) return
        val steps = plan.getJSONArray("steps")
        require(steps.length() in 1..60) { "操作步骤应为 1 至 60 项" }
        val singleRecord = steps.objects().count { it.s("actionId") == "atlas.record" } == 1
        val previous = mutableSetOf<String>()
        fun requireReference(value: Any, table: String, label: String): JSONObject? {
            if (value is JSONObject) {
                val reference = value.s("\$ref")
                require(reference.isNotBlank() && reference.substringBefore('.') in previous) { "$label 引用无效" }
                return null
            } else {
                val row = store.one(table, value.toString())
                require(row != null && row.optInt("deleted") == 0 && row.optInt("archived") == 0) { "$label 不存在，请查找实际对象" }
                if (label == "指标") require(row.s("type") == "METRIC") { "请选择实际指标" }
                return row
            }
        }
        for (step in steps.objects()) {
            val action = step.getString("actionId")
            val args = step.getJSONObject("args")
            val id = step.s("stepId")
            require(id.isNotBlank() && id !in previous) { "步骤需要唯一编号" }
            when (action) {
                "atlas.createObject" -> {
                    val type = args.getString("type")
                    val title = args.getString("title").trim()
                    require(type in objectTypes && title.isNotBlank()) { "请选择对象类型并填写名称" }
                    if (!args.isNull("parentId") && args.s("parentId").isNotBlank()) {
                        requireReference(args.get("parentId"), "atlas_objects", "上级对象")
                    }
                    if (type == "VIEW") {
                        require(args.optJSONObject("config")?.s("component") in setOf("schema", "chart", "report")) {
                            "页面要有可用内容，请使用 atlas.createView 或 atlas.report"
                        }
                    }
                    if (type == "METRIC") {
                        validateMetric(args.getJSONObject("config"))
                        if (args.optJSONObject("parentId") == null) {
                            val parent = args.s("parentId")
                            val existing = store.query(
                                "SELECT id FROM atlas_objects WHERE type='METRIC' AND archived=0 AND title=? AND COALESCE(parent_id,'')=? LIMIT 1",
                                arrayOf(title, parent)
                            )
                            require(existing.isEmpty()) { "已有指标「$title」，请使用它来保存记录" }
                        }
                    }
                }
                "atlas.record" -> {
                    val metric = requireReference(args.get("metricId"), "atlas_objects", "指标")
                    evidence?.prepareRecord(args, metric, singleRecord)
                    LocalDate.parse(args.getString("date"))
                    args.s("value").takeIf { it.isNotBlank() }?.let(::decimal)
                    require(args.isNull("fields") || args.optJSONObject("fields") != null) { "记录字段应按名称和数值提供" }
                    listOf("occurredAt", "startAt", "endAt").forEach { key ->
                        args.s(key).takeIf { it.isNotBlank() }?.let { Instant.parse(it) }
                    }
                }
                "atlas.editRecord", "atlas.deleteRecord", "atlas.restoreRecord" -> {
                    if (action == "atlas.restoreRecord") {
                        require(store.one("atlas_facts", args.getString("id")) != null) { "要恢复的记录不存在" }
                    } else {
                        requireReference(args.get("id"), "atlas_facts", "记录")
                    }
                    if (action == "atlas.editRecord") {
                        require(args.keys().asSequence().any { it in setOf("value", "date", "fields", "occurredAt", "startAt", "endAt", "clearTime") }) {
                            "请先问清楚要修改的内容"
                        }
                        require(args.isNull("fields") || args.optJSONObject("fields") != null) { "修改字段应按名称和数值提供" }
                        args.s("value").takeIf { it.isNotBlank() }?.let(::decimal)
                    }
                    val row = store.one("atlas_facts", args.s("id"))
                    if (row != null) {
                        val previousReview = step.optJSONObject("review")
                        require(previousReview == null || previousReview.optLong("revision") == row.optLong("revision")) {
                            "记录已变化，请重新提交指令核对"
                        }
                        val metric = store.one("atlas_objects", row.s("metric_id"))
                        val config = JSONObject(metric?.s("config", "{}") ?: "{}")
                        val fields = JSONObject(row.s("fields_json"))
                        args.put("_expectedRevision", row.optLong("revision"))
                        val value = resolvedRecordValue(row.s("value").takeIf { it.isNotBlank() }?.let(::decimal), fields, config)
                        val proposedFields = fields.copyJson()
                        args.optJSONObject("fields")?.let { changes ->
                            changes.keys().forEach { key -> if (!key.startsWith("_")) proposedFields.put(key, changes.get(key)) }
                        }
                        proposedFields.put("_unitVersion", config.optInt("unitVersion"))
                            .put("_schemaRevision", metric?.optLong("revision", 1) ?: 1)
                        val proposedValue = if (args.has("value")) {
                            val raw = args.s("value").takeIf { it.isNotBlank() }?.let(::decimal)
                            if (raw != null) evidence?.requireEditQuantity(raw, args.s("unit").ifBlank { config.s("unit") })
                            if (raw != null) normalizedValue(raw, proposedFields, config) else null
                        } else value
                        step.put("review", obj("title" to metric?.s("title"), "date" to row.s("local_date"),
                            "proposedValue" to proposedValue?.toPlainString(),
                            "value" to value?.toPlainString(), "unit" to config.s("unit"), "revision" to row.optLong("revision"),
                            "zone" to row.s("zone"), "startAt" to row.opt("start_at"), "endAt" to row.opt("end_at"),
                            "occurredAt" to row.opt("occurred_at"), "fields" to fields))
                    }
                }
                "atlas.installPlugin", "atlas.updatePlugin" -> manifestValid(args.getJSONObject("manifest"))
                "atlas.navigate" -> require(args.s("page") in setOf("workspace", "spaces", "history", "analytics", "settings")) {
                    "请选择输入、时间轴、分析或我的"
                }
                "atlas.createView" -> {
                    val components = args.getJSONArray("components")
                    validateUi(components)
                    for (component in components.objects()) {
                        when (component.s("component")) {
                            "form", "history", "chart", "summary" -> requireReference(component.get("metricId"), "atlas_objects", "指标")
                            "navigation" -> requireReference(component.get("objectId"), "atlas_objects", "跳转对象")
                        }
                    }
                }
                "atlas.setAppearance" -> {
                    val compact = args.opt("compact")
                    if (compact is Number && compact.toDouble() in setOf(0.0, 1.0)) args.put("compact", compact.toInt() == 1)
                }
                "atlas.report" -> {
                    evidence?.fillReportRange(args)
                    val metrics = args.getJSONArray("metrics")
                    require(metrics.length() in 1..50) { "请选已有指标生成报告" }
                    for (index in 0 until metrics.length()) requireReference(metrics.get(index), "atlas_objects", "指标")
                    require(LocalDate.parse(args.getString("from")) <= LocalDate.parse(args.getString("to"))) { "报告日期范围有误" }
                }
            }
            previous.add(id)
        }
    }

    fun execute(plan:JSONObject,confirmed:Boolean=false):JSONObject {
        val steps=plan.getJSONArray("steps");require(steps.length() in 1..60)
        if(!confirmed && (steps.objects().any{it.s("actionId") in setOf("atlas.installPlugin","atlas.updatePlugin","atlas.rollbackPlugin")} || steps.objects().count{it.s("actionId") in setOf("atlas.deleteRecord","atlas.deleteObject")}>10)) return obj("status" to "PREVIEW","plan" to plan.copyJson().put("impact",pluginImpact(steps)))
        val refs=linkedMapOf<String,JSONObject>();val request=plan.s("requestId").ifEmpty{uid()};require(request.length<=160)
        store.one("atlas_operations",request)?.let{return JSONObject(it.s("result"))}
        val hasExternal=steps.objects().any{it.s("actionId") in ExternalActions.ids}
        val atomic=plan.optBoolean("atomic",true)
        if(hasExternal)require(!atomic || steps.length()==1){"外部操作与本地多步操作请使用 atomic=false"}
        if(!atomic || hasExternal) {
            steps.objects().forEachIndexed{i,step->
                val id=step.s("stepId","s$i");require(id !in refs);val args=resolve(step.optJSONObject("args") ?: JSONObject(),refs) as JSONObject;val key="$request:$id"
                try {
                    val old=store.one("atlas_operations",key)
                    refs[id]=if(old!=null)JSONObject(old.s("result"))else {
                        val result=if(step.s("actionId") in ExternalActions.ids)ExternalActions(store).dispatch(step.s("actionId"),args)else null
                        store.transact(plan.s("title","执行操作"),key){result ?: dispatch(step.s("actionId"),args,plan.s("rawInput"))}
                    }
                }catch(e:Exception){return obj("status" to "PARTIAL","steps" to JSONObject(refs.mapValues{it.value}),"failedStep" to id,"message" to "已完成 ${refs.size} 项；${e.message}","retryPlan" to plan)}
            }
            return store.transact(plan.s("title","执行操作"),request){obj("status" to "DONE","steps" to JSONObject(refs.mapValues{it.value}),"message" to completionMessage(steps,refs.size))}
        }
        return store.transact(plan.s("title","执行操作"),request) {
            steps.objects().forEachIndexed {i,step->val id=step.s("stepId","s$i");require(id !in refs) {"重复步骤"};val args=resolve(step.optJSONObject("args") ?: JSONObject(),refs) as JSONObject;refs[id]=dispatch(step.getString("actionId"),args,plan.s("rawInput"))}
            obj("status" to "DONE","steps" to JSONObject(refs.mapValues{it.value}),"message" to completionMessage(steps,refs.size))
        }
    }

    private fun completionMessage(steps:JSONArray,count:Int):String {val records=steps.objects().count{it.s("actionId")=="atlas.record"};return if(records>0)"已保存 $records 条记录" else "已完成 $count 项操作"}
    private fun resolve(value:Any,refs:Map<String,JSONObject>,deferRecord:Boolean=false):Any = when(value) {
        is JSONObject->if(value.has("\$ref")) {
            val path=value.getString("\$ref").split('.')
            if(deferRecord && path[0]=="record" && path[0] !in refs)value.copyJson()
            else {var result:Any=refs[path[0]] ?: error("引用的步骤尚未执行");val fields=path.drop(1).ifEmpty{listOf("id")};fields.forEach{field->result=when(val current=result){is JSONObject->current.get(field);is JSONArray->current.get(field.toInt());else->error("引用路径无效")}};when(result){is JSONObject->(result as JSONObject).copyJson();is JSONArray->JSONArray(result.toString());else->result}}
        }else JSONObject().apply{value.keys().forEach{key->put(key,resolve(value.get(key),refs,deferRecord || key in setOf("manifest","spec")))}}
        is JSONArray->JSONArray().apply{for(i in 0 until value.length())put(resolve(value.get(i),refs,deferRecord))}
        else->value
    }
    fun single(action:String,args:JSONObject,title:String=action,confirmed:Boolean=false)=execute(obj("requestId" to uid(),"title" to title,"steps" to JSONArray().put(obj("stepId" to "result","actionId" to action,"args" to args))),confirmed)
    fun dispatch(action:String,a:JSONObject,raw:String=""):JSONObject {
        when(action) {
            "atlas.getTheme"->{val t=ThemeRepository(store);return obj("appearance" to t.appearance(),"current" to t.current(),"themes" to JSONArray(t.themes()))}
            "atlas.saveTheme"->return ThemeRepository(store).save(a)
            "atlas.setAppearance"->return ThemeRepository(store).select(a)
            "atlas.resetTheme"->return ThemeRepository(store).reset()
            "atlas.deleteTheme"->return ThemeRepository(store).delete(a.getString("id"))
            "atlas.createObject" -> {
                val type=a.getString("type");require(type in objectTypes);val title=a.getString("title").trim();require(title.length in 1..120)
                val config=a.optJSONObject("config") ?: JSONObject();if(type=="METRIC") validateMetric(config)
                val parent=a.s("parentId").ifEmpty{null};if(parent!=null) require(store.one("atlas_objects",parent)!=null) {"上级对象不存在"}
                val id=a.s("id").ifEmpty{uid()};val t=now();store.put("atlas_objects",obj("id" to id,"parent_id" to parent,"type" to type,"title" to title,"config" to config.toString(),"plugin_id" to a.s("pluginId").ifEmpty{null},"revision" to 1,"archived" to 0,"created_at" to t,"updated_at" to t));return obj("id" to id,"title" to title)
            }
            "atlas.updateObject" -> {
                val row=store.one("atlas_objects",a.getString("id")) ?: error("对象不存在")
                if(a.has("parentId")) {val parent=a.s("parentId").ifEmpty{null};var cursor=parent;val seen=hashSetOf(row.getString("id"));while(cursor!=null){require(seen.add(cursor)){"目录不能循环包含"};cursor=store.one("atlas_objects",cursor)?.s("parent_id")?.ifEmpty{null}};row.put("parent_id",parent ?: JSONObject.NULL)}
                if(a.has("title")){require(a.getString("title").trim().isNotEmpty());row.put("title",a.getString("title").trim())}
                if(a.has("config")) {val next=a.getJSONObject("config");if(row.s("type")=="METRIC"){validateMetric(next);val before=JSONObject(row.s("config"));require(before.s("kind")==next.s("kind")) {"指标语义已有历史，需创建新指标"};require(before.s("unit")==next.s("unit")){"单位变化请通过插件迁移"};next.put("unitVersion",before.optInt("unitVersion",0));next.put("conversions",before.optJSONArray("conversions") ?: JSONArray());retainCalculationHistory(before,next,row.optLong("revision",1)+1,a.s("historyMode"),store.query("SELECT id FROM atlas_facts WHERE metric_id=? LIMIT 1",arrayOf(row.s("id"))).isNotEmpty())};row.put("config",next.toString())}
                if(a.has("archived")) row.put("archived",if(a.getBoolean("archived")) 1 else 0)
                row.put("revision",row.getLong("revision")+1);row.put("updated_at",now());store.put("atlas_objects",row);return obj("id" to row.s("id"))
            }
            "atlas.createEvent" -> return obj("id" to event(a.getString("text"),a.s("id").ifEmpty{uid()},a.s("date").ifEmpty{null},a.s("occurredAt").ifEmpty{null},a.s("zone",ZoneId.systemDefault().id),source="text"))
            "atlas.editEvent" -> {
                val event = store.one("atlas_events",a.getString("id")) ?: error("记录不存在")
                require(event.s("source")=="text") {"请选择文字记录"}
                val text = a.getString("text").trim()
                require(text.isNotBlank() && text.length <= 100000) {"请填写记录内容"}
                val date = a.s("date").takeIf { it.isNotBlank() }?.let(LocalDate::parse)
                val time = event.s("occurred_at").takeIf { it.isNotBlank() && date != null }?.let {
                    val old = Instant.parse(it).atZone(ZoneId.of(event.s("zone")))
                    if (old.toLocalDate() == date) old.toInstant().toString()
                    else old.with(java.time.temporal.ChronoField.EPOCH_DAY,date!!.toEpochDay()).toInstant().toString()
                }
                event.put("raw_input",text).put("local_date",date?.toString() ?: JSONObject.NULL)
                    .put("occurred_at",time ?: JSONObject.NULL)
                    .put("precision",if (time!=null) "INSTANT" else if (date!=null) "DATE" else "UNKNOWN")
                    .put("updated_at",now())
                store.put("atlas_events",event)
                return obj("id" to event.s("id"))
            }
            "atlas.deleteObject" -> return dispatch("atlas.updateObject",obj("id" to a.getString("id"),"archived" to true))
            "atlas.copyObject" -> {val source=store.one("atlas_objects",a.getString("id")) ?: error("对象不存在");return dispatch("atlas.createObject",obj("type" to source.s("type"),"title" to a.s("title",source.s("title")+" 副本"),"parentId" to source.s("parent_id").ifEmpty{null},"config" to JSONObject(source.s("config"))))}
            "atlas.relate"->{val from=a.getString("fromId");val to=a.getString("toId");require(store.one("atlas_objects",from)!=null && store.one("atlas_objects",to)!=null);val id=uid();store.put("atlas_relations",obj("id" to id,"from_id" to from,"to_id" to to,"kind" to a.s("kind","related")));return obj("id" to id)}
            "atlas.record" -> return record(a,raw)
            "atlas.editRecord","atlas.deleteRecord","atlas.restoreRecord" -> {
                val r=store.one("atlas_facts",a.getString("id")) ?: error("记录不存在")
                require(!a.has("_expectedRevision") || a.optLong("_expectedRevision") == r.optLong("revision")) {
                    "记录已变化，请重新提交指令核对"
                }
                if(action=="atlas.editRecord") {
                    val c=store.config(r.s("metric_id"));val fields=JSONObject(r.s("fields_json"));val transferGroup=if(fields.s("flow")=="transfer")fields.s("transferGroup")else ""
                    if(a.has("fields")){val supplied=a.getJSONObject("fields");supplied.keys().forEach{key->if(!key.startsWith("_"))fields.put(key,supplied.get(key))};validateFields(c,fields,a.optBoolean("allowIncomplete"))}
                    if(transferGroup.isNotEmpty())fields.put("flow","transfer").put("transferGroup",transferGroup)
                    val recompute=c.has("expression") && a.has("fields")
                    if(a.has("value") || recompute) {
                        var v=if(recompute)Expressions.eval(c.getJSONObject("expression"),fields.keys().asSequence().mapNotNull{k->fields.s(k).toBigDecimalOrNull()?.let{k to it}}.toMap())else a.s("value").ifEmpty{null}?.let(::decimal)
                        if(fields.s("flow")!="transfer"){if(c.s("role")=="expense")v=if(fields.s("flow")=="refund")v?.abs() else v?.abs()?.negate();if(c.s("role")=="income")v=v?.abs()}
                        if(c.s("kind")=="ALLOCATION")require(v==null || v>=BigDecimal.ZERO)
                        if(v!=null && c.s("role") in setOf("income","expense","balance","accountChange"))v=v.setScale(c.optInt("decimalPlaces",2),java.math.RoundingMode.UNNECESSARY)
                        r.put("value",v?.toPlainString() ?: JSONObject.NULL);fields.put("_unitVersion",c.optInt("unitVersion",0));fields.put("_schemaRevision",store.one("atlas_objects",r.s("metric_id"))!!.optLong("revision",1))
                        if(c.s("role") in setOf("income","expense","balance","accountChange")){if(v==null)fields.remove("_minorUnits")else fields.put("_minorUnits",v.movePointRight(c.optInt("decimalPlaces",2)).toBigIntegerExact().toString())}
                    }
                    if(a.has("date")){LocalDate.parse(a.getString("date"));require(a.getString("date")==r.s("local_date") || r.s("start_at").isEmpty() || a.optBoolean("clearTime") || a.has("startAt")){"修改时段日期需同时修改起止时间，或选择仅保留时长"};r.put("local_date",a.getString("date"))}
                    if(a.optBoolean("clearTime")){r.put("start_at",JSONObject.NULL);r.put("end_at",JSONObject.NULL);r.put("occurred_at",JSONObject.NULL);r.put("precision","DATE")}
                    if(a.has("startAt") || a.has("endAt")){require(c.s("kind")=="ALLOCATION");val start=Instant.parse(a.s("startAt",r.s("start_at")));val end=Instant.parse(a.s("endAt",r.s("end_at")));require(end>=start);val zone=ZoneId.of(a.s("zone",r.s("zone")));require(start.atZone(zone).toLocalDate().toString()==r.s("local_date") || c.s("role")=="sleep");r.put("start_at",start.toString());r.put("end_at",end.toString());r.put("zone",zone.id);r.put("precision","INTERVAL");r.put("value",Units.convert(secondsBetween(start,end),"s",c.s("unit")).toPlainString())}
                    if(r.s("start_at").isNotEmpty() && a.has("value") && !a.has("startAt") && !a.has("endAt")){val duration=Units.convert(secondsBetween(Instant.parse(r.s("start_at")),Instant.parse(r.s("end_at"))),"s",c.s("unit"));require(r.s("value").isNotEmpty() && decimal(r.s("value")).compareTo(duration)==0){"时长与原时段不一致，请修改起止时间或选择仅保留时长"}}
                    if(a.has("occurredAt")){r.put("occurred_at",Instant.parse(a.getString("occurredAt")).toString());r.put("precision","INSTANT")}
                    if(r.s("occurred_at").isNotEmpty())require(Instant.parse(r.s("occurred_at")).atZone(ZoneId.of(r.s("zone"))).toLocalDate().toString()==r.s("local_date")){"发生日期与原时刻不一致"}
                    r.put("fields_json",fields.toString())
                } else r.put("deleted",if(action=="atlas.deleteRecord") 1 else 0)
                r.put("updated_at",now());r.put("revision",r.getLong("revision")+1);store.put("atlas_facts",r);indexFields(r)
                val transferFields=JSONObject(r.s("fields_json"))
                if(transferFields.s("flow")=="transfer") {
                    val group=transferFields.s("transferGroup");val legs=store.query("SELECT * FROM atlas_facts WHERE event_id=?",arrayOf(r.s("event_id"))).filter{JSONObject(it.s("fields_json")).s("transferGroup")==group};require(legs.size==2){"转账需要两条关联变化"}
                    val peer=legs.first{it.s("id")!=r.s("id")};peer.put("deleted",r.getInt("deleted"))
                    if(action=="atlas.editRecord"){
                        require(r.s("value").isNotEmpty()){"转账金额不能为空"};val sourceConfig=store.config(r.s("metric_id"));val peerConfig=store.config(peer.s("metric_id"));val amount=normalizedValue(decimal(r.s("value")),transferFields,sourceConfig)
                        val other=Units.convert(amount,sourceConfig.s("unit"),peerConfig.s("unit")).negate();peer.put("value",other.toPlainString()).put("local_date",r.s("local_date"));val pf=JSONObject(peer.s("fields_json")).put("_unitVersion",peerConfig.optInt("unitVersion",0));if(peerConfig.s("role") in setOf("income","expense","balance","accountChange"))pf.put("_minorUnits",other.movePointRight(peerConfig.optInt("decimalPlaces",2)).toBigIntegerExact().toString());peer.put("fields_json",pf.toString())
                        peer.put("occurred_at",r.opt("occurred_at") ?: JSONObject.NULL).put("precision",r.s("precision"))
                    }
                    peer.put("updated_at",now()).put("revision",peer.getLong("revision")+1);store.put("atlas_facts",peer);indexFields(peer)
                }
                return obj("id" to r.s("id"))
            }
            "atlas.searchObjects" -> {val keyword=a.s("keyword");require(keyword.length<=100);return obj("objects" to JSONArray(store.query("SELECT * FROM atlas_objects WHERE archived=0 AND title LIKE ? ${if(a.s("type").isNotEmpty()) "AND type=?" else ""} ORDER BY title LIMIT 100 OFFSET ?",(listOf("%$keyword%")+listOfNotNull(a.s("type").ifEmpty{null})+a.optInt("offset").coerceAtLeast(0).toString()).toTypedArray())))}
            "atlas.query" -> return obj("metricId" to a.s("metricId"),"from" to a.s("from","0001-01-01"),"to" to a.s("to","9999-12-31"),"records" to JSONArray(store.facts(a.s("metricId").ifEmpty{null},a.s("from","0001-01-01"),a.s("to","9999-12-31"),a.optInt("offset"),a.optInt("limit",50).coerceAtMost(100))))
            "atlas.chart"->{val id=a.getString("metricId");val chart=a.s("chart","line");require(chart in setOf("line","bar","heatmap"));return obj("metricId" to id,"chart" to chart,"unit" to store.config(id).s("unit"),"points" to JSONArray(store.dailySeries(id,a.getString("from"),a.getString("to"))))}
            "atlas.aggregate" -> return store.aggregate(a.getString("metricId"),a.getString("from"),a.getString("to"),a.s("method",store.config(a.getString("metricId")).s("aggregate","SUM")))
            "atlas.createView" -> {val components=a.getJSONArray("components");validateUi(components);return dispatch("atlas.createObject",obj("type" to "VIEW","title" to a.getString("title"),"parentId" to a.s("parentId").ifEmpty{null},"config" to obj("component" to "schema","components" to components)))}
            "atlas.report" -> {val results=JSONArray();a.getJSONArray("metrics").let{for(i in 0 until it.length()) results.put(store.aggregate(it.getString(i),a.getString("from"),a.getString("to")))};return dispatch("atlas.createObject",obj("type" to "VIEW","title" to a.s("title","统计报告"),"config" to obj("component" to "report","metrics" to a.getJSONArray("metrics"),"results" to results,"from" to a.s("from"),"to" to a.s("to"))))}
            "atlas.installPlugin","atlas.updatePlugin" -> return install(a.getJSONObject("manifest"),action=="atlas.updatePlugin")
            "atlas.setPluginStatus" -> {if(!a.getBoolean("enabled")){val target=a.getString("pluginId");val dependents=store.query("SELECT manifest FROM atlas_plugins WHERE status='ACTIVE'").filter{row->(JSONObject(row.s("manifest")).optJSONArray("requires") ?: JSONArray()).objects().any{it.s("pluginId")==target}};require(dependents.isEmpty()){ "有已启用插件依赖此功能包" }};store.query("SELECT * FROM atlas_plugins WHERE plugin_id=? AND status='ACTIVE'",arrayOf(a.getString("pluginId"))).forEach{store.put("atlas_plugins",it.put("status",if(a.getBoolean("enabled")) "ACTIVE" else "DISABLED"))};if(a.getBoolean("enabled")) store.query("SELECT * FROM atlas_plugins WHERE plugin_id=? ORDER BY version DESC LIMIT 1",arrayOf(a.getString("pluginId"))).forEach{store.put("atlas_plugins",it.put("status","ACTIVE"))};return obj("pluginId" to a.s("pluginId"))}
            "atlas.rollbackPlugin" -> {
                val versions=store.query("SELECT * FROM atlas_plugins WHERE plugin_id=? ORDER BY version DESC",arrayOf(a.getString("pluginId")));require(versions.size>=2){"没有可回退的版本"}
                val current=JSONObject(versions[0].s("manifest"));val target=JSONObject(versions[1].s("manifest"));target.put("version",versions[0].getInt("version")+1);target.put("historyMode","recalculate")
                target.getJSONArray("metrics").objects().forEach{metric->current.getJSONArray("metrics").objects().firstOrNull{it.s("key")==metric.s("key")}?.let{latest->if(latest.s("unit")!=metric.s("unit")) metric.put("migrationFactor",BigDecimal.ONE.divide(decimal(latest.getString("migrationFactor")),java.math.MathContext.DECIMAL128).toPlainString())}}
                return install(target,true)
            }
            "atlas.layout" -> {val id=a.getString("objectId");require(store.one("atlas_objects",id)!=null);store.put("atlas_layout",obj("id" to id,"object_id" to id,"position" to a.optInt("position"),"hidden" to if(a.optBoolean("hidden")) 1 else 0));return obj("id" to id)}
            "atlas.workflow" -> {
                val spec=a.getJSONObject("spec").copyJson();require((spec.optJSONArray("steps")?.length() ?: 0) in 1..10);require(store.one("atlas_objects",spec.getString("triggerMetricId"))?.s("type")=="METRIC"){"触发指标不存在"}
                val id=a.s("id").ifBlank{uid()};require(id.length<=180);val before=store.one("atlas_workflows",id);spec.put("version",(before?.let{JSONObject(it.s("spec")).optInt("version",1)} ?: 0)+1)
                store.put("atlas_workflows",obj("id" to id,"title" to a.getString("title"),"spec" to spec.toString(),"enabled" to (before?.optInt("enabled") ?: 1)));return obj("id" to id,"version" to spec.optInt("version"))
            }
            "atlas.setWorkflowStatus"->{val row=store.one("atlas_workflows",a.getString("id")) ?: error("工作流不存在");store.put("atlas_workflows",row.put("enabled",if(a.getBoolean("enabled"))1 else 0));return obj("id" to row.s("id"))}
            "atlas.deleteWorkflow"->{store.remove("atlas_workflows",a.getString("id"));return obj("message" to "工作流已删除")}
            "time.start","time.pause","time.resume","time.stop" -> return timer(action,a)
            "finance.balance" -> {
                val anchorId=a.getString("snapshotMetricId");val definition=store.config(anchorId);require(definition.s("kind")=="SNAPSHOT");val to=a.s("asOf",LocalDate.now().toString());LocalDate.parse(to)
                val anchor=store.query("SELECT * FROM atlas_facts WHERE metric_id=? AND deleted=0 AND value IS NOT NULL AND local_date<=? ORDER BY local_date DESC,occurred_at DESC,created_at DESC LIMIT 1",arrayOf(anchorId,to)).firstOrNull() ?: return obj("value" to null,"reason" to "缺少实测快照")
                var balance=normalizedValue(decimal(anchor.s("value")),JSONObject(anchor.s("fields_json")),definition);var uncertain=BigDecimal.ZERO;var uncertainCount=0;var missingCount=0
                val changes=a.getJSONArray("changeMetricIds");require((0 until changes.length()).map{changes.getString(it)}.distinct().size==changes.length()){"账户变化指标重复"};for(i in 0 until changes.length()){
                    val id=changes.getString(i);val c=store.config(id);require(c.s("kind")=="CHANGE" && c.s("unit")==definition.s("unit")){"账户变化必须为同单位的变化指标"}
                    store.readableDatabase.rawQuery("SELECT value,fields_json,local_date,occurred_at FROM atlas_facts WHERE metric_id=? AND deleted=0 AND local_date BETWEEN ? AND ?",arrayOf(id,anchor.s("local_date"),to)).use{cursor->while(cursor.moveToNext()){
                        val relation=if(cursor.getString(2)>anchor.s("local_date")) 1 else if(anchor.s("occurred_at").isNotEmpty() && !cursor.isNull(3)) Instant.parse(cursor.getString(3)).compareTo(Instant.parse(anchor.s("occurred_at"))) else 0
                        if(relation<0)continue
                        if(cursor.isNull(0)){missingCount++;continue}
                        val v=normalizedValue(decimal(cursor.getString(0)),JSONObject(cursor.getString(1)),c)
                        if(relation>0) balance+=v else {uncertain+=v;uncertainCount++}
                    }}
                }
                return obj("value" to if(missingCount==0 && uncertainCount==0)balance.toPlainString() else null,"confirmedPart" to balance.toPlainString(),"unit" to definition.s("unit"),"anchorDate" to anchor.s("local_date"),"uncertainSameDayCount" to uncertainCount,"uncertainSameDayDelta" to uncertain.toPlainString(),"missingCount" to missingCount)
            }
            "finance.transfer" -> {
                val from=a.getString("fromMetricId");val to=a.getString("toMetricId");require(from!=to);val f=store.config(from);val t=store.config(to);require(f.s("kind")=="CHANGE" && t.s("kind")=="CHANGE" && f.s("unit")==t.s("unit")){"转账需要同币种的变化指标"}
                val amount=decimal(a.getString("amount"));require(amount>BigDecimal.ZERO);val e=event(raw,date=a.getString("date"));record(obj("metricId" to from,"value" to (-amount).toPlainString(),"date" to a.getString("date"),"eventId" to e,"fields" to obj("flow" to "transfer","transferGroup" to e)),raw,false);record(obj("metricId" to to,"value" to amount.toPlainString(),"date" to a.getString("date"),"eventId" to e,"fields" to obj("flow" to "transfer","transferGroup" to e)),raw,false);return obj("eventId" to e,"netChange" to "0")
            }
            "atlas.capabilities" -> {val all=JSONObject(catalog);store.query("SELECT manifest FROM atlas_plugins WHERE status='ACTIVE'").forEach{row->val m=JSONObject(row.s("manifest"));(m.optJSONArray("actions") ?: JSONArray()).objects().forEach{all.put("${m.s("id")}.${it.s("id")}",it)}};return obj("actions" to all)}
            "atlas.navigate" -> return obj("page" to a.s("page","spaces"),"objectId" to a.s("objectId"))
            "atlas.requestRollback","atlas.retryBuild"->{val row=store.one("atlas_builds",a.getString("buildId")) ?: error("构建任务不存在");val spec=JSONObject(row.s("request"));if(action=="atlas.requestRollback"){val commit=spec.s("baselineCommit");require(commit.matches(Regex("[a-f0-9]{40}"))){"此构建尚未保存可回退基线"};spec.put("kind","rollback").put("sourceCommit",commit).put("title","回退 · "+spec.s("title"))};spec.put("previousBuildId",row.s("id"));return dispatch("atlas.requestBuild",spec)}
            "atlas.requestBuild" -> {val id=uid();store.put("atlas_builds",obj("id" to id,"request" to a.toString(),"status" to "AWAITING_SERVICE","remote_id" to null,"artifact_url" to null,"detail" to "","created_at" to now(),"updated_at" to now()));return obj("id" to id,"status" to "AWAITING_SERVICE")}
            "atlas.correlation" -> {
                fun byDay(id:String):Map<String,Double> = store.dailySeries(id,a.getString("from"),a.getString("to")).mapNotNull{p->p.s("value").toDoubleOrNull()?.takeIf{it.isFinite()}?.let{p.s("date") to it}}.toMap()
                val x=byDay(a.getString("xMetricId"));val y=byDay(a.getString("yMetricId"));val pairs=x.keys.intersect(y.keys).map{x.getValue(it) to y.getValue(it)};return obj("pearson" to MathEngine.pearson(pairs),"samplePairs" to pairs.size,"definition" to "共同有数据日期；按各指标的日统计规则对齐；相关性不表明因果")
            }
            else -> {
                val active=store.query("SELECT manifest FROM atlas_plugins WHERE status='ACTIVE'")
                active.forEach {row->val m=JSONObject(row.s("manifest"));(m.optJSONArray("actions") ?: JSONArray()).objects().firstOrNull{"${m.s("id")}.${it.s("id")}"==action}?.let{spec->val metricId=store.query("SELECT id FROM atlas_objects WHERE plugin_id=? AND type='METRIC'",arrayOf(m.s("id"))).firstOrNull{store.config(it.s("id")).s("key")==spec.s("metricKey")}?.s("id") ?: error("指标未加载");return dispatch("atlas.${spec.s("template")}",a.copyJson().put("metricId",metricId),raw)}}
                throw CapabilityMissing(action)
            }
        }
    }
    private fun pluginImpact(steps:JSONArray):JSONArray {
        val result=JSONArray()
        steps.objects().forEach{step->val manifest=step.optJSONObject("args")?.optJSONObject("manifest") ?: return@forEach;val plugin=manifest.s("id")
            val existing=store.query("SELECT id,title,config FROM atlas_objects WHERE plugin_id=? AND type='METRIC'",arrayOf(plugin))
            (manifest.optJSONArray("metrics") ?: JSONArray()).objects().forEach{next->val old=existing.firstOrNull{JSONObject(it.s("config")).s("key")==next.s("key")} ?: return@forEach
                val count=store.query("SELECT COUNT(*) n FROM atlas_facts WHERE metric_id=? AND deleted=0",arrayOf(old.s("id"))).first().getLong("n")
                result.put(obj("title" to next.s("title"),"count" to count,"oldUnit" to JSONObject(old.s("config")).s("unit"),"newUnit" to next.s("unit"),"factor" to next.s("migrationFactor"),"expressionChanged" to (JSONObject(old.s("config")).optJSONObject("expression")?.toString()!=next.optJSONObject("expression")?.toString())))
            }
        };return result
    }
    private fun event(raw:String,id:String=uid(),date:String?=null,occurred:String?=null,zone:String=ZoneId.systemDefault().id,source:String="workspace"):String {
        require(id.length<=120 && raw.length<=100000);if(store.one("atlas_events",id)!=null)return id
        if(date!=null)LocalDate.parse(date);ZoneId.of(zone);if(occurred!=null)require(Instant.parse(occurred).atZone(ZoneId.of(zone)).toLocalDate().toString()==date){"事件发生时刻与日期不一致"}
        val t=now();store.put("atlas_events",obj("id" to id,"raw_input" to raw,"source" to source,"created_at" to t,"updated_at" to t,"zone" to zone,"local_date" to date,"occurred_at" to occurred,"precision" to if(occurred!=null) "INSTANT" else if(date!=null) "DATE" else "UNKNOWN"));return id
    }
    private fun record(a:JSONObject,raw:String,runWorkflow:Boolean=true):JSONObject {
        val metric=a.getString("metricId");val owner=store.one("atlas_objects",metric) ?: error("指标不存在");require(owner.s("type")=="METRIC" && owner.optInt("archived")==0)
        val plugin=owner.s("plugin_id");if(plugin.isNotEmpty()) require(store.query("SELECT id FROM atlas_plugins WHERE plugin_id=? AND status='ACTIVE'",arrayOf(plugin)).isNotEmpty()) {"请先启用插件"}
        val c=JSONObject(owner.s("config"));val kind=c.getString("kind");val date=a.s("date",LocalDate.now().toString());LocalDate.parse(date)
        val fields=(a.optJSONObject("fields") ?: JSONObject()).copyJson();if(a.s("sourceExcerpt").isNotBlank())fields.put("_sourceExcerpt",a.s("sourceExcerpt").take(4000));fields.put("_unitVersion",c.optInt("unitVersion",0));fields.put("_schemaRevision",owner.optLong("revision",1))
        validateFields(c,fields,a.optBoolean("allowIncomplete"));if(a.optBoolean("allowIncomplete"))fields.put("_incomplete",true)
        val value = recordValue(a, fields, c)
        if (c.s("role") in setOf("income","expense","balance","accountChange") && value != null) {
            fields.put("_minorUnits", value.movePointRight(value.scale()).toBigIntegerExact().toString())
        }
        val start=a.s("startAt").ifEmpty{null};val end=a.s("endAt").ifEmpty{null};val occurred=a.s("occurredAt").ifEmpty{null};val zone=a.s("zone").ifBlank{ZoneId.systemDefault().id};ZoneId.of(zone)
        if(start!=null){val startDate=Instant.parse(start).atZone(ZoneId.of(zone)).toLocalDate().toString();require(startDate==date || (c.s("role")=="sleep" && date<=startDate)){"时段开始日期与发生日期不一致"}}
        if(occurred!=null) {require(Instant.parse(occurred).atZone(ZoneId.of(zone)).toLocalDate().toString()==date){"发生时刻与日期不一致"}}
        val e=a.s("eventId").ifEmpty{event(raw,date=date,occurred=occurred,zone=zone)};require(store.one("atlas_events",e)!=null);val id=a.s("recordId").ifEmpty{uid()};require(id.length<=120 && store.one("atlas_facts",id)==null){"记录标识已存在"};val t=now()
        val fact=obj("id" to id,"event_id" to e,"metric_id" to metric,"kind" to kind,"value" to value?.toPlainString(),"local_date" to date,"occurred_at" to occurred,"start_at" to start,"end_at" to end,"precision" to if(start!=null) "INTERVAL" else if(occurred!=null) "INSTANT" else "DATE","zone" to zone,"fields_json" to fields.toString(),"deleted" to 0,"revision" to 1,"created_at" to t,"updated_at" to t)
        store.put("atlas_facts",fact);indexFields(fact)
        if(runWorkflow) store.query("SELECT * FROM atlas_workflows WHERE enabled=1 LIMIT 100").forEach{w->val spec=JSONObject(w.s("spec"));if(spec.s("triggerMetricId")==metric){val vars=fields.keys().asSequence().mapNotNull{k->fields.s(k).toBigDecimalOrNull()?.let{k to it}}.toMap()+listOfNotNull(value?.let{"value" to it}).toMap();if(!spec.has("condition") || Expressions.eval(spec.getJSONObject("condition"),vars)>BigDecimal.ZERO) (spec.optJSONArray("steps") ?: JSONArray()).objects().forEach{step->val actionId=step.s("actionId");require(actionId in setOf("atlas.createObject","atlas.updateObject","atlas.layout","atlas.relate","atlas.requestBuild","atlas.record","atlas.report")){"工作流动作未获授权"};val args=resolve(step.getJSONObject("args"),mapOf("record" to obj("id" to id,"metricId" to metric,"value" to value?.toPlainString(),"date" to date,"eventId" to e))) as JSONObject;if(actionId=="atlas.record") record(args,raw,false) else dispatch(actionId,args,raw)}}}
        return obj("id" to id,"eventId" to e,"metricId" to metric,"metricTitle" to owner.s("title"),"value" to value?.toPlainString(),"unit" to c.s("unit"),"date" to date,"fields" to fields)
    }
    private fun indexFields(f:JSONObject) {
        val fields=JSONObject(f.s("fields_json"));store.query("SELECT id,field_id FROM atlas_fields WHERE fact_id=?",arrayOf(f.s("id"))).filter{!fields.has(it.s("field_id"))}.forEach{store.remove("atlas_fields",it.s("id"))};fields.keys().forEach{k->store.put("atlas_fields",obj("id" to "${f.s("id")}:$k","fact_id" to f.s("id"),"field_id" to k,"value" to fields.s(k),"type" to if(fields.s(k).toBigDecimalOrNull()!=null) "decimal" else "text"))}
    }
    private fun install(m:JSONObject,upgrade:Boolean):JSONObject {
        manifestValid(m);val plugin=m.getString("id");val existing=store.query("SELECT * FROM atlas_plugins WHERE plugin_id=? ORDER BY version DESC",arrayOf(plugin));require(upgrade==existing.isNotEmpty()) {if(upgrade) "插件尚未安装" else "插件已存在，请选择升级"}
        (m.optJSONArray("requires") ?: JSONArray()).objects().forEach{dependency->require(dependency.s("pluginId")!=plugin);val installed=store.query("SELECT version FROM atlas_plugins WHERE plugin_id=? AND status='ACTIVE' ORDER BY version DESC LIMIT 1",arrayOf(dependency.s("pluginId"))).firstOrNull();require(installed!=null && installed.optInt("version")>=dependency.optInt("minVersion",1)){"缺少插件依赖 ${dependency.s("pluginId")}"}}
        val dependencies=store.query("SELECT manifest FROM atlas_plugins WHERE status='ACTIVE'").map{JSONObject(it.s("manifest"))}.associate{it.s("id") to (it.optJSONArray("requires") ?: JSONArray()).objects().map{r->r.s("pluginId")}}.toMutableMap()
        dependencies[plugin]=(m.optJSONArray("requires") ?: JSONArray()).objects().map{it.s("pluginId")}
        val visiting=hashSetOf<String>();val visited=hashSetOf<String>()
        fun visit(id:String){if(id in visited)return;require(visiting.add(id)){"插件依赖形成循环"};dependencies[id].orEmpty().forEach(::visit);visiting.remove(id);visited.add(id)}
        visit(plugin)
        val version=m.optInt("version",1);require(existing.isEmpty() || version>existing.first().getInt("version")){"版本号必须递增"}
        val parent=if(existing.isEmpty()) dispatch("atlas.createObject",obj("type" to "PROJECT","title" to m.getString("title"),"pluginId" to plugin)).getString("id") else store.query("SELECT id FROM atlas_objects WHERE plugin_id=? AND type='PROJECT'",arrayOf(plugin)).first().s("id")
        val old=store.query("SELECT * FROM atlas_objects WHERE plugin_id=? AND type='METRIC'",arrayOf(plugin)).associateBy{JSONObject(it.s("config")).s("key")};val ids=linkedMapOf<String,String>()
        m.getJSONArray("metrics").objects().forEach {c->val key=c.getString("key");val prev=old[key]
            if(prev==null) ids[key]=dispatch("atlas.createObject",obj("type" to "METRIC","title" to c.getString("title"),"parentId" to parent,"pluginId" to plugin,"config" to c)).getString("id")
            else {val before=JSONObject(prev.s("config"));require(before.s("kind")==c.s("kind")){"不能改变历史指标语义"};val id=prev.s("id");ids[key]=id
                val conversions=before.optJSONArray("conversions") ?: JSONArray();var unitVersion=before.optInt("unitVersion",0)
                if(before.s("unit")!=c.s("unit")){val factor=decimal(c.getString("migrationFactor"));require(factor>BigDecimal.ZERO);if(Units.dimension(before.s("unit"))!=null && Units.dimension(c.s("unit"))!=null)require(Units.convert(BigDecimal.ONE,before.s("unit"),c.s("unit")).compareTo(factor)==0){"单位转换系数不正确"};unitVersion++;conversions.put(obj("version" to unitVersion,"factor" to factor.toPlainString(),"from" to before.s("unit"),"to" to c.s("unit")))}
                c.put("unitVersion",unitVersion);c.put("conversions",conversions);retainCalculationHistory(before,c,prev.optLong("revision",1)+1,c.s("historyMode",m.s("historyMode")),store.query("SELECT id FROM atlas_facts WHERE metric_id=? LIMIT 1",arrayOf(id)).isNotEmpty())
                prev.put("config",c.toString());prev.put("title",c.getString("title"));prev.put("revision",prev.getLong("revision")+1);prev.put("updated_at",now());store.put("atlas_objects",prev)
            }
        }
        existing.forEach{store.put("atlas_plugins",it.put("status","SUPERSEDED"))};store.put("atlas_plugins",obj("id" to "$plugin@$version","plugin_id" to plugin,"version" to version,"manifest" to m.toString(),"status" to "ACTIVE","created_at" to now()))
        if(upgrade) store.query("SELECT * FROM atlas_objects WHERE plugin_id=? AND type='VIEW' AND archived=0",arrayOf(plugin)).forEach{store.put("atlas_objects",it.put("archived",1).put("updated_at",now()))}
        val views=m.optJSONArray("views") ?: JSONArray();views.objects().forEach{v->dispatch("atlas.createObject",obj("type" to "VIEW","title" to v.s("title","趋势"),"parentId" to parent,"pluginId" to plugin,"config" to obj("component" to "chart","metricId" to ids.getValue(v.getString("metricKey")),"chart" to v.s("chart","line"))))}
        (m.optJSONArray("screens") ?: JSONArray()).objects().forEach{screen->val components=JSONArray(screen.getJSONArray("components").toString());components.objects().forEach{c->if(c.has("metricKey"))c.put("metricId",ids.getValue(c.getString("metricKey")))};dispatch("atlas.createObject",obj("type" to "VIEW","title" to screen.s("title","页面"),"parentId" to parent,"pluginId" to plugin,"config" to obj("component" to "schema","components" to components)))}
        val prefix="plugin:"+plugin+":workflow:"
        store.query("SELECT * FROM atlas_workflows WHERE substr(id,1,?)=?",arrayOf(prefix.length.toString(),prefix)).forEach{store.put("atlas_workflows",it.put("enabled",0))}
        fun bind(value:Any):Any=when(value){is JSONObject->if(value.has("\$metric"))ids.getValue(value.getString("\$metric"))else JSONObject().apply{value.keys().forEach{put(it,bind(value.get(it)))}};is JSONArray->JSONArray().apply{for(i in 0 until value.length())put(bind(value.get(i)))};else->value}
        (m.optJSONArray("workflows") ?: JSONArray()).objects().forEachIndexed{i,w->val spec=bind(w.optJSONObject("spec") ?: w) as JSONObject;spec.put("triggerMetricId",ids.getValue(w.s("triggerMetricKey",spec.s("triggerMetricKey"))));val id=prefix+w.s("key",i.toString());dispatch("atlas.workflow",obj("id" to id,"title" to w.s("title","工作流"),"spec" to spec));val saved=store.one("atlas_workflows",id)!!;store.put("atlas_workflows",saved.put("enabled",1))}
        return obj("id" to parent,"pluginId" to plugin,"version" to version,"metricIds" to JSONObject(ids))
    }
    private fun timer(action:String,a:JSONObject):JSONObject {
        if(action=="time.start") {val metric=a.getString("metricId");require(store.config(metric).s("kind")=="ALLOCATION");val id=uid();store.put("atlas_timers",obj("id" to id,"metric_id" to metric,"state" to "RUNNING","periods" to "[]","running_at" to now(),"zone" to ZoneId.systemDefault().id,"created_at" to now(),"elapsed_start" to android.os.SystemClock.elapsedRealtime(),"boot_count" to android.provider.Settings.Global.getInt(store.appContext.contentResolver,"boot_count",0)));return obj("id" to id)}
        val timer=store.one("atlas_timers",a.getString("timerId")) ?: error("计时不存在");require(timer.s("state")!="STOPPED");val periods=JSONArray(timer.s("periods"));val running=timer.s("running_at")
        if(action=="time.resume") {require(timer.s("state")=="PAUSED");timer.put("state","RUNNING");timer.put("running_at",now());timer.put("elapsed_start",android.os.SystemClock.elapsedRealtime());timer.put("boot_count",android.provider.Settings.Global.getInt(store.appContext.contentResolver,"boot_count",0))}
        else {if(running.isNotEmpty()){val sameBoot=timer.optInt("boot_count",-1)==android.provider.Settings.Global.getInt(store.appContext.contentResolver,"boot_count",0);val end=if(sameBoot && !timer.isNull("elapsed_start")) Instant.parse(running).plusMillis((android.os.SystemClock.elapsedRealtime()-timer.getLong("elapsed_start")).coerceAtLeast(0)) else maxOf(Instant.parse(running),Instant.now());periods.put(obj("start" to running,"end" to end.toString()))};timer.put("periods",periods.toString());timer.put("running_at",JSONObject.NULL);timer.put("state",if(action=="time.stop") "STOPPED" else "PAUSED")}
        store.put("atlas_timers",timer)
        if(action=="time.stop") {
            val e=event("计时记录");val config=store.config(timer.s("metric_id"));val zone=ZoneId.of(timer.s("zone"))
            periods.objects().forEach{p->
                val start=Instant.parse(p.getString("start"));val end=Instant.parse(p.getString("end"))
                val pieces=if(config.s("role")=="sleep") listOf(start to end) else MathEngine.splitByDate(start,end,zone).keys.map{d->maxOf(start,d.atStartOfDay(zone).toInstant()) to minOf(end,d.plusDays(1).atStartOfDay(zone).toInstant())}
                pieces.forEach{(a,b)->
                    val seconds=secondsBetween(a,b);val amount=Units.convert(seconds,"s",config.s("unit"))
                    record(obj("eventId" to e,"metricId" to timer.s("metric_id"),"date" to (if(config.s("role")=="sleep")Instant.parse(timer.s("created_at")).atZone(zone).toLocalDate() else a.atZone(zone).toLocalDate()).toString(),"zone" to zone.id,"value" to amount.toPlainString(),"startAt" to a.toString(),"endAt" to b.toString()),"计时记录",false)
                }
            }
        }
        val result = obj("id" to timer.s("id"),"state" to timer.s("state"))
        if(action=="time.stop") {
            val unit=store.config(timer.s("metric_id")).s("unit")
            val seconds=MathEngine.sum(periods.objects().map{p->secondsBetween(Instant.parse(p.s("start")),Instant.parse(p.s("end")))})
            result.put("duration",Units.convert(seconds,"s",unit).stripTrailingZeros().toPlainString()).put("unit",unit)
        }
        return result
    }
}
