package com.kotunnel.client.mcp

import kotlinx.serialization.json.JsonElement

/**
 * Abstraction over the MCP server behind the tunnel. Two implementations:
 *  - [InMemoryMcpServer] (built-in demo tools)
 *  - [HttpMcpClient] (forwards JSON-RPC to a local Streamable-HTTP MCP server)
 */
interface McpDispatcher {
    /**
     * Dispatch one incoming JSON-RPC request/notification.
     *
     * @param request the raw JSON-RPC object from the poll command.
     * @param cmdHeaders multi-valued headers from the poll command (Mcp-Session-Id etc).
     * @return [McpResult] — response element (null for notification-only), any intermediate
     *         SSE notifications, response headers to echo, HTTP-style code.
     */
    suspend fun dispatch(
        request: JsonElement,
        cmdHeaders: Map<String, List<String>>?
    ): McpResult
}

data class McpResult(
    val response: JsonElement? = null,
    val notifications: List<JsonElement> = emptyList(),
    val respHeaders: Map<String, List<String>> = emptyMap(),
    val respCode: Int = 200,
    /** True when a tunnel_failure error should be synthesized (upstream unreachable). */
    val isTunnelFailure: Boolean = false,
    /** tunnel_failure source tag when [isTunnelFailure] is true. */
    val failureSource: String = "client_internal"
)
