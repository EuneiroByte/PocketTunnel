# KoTunnel — Kotlin Android OpenAI Secure MCP 隧道客户端

面向 OpenAI Secure MCP 隧道控制平面的纯 Kotlin Android 客户端。通过长轮询
`https://api.openai.com/v1/tunnels/{tunnel_id}/...`，将 JSON-RPC 请求转发至内置的
内存中 MCP 服务器或本地 Streamable-HTTP MCP 服务器，并通过
HTTPS 返回响应。无需开放入站端口，无需 NDK，无需 Go/Rust。


## 构建

```bash
./gradlew assembleRelease
```

已签名的发布版 APK：`app/build/outputs/apk/release/app-release.apk`。

## 安装

将 `kotunnel-v1.3.0-release.apk` 复制到手机（Android 8.0+）并点击它。

## 配置

1. 启动 KoTunnel。
2. 输入 **隧道 ID** 和 **控制平面 API 密钥**。
3. 选择 MCP 模式：
   - **内置演示** — 使用捆绑的示例工具。
   - **本地 HTTP** — 输入您的本地 MCP 服务器 URL（例如 `http://127.0.0.1:8765/mcp`），
 点击 **测试连接** 进行验证，然后点击 **开始**。
4. 点击 **开始**，等待状态卡显示 **已连接**（绿色圆点）。

## 从 ChatGPT 连接（插件 → 新插件 → 隧道模式）

1. 在 ChatGPT 插件页面，点击 **+ 新插件**。
2. 选择 **隧道 (Tunnel)** 连接模式。
3. 输入与应用中相同的 **隧道 ID**。
4. 在 **身份验证 (Authentication)** 处选择 **无身份验证 (No authentication)** ——
   隧道本身已通过控制平面 API 密钥完成身份验证。
5. 勾选风险确认框，然后点击 **创建 (Create)**。
6. ChatGPT 将通过隧道发送 `initialize` → `notifications/initialized` →
   `tools/list` 来建立会话。在本地 HTTP 模式下，这些调用将转发至您的
   MCP 服务器；在内置模式下，则由本地进行响应。

操作顺序很重要：**请先启动应用并等待显示“已连接”**，然后在
ChatGPT 端点击“创建”。

## 本地 HTTP 模式 — 注意事项

- 应用会将原始的 JSON-RPC 请求正文原样 POST 到您的 URL，并携带
  `Content-Type: application/json`、`Accept: application/json, text/event-stream` 头部，以及
  隧道提供的任何 `Mcp-Session-Id` / `Mcp-Protocol-Version` / `Authorization` 头部。
- 如果上游响应的 `Content-Type: text/event-stream`，应用会解析 SSE 事件
  并将每条通知（无 ID）作为 `jsonrpc_notify` 发回，然后将最终
  结果/错误作为 `jsonrpc_response` 发回。
- 上游 HTTP 状态码将作为 `resp_code` 回传；连接失败将模拟为
  `-32603` / `502`，并附带 `tunnel_failure` 请求体。
- “高级”部分中的 `Base URL` 是 OpenAI 控制平面 URL（默认
  `https://api.openai.com`），而非本地 MCP URL。

## 已实现的协议

- `GET  /v1/tunnels/{tunnel_id}/poll?limit=25&timeout_ms=15000`（204 = 继续）
- `POST /v1/tunnels/{tunnel_id}/response`，需包含 `X-Tunnel-Shard-Token`
- 请求头：`Authorization`、`X-Tunnel-Client-Name`、`X-Tunnel-Client-Version`、
  `X-Tunnel-Client-Capabilities: wrong-cluster-v1`、
  `X-Tunnel-Client-Wire-Protocol-Version: 2026-08-25`、
  `X-Tunnel-MCP-Server-Info: {"version":1,"channels":[{"name":"main","proc_affinity":true}]}`,
  `X-Tunnel-Client-Instance-Id`
- `response_timeout` 的相对时长以轮询请求接收时间为基准。
- 重试策略：网络/429/5xx 错误采用指数退避 + 抖动；401/403 错误则停止处理并向用户界面报错。
- 并发控制：单个轮询循环 + 有限的工作线程池（4 个）。
