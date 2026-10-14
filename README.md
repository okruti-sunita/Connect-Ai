# Connect AI

An AI investigation assistant. You connect MCP servers (for example the GitHub MCP server),
ask a question, and the backend:

1. builds a catalog of the **live** tools of every connected server,
2. shortlists the few tools relevant to the question (deterministic ranking),
3. lets the LLM pick one candidate and its arguments (schema-constrained JSON output),
4. validates the choice against the server-side allow-list and the tool's real input schema,
5. executes it over MCP, records the step, and repeats within strict budgets,
6. writes an answer that may only cite the evidence the tools actually returned.

```
client/  React 19 + Vite + TypeScript + Ant Design   (Dashboard, Connections, Investigations)
server/  Spring Boot 3.5, Java 21, PostgreSQL + Liquibase, MCP Java SDK 2.0.1, Ollama or Anthropic
```

## Run it locally

Requirements: JDK 21+ (JDK 25 works too), Maven 3.9+, Node 22, Docker (for PostgreSQL), Ollama.

```bash
# 1. Database (pgvector/PostgreSQL 16 with connect/connect)
docker compose up -d
#    port 5432 already used by another PostgreSQL?  CONNECTAI_PG_PORT=5433 docker compose up -d
#    and set DB_URL=jdbc:postgresql://localhost:5433/connect for the backend.

# 2. Local model
ollama pull qwen3:1.7b
ollama serve            # if it is not already running as a service

# 3. Backend OAuth settings (one-time; edit the ignored file with your own OAuth app values)
cp server/config-example/application.yml server/config/application.yml
# Edit server/config/application.yml and fill the provider client IDs and secrets you use.

```

4. Start the backend from Windows PowerShell. The helper restores the protected local encryption key and
   starts Spring Boot with `server/` as its working directory:

   ```powershell
   cd server
   .\start-local-google-drive.ps1
   ```

5. Start the frontend in another terminal:

   ```bash
   cd client && npm ci && npm run dev
   ```

The local OAuth file is git-ignored and loaded by Spring Boot from `server/config/application.yml` when
the backend runs with `server/` as its working directory. It contains blank fields for GitHub, Google
Drive, Slack, and Grafana. Fill only the providers you use; the corresponding sign-in buttons stay
unconfigured while their fields are blank. The file is not overwritten on later runs. The existing
`server/start-local-google-drive.ps1` helper can still be used to supply the persistent encryption key.
When this local file is present, edit its provider values directly; they take precedence over the OAuth
environment-variable placeholders listed below.

Then open http://localhost:5173 → **Connections** → add the GitHub MCP server → **Investigations**.

### Environment variables (placeholders only, never commit real values)

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/connect`, `connect`, `connect` | PostgreSQL |
| `CONNECTAI_ENCRYPTION_KEY` | required | At least 32 characters; encrypts stored MCP tokens. Use a high-entropy value and back it up securely. Changing it makes stored tokens unreadable. |
| `CONNECTAI_LLM_ENABLED` | `true` in profile `local` | `false` = rule-based planner + template answers, no model calls |
| `CONNECTAI_LLM_PROVIDER` | `ollama` in profile `local` | `ollama` or `anthropic` |
| `OLLAMA_BASE_URL` / `OLLAMA_MODEL` | `http://localhost:11434` / `qwen3:1.7b` | Local model |
| `OLLAMA_NUM_CTX` | `8192` | Context window sent to Ollama (its own default truncates prompts) |
| `CONNECTAI_LLM_TIMEOUT_SECONDS` | `180` (local) | Per model call |
| `ANTHROPIC_API_KEY` | – | Only with provider `anthropic` |
| `CONNECTAI_MCP_REQUEST_TIMEOUT_SECONDS` / `..._CONNECT_TIMEOUT_SECONDS` | `20` / `10` | MCP calls |
| `CONNECTAI_MCP_ALLOW_PRIVATE_HOSTS` | `false` | Allows explicitly configured private/local MCP destinations; keep disabled unless required. |
| `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` | unset | Required to enable Google Drive sign-in. |
| `GITHUB_OAUTH_CLIENT_ID` / `GITHUB_OAUTH_CLIENT_SECRET` | unset | Optional; enables GitHub OAuth sign-in. GitHub PAT auth remains available without these. |
| `GITHUB_OAUTH_SCOPES` | `repo read:org read:user` | GitHub OAuth scopes requested by the configured server-side OAuth app. Review carefully before enabling. |
| `CONNECTAI_OAUTH_REDIRECT_URI` | `http://localhost:8080/connect-ai/api/oauth/callback` | Exact OAuth callback registered with the provider. |
| `CONNECTAI_FRONTEND_ORIGIN` | `http://localhost:5173` | Exact frontend origin allowed to receive the OAuth popup message. |
| `CONNECTAI_CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | Only needed without the Vite proxy |
| `VITE_API_BASE_URL` (client) | `/connect-ai/api` | API base; the default goes through the Vite proxy |
| `VITE_BACKEND_URL` (client) | `http://localhost:8080` | Proxy target |

