package com.kotunnel.client.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory MCP server. Handles JSON-RPC 2.0 requests from the tunnel:
 *   initialize / notifications/initialized / tools/list / tools/call
 * Built-in demo tools: server_info, echo, uppercase.
 */
@Singleton
class InMemoryMcpServer @Inject constructor() : McpDispatcher {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    override suspend fun dispatch(
        request: JsonElement,
        cmdHeaders: Map<String, List<String>>?
    ): McpResult {
        val resp = handle(request)
        return McpResult(
            response = resp,
            respHeaders = mapOf("Content-Type" to listOf("application/json")),
            respCode = if (resp != null) 200 else 204
        )
    }

    /**
     * Handle a raw JSON-RPC request/notification element. Returns the JSON-RPC response element
     * (with matching id) or null if this was a notification (no response expected).
     */
    fun handle(request: JsonElement): JsonElement? {
        val obj = request as? JsonObject ?: return error(null, -32600, "invalid request")
        val id = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.content
        val params = obj["params"]

        if (method == null) {
            return error(id, -32600, "missing method")
        }

        // Notifications have no id -> no response.
        val isNotification = id == null || id is JsonNull

        return when (method) {
            "initialize" -> {
                // Negotiate: honor the client's requested protocolVersion if present,
                // otherwise advertise our latest supported MCP version.
                val requested = (params as? JsonObject)?.get("protocolVersion") as? JsonPrimitive
                val negotiated = requested?.content ?: "2026-07-28"
                val result = buildJsonObject {
                    put("protocolVersion", negotiated)
                    putJsonObject("capabilities") {
                        putJsonObject("tools") {}
                    }
                    putJsonObject("serverInfo") {
                        put("name", "kotunnel-android")
                        put("version", "1.2.0")
                    }
                }
                if (isNotification) null else ok(id, result)
            }
            "notifications/initialized" -> null // ack only
            "ping" -> if (isNotification) null else ok(id, buildJsonObject {})
            "tools/list" -> {
                val result = buildJsonObject {
                    putJsonArray("tools") {
                        addJsonObject {
                            put("name", "server_info")
                            put("description", "Return app and client version info.")
                            putJsonObject("inputSchema") {
                                put("type", "object")
                            }
                        }
                        addJsonObject {
                            put("name", "echo")
                            put("description", "Echo back the given text.")
                            putJsonObject("inputSchema") {
                                put("type", "object")
                                putJsonArray("required") { add("text") }
                                putJsonObject("properties") {
                                    putJsonObject("text") {
                                        put("type", "string")
                                        put("description", "Text to echo")
                                    }
                                }
                            }
                        }
                        addJsonObject {
                            put("name", "uppercase")
                            put("description", "Uppercase the given text.")
                            putJsonObject("inputSchema") {
                                put("type", "object")
                                putJsonArray("required") { add("text") }
                                putJsonObject("properties") {
                                    putJsonObject("text") {
                                        put("type", "string")
                                    }
                                }
                            }
                        }
                    }
                }
                if (isNotification) null else ok(id, result)
            }
            "tools/call" -> {
                val result = handleToolCall(params)
                if (isNotification) null else ok(id, result)
            }
            else -> {
                if (isNotification) null else error(id, -32601, "method not found: $method")
            }
        }
    }

    private fun handleToolCall(params: JsonElement?): JsonObject {
        val p = (params as? JsonObject) ?: return toolError("missing params")
        val name = (p["name"] as? JsonPrimitive)?.content
        val args = (p["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
        return when (name) {
            "server_info" -> buildJsonObject {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", "kotunnel-android v1.0.0; client=kotlin-android-tunnel-client")
                    }
                }
            }
            "echo" -> {
                val text = (args["text"] as? JsonPrimitive)?.content ?: ""
                buildJsonObject {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    }
                }
            }
            "uppercase" -> {
                val text = (args["text"] as? JsonPrimitive)?.content ?: ""
                buildJsonObject {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", text.uppercase())
                        }
                    }
                }
            }
            else -> toolError("unknown tool: $name")
        }
    }

    private fun toolError(msg: String): JsonObject = buildJsonObject {
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", msg)
            }
        }
        put("isError", true)
    }

    private fun ok(id: JsonElement?, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }

    private fun error(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }
}
