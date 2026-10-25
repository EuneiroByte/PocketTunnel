package com.kotunnel.client.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feishu/Lark MCP dispatcher. Tool names aligned with official @larksuiteoapi/lark-mcp
 * (snake_case 4-segment: <module>_v<n>_<resource>_<action>).
 * 13 tools, descriptions in Chinese.
 */
@Singleton
class FeishuMcpDispatcher @Inject constructor(
    private val api: FeishuApiClient
) : McpDispatcher {

    override suspend fun dispatch(
        request: JsonElement,
        cmdHeaders: Map<String, List<String>>?
    ): McpResult {
        val obj = request as? JsonObject
        val id = obj?.get("id") ?: JsonNull
        val method = (obj?.get("method") as? JsonPrimitive)?.content
        val isNotification = id is JsonNull

        return when (method) {
            "initialize" -> McpResult(
                response = buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id)
                    putJsonObject("result") {
                        put("protocolVersion", "2026-07-28")
                        putJsonObject("capabilities") { putJsonObject("tools") {} }
                        putJsonObject("serverInfo") {
                            put("name", "kotunnel-feishu"); put("version", "2.2.0")
                        }
                    }
                },
                respHeaders = mapOf("Content-Type" to listOf("application/json")),
                respCode = 200
            )
            "tools/list" -> McpResult(
                response = buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id)
                    putJsonObject("result") {
                        putJsonArray("tools") {
                            add(toolImMessageCreate())
                            add(toolImMessageList())
                            add(toolImChatSearch())
                            add(toolImChatList())
                            add(toolDocxRawContent())
                            add(toolDocxSearch())
                            add(toolWikiGetNode())
                            add(toolContactBatchGetId())
                            add(toolCalendarPrimary())
                            add(toolCalendarEventList())
                            add(toolCalendarEventCreate())
                            add(toolCalendarEventPatch())
                            add(toolApprovalInstanceGet())
                        }
                    }
                },
                respHeaders = mapOf("Content-Type" to listOf("application/json")),
                respCode = 200
            )
            "tools/call" -> {
                val params = (obj?.get("params") as? JsonObject) ?: return McpResult(response = null)
                val toolName = (params["name"] as? JsonPrimitive)?.content
                val args = (params["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
                dispatchTool(id, toolName, args)
            }
            "notifications/initialized", "ping" -> {
                if (isNotification) McpResult(response = null)
                else McpResult(response = buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id); putJsonObject("result") {}
                })
            }
            else -> {
                if (isNotification) McpResult(response = null)
                else McpResult(response = buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id)
                    putJsonObject("error") { put("code", -32601); put("message", "method not found: $method") }
                })
            }
        }
    }

    private suspend fun dispatchTool(id: JsonElement, name: String?, args: JsonObject): McpResult {
        return try {
            val data: JsonElement = when (name) {
                "im_v1_message_create" -> {
                    val t = str(args, "receive_id_type") ?: return argErr(id, "receive_id_type 必填")
                    val r = str(args, "receive_id") ?: return argErr(id, "receive_id 必填")
                    val m = str(args, "msg_type") ?: return argErr(id, "msg_type 必填")
                    val c = str(args, "content") ?: return argErr(id, "content 必填")
                    api.imMessageCreate(t, r, m, c, str(args, "uuid"))
                }
                "im_v1_message_list" -> {
                    val chatId = str(args, "container_id") ?: return argErr(id, "container_id (chat_id) 必填")
                    api.imMessageList(chatId)
                }
                "im_v1_chat_search" -> {
                    val q = str(args, "query") ?: return argErr(id, "query 必填")
                    api.imChatSearch(q)
                }
                "im_v1_chat_list" -> api.imChatList()
                "docx_v1_document_raw_content" -> {
                    val docId = str(args, "document_id") ?: return argErr(id, "document_id 必填")
                    api.docxRawContent(docId)
                }
                "docx_builtin_search" -> {
                    val q = str(args, "search_key") ?: return argErr(id, "search_key 必填")
                    api.docxSearch(q)
                }
                "wiki_v2_space_get_node" -> {
                    val token = str(args, "token") ?: return argErr(id, "token 必填")
                    api.wikiGetNode(token)
                }
                "contact_v3_user_batch_get_id" -> {
                    val emails = strList(args, "emails")
                    val mobiles = strList(args, "mobiles")
                    if (emails.isEmpty() && mobiles.isEmpty()) return argErr(id, "emails 或 mobiles 至少填一个")
                    api.contactBatchGetId(emails, mobiles)
                }
                "calendar_v4_calendar_primary" -> api.calendarPrimary()
                "calendar_v4_calendar_event_list" -> {
                    val calId = str(args, "calendar_id") ?: return argErr(id, "calendar_id 必填")
                    api.calendarEventList(calId)
                }
                "calendar_v4_calendar_event_create" -> {
                    val calId = str(args, "calendar_id") ?: return argErr(id, "calendar_id 必填")
                    val summary = str(args, "summary") ?: return argErr(id, "summary 必填")
                    val startTs = long(args, "start_time") ?: return argErr(id, "start_time (unix秒) 必填")
                    val endTs = long(args, "end_time") ?: return argErr(id, "end_time (unix秒) 必填")
                    api.calendarEventCreate(calId, summary, startTs, endTs, str(args, "description"))
                }
                "calendar_v4_calendar_event_patch" -> {
                    val calId = str(args, "calendar_id") ?: return argErr(id, "calendar_id 必填")
                    val eventId = str(args, "event_id") ?: return argErr(id, "event_id 必填")
                    api.calendarEventPatch(calId, eventId, str(args, "summary"), str(args, "status"))
                }
                "approval_v4_instance_get" -> {
                    val instId = str(args, "instance_id") ?: return argErr(id, "instance_id 必填")
                    api.approvalInstanceGet(instId)
                }
                else -> return argErr(id, "未知工具: $name")
            }
            okResult(id, data.toString(), isError = false)
        } catch (e: FeishuException) {
            okResult(id, "飞书错误 [${e.code}] ${e.message}", isError = true)
        } catch (e: Exception) {
            McpResult(
                response = buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id)
                    putJsonObject("result") {
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", "text"); put("text", "网络错误: ${e.message}") })
                        }
                        put("isError", true)
                    }
                },
                respHeaders = mapOf("Content-Type" to listOf("application/json")),
                respCode = 502, isTunnelFailure = true, failureSource = "connect"
            )
        }
    }

    private fun okResult(id: JsonElement, text: String, isError: Boolean): McpResult = McpResult(
        response = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            putJsonObject("result") {
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", text) })
                }
                put("isError", isError)
            }
        },
        respHeaders = mapOf("Content-Type" to listOf("application/json")),
        respCode = 200
    )

    private fun argErr(id: JsonElement, msg: String): McpResult = okResult(id, msg, isError = true)

    private fun str(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun long(args: JsonObject, key: String): Long? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun strList(args: JsonObject, key: String): List<String> {
        val arr = (args[key] as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    // ---- tool schemas ----

    private fun tool(name: String, desc: String, required: List<String>,
                     props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("name", name); put("description", desc)
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonArray("required") { required.forEach { add(it) } }
                putJsonObject("properties", props)
            }
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.strProp(k: String, desc: String) {
        putJsonObject(k) { put("type", "string"); put("description", desc) }
    }
    private fun kotlinx.serialization.json.JsonObjectBuilder.intProp(k: String, desc: String) {
        putJsonObject(k) { put("type", "integer"); put("description", desc) }
    }
    private fun kotlinx.serialization.json.JsonObjectBuilder.enumProp(k: String, values: List<String>, desc: String) {
        putJsonObject(k) {
            put("type", "string"); put("description", desc)
            putJsonArray("enum") { values.forEach { add(it) } }
        }
    }
    private fun kotlinx.serialization.json.JsonObjectBuilder.strArr(k: String, desc: String) {
        putJsonObject(k) {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put("description", desc)
        }
    }

    private fun toolImMessageCreate() = tool("im_v1_message_create", "发送飞书消息（文本/富文本/卡片等）",
        listOf("receive_id_type","receive_id","msg_type","content")) {
        enumProp("receive_id_type", listOf("open_id","union_id","user_id","email","chat_id"), "接收者ID类型")
        strProp("receive_id", "接收者ID（与receive_id_type对应，如ou_xxx或oc_xxx）")
        enumProp("msg_type", listOf("text","post","image","file","interactive"), "消息类型")
        strProp("content", "消息体JSON字符串，text类型传 {\"text\":\"你好\"}")
        strProp("uuid", "可选，幂等键，防止重复发送")
    }
    private fun toolImMessageList() = tool("im_v1_message_list", "拉取指定群聊的历史消息（最近20条，按时间倒序）",
        listOf("container_id")) {
        strProp("container_id", "群聊chat_id (oc_xxx)")
    }
    private fun toolImChatSearch() = tool("im_v1_chat_search", "按名称搜索机器人所在的飞书群", listOf("query")) {
        strProp("query", "群名关键词")
    }
    private fun toolImChatList() = tool("im_v1_chat_list", "列出机器人所在的所有飞书群", emptyList()) {}
    private fun toolDocxRawContent() = tool("docx_v1_document_raw_content", "读取飞书云文档(docx)的纯文本内容",
        listOf("document_id")) { strProp("document_id", "文档document_id") }
    private fun toolDocxSearch() = tool("docx_builtin_search", "全局搜索飞书云文档", listOf("search_key")) {
        strProp("search_key", "搜索关键词")
    }
    private fun toolWikiGetNode() = tool("wiki_v2_space_get_node", "把wiki token解析成实际文档/知识库节点",
        listOf("token")) { strProp("token", "wiki节点token") }
    private fun toolContactBatchGetId() = tool("contact_v3_user_batch_get_id", "用邮箱或手机号批量换取飞书open_id",
        emptyList()) {
        strArr("emails", "邮箱列表")
        strArr("mobiles", "手机号列表")
    }
    private fun toolCalendarPrimary() = tool("calendar_v4_calendar_primary", "获取主日历calendar_id（创建日程前先调这个）",
        emptyList()) {}
    private fun toolCalendarEventList() = tool("calendar_v4_calendar_event_list", "列出指定日历下的日程",
        listOf("calendar_id")) { strProp("calendar_id", "日历ID（先调calendar_primary获取）") }
    private fun toolCalendarEventCreate() = tool("calendar_v4_calendar_event_create", "在指定日历上创建日程（时区Asia/Shanghai）",
        listOf("calendar_id","summary","start_time","end_time")) {
        strProp("calendar_id", "日历ID")
        strProp("summary", "日程标题")
        strProp("description", "日程描述（可选）")
        intProp("start_time", "开始时间（Unix秒）")
        intProp("end_time", "结束时间（Unix秒）")
    }
    private fun toolCalendarEventPatch() = tool("calendar_v4_calendar_event_patch", "修改或取消日程（改summary或置status为cancelled）",
        listOf("calendar_id","event_id")) {
        strProp("calendar_id", "日历ID")
        strProp("event_id", "日程ID")
        strProp("summary", "新标题（可选）")
        enumProp("status", listOf("tentative","confirmed","cancelled"), "日程状态（可选）")
    }
    private fun toolApprovalInstanceGet() = tool("approval_v4_instance_get", "查询飞书审批实例状态",
        listOf("instance_id")) { strProp("instance_id", "审批实例ID") }
}