Agent budgets live in `server/src/main/resources/application.yml` under `connectai.agent.*`
(max steps, max duration, consecutive failures, shortlist size, model attempts per decision).

### Connecting the GitHub MCP server

In **Connections → Add a Tool → GitHub** (or a custom server):

- Transport: Streamable HTTP (the only transport implemented; stdio is not supported).
- Endpoint: the remote GitHub MCP server, `https://api.githubcopilot.com/mcp/`
  (check GitHub's MCP server documentation for the current URL).
- Authentication: Bearer token = a GitHub personal access token with the scopes you want the agent to use.
  The token is encrypted at rest and never returned by the API.

#### GitHub authentication

Use the GitHub catalog form with a fine-grained personal access token. Connect AI sends it as a bearer
token and stores it encrypted only after the GitHub MCP endpoint passes MCP initialization and tool
discovery. OAuth sign-in is also available when the backend is configured with `GITHUB_OAUTH_CLIENT_ID`
and `GITHUB_OAUTH_CLIENT_SECRET`; register the exact `CONNECTAI_OAUTH_REDIRECT_URI` (local default
`http://localhost:8080/connect-ai/api/oauth/callback`) in the GitHub OAuth app. OAuth uses the app's
preconfigured authorization and token endpoints; this implementation does not discover OAuth metadata.
The callback reports success only after authenticated MCP initialization and tool discovery. See the
[official host integration guide](https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md).

#### Connect Google Drive MCP

Google Drive is available in the Tools catalog using the official endpoint
`https://drivemcp.googleapis.com/mcp/v1`. Google currently requires the Google Drive API and Drive MCP API,
Google Workspace Developer Preview Program membership, an OAuth consent screen, and a Web application
OAuth client. Follow Google's current [Drive MCP setup instructions](https://developers.google.com/workspace/drive/api/guides/configure-mcp-server)
for the project-side Data Access scopes; Connect AI's authorization request remains limited to the
read-only `https://www.googleapis.com/auth/drive.readonly` scope.
Add the backend redirect URI as an authorized redirect URI; locally it defaults to
`http://localhost:8080/connect-ai/api/oauth/callback` and can be changed with
`CONNECTAI_OAUTH_REDIRECT_URI`. The URI must be reachable by Google and match exactly.

Set `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET` in the backend environment, then
restart the backend. Connect AI requests offline access and PKCE, stores tokens encrypted, and
refreshes access tokens as MCP requests are made. In **Tools → Google Drive**, select **Sign in with
Google** and approve the read-only scope. Google currently lists the Drive MCP service as a Developer
Preview. The project-side OAuth consent-screen scopes documented by Google are separate from the scope
Connect AI requests during sign-in; Connect AI requests only `drive.readonly`.
`drive.readonly` can expose all Drive files readable by the signed-in account and is classified as a
restricted Google scope, so consent-screen testing or verification requirements may apply. Google’s
current tool reference lists `search_files` and `read_file_content` as read-only tools that accept this
scope. To verify manually, choose a specifically named non-sensitive test document and ask Connect AI to
find and summarize that file; inspect that the steps used `search_files` and then `read_file_content` and
that evidence references the selected file. Do not ask the test to create, copy, or edit a file.

Also set `CONNECTAI_ENCRYPTION_KEY` before starting the backend. The callback reports success only after
Google issued a token and the Drive MCP endpoint initialized and returned its tool list. Disconnect closes the MCP session but preserves authorization
for reconnect; removing the saved connection attempts provider revocation when its provider config has a
`revocation-url`, then deletes its locally encrypted token. The Google provider is configured with Google's
revocation endpoint; GitHub revocation is not configured. Google documents that revocation may invalidate
issued access and refresh tokens for the OAuth project, so other connections using the same project may
also need authorization again.

Google Drive has a static OAuth provider implementation. GitHub OAuth 2.1 discovery and dynamic client
registration are not implemented. Other catalog entries are not OAuth-ready unless their provider is
explicitly configured and tested. Application user accounts are not implemented; the callback is
browser-bound and tied to the initiating saved connection.
Authorization attempts are held in short-lived process memory, so restarting the server or routing the
callback to another backend instance requires starting sign-in again. Expired/revoked credentials are
shown as **Sign-in expired** and require reauthorization before reconnecting.

"Connected" is only shown after a completed MCP `initialize` handshake and tool discovery. Live sessions do not survive a
backend restart: after a restart every server shows **Disconnected** with an explanation; click **Reconnect**.

## How an investigation runs

`POST /api/investigations {question}` stores the investigation as `RUNNING` and returns immediately;
the run happens on a small background pool and the UI polls `GET /api/investigations/{id}`.
Each tool call is persisted as soon as it finishes (tool, validated arguments with credentials
redacted, planner reason, duration, error). Submitting the same question while it is still running
returns the existing run.

| Status | Meaning |
|---|---|
| `RUNNING` | Agent still working |
| `ANSWERED` | Completed; the answer cites real evidence (`[E1]` = evidence record E1) |
| `PARTIAL` | Some evidence, but the run stopped early or failed later (see `stopReason`) |
| `INCONCLUSIVE` | Run ended normally but no tool returned usable evidence |
| `FAILED` | Could not be carried out: no connected tools, model error, every tool call failed, or the server restarted mid-run |

Stop conditions: planner FINISH, no relevant tool, planner error after its retries, identical call
repeated, two consecutive tool failures, step budget, time budget.

### Hybrid engine: fast path first, AI only when needed

Most questions follow a few patterns, so a deterministic **fast path** answers them with **no model call**:

1. `IntentParser` recognises the question (who am I, my repositories, my pull requests, commits / pull
   requests / issues / releases / branches of `owner/repo`) and extracts owner, repo, state and limit.
   Anything that needs judgement (why, compare, most, root cause, failures, time windows...) is left to the AI.
2. A fixed recipe of **read-only** MCP tool calls runs through the same validated executor (steps and
   evidence are recorded exactly like an agent run). "My ..." recipes run on every connection, each scoped
   to that connection's own login.
3. `AnswerTemplates` render the tool's JSON into an exact answer with citations; nothing is paraphrased.
   If the output has an unexpected shape, the regular (AI) answer step writes the answer instead.

**Works with any MCP server, not just GitHub.** When no known-tool recipe applies, a generic layer reads
the live catalog instead:

- `GenericQuery` classifies the question (identity / list / get one item) and extracts values:
  `owner/repo`, keys like `PAY-123`, `#42`, quoted search text, `#channel`, state, "last N", "my".
- `GenericToolMatcher` picks a read-only tool whose *name* matches the entity (with synonyms such as
  ticket↔issue, PR↔pull request) and fills **required parameters by name** (`owner`, `repo`, `key`, `id`,
  `number`, `state` via the tool's enum, `limit`/`perPage`/`maxResults`, `channel`, and `assignee`/`user`/
  `accountId` from the identity call for "my ..."). It refuses, and the AI takes over, when a required value is
  missing, a requested filter does not exist on the tool, "my" cannot be scoped, or two tools tie.
- `GenericRenderer` turns any JSON into an exact list or record (title/summary/name/login, key/number/sha,
  state/status, people, dates; Jira-style `fields.*`; grouped results are flattened).
- If one connection fails (e.g. a token lacks a scope), the others' results are shown, the failure is listed,
  and the answer is `PARTIAL`.

Covered by tests with Jira-, Slack- and Linear-shaped catalogs and by an integration test against a real
in-process MCP server with ticket tools (`GenericMcpServerIntegrationTest`).

Unrecognised questions go to the AI agent. Each investigation records its `route` (`FAST_PATH` / `AI_AGENT`),
shown in the UI with the time taken. Measured on a laptop CPU with qwen3:1.7b: fast path ~4-7 s (mostly
GitHub latency) versus ~50-90 s through the model. Disable with `CONNECTAI_FAST_PATH_ENABLED=false`.

### Safeguards against wrong answers (small local models)

- **Read-only tools only.** Investigations never see tools that may change data (MCP `readOnlyHint`,
  falling back to mutating-verb names such as `create_*`, `push_*`, `*_write`). Set
  `connectai.agent.allow-write-tools=true` only if you really want the agent to write.
- **Topic gate.** Words that name a kind of data (failures, workflows, commits, pull requests...) must be
  covered by some connected tool's name/description. "Which repo has the most GitHub Actions failures?"
  against a GitHub server without the Actions toolset ends INCONCLUSIVE ("None of the connected tools
  provides data about: action, failures") instead of answering from a keyword search. Entity names
  (`PAY-123`, `octo/api`) never gate.
- **First-person scoping.** After an identity call (e.g. `get_me`) the login is known; for "my/I/own"
  questions a free-text search query must contain it (`user:<login>`), otherwise the plan is rejected and repaired.
- **Answer coverage.** The answer step returns `coverage` = full / partial / none plus what is missing.
  Only a verified *full* answer of a completed run is `ANSWERED`; *partial* → `PARTIAL`, *none* → `INCONCLUSIVE`.
  Specific values the answer asserts (quoted names, numbers) must appear in the evidence; one repair attempt,
  then the raw evidence is listed and the run is `PARTIAL`.

The remote GitHub MCP server enables only some toolsets by default; questions about GitHub Actions need its
Actions toolset (see GitHub's MCP server documentation for selecting toolsets).

## API

| Endpoint | Notes |
|---|---|
| `GET /api/health` | Backend, LLM (Ollama reachable? model installed?) and each MCP server (persisted status vs live session) |
| `POST /api/tools` | Register an MCP server. 400 invalid URL/transport/missing token, 409 duplicate name |
| `GET /api/oauth/providers`, `POST /api/tools/{id}/oauth/start`, `GET /api/oauth/attempts/{id}`, `GET /api/oauth/callback` | OAuth sign-in: providers, start (returns the authorization URL), poll the outcome, provider redirect target |
| `POST /api/tools/{id}/connect` / `disconnect` | Real handshake. 502 with the cause (refused, 401, unknown host...) |
| `DELETE /api/tools/{id}` | Removes the saved connection and its token. **409 while connected** (disconnect first); 204 when removed. Investigation history is kept. |

Several connections of the same tool (e.g. two GitHub accounts) are supported: names must be unique, the
planner shows each tool with its connection name, and "my ..." questions are scoped to the login of the
connection being used.
| `GET /api/tools/{id}/connection` | Live status; a stale persisted CONNECTED is reported and stored as DISCONNECTED |
| `GET /api/tools/{id}/tools` | Live discovery (paginated `tools/list`). 409 when not connected |
| `POST /api/tools/{id}/tools/{tool}/execute` | `{arguments}`; 404 unknown tool, 400 schema violation, **409 not connected / session expired**, 502 transport failure, 200 + `isError` for tool-level errors |
| `POST /api/investigations`, `GET /api/investigations[/{id}]` | See above |
| `POST /api/agent/chat` | Synchronous variant of the same agent, stored as agent conversations |

Request samples: `server/http/*.http`.

### Catalog-based connection setup

The backend connector definitions live in `server/src/main/resources/catalog/mcp-catalog.json` and are
validated when Spring starts. `GET /connect-ai/api/catalog` returns those definitions plus derived connection
counts and status; unsupported authentication choices are marked `supported: false`. The Tools page reads
this API rather than maintaining a second vendor list.

For token or unauthenticated connections the wizard first calls `POST /connect-ai/api/connections/test`.
That endpoint performs a temporary Streamable HTTP MCP initialize and paginated tool discovery without
persisting a connection. On success, `POST /connect-ai/api/connections` encrypts credentials server-side,
connects, discovers tools, and saves the connection. The legacy `/api/tools` registration and OAuth routes
remain for compatibility. OAuth discovery and dynamic client registration are not implemented; Google
Drive uses its configured static provider and now verifies MCP and discovers tools in the callback before
the attempt is marked successful.

Authentication configuration is encrypted in the database. `CONNECTAI_ENCRYPTION_KEY` must remain stable
to decrypt stored connection credentials. Remote MCP endpoints must use HTTPS. The backend rejects DNS
results resolving to loopback, link-local, private, or reserved addresses unless
`CONNECTAI_MCP_ALLOW_PRIVATE_HOSTS=true` is explicitly set for an environment that requires private MCP
servers. This DNS check occurs before connection establishment and cannot fully prevent a hostile DNS
rebinding between validation and the HTTP client resolving the host; deploy behind suitable egress controls.
See [CONNECTORS.md](CONNECTORS.md) for supported auth methods, connector limitations, and extension notes.

## Tests

```bash
cd server && mvn test                    # unit + Spring + real in-process MCP server tests
cd server && mvn test -Dtest=OllamaLivePlannerTest -Dconnectai.live-ollama=true   # real local model, opt-in
cd client && npm run build               # TypeScript check + production build
```

- `McpInvestigationPipelineIntegrationTest` runs a real MCP server (official SDK, Streamable HTTP) inside
  the test and drives register → handshake → discovery → execution → investigation → disconnect/stale/failure.
  It validates Connect AI's MCP plumbing, not GitHub itself.
- `OllamaLivePlannerTest` is skipped unless enabled; it repeats each scenario 5 times against the real model.
- Planner/parser tests use scripted (mock) model replies, including malformed output.

## Known limitations

- Only the Streamable HTTP transport is implemented (no stdio/SSE).
- Live MCP sessions are in memory: reconnect after each backend restart (no automatic reconnect).
- No user authentication; anyone who can reach the backend can register MCP endpoints.
  The backend requires HTTPS for remote endpoints and blocks loopback, private, link-local,
  and reserved destinations unless `CONNECTAI_MCP_ALLOW_PRIVATE_HOSTS=true` is explicitly set.
  DNS is validated before connection, but deployment egress controls are still needed to address
  DNS rebinding. Run the service only in a trusted environment.
- Evidence and questions are sent to the configured model provider.

## Layout (server)

- `agent/`       loop, planner contract, shortlister, argument validator, answer context (no HTTP/DB/vendor code)
- `llm/`         Ollama/Anthropic clients, LLM planner, grounded answer generator, grounding validator
- `mcp/`         MCP server registry, connection lifecycle, discovery, execution, agent adapter
- `service/`     investigation submission, background runner, transactional store
- `api/`         REST controllers + DTOs, health
- `tools/fake/`  demo tools for `connectai.tools.mode=fake`
