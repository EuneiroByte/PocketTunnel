package com.kotunnel.client.mcp

import com.kotunnel.client.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forwards JSON-RPC to a local Streamable-HTTP MCP server.
 * Handles both plain JSON responses and SSE (text/event-stream) streams.
 */
@Singleton
class HttpMcpClient @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    // Longer call timeout: tool execution can be slow.
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val ACCEPT = "application/json, text/event-stream"

    suspend fun dispatch(
        url: String,
        request: JsonElement,
        cmdHeaders: Map<String, List<String>>?
    ): McpResult = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(JsonElement.serializer(), request)
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", ACCEPT)
            .post(payload.toRequestBody(JSON_MEDIA))

        cmdHeaders?.get("Mcp-Session-Id")?.firstOrNull()?.let { builder.header("Mcp-Session-Id", it) }
        cmdHeaders?.get("Mcp-Protocol-Version")?.firstOrNull()?.let { builder.header("Mcp-Protocol-Version", it) }
        cmdHeaders?.get("Authorization")?.firstOrNull()?.let { builder.header("Authorization", it) }

        try {
            client.newCall(builder.build()).execute().use { resp ->
                val contentType = resp.header("Content-Type").orEmpty()
                val respHeaders = mutableMapOf<String, List<String>>()
                resp.header("Content-Type")?.let { respHeaders["Content-Type"] = listOf(it) }
                resp.header("Mcp-Session-Id")?.let { respHeaders["Mcp-Session-Id"] = listOf(it) }
                resp.header("Mcp-Protocol-Version")?.let { respHeaders["Mcp-Protocol-Version"] = listOf(it) }
                resp.header("Last-Event-ID")?.let { respHeaders["Last-Event-ID"] = listOf(it) }

                val bodyStr = resp.body?.string().orEmpty()
                when {
                    contentType.startsWith("text/event-stream") -> {
                        val (notifications, finalResp) = parseSse(bodyStr, request)
                        McpResult(
                            response = finalResp,
                            notifications = notifications,
                            respHeaders = respHeaders,
                            respCode = resp.code
                        )
                    }
                    else -> {
                        val parsed = runCatching { json.parseToJsonElement(bodyStr) }.getOrNull()
                        McpResult(
                            response = parsed,
                            respHeaders = respHeaders,
                            respCode = resp.code
                        )
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            tunnelFailure(request, "timeout", "upstream timeout")
        } catch (e: java.net.UnknownHostException) {
            tunnelFailure(request, "dns", "dns failure")
        } catch (e: java.net.ConnectException) {
            tunnelFailure(request, "connect", "connection refused")
        } catch (e: IOException) {
            tunnelFailure(request, "transport_closed", "io error: ${e.message}")
        } catch (e: Exception) {
            tunnelFailure(request, "client_internal", "unexpected: ${e.message}")
        }
    }

    /**
     * Parse an SSE body. Each event is separated by a blank line. `data: ` lines carry JSON
     * payloads. A notification (no id / null id) becomes an intermediate notification;
     * a request with an id becomes the final response.
     */
    private fun parseSse(body: String, originalRequest: JsonElement): Pair<List<JsonElement>, JsonElement?> {
        val notifications = mutableListOf<JsonElement>()
        var finalResp: JsonElement? = null

        val events = body.split(Regex("\r?\n\r?\n"))
        for (event in events) {
            val dataLines = event.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .toList()
            if (dataLines.isEmpty()) continue
            val joined = dataLines.joinToString("\n")
            val parsed = runCatching { json.parseToJsonElement(joined) }.getOrNull() ?: continue
            val obj = parsed as? JsonObject ?: continue
            val id = obj["id"]
            val hasMethod = obj.containsKey("method")
            val isNotification = id == null || id is JsonNull
            when {
                // Intermediate notification (no id, or has method and no id).
                isNotification -> notifications.add(parsed)
                // Final response (has id, has result or error).
                else -> finalResp = parsed
            }
            // Sanity: if we saw a method on a request-shaped object without id, treat as notification.
            if (hasMethod && isNotification && finalResp == null) {
                // already added above
            }
        }
        return notifications to finalResp
    }

    private fun tunnelFailure(
        request: JsonElement,
        source: String,
        message: String
    ): McpResult {
        val id = (request as? JsonObject)?.get("id") ?: JsonNull
        val err = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", -32603)
                put("message", "Bad Gateway")
                putJsonObject("data") {
                    putJsonObject("tunnel_failure") {
                        put("version", 1)
                        put("source", source)
                        put("upstream_response_received", false)
                    }
                }
            }
        }
        return McpResult(
            response = err,
            respHeaders = mapOf("Content-Type" to listOf("application/json")),
            respCode = 502,
            isTunnelFailure = true,
            failureSource = source
        )
    }

    /** Quick probe used by the UI "Test connection" button. */
    suspend fun probe(url: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val initReq = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", JsonPrimitive(1))
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", "2026-07-28")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "kotunnel-android")
                    put("version", BuildConfig.VERSION_NAME)
                }
            }
        }
        try {
            val result = dispatch(url, initReq, null)
            val resp = result.response
            val serverInfo = (resp as? JsonObject)?.get("result") as? JsonObject
            val si = serverInfo?.get("serverInfo") as? JsonObject
            val name = (si?.get("name") as? JsonPrimitive)?.contentOrNull
            val version = (si?.get("version") as? JsonPrimitive)?.contentOrNull
            if (result.isTunnelFailure) {
                false to "Upstream unreachable (${result.failureSource})"
            } else if (name != null) {
                true to "OK: $name v${version ?: "?"}"
            } else {
                true to "OK (initialize returned)"
            }
        } catch (e: Exception) {
            false to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
