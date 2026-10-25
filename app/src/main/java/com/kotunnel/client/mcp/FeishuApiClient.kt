package com.kotunnel.client.mcp

import com.kotunnel.client.data.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class FeishuException(val code: Int, msg: String) : IOException("[$code] $msg")

/**
 * Thin Feishu/Lark Open Platform client. Manages tenant_access_token lifecycle
 * (cached in memory + ConfigStore, refreshed 5 min before expiry).
 */
@Singleton
class FeishuApiClient @Inject constructor(
    private val config: ConfigStore
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val media = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L
    private val tokenMutex = Mutex()

    /** Probe used by UI "Connect Feishu" / "Test connection" button.
     *  Two layers: (1) fetch tenant_access_token, (2) call bot/v3/info.
     *  Deliberately does NOT call contact/user endpoints (those need extra scopes
     *  and should only fail when the actual tool is invoked). */
    suspend fun testConnection(): Result<String> {
        return try {
            android.util.Log.i("FeishuApi", "testConnection: fetching tenant_access_token...")
            val t = ensureToken()
            android.util.Log.i("FeishuApi", "testConnection: token ok (len=${t.length}), checking bot/v3/info...")
            val remainMin = (tokenExpiresAt - System.currentTimeMillis()) / 60000
            val bot = authedGet("/open-apis/bot/v3/info") as? JsonObject
            val botObj = bot?.get("bot") as? JsonObject
            val appName = (botObj?.get("app_name") as? JsonPrimitive)?.contentOrNull
            val activateStatus = (botObj?.get("activate_status") as? JsonPrimitive)?.contentOrNull
            val botOk = activateStatus == "2"
            val botHint = if (botOk) "机器人已激活" else "机器人状态异常 (activate_status=$activateStatus)"
            Result.success("✓ 飞书连接成功 (token约${remainMin}分钟, $botHint${appName?.let{" app=$it"}?:""})")
        } catch (e: FeishuException) {
            android.util.Log.e("FeishuApi", "testConnection feishu error code=${e.code}", e)
            Result.failure(IOException(translateFeishuError(e.code, e.message ?: "")))
        } catch (e: java.net.SocketTimeoutException) {
            android.util.Log.e("FeishuApi", "testConnection timeout", e)
            Result.failure(IOException("连接飞书超时（15s内无响应），请检查网络后重试"))
        } catch (e: java.net.UnknownHostException) {
            android.util.Log.e("FeishuApi", "testConnection dns fail", e)
            Result.failure(IOException("无法解析 open.feishu.cn，请检查 DNS/网络"))
        } catch (e: java.net.ConnectException) {
            android.util.Log.e("FeishuApi", "testConnection connect fail", e)
            Result.failure(IOException("无法连接飞书服务器，请检查网络/代理/VPN"))
        } catch (e: javax.net.ssl.SSLException) {
            android.util.Log.e("FeishuApi", "testConnection ssl fail", e)
            Result.failure(IOException("与飞书的SSL握手失败: ${e.message ?: "unknown SSL error"}"))
        } catch (e: kotlinx.serialization.SerializationException) {
            android.util.Log.e("FeishuApi", "testConnection parse fail", e)
            Result.failure(IOException("飞书返回数据格式异常: ${e.message ?: "parse error"}"))
        } catch (e: Throwable) {
            android.util.Log.e("FeishuApi", "testConnection unexpected", e)
            val msg = e.message ?: e.javaClass.simpleName ?: "unknown error"
            Result.failure(IOException("内部错误: ${e.javaClass.simpleName} - $msg"))
        }
    }

    private fun translateFeishuError(code: Int, msg: String): String {
        val zh = when (code) {
            99991663 -> "应用未发布：请到飞书开放平台创建版本并提交发布，等待管理员审核通过"
            99991672 -> "应用未开通权限：请到开放平台「权限管理」勾选所需权限并发布版本"
            10003, 10004 -> "App ID 或 App Secret 错误，请检查后重试"
            99991668 -> "应用已被下架或封禁"
            99991661 -> "应用未开启机器人能力：请到开放平台「应用功能-机器人」开启"
            -1 -> "app_id 或 app_secret 未填写"
            else -> null
        }
        return if (zh != null) "✗ $zh [code=$code]" else "✗ 飞书错误 [code=$code] $msg"
    }

    private suspend fun ensureToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let {
            if (now < tokenExpiresAt) return it
        }
        // Restore from persistent storage on first call after process restart.
        if (cachedToken == null && config.feishuUserAccessToken.isNotBlank() && config.feishuTokenExpiry > now) {
            cachedToken = config.feishuUserAccessToken
            tokenExpiresAt = config.feishuTokenExpiry
            return cachedToken!!
        }
        return withContext(Dispatchers.IO) {
            tokenMutex.withLock {
                // double-check after lock
                cachedToken?.let {
                    if (System.currentTimeMillis() < tokenExpiresAt) return@withContext it
                }
                val appId = config.feishuAppId
                val appSecret = config.feishuAppSecret
                if (appId.isBlank() || appSecret.isBlank()) {
                    throw FeishuException(-1, "app_id or app_secret not configured")
                }
                val body = buildJsonObject {
                    put("app_id", appId)
                    put("app_secret", appSecret)
                }.toString().toRequestBody(media)
                val req = Request.Builder()
                    .url("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal/")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val obj = json.parseToJsonElement(text) as JsonObject
                    val code = (obj["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: -1
                    if (code != 0) {
                        val msg = (obj["msg"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
                        throw FeishuException(code, msg)
                    }
                    val token = (obj["tenant_access_token"] as? JsonPrimitive)?.contentOrNull
                        ?: throw FeishuException(-1, "no tenant_access_token in response")
                    val expireSec = (obj["expire"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 7200L
                    cachedToken = token
                    tokenExpiresAt = System.currentTimeMillis() + (expireSec - 300L) * 1000L
                    config.feishuUserAccessToken = token
                    config.feishuTokenExpiry = tokenExpiresAt
                    token
                }
            }
        }
    }

    private suspend fun authedGet(path: String): JsonElement = withContext(Dispatchers.IO) {
        val token = ensureToken()
        val req = Request.Builder()
            .url("https://open.feishu.cn$path")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = json.parseToJsonElement(text) as JsonObject
            val code = (parsed["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: -1
            if (code != 0) {
                val msg = (parsed["msg"] as? JsonPrimitive)?.contentOrNull ?: "request failed"
                throw FeishuException(code, msg)
            }
            return@withContext (parsed["data"] ?: parsed)
        }
    }

    private suspend fun authedPost(path: String, body: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val token = ensureToken()
        val req = Request.Builder()
            .url("https://open.feishu.cn$path")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(media))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = json.parseToJsonElement(text) as JsonObject
            val code = (parsed["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: -1
            if (code != 0) {
                val msg = (parsed["msg"] as? JsonPrimitive)?.contentOrNull ?: "request failed"
                throw FeishuException(code, msg)
            }
            return@withContext (parsed["data"] ?: parsed)
        }
    }

    // ---- 6 tools ----

    suspend fun sendMessage(receiveId: String, text: String): JsonElement {
        val body = buildJsonObject {
            put("receive_id", receiveId)
            put("msg_type", "text")
            put("content", """{"text":${JsonPrimitive(text)}}""")
        }
        return authedPost("/open-apis/im/v1/messages?receive_id_type=open_id", body)
    }

    suspend fun readDoc(documentId: String): JsonElement {
        return authedGet("/open-apis/docx/v1/documents/$documentId/raw_content")
    }

    suspend fun listCalendarEvents(): JsonElement {
        return authedGet("/open-apis/calendar/v4/calendars/primary/events?page_size=20")
    }

    suspend fun createCalendarEvent(
        summary: String,
        startTimeSec: Long,
        endTimeSec: Long,
        description: String?
    ): JsonElement {
        val body = buildJsonObject {
            put("summary", summary)
            if (!description.isNullOrBlank()) put("description", description)
            putJsonObject("start_time") {
                put("timestamp", startTimeSec.toString())
                put("timezone", "Asia/Shanghai")
            }
            putJsonObject("end_time") {
                put("timestamp", endTimeSec.toString())
                put("timezone", "Asia/Shanghai")
            }
        }
        return authedPost("/open-apis/calendar/v4/calendars/primary/events", body)
    }

    suspend fun getApproval(instanceId: String): JsonElement {
        return authedGet("/open-apis/approval/v4/instances/$instanceId")
    }

    suspend fun getUserInfo(userId: String): JsonElement {
        return authedGet("/open-apis/contact/v3/users/$userId?user_id_type=open_id")
    }

    // ---- v2.2.0: aligned with official lark-mcp ----

    suspend fun imMessageCreate(receiveIdType: String, receiveId: String, msgType: String, content: String, uuid: String?): JsonElement {
        val body = buildJsonObject {
            put("receive_id", receiveId)
            put("msg_type", msgType)
            put("content", content)
            if (!uuid.isNullOrBlank()) put("uuid", uuid)
        }
        return authedPost("/open-apis/im/v1/messages?receive_id_type=$receiveIdType", body)
    }

    suspend fun imChatList(pageSize: Int = 20): JsonElement =
        authedGet("/open-apis/im/v1/chats?page_size=$pageSize")

    suspend fun imChatSearch(query: String, pageSize: Int = 20): JsonElement =
        authedGet("/open-apis/im/v1/chats?search_key=$query&page_size=$pageSize")

    suspend fun imMessageList(chatId: String, pageSize: Int = 20): JsonElement =
        authedGet("/open-apis/im/v1/messages?container_id_type=chat&container_id=$chatId&sort_type=ByCreateTimeDesc&page_size=$pageSize")

    suspend fun docxRawContent(documentId: String): JsonElement =
        authedGet("/open-apis/docx/v1/documents/$documentId/raw_content")

    suspend fun docxSearch(searchKey: String, pageSize: Int = 20): JsonElement =
        authedGet("/open-apis/suite/docs/search?docs_type=docx&search_key=$searchKey&page_size=$pageSize")

    suspend fun wikiGetNode(token: String): JsonElement =
        authedGet("/open-apis/wiki/v2/spaces/get_node?token=$token")

    suspend fun contactBatchGetId(emails: List<String>, mobiles: List<String>): JsonElement {
        val body = buildJsonObject {
            if (emails.isNotEmpty()) putJsonArray("emails") { emails.forEach { add(it) } }
            if (mobiles.isNotEmpty()) putJsonArray("mobiles") { mobiles.forEach { add(it) } }
        }
        return authedPost("/open-apis/contact/v3/users/batch_get_id", body)
    }

    suspend fun calendarPrimary(): JsonElement =
        authedGet("/open-apis/calendar/v4/calendars/primary")

    suspend fun calendarEventList(calendarId: String, pageSize: Int = 20): JsonElement =
        authedGet("/open-apis/calendar/v4/calendars/$calendarId/events?page_size=$pageSize")

    suspend fun calendarEventCreate(
        calendarId: String, summary: String, startTimeSec: Long, endTimeSec: Long, description: String?
    ): JsonElement {
        val body = buildJsonObject {
            put("summary", summary)
            if (!description.isNullOrBlank()) put("description", description)
            putJsonObject("start_time") {
                put("timestamp", startTimeSec.toString()); put("timezone", "Asia/Shanghai")
            }
            putJsonObject("end_time") {
                put("timestamp", endTimeSec.toString()); put("timezone", "Asia/Shanghai")
            }
        }
        return authedPost("/open-apis/calendar/v4/calendars/$calendarId/events", body)
    }

    suspend fun calendarEventPatch(calendarId: String, eventId: String, summary: String?, status: String?): JsonElement {
        val body = buildJsonObject {
            if (summary != null) put("summary", summary)
            if (status != null) put("status", status)
        }
        return authedPatch("/open-apis/calendar/v4/calendars/$calendarId/events/$eventId", body)
    }

    suspend fun approvalInstanceGet(instanceId: String): JsonElement =
        authedGet("/open-apis/approval/v4/instances/$instanceId")

    private suspend fun authedPatch(path: String, body: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val token = ensureToken()
        val req = Request.Builder()
            .url("https://open.feishu.cn$path")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .patch(body.toString().toRequestBody(media))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = json.parseToJsonElement(text) as JsonObject
            val code = (parsed["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: -1
            if (code != 0) {
                val msg = (parsed["msg"] as? JsonPrimitive)?.contentOrNull ?: "request failed"
                throw FeishuException(code, msg)
            }
            return@withContext (parsed["data"] ?: parsed)
        }
    }
}
