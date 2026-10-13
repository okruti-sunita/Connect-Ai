# MCP connector support

Connect AI has one persisted MCP connection registry. The server-owned definitions in `server/src/main/resources/catalog/mcp-catalog.json` drive the Tools catalog and connection-method choices. A catalog card is not proof that a vendor integration is configured. For token and no-auth connections, the wizard tests a temporary MCP handshake and tool discovery before saving, then creates the persistent session and exposes only tools actually reported by that server.

## Current support in this repository

| Catalog entry | Current path | Status |
| --- | --- | --- |
| GitHub | GitHub.com hosted MCP over bearer PAT or a preconfigured GitHub OAuth app | PAT and OAuth tokens are stored encrypted. OAuth success waits for authenticated MCP initialize and tools/list. Fake OAuth/MCP only; no live-provider test. The app uses static provider settings and does not discover OAuth metadata. |
| Google Drive | Official Drive MCP endpoint over static Google OAuth | Callback now exchanges the code, stores/refreshes tokens encrypted, then automatically initializes MCP and discovers tools before success is reported. Fake OAuth/MCP tested; no live-provider test. Requires Workspace Developer Preview membership and Google Cloud setup. |
| Jira | `mcp-atlassian` running separately on the configured local endpoint; its Jira credentials belong to that MCP process | Local MCP required; Connect AI does not host or provision that server. |
| Linear | Documented hosted endpoint; bearer token | Generic MCP bearer connection can test and discover tools. OAuth discovery is marked unsupported. |
| Atlassian | Documented Rovo MCP endpoint; Basic email/API-token method | Catalog enables Basic authentication. This path has not been verified against a live Atlassian tenant and may require organization-admin enablement. OAuth discovery remains unsupported. |
| Grafana | User supplies a reachable Grafana MCP Streamable HTTP endpoint; no endpoint auth or bearer endpoint auth | Generic MCP lifecycle works after an operator runs/hosts the server. `GRAFANA_SERVICE_ACCOUNT_TOKEN` configures the MCP server's access to Grafana; Connect AI's bearer field is only for separate HTTP endpoint auth. Hosted Grafana Cloud OAuth is not implemented. Fake MCP only. |
| Splunk | User supplies the endpoint copied from the Splunk MCP Server app and its encrypted MCP token as bearer | Generic MCP lifecycle works. An ordinary Splunk URL/token is not equivalent. Fake MCP only. |
| PagerDuty | Hosted `https://mcp.pagerduty.com/mcp`; Authorization custom header | The shared custom-header transport can send `Authorization: Token token=…`; a successful MCP handshake is required before saving. Fake MCP only. |
| Datadog | User supplies site-specific hosted endpoint; `DD_API_KEY` and `DD_APPLICATION_KEY` custom headers | Multi-header auth is supported and verifies via MCP. OAuth 2.1 is not implemented. Fake MCP only; endpoint must be copied from Datadog's site selector. |
| Notion | Not offered as a direct connect method | Notion MCP is hosted and consent-based, but this backend does not implement its MCP OAuth discovery / authorization exchange. Use another supported MCP client until this app implements that protocol. |
| Slack | Candidate metadata only | Endpoint/authentication are not verified and no method is advertised. |
| Custom MCP | User supplies endpoint; None, bearer, Basic, custom header, or multiple headers | Generic Streamable HTTP methods implemented. OAuth discovery is disabled. Test and save use the shared API. |

The backend currently accepts `STREAMABLE_HTTP`; stdio and SSE are not supported by this connection path. Static OAuth provider IDs are configured on the server and exposed through `/api/oauth/providers`. Protected-resource metadata discovery and dynamic client registration are not implemented. The catalog endpoint computes supported methods and status from the backend configuration and runtime state; the UI does not infer OAuth readiness from a catalog card alone. OAuth completion remains pending until authenticated MCP initialize and bounded tools/list discovery have succeeded.

### Grafana

The catalog entry is not a managed Grafana OAuth integration. Grafana's open-source MCP server is a separate process; its operator configures `GRAFANA_URL` and `GRAFANA_SERVICE_ACCOUNT_TOKEN` on that server so it can call the Grafana API. Those are not credentials automatically sent by Connect AI to the MCP endpoint. The wizard's optional bearer credential is only for a separately protected MCP HTTP endpoint. Grafana Cloud's hosted MCP uses OAuth 2.1, which Connect AI does not implement. See [Grafana MCP options](https://grafana.com/docs/grafana/latest/developer-resources/mcp/) and [Grafana authentication](https://grafana.com/docs/grafana/latest/developer-resources/mcp/configure/authentication/).

Google Drive setup: a server administrator enables Drive API and Drive MCP API, enrolls the project in Workspace Developer Preview, configures the OAuth consent screen (Internal or External with test users), registers the Drive scopes in Google Auth Platform Data Access, and sets backend `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET`. Add the app callback URI as an authorized redirect URI: local default `http://localhost:8080/connect-ai/api/oauth/callback`; production must use the HTTPS URI configured by `CONNECTAI_OAUTH_REDIRECT_URI`. The runtime requests `https://www.googleapis.com/auth/drive.readonly`; Google’s setup guide asks administrators to list both `drive.readonly` and `drive.file` in Data Access. See [Configure the Drive MCP server](https://developers.google.com/workspace/drive/api/guides/configure-mcp-server).

GitHub: create a fine-grained PAT with only the repository and organization read permissions needed, paste it into the GitHub catalog wizard, and let the wizard verify the actual remote MCP handshake. GitHub's remote host is `https://api.githubcopilot.com/mcp/`. OAuth is available only when the backend has `GITHUB_OAUTH_CLIENT_ID` and `GITHUB_OAUTH_CLIENT_SECRET`; the registered callback must exactly match `CONNECTAI_OAUTH_REDIRECT_URI` (local default `http://localhost:8080/connect-ai/api/oauth/callback`). This application uses a preconfigured authorization-code client and does not perform OAuth metadata discovery. The callback still proves the token against the real MCP endpoint before reporting success. See the [GitHub MCP host integration guide](https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md).

PagerDuty: use the hosted endpoint and enter an API key in the Authorization header value using the documented `Token token=<key>` syntax. See [PagerDuty remote MCP](https://docs.pagerduty.com/developer/mcp-tooling-remote-server).

Datadog: choose the MCP endpoint for the account's site in Datadog's setup page. Configure custom headers exactly named `DD_API_KEY` and `DD_APPLICATION_KEY`, with scoped keys and `mcp_read` plus resource-level read permissions. Connect AI implements this header method, not Datadog's OAuth discovery. See [Datadog MCP setup and authentication](https://docs.datadoghq.com/mcp_server/setup/).

Splunk: the administrator installs/configures the Splunk MCP Server app and grants `mcp_tool_execute`; users generate an encrypted MCP token in that app (own token creation requires `edit_tokens_own` and `mcp_tool_admin`). Enter the MCP endpoint copied from that app and the encrypted token. Do not use the platform URL or an ordinary Splunk token. See [Splunk MCP connection settings](https://help.splunk.com/en/splunk-enterprise/mcp-server-for-splunk-platform/1.1/connecting-to-the-mcp-server-and-settings).

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
- OAuth callback tool discovery is server-side; the UI reads the final attempt status and fetches already discovered tools. Token audience/issuer validation is provider-specific and not implemented generically; each provider must be tested with its real endpoint before production use.
- `CONNECTAI_MCP_ALLOW_PRIVATE_HOSTS=true` is required for private/local MCP addresses. DNS is checked before connecting, but a DNS change between validation and client resolution cannot be fully pinned by the current JDK HTTP stack.
