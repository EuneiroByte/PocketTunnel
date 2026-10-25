package com.kotunnel.client.tunnel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

/** Wire types for the OpenAI Secure MCP Tunnel protocol. */

@Serializable
data class PollEnvelope(val commands: List<PollCommand> = emptyList())

@Serializable
data class PollCommand(
    val request_id: String,
    val shard_token: String? = null,
    val command_type: String,
    val channel: String? = null,
    val created_at: String? = null,
    val response_timeout: String? = null,
    val headers: Map<String, List<String>>? = null,
    // Raw JSON-RPC request/notification. Left as JsonElement so we preserve IDs and params verbatim.
    val jsonrpc: JsonElement? = null
)

@Serializable
data class ResponseEnvelope(
    val request_id: String,
    val channel: String? = null,
    val resp_json: JsonElement? = null,
    val resp_headers: Map<String, List<String>>? = null,
    val resp_code: Int = 200,
    val resp_type: String = "jsonrpc_response"
)

@Serializable
data class TunnelMeta(
    val tunnel_id: String? = null,
    val status: String? = null
)
