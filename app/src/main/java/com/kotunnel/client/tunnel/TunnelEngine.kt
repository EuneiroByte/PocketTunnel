package com.kotunnel.client.tunnel

import android.util.Log
import com.kotunnel.client.data.ConfigStore
import com.kotunnel.client.mcp.FeishuMcpDispatcher
import com.kotunnel.client.mcp.HttpMcpClient
import com.kotunnel.client.mcp.InMemoryMcpServer
import com.kotunnel.client.mcp.McpResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

data class LogLine(val ts: Long, val level: String, val msg: String)

@Singleton
class TunnelEngine @Inject constructor(
    private val client: TunnelClient,
    private val config: ConfigStore,
    private val mcp: InMemoryMcpServer,
    private val httpMcp: HttpMcpClient,
    private val feishuMcp: FeishuMcpDispatcher
) {
    companion object {
        private const val TAG = "TunnelEngine"
        const val MAX_WORKERS = 4
    }

    @Volatile var running: Boolean = false
        private set

    private val json = Json { ignoreUnknownKeys = true }

    // Observable state for UI.
    @Volatile var status: Status = Status.Idle
        private set

    sealed class Status {
        object Idle : Status()
        object Polling : Status()
        object Connected : Status()
        data class Error(val message: String) : Status()
        data class AuthError(val message: String) : Status()
    }

    suspend fun start(onLog: (LogLine) -> Unit) = coroutineScope {
        if (running) return@coroutineScope
        val tunnelId = config.tunnelId
        if (tunnelId.isBlank()) {
            status = Status.Error("Tunnel ID not configured")
            onLog(LogLine(System.currentTimeMillis(), "E", "Tunnel ID not configured, please fill in before starting"))
            return@coroutineScope
        }
        if (config.apiKey.isBlank()) {
            status = Status.Error("API key not configured")
            onLog(LogLine(System.currentTimeMillis(), "E", "API key not configured, please fill in before starting"))
            return@coroutineScope
        }
        running = true
        status = Status.Polling
        onLog(LogLine(System.currentTimeMillis(), "I", "starting poll loop; tunnel=$tunnelId"))

        val workers = Semaphore(MAX_WORKERS)
        var pollBackoffMs = 500L
        var pollCount = 0

        while (running) {
            try {
                val result = withContext(Dispatchers.IO) { client.poll(tunnelId) }
                pollBackoffMs = 500L
                pollCount++
                when (result) {
                    is TunnelClient.PollResult.NoCommands -> {
                        status = Status.Connected
                        // Heartbeat log every ~5 polls (~75s) so user sees the tunnel is alive.
                        if (pollCount % 5 == 1) {
                            onLog(LogLine(System.currentTimeMillis(), "I", "[POLL] heartbeat 204 (no commands, alive)"))
                        }
                    }
                    is TunnelClient.PollResult.Ok -> {
                        status = Status.Connected
                        val cmds = result.envelope.commands
                        onLog(LogLine(System.currentTimeMillis(), "I", "[POLL] 200, ${cmds.size} command(s)"))
                        val jobs = mutableListOf<Deferred<Unit>>()
                        for (cmd in cmds) {
                            workers.acquire()
                            jobs.add(async(Dispatchers.IO) {
                                try {
                                    processCommand(tunnelId, cmd, result.receivedAtNanos, onLog)
                                } catch (t: Throwable) {
                                    onLog(LogLine(System.currentTimeMillis(), "W", "cmd ${cmd.request_id} failed: ${t.message}"))
                                } finally {
                                    workers.release()
                                }
                            })
                        }
                        jobs.awaitAll()
                    }
                }
            } catch (e: AuthException) {
                status = Status.AuthError(e.message ?: "auth failed")
                onLog(LogLine(System.currentTimeMillis(), "E", "auth failure: ${e.message}; check API key"))
                running = false
                break
            } catch (e: IOException) {
                onLog(LogLine(System.currentTimeMillis(), "W", "poll error: ${e.message}; backing off"))
                status = Status.Error(e.message ?: "network error")
                delay(pollBackoffMs + Random.nextLong(0, 250))
                pollBackoffMs = min(pollBackoffMs * 2, 10_000L)
            } catch (c: CancellationException) {
                onLog(LogLine(System.currentTimeMillis(), "I", "poll loop cancelled"))
                throw c
            } catch (t: Throwable) {
                onLog(LogLine(System.currentTimeMillis(), "E", "unexpected: ${t.message}"))
                delay(1000)
            }
        }
        status = Status.Idle
        onLog(LogLine(System.currentTimeMillis(), "I", "poll loop stopped"))
    }

    fun stop() {
        running = false
    }

    private suspend fun processCommand(
        tunnelId: String,
        cmd: PollCommand,
        receivedAtNanos: Long,
        onLog: (LogLine) -> Unit
    ) {
        // Deadline from response_timeout.
        val timeoutNanos = parseResponseTimeout(cmd.response_timeout)
        if (timeoutNanos != null) {
            val deadline = receivedAtNanos + timeoutNanos
            val now = System.nanoTime()
            if (now >= deadline) {
                onLog(LogLine(System.currentTimeMillis(), "W", "drop ${cmd.request_id}: deadline already passed"))
                return
            }
        }

        when (cmd.command_type) {
            "jsonrpc" -> handleJsonRpc(tunnelId, cmd, onLog)
            "session_termination" -> {
                onLog(LogLine(System.currentTimeMillis(), "I", "session_termination ${cmd.request_id} channel=${cmd.channel}"))
                postResponse(
                    tunnelId, cmd,
                    ResponseEnvelope(
                        request_id = cmd.request_id,
                        channel = cmd.channel ?: "main",
                        resp_json = null,
                        resp_code = 204,
                        resp_type = "session_termination_response"
                    ),
                    onLog
                )
            }
            else -> {
                onLog(LogLine(System.currentTimeMillis(), "W", "unknown command_type=${cmd.command_type} for ${cmd.request_id}; skipping"))
            }
        }
    }

    /** Extract Mcp-Session-Id (and other allowlisted headers) from the command to echo back. */
    private fun buildRespHeaders(cmd: PollCommand): Map<String, List<String>> {
        val out = mutableMapOf<String, List<String>>()
        out["Content-Type"] = listOf("application/json")
        val cmdHeaders = cmd.headers ?: return out
        // Echo session id if present.
        cmdHeaders["Mcp-Session-Id"]?.firstOrNull()?.let { out["Mcp-Session-Id"] = listOf(it) }
        cmdHeaders["Mcp-Protocol-Version"]?.firstOrNull()?.let { out["Mcp-Protocol-Version"] = listOf(it) }
        return out
    }

    private suspend fun handleJsonRpc(
        tunnelId: String,
        cmd: PollCommand,
        onLog: (LogLine) -> Unit
    ) {
        val rpc: JsonElement = cmd.jsonrpc ?: run {
            onLog(LogLine(System.currentTimeMillis(), "W", "jsonrpc command ${cmd.request_id} missing jsonrpc field"))
            return
        }
        val rpcObj = rpc as? kotlinx.serialization.json.JsonObject
        val method = (rpcObj?.get("method") as? kotlinx.serialization.json.JsonPrimitive)?.content
        val sessionId = cmd.headers?.get("Mcp-Session-Id")?.firstOrNull()
        val mode = config.mcpMode
        onLog(
            LogLine(
                System.currentTimeMillis(), "I",
                "cmd ${cmd.request_id} method=$method channel=${cmd.channel} session=$sessionId mode=$mode"
            )
        )

        val result: McpResult = try {
            when (mode) {
                "http" -> {
                    val url = config.mcpServerUrl
                    if (url.isBlank()) {
                        onLog(LogLine(System.currentTimeMillis(), "E", "HTTP mode selected but MCP URL not set"))
                        return
                    }
                    withContext(Dispatchers.IO) { httpMcp.dispatch(url, rpc, cmd.headers) }
                }
                "feishu" -> {
                    onLog(LogLine(System.currentTimeMillis(), "I", "  [MCP] feishu (stub)"))
                    feishuMcp.dispatch(rpc, cmd.headers)
                }
                else -> mcp.dispatch(rpc, cmd.headers)
            }
        } catch (t: Throwable) {
            onLog(LogLine(System.currentTimeMillis(), "E", "mcp dispatch error: ${t.message}"))
            return
        }

        // Echo upstream resp headers merged with tunnel-required ones.
        val respHeaders = buildRespHeaders(cmd).toMutableMap()
        result.respHeaders.forEach { (k, v) -> respHeaders[k] = v }

        // 1) Post intermediate SSE notifications first (non-terminal).
        for (note in result.notifications) {
            val noteEnv = ResponseEnvelope(
                request_id = cmd.request_id,
                channel = cmd.channel ?: "main",
                resp_json = note,
                resp_headers = respHeaders,
                resp_code = 200,
                resp_type = "jsonrpc_notify"
            )
            postResponse(tunnelId, cmd, noteEnv, onLog)
        }

        // 2) Post final response.
        val finalResp = result.response
        val id = rpcObj?.get("id")
        val isNotification = id == null || id is JsonNull

        if (finalResp != null) {
            onLog(LogLine(System.currentTimeMillis(), "I", "  -> result/error for id=$id code=${result.respCode}"))
            postResponse(
                tunnelId, cmd,
                ResponseEnvelope(
                    request_id = cmd.request_id,
                    channel = cmd.channel ?: "main",
                    resp_json = finalResp,
                    resp_headers = respHeaders,
                    resp_code = result.respCode,
                    resp_type = "jsonrpc_response"
                ),
                onLog
            )
        } else if (isNotification) {
            onLog(LogLine(System.currentTimeMillis(), "I", "  -> notify_ack for $method"))
            postResponse(
                tunnelId, cmd,
                ResponseEnvelope(
                    request_id = cmd.request_id,
                    channel = cmd.channel ?: "main",
                    resp_json = null,
                    resp_headers = respHeaders,
                    resp_code = 204,
                    resp_type = "notify_ack"
                ),
                onLog
            )
        }
    }

    private suspend fun postResponse(
        tunnelId: String,
        cmd: PollCommand,
        envelope: ResponseEnvelope,
        onLog: (LogLine) -> Unit
    ) {
        // Best-effort post with bounded retry for transient errors.
        var attempt = 0
        while (true) {
            try {
                val code = withContext(Dispatchers.IO) {
                    client.postResponse(tunnelId, cmd.shard_token, envelope)
                }
                when {
                    code in 200..299 -> {
                        onLog(LogLine(System.currentTimeMillis(), "I", "  POST ${envelope.resp_type} ${cmd.request_id} -> $code"))
                        return
                    }
                    code == 401 || code == 403 -> {
                        onLog(LogLine(System.currentTimeMillis(), "E", "  POST ${cmd.request_id} -> $code (auth)"))
                        return
                    }
                    code == 404 -> {
                        onLog(LogLine(System.currentTimeMillis(), "W", "  POST ${cmd.request_id} -> 404 (already done)"))
                        return
                    }
                    code == 429 || code == 408 || code == 502 || code == 503 || code == 504 -> {
                        attempt++
                        if (attempt > 4) {
                            onLog(LogLine(System.currentTimeMillis(), "W", "  POST ${cmd.request_id} gave up after $code"))
                            return
                        }
                        delay(500L * attempt + Random.nextLong(0, 250))
                    }
                    else -> {
                        onLog(LogLine(System.currentTimeMillis(), "W", "  POST ${cmd.request_id} -> $code (no retry)"))
                        return
                    }
                }
            } catch (e: IOException) {
                attempt++
                if (attempt > 4) {
                    onLog(LogLine(System.currentTimeMillis(), "W", "  POST ${cmd.request_id} net fail: ${e.message}"))
                    return
                }
                delay(500L * attempt + Random.nextLong(0, 250))
            }
        }
    }

    /** Parse "30s"/"4500ms"/"0s" into nanos; returns null on malformed. */
    private fun parseResponseTimeout(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val match = Regex("^(\\d+)(ns|us|ms|s|m|h)$").matchEntire(s) ?: return null
        val value = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2]
        return when (unit) {
            "ns" -> value
            "us" -> value * 1_000
            "ms" -> value * 1_000_000
            "s" -> value * 1_000_000_000
            "m" -> value * 60_000_000_000
            "h" -> value * 3_600_000_000_000
            else -> null
        }
    }
}
