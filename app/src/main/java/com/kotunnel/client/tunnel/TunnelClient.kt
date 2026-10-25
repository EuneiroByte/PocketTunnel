package com.kotunnel.client.tunnel

import com.kotunnel.client.data.ConfigStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class AuthException(message: String) : IOException(message)

@Singleton
class TunnelClient @Inject constructor(
    private val config: ConfigStore
) {
    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val CLIENT_NAME = "kotlin-android-tunnel-client"
        const val CLIENT_VERSION = "3.0.0"
        const val WIRE_PROTOCOL_VERSION = "2026-08-25"
        const val CAPABILITIES = "wrong-cluster-v1"
        val MCP_SERVER_INFO: String = buildJsonObject {
            put("version", JsonPrimitive(1))
            put("channels", buildJsonArray {
                add(buildJsonObject {
                    put("name", JsonPrimitive("main"))
                    // In-memory MCP server is process-affine (protocol.md: stdio/in-memory rule).
                    put("proc_affinity", JsonPrimitive(true))
                })
            })
        }.toString()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    // Long-poll: read timeout must exceed server long-poll window (15s).
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun base() = config.baseUrl.ifBlank { "https://api.openai.com" }

    private fun Request.Builder.withCommonHeaders(): Request.Builder {
        header("Authorization", "Bearer ${config.apiKey}")
        header("X-Tunnel-Client-Name", CLIENT_NAME)
        header("X-Tunnel-Client-Version", CLIENT_VERSION)
        header("X-Tunnel-Client-Capabilities", CAPABILITIES)
        header("X-Tunnel-Client-Wire-Protocol-Version", WIRE_PROTOCOL_VERSION)
        header("X-Tunnel-MCP-Server-Info", MCP_SERVER_INFO)
        header("X-Tunnel-Client-Instance-Id", config.instanceId)
        return this
    }

    sealed class PollResult {
        object NoCommands : PollResult()
        data class Ok(val envelope: PollEnvelope, val receivedAtNanos: Long) : PollResult()
    }

    /** Returns HTTP status. Throws AuthException on 401/403. */
    fun poll(tunnelId: String, limit: Int = 25, timeoutMs: Int = 15000): PollResult {
        val url = "${base()}/v1/tunnels/$tunnelId/poll?limit=$limit&timeout_ms=$timeoutMs"
        val req = Request.Builder()
            .url(url)
            .withCommonHeaders()
            .get()
            .build()

        http.newCall(req).execute().use { resp ->
            val receivedAt = System.nanoTime()
            if (resp.code == 204) return PollResult.NoCommands
            if (resp.code == 401 || resp.code == 403) {
                throw AuthException("auth failed: HTTP ${resp.code}")
            }
            if (!resp.isSuccessful) {
                throw IOException("poll http ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return PollResult.NoCommands
            val envelope = json.decodeFromString(PollEnvelope.serializer(), body)
            return PollResult.Ok(envelope, receivedAt)
        }
    }

    /** Post a response. Returns HTTP status. */
    fun postResponse(
        tunnelId: String,
        shardToken: String?,
        envelope: ResponseEnvelope
    ): Int {
        val url = "${base()}/v1/tunnels/$tunnelId/response"
        val payload = json.encodeToString(ResponseEnvelope.serializer(), envelope)
        val builder = Request.Builder()
            .url(url)
            .withCommonHeaders()
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA))
        if (!shardToken.isNullOrBlank()) {
            builder.header("X-Tunnel-Shard-Token", shardToken)
        }
        http.newCall(builder.build()).execute().use { resp ->
            resp.body?.close()
            return resp.code
        }
    }
}
