<div align="center">

# PocketTunnel

</div>


[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-red.svg)](LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-100%237F52FF.svg?logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg?logo=android&logoColor=white)
![MCP](https://img.shields.io/badge/MCP-2026--07--28-000000.svg)
![ChatGPT](https://img.shields.io/badge/ChatGPT-Compatible-10A37F.svg?logo=openai&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4.svg)
[![Release](https://img.shields.io/github/v/release/EuneiroByte/PocketTunnel?label=Download)](https://github.com/EuneiroByte/PocketTunnel/releases/latest)

---

A native Android Kotlin MCP tunnel client. Connect ChatGPT to your phone, then to Feishu/Lark — no local server needed.

---

# Lark / Feishu MCP Connector

Connect Lark / Feishu to AI assistants through the Model Context Protocol (MCP).

Lark / Feishu MCP Connector provides an MCP-based integration with the Lark / Feishu Open Platform, allowing compatible AI clients to access supported collaboration tools.

The project is designed for self-hosted deployments and can be integrated with ChatGPT through OpenAI Secure MCP Tunnels.

## Features

- **MCP integration**: Expose supported Lark / Feishu API operations through MCP tools.
- **ChatGPT integration**: Connect through custom MCP connectors and OpenAI Secure MCP Tunnels.
- **Configurable tools**: Configure the tools available in the MCP server.
- **Lark / Feishu support**: Select the API endpoint for your deployment.
- **Self-hosted configuration**: Supply your own application credentials.
- **Extensible integration**: Build additional AI-powered collaboration workflows.

Available tools depend on the MCP server implementation, configuration, and application permissions.

## Architecture

```text
ChatGPT / MCP-Compatible AI Client
                 |
                 v
          Secure MCP Tunnel
                 |
                 v
              MCP Server
                 |
                 v
        Lark / Feishu Open API
                 |
                 v
       Your Lark / Feishu Workspace
```

## Requirements

Before setup, prepare:

- A Lark or Feishu account.
- An application created on the corresponding Open Platform.
- The application credentials required by the MCP server.
- The runtime and dependencies required by the project.
- An MCP-compatible client.
- For ChatGPT integration, access to custom MCP connectors and Secure MCP Tunnels.

## Getting Started

### 1. Create an Application

Choose the appropriate developer platform:

- **Lark Open Platform:** https://open.larksuite.com/
- **Feishu Open Platform:** https://open.feishu.cn/

Create an application and grant the permissions required by the tools you intend to use.

Keep your application credentials private.

### 2. Configure Environment Variables

The integration uses environment variables to configure the application credentials and API endpoint.

| Variable | Description |
|---|---|
| `APP_ID` | Lark / Feishu application ID |
| `APP_SECRET` | Lark / Feishu application secret |
| `LARK_DOMAIN` | API endpoint for the selected deployment |
| `LARK_TOOLS` | Tool selection configuration, where supported |

For a Feishu deployment, the API endpoint is typically:

```bash
LARK_DOMAIN=https://open.feishu.cn
```

Use the appropriate endpoint for your Lark deployment.

Set credentials in the environment used by the MCP server. Never commit real credentials or tokens to source control.

### 3. Configure the MCP Server

Configure your MCP client to launch the server using the command and environment variables required by this project.

The following illustrates the general MCP configuration structure:

```json
{
  "mcpServers": {
    "lark-feishu": {
      "command": "YOUR_MCP_SERVER_COMMAND",
      "args": [],
      "env": {
        "APP_ID": "${APP_ID}",
        "APP_SECRET": "${APP_SECRET}",
        "LARK_DOMAIN": "https://open.feishu.cn"
      }
    }
  }
}
```

Replace the example command with the actual launch command for your installation.

Environment-variable interpolation varies by MCP client. Verify that the server process receives the required credentials.

### 4. Verify the Integration

After starting the MCP server:

1. Confirm that the server starts successfully.
2. Confirm that the expected MCP tools are discovered.
3. Verify that the application has the required API permissions.
4. Test a supported operation against your Lark / Feishu workspace.

For example, `im.v1.chat.list` can be used to list chat groups when the corresponding tool is exposed and the application has sufficient permissions.

## ChatGPT Integration

This project is designed to work with ChatGPT through OpenAI Secure MCP Tunnels.

### Setup

1. Configure and test the MCP server.
2. Configure the tunnel client to launch or connect to the MCP server.
3. Associate the tunnel with the appropriate Platform organization and ChatGPT workspace.
4. Create a custom MCP connector in ChatGPT and select the tunnel.
5. Verify that ChatGPT discovers the expected tools.
6. Test the integration using an application with the required permissions.

### Official Documentation

- [OpenAI Secure MCP Tunnels](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)
- [OpenAI Custom MCP Servers](https://developers.openai.com/api/docs/guides/custom-mcp-server)
- [OpenAI Tunnel Client](https://github.com/openai/tunnel-client)
- [Lark OpenAPI MCP](https://github.com/larksuite/lark-openapi-mcp)
- [Model Context Protocol](https://modelcontextprotocol.io/)

**Troubleshooting:** A connected tunnel does not necessarily mean MCP tool discovery succeeded. If tools are missing, check the server process, environment variables, tunnel association, and tool discovery results.

## Security

- Never commit application secrets, access tokens, or private configuration files.
- Grant only the API permissions required by your use case.
- Avoid logging credentials or sensitive API responses.
- Use a dedicated development application for testing.
- Protect the tunnel client and MCP server runtime.
- Review the permissions and side effects of each enabled tool.
- Rotate exposed credentials immediately.

## Development

Contributions are welcome.

A typical development workflow:

1. Clone the repository.
2. Install the dependencies required by the implementation.
3. Configure a development application and its permissions.
4. Set the required environment variables.
5. Start the MCP server using the project's configured command.
6. Verify tool discovery with an MCP-compatible client.
7. Test supported API operations against a development workspace.

Refer to the repository's actual configuration and source code for the precise installation and launch commands.

## Contributing

Bug reports, feature requests, documentation improvements, and pull requests are welcome.

When reporting an issue, include:

- Operating system and runtime versions.
- MCP client and tunnel-client versions.
- The error message and relevant logs.
- Reproduction steps.

Remove credentials, tokens, and sensitive workspace information before sharing logs.

## License

The project license must be specified in the repository's `LICENSE` file.

Until a license is added, the project's licensing terms remain unspecified. Please add the intended open-source license before publishing the repository.

## Acknowledgements

- [Lark OpenAPI MCP](https://github.com/larksuite/lark-openapi-mcp)
- [OpenAI Tunnel Client](https://github.com/openai/tunnel-client)
- [Model Context Protocol](https://modelcontextprotocol.io/)

---

Built for open collaboration between AI assistants and Lark / Feishu.

---

## Acknowledgments & References

This project is an **independent Kotlin rewrite**, not a fork of any existing project.

- **[openai/tunnel-client](https://github.com/openai/tunnel-client)** — We implemented the
  Secure MCP Tunnel wire protocol (`GET /v1/tunnels/{id}/poll` + `POST /v1/tunnels/{id}/response`)
  from scratch in pure Kotlin, referencing their protocol specification only.
- **[@larksuiteoapi/lark-mcp](https://www.npmjs.com/package/@larksuiteoapi/lark-mcp)** — Tool naming
  conventions (`im_v1_message_create`, `docx_v1_document_raw_content`, etc.) align with the
  official Lark/Feishu MCP tool schema for compatibility, but all API calls are implemented
  directly via OkHttp against `open.feishu.cn` — no Lark SDK dependency.
- **Jetpack Compose / Material 3** — UI built with Google's official Android toolkit.

All application logic, Feishu adapters, tunnel engine, and UI design are original work.


## 赞助sponsor

如果这个项目帮到了你，欢迎请我喝杯奶茶~

![微信赞赏码](assets/wechat-donate.png)
