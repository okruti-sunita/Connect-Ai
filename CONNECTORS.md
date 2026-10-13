# MCP connector support

Connect AI has one persisted MCP connection registry. The server-owned definitions in `server/src/main/resources/catalog/mcp-catalog.json` drive the Tools catalog and connection-method choices. A catalog card is not proof that a vendor integration is configured. For token and no-auth connections, the wizard tests a temporary MCP handshake and tool discovery before saving, then creates the persistent session and exposes only tools actually reported by that server.

## Current support in this repository

| Catalog entry | Current path | Status |
| --- | --- | --- |
| GitHub | GitHub MCP endpoint; configured static OAuth app or bearer token | Existing OAuth flow remains available. Token path uses the catalog test/save lifecycle. Tool access depends on provider authorization and discovered server tools. Not live-verified in this change. |
| Google Drive | Official Drive MCP endpoint and configured static Google OAuth provider | Existing OAuth flow is available; it has not been live-verified. Google lists the Drive MCP service as Developer Preview. OAuth callback still requires a separate MCP connect step in this release. |
| Jira | `mcp-atlassian` running separately on the configured local endpoint; its Jira credentials belong to that MCP process | Local MCP required; Connect AI does not host or provision that server. |
| Linear | Documented hosted endpoint; bearer token | Generic MCP bearer connection can test and discover tools. OAuth discovery is marked unsupported. |
| Atlassian | Documented Rovo MCP endpoint; Basic email/API-token method | Catalog enables Basic authentication. This path has not been verified against a live Atlassian tenant and may require organization-admin enablement. OAuth discovery remains unsupported. |
| Grafana, Splunk | User supplies a reachable Streamable HTTP endpoint; None or bearer token | Generic connection only; no hosted endpoint or provider account is provisioned. |
| Slack, Notion, PagerDuty, Datadog | Candidate metadata only | Required endpoint/auth method is unverified or unsupported; methods are disabled in the catalog. |
| Custom MCP | User supplies endpoint; None, bearer, Basic, custom header, or multiple headers | Generic Streamable HTTP methods implemented. OAuth discovery is disabled. Test and save use the shared API. |

The backend currently accepts `STREAMABLE_HTTP`; stdio and SSE are not supported by this connection path. Static OAuth provider IDs are configured on the server and exposed through `/api/oauth/providers`. Protected-resource metadata discovery and dynamic client registration are not implemented. The frontend must not infer OAuth readiness from a catalog card alone.

### Grafana

The catalog entry is not a managed Grafana OAuth integration. Grafana's open-source MCP server is a separate process and supports a Grafana service-account token (or username/password), while Grafana Cloud's hosted MCP server uses OAuth 2.1 and has its own client registration flow. Connect AI currently supports generic Streamable HTTP plus None/Bearer or its configured generic OAuth providers; it does not provision Grafana's server, register Grafana Cloud OAuth clients, or adapt arbitrary per-request Grafana authentication. See [Grafana MCP options](https://grafana.com/docs/grafana/latest/developer-resources/mcp/) and [Grafana authentication](https://grafana.com/docs/grafana/latest/developer-resources/mcp/configure/authentication/).

Google's official Drive MCP endpoint is `https://drivemcp.googleapis.com/mcp/v1`, uses Streamable HTTP, and needs Google Cloud API and OAuth setup. Google's current instructions list Drive API and Drive MCP API enablement and document the required scopes. See [Configure the Drive MCP server](https://developers.google.com/workspace/drive/api/guides/configure-mcp-server).

## Adding a connector

1. Add a definition to `server/src/main/resources/catalog/mcp-catalog.json` and keep keys stable. The server validates required metadata and method names at startup.
2. Mark only implemented authentication methods as supported. The shared connection client currently implements None, bearer, Basic, one custom header, and multiple headers. Only static OAuth app configuration is supported; protected-resource discovery/DCR is not.
3. Keep endpoint variants and provider claims accurate. An endpoint in the catalog does not establish that the tenant is enabled, the endpoint is reachable, or its discovered tools are read-only.
4. For static OAuth, add provider settings under `connectai.oauth.providers.<id>`. Keep client secrets in backend environment variables. `CONNECTAI_OAUTH_REDIRECT_URI` is the exact provider callback; `CONNECTAI_FRONTEND_ORIGIN` is the exact callback-message destination. Reuse the existing OAuth resource/service.
5. Add fake HTTP/MCP tests for methods, handshake, discovery, callback/token handling, errors, and secret redaction. Live provider verification requires configured credentials and explicit user authorization.

## Credentials and recovery

Bearer credentials are encrypted by the backend with `CONNECTAI_ENCRYPTION_KEY`; keep that key stable and back it up securely. Changing it makes existing ciphertext unreadable. Connect AI cannot recover an old plaintext token from ciphertext encrypted with a different key. For a BEARER connection, Tools → connection details → **Replace token** stores a newly supplied credential encrypted under the current key, then attempts a fresh MCP handshake. OAuth credentials must be renewed through the provider sign-in flow. Secrets are never returned by the connection API.

## Known limits

- There is no arbitrary transport/auth negotiation or dynamic OAuth client registration.
- Catalog entries do not imply that a vendor MCP endpoint exists, is enabled, or is read-only. Inspect the tools actually discovered and their permissions before relying on them.
- OAuth scopes do not by themselves guarantee read-only MCP tools. The investigation tool filtering and provider-side permissions remain important safeguards.
- Local/manual integrations require the external MCP process and its own service credentials to be installed and managed.
- OAuth callback completion still requires the frontend to establish the MCP connection; the callback does not yet auto-connect or return discovered-tool counts.
- `CONNECTAI_MCP_ALLOW_PRIVATE_HOSTS=true` is required for private/local MCP addresses. DNS is checked before connecting, but a DNS change between validation and client resolution cannot be fully pinned by the current JDK HTTP stack.
