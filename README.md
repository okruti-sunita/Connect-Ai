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

# 3. Backend (profile "local" is active by default: Ollama + MCP tools)
cd server && mvn spring-boot:run

# 4. Frontend (http://localhost:5173, API proxied to :8080)
cd client && npm ci && npm run dev
```

Then open http://localhost:5173 → **Connections** → add the GitHub MCP server → **Investigations**.

### Environment variables (placeholders only, never commit real values)

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/connect`, `connect`, `connect` | PostgreSQL |
| `CONNECTAI_ENCRYPTION_KEY` | `local-dev-only-change-me` | Encrypts stored MCP tokens. **Set your own.** Changing it makes stored tokens unreadable. |
| `CONNECTAI_LLM_ENABLED` | `true` in profile `local` | `false` = rule-based planner + template answers, no model calls |
| `CONNECTAI_LLM_PROVIDER` | `ollama` in profile `local` | `ollama` or `anthropic` |
| `OLLAMA_BASE_URL` / `OLLAMA_MODEL` | `http://localhost:11434` / `qwen3:1.7b` | Local model |
| `OLLAMA_NUM_CTX` | `8192` | Context window sent to Ollama (its own default truncates prompts) |
| `CONNECTAI_LLM_TIMEOUT_SECONDS` | `180` (local) | Per model call |
| `ANTHROPIC_API_KEY` | – | Only with provider `anthropic` |
| `CONNECTAI_MCP_REQUEST_TIMEOUT_SECONDS` / `..._CONNECT_TIMEOUT_SECONDS` | `20` / `10` | MCP calls |
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

#### Sign in with GitHub (OAuth) instead of pasting a token

Each tool can be connected either with an **access token** or, where an OAuth provider is configured,
with **Sign in with GitHub** (Authorization Code + PKCE; the token is exchanged and stored encrypted by
the backend and never shown or returned).

One-time setup by whoever runs the backend:

1. GitHub → Settings → Developer settings → **OAuth Apps → New OAuth App**.
   Homepage URL `http://localhost:5173`, **Authorization callback URL
   `http://localhost:8080/connect-ai/api/oauth/callback`** (must match `connectai.oauth.redirect-uri`).
2. Generate a client secret.
3. Put the client id/secret in `server/config/application.yml` (git-ignored; see
   `server/config-example/application.yml`) or set `GITHUB_OAUTH_CLIENT_ID` / `GITHUB_OAUTH_CLIENT_SECRET`,
   then restart the backend. `GET /api/oauth/providers` then reports `"configured": true`.

Then: Connections → GitHub → *Sign in with GitHub* → approve in the popup → the connection is tested and
saved. **Re-authorize** on the connection's page replaces the token (e.g. after revoking it on GitHub).
Scopes default to `repo read:org read:user` (`GITHUB_OAUTH_SCOPES`); investigations still only use
read-only tools. Other OAuth providers are added under `connectai.oauth.providers.<id>` and referenced
by a catalog entry's `oauthProvider`.

"Connected" is only shown after a completed MCP `initialize` handshake. Live sessions do not survive a
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
- No user authentication; anyone who can reach the backend can register MCP endpoints
  (the backend will make HTTP requests to any URL entered). Run it locally only.
- Evidence and questions are sent to the configured model provider.

## Layout (server)

- `agent/`       loop, planner contract, shortlister, argument validator, answer context (no HTTP/DB/vendor code)
- `llm/`         Ollama/Anthropic clients, LLM planner, grounded answer generator, grounding validator
- `mcp/`         MCP server registry, connection lifecycle, discovery, execution, agent adapter
- `service/`     investigation submission, background runner, transactional store
- `api/`         REST controllers + DTOs, health
- `tools/fake/`  demo tools for `connectai.tools.mode=fake`
